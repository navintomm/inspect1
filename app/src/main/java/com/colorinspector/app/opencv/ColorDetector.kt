package com.colorinspector.app.opencv

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.PointF
import android.graphics.RectF
import com.colorinspector.app.model.DetectedSpot
import com.colorinspector.app.model.DetectionParams
import com.colorinspector.app.model.DetectionResult
import com.colorinspector.app.model.InspectionRegion
import org.opencv.android.Utils
import org.opencv.core.*
import org.opencv.imgproc.Imgproc
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * All OpenCV processing lives here. This class is intentionally stateless so
 * that the algorithm can be swapped or extended without touching the UI layer.
 *
 * Detection pipeline (improved):
 *  1.  Build region mask.
 *  2.  Apply CLAHE on L-channel inside region → normalise uneven lighting.
 *  3.  Convert to Lab.
 *  4.  Compute per-pixel ΔE from background reference.
 *  5.  Threshold → raw binary mask.
 *  6.  MORPH_CLOSE first (fills gaps inside fragmented defects).
 *  7.  MORPH_OPEN  second (removes isolated noise specks).
 *  8.  DILATE slightly (expands regions to catch full defect extent).
 *  9.  Find contours.
 *  10. Filter by min area.
 *  11. Merge nearby / overlapping bounding boxes (with padding).
 *  12. Temporal accumulation: hotspot vote over N frames for stability.
 */
object ColorDetector {

    // ── Temporal accumulation ────────────────────────────────────────────────
    /** Accumulator: each cell counts how many recent frames had a "hit" pixel */
    private var accumulator: Mat? = null
    private var accumW = 0
    private var accumH = 0
    private const val ACCUM_FRAMES = 3      // blend over this many frames
    private const val ACCUM_THRESHOLD = 1   // pixel voted in ≥1 frames → keep

    fun resetAccumulator() {
        accumulator?.release()
        accumulator = null
        accumW = 0; accumH = 0
    }

    // ── Background colour sampling ───────────────────────────────────────────

    fun sampleBackgroundColor(
        previewBitmap: Bitmap,
        region: InspectionRegion,
        previewWidth: Int,
        previewHeight: Int
    ): Int {
        val mat = bitmapToMat(previewBitmap)
        val mask = buildMask(region, previewWidth, previewHeight)

        // Apply CLAHE before sampling so the sampled colour represents the
        // lighting-normalised surface, matching what detect() will see.
        val normalised = applyClaheInsideMask(mat, mask)

        val labMat = Mat()
        Imgproc.cvtColor(normalised, labMat, Imgproc.COLOR_RGB2Lab)

        val pixels = mutableListOf<FloatArray>()
        for (y in 0 until labMat.rows()) {
            for (x in 0 until labMat.cols()) {
                if (mask[y, x][0] > 0) {
                    val px = labMat[y, x]
                    pixels.add(floatArrayOf(px[0].toFloat(), px[1].toFloat(), px[2].toFloat()))
                }
            }
        }

        if (pixels.isEmpty()) {
            mat.release(); mask.release(); normalised.release(); labMat.release()
            return Color.WHITE
        }

        // Use MEDIAN rather than mean – much more robust against defect pixels
        // skewing the background estimate.
        pixels.sortBy { it[0] }
        val mid = pixels.size / 2
        val medL = pixels[mid][0].toDouble()
        val medA = pixels[mid][1].toDouble()
        val medB = pixels[mid][2].toDouble()

        val meanLabMat = Mat(1, 1, CvType.CV_8UC3)
        meanLabMat.put(0, 0, medL, medA, medB)
        val meanRgbMat = Mat()
        Imgproc.cvtColor(meanLabMat, meanRgbMat, Imgproc.COLOR_Lab2RGB)
        val rgb = meanRgbMat[0, 0]

        mat.release(); labMat.release(); mask.release()
        normalised.release(); meanLabMat.release(); meanRgbMat.release()

        return Color.rgb(
            rgb[0].toInt().coerceIn(0, 255),
            rgb[1].toInt().coerceIn(0, 255),
            rgb[2].toInt().coerceIn(0, 255)
        )
    }

    fun sampleColorAtPoint(previewBitmap: Bitmap, point: PointF): Int {
        val x = point.x.toInt().coerceIn(0, previewBitmap.width - 1)
        val y = point.y.toInt().coerceIn(0, previewBitmap.height - 1)
        var r = 0; var g = 0; var b = 0; var count = 0
        for (dy in -2..2) for (dx in -2..2) {
            val px = previewBitmap.getPixel(
                (x + dx).coerceIn(0, previewBitmap.width - 1),
                (y + dy).coerceIn(0, previewBitmap.height - 1)
            )
            r += Color.red(px); g += Color.green(px); b += Color.blue(px); count++
        }
        return Color.rgb(r / count, g / count, b / count)
    }

    // ── Main detection pipeline ──────────────────────────────────────────────

    fun detect(
        previewBitmap: Bitmap,
        region: InspectionRegion,
        bgColor: Int,
        params: DetectionParams
    ): DetectionResult {

        val previewW = previewBitmap.width
        val previewH = previewBitmap.height

        val mat = bitmapToMat(previewBitmap)

        // 1. Region mask
        val regionMask = buildMask(region, previewW, previewH)

        // 2. CLAHE lighting normalisation – equalises contrast per tile
        //    so defects in shadowed areas are as detectable as lit ones.
        val normalised = applyClaheInsideMask(mat, regionMask)

        // 3. Lab conversion
        val labMat = Mat()
        Imgproc.cvtColor(normalised, labMat, Imgproc.COLOR_RGB2Lab)
        normalised.release()

        // 4. Reference Lab values for the background colour
        val bgLab = rgbToLab(bgColor)

        // 5. Compute per-pixel ΔE inside region only
        val deltaMat = Mat(labMat.size(), CvType.CV_32F)
        for (y in 0 until labMat.rows()) {
            for (x in 0 until labMat.cols()) {
                if (regionMask[y, x][0] < 1) {
                    deltaMat.put(y, x, 0.0)
                    continue
                }
                val px = labMat[y, x]
                val dE = deltaE(px[0], px[1], px[2], bgLab[0], bgLab[1], bgLab[2])
                deltaMat.put(y, x, dE)
            }
        }

        // 6. Threshold → raw binary
        val binaryMat = Mat()
        Imgproc.threshold(
            deltaMat, binaryMat,
            params.colorTolerance.toDouble(), 255.0,
            Imgproc.THRESH_BINARY
        )
        binaryMat.convertTo(binaryMat, CvType.CV_8U)
        Core.bitwise_and(binaryMat, regionMask, binaryMat)
        deltaMat.release()

        // 7. Temporal accumulation: blend this frame's binary mask into the
        //    rolling accumulator and use the accumulated image for detection.
        //    This dramatically reduces flicker and catches defects that are
        //    partially detected in each frame but fully caught across frames.
        val stableMat = temporalAccumulate(binaryMat, previewW, previewH)

        // 8. MORPH_CLOSE first → fills gaps within fragmented defects
        //    (e.g. a long scratch detected in broken segments → one solid blob)
        val closeKernel = Imgproc.getStructuringElement(
            Imgproc.MORPH_ELLIPSE, Size(7.0, 7.0)
        )
        val closedMat = Mat()
        Imgproc.morphologyEx(stableMat, closedMat, Imgproc.MORPH_CLOSE, closeKernel)
        closeKernel.release()

        // 9. MORPH_OPEN → removes isolated noise specks that survived accumulation
        val openKernel = Imgproc.getStructuringElement(
            Imgproc.MORPH_ELLIPSE, Size(3.0, 3.0)
        )
        val cleanMat = Mat()
        Imgproc.morphologyEx(closedMat, cleanMat, Imgproc.MORPH_OPEN, openKernel)
        openKernel.release(); closedMat.release()

        // 10. DILATE slightly to expand each detected region to its full extent
        val dilateKernel = Imgproc.getStructuringElement(
            Imgproc.MORPH_ELLIPSE, Size(5.0, 5.0)
        )
        Imgproc.dilate(cleanMat, cleanMat, dilateKernel)
        dilateKernel.release()

        // 11. Find contours
        val contours = mutableListOf<MatOfPoint>()
        val hierarchy = Mat()
        Imgproc.findContours(
            cleanMat, contours, hierarchy,
            Imgproc.RETR_EXTERNAL, Imgproc.CHAIN_APPROX_SIMPLE
        )

        // 12. Filter by min area and collect raw bounding boxes
        val rawBoxes = mutableListOf<RectF>()
        val rawColors = mutableListOf<Int>()
        for (contour in contours) {
            val area = Imgproc.contourArea(contour)
            if (area < params.minSpotAreaPx) continue
            val rect = Imgproc.boundingRect(contour)
            rawBoxes.add(
                RectF(
                    rect.x.toFloat(), rect.y.toFloat(),
                    (rect.x + rect.width).toFloat(), (rect.y + rect.height).toFloat()
                )
            )
            rawColors.add(sampleSpotColor(labMat, rect))
        }

        // 13. Merge overlapping / nearby bounding boxes with a padding margin
        //     so two detections of the same defect become one box.
        val mergedBoxes = mergeOverlappingBoxes(rawBoxes, paddingPx = 12f)
        val spots = mergedBoxes.mapIndexed { i, box ->
            // Snap colour from the centre of the merged box
            val cx = ((box.left + box.right) / 2).toInt().coerceIn(0, labMat.cols() - 1)
            val cy = ((box.top + box.bottom) / 2).toInt().coerceIn(0, labMat.rows() - 1)
            val spotColor = rawColors.getOrElse(i) {
                sampleSpotColor(labMat,
                    org.opencv.core.Rect(cx, cy, 1, 1))
            }
            DetectedSpot(i, box, spotColor)
        }

        // Cleanup
        mat.release(); regionMask.release(); labMat.release()
        binaryMat.release(); stableMat.release(); cleanMat.release()
        hierarchy.release(); contours.forEach { it.release() }

        return DetectionResult(spots)
    }

    // ── Temporal accumulation helper ─────────────────────────────────────────

    /**
     * Maintains a running accumulator Mat.
     * Each frame's binary mask is added; the accumulator is decayed so old
     * frames fade out.  A pixel survives if it was "on" in ≥ ACCUM_THRESHOLD
     * recent frames.
     */
    private fun temporalAccumulate(binaryMat: Mat, w: Int, h: Int): Mat {
        if (accumulator == null || accumW != w || accumH != h) {
            accumulator?.release()
            accumulator = Mat.zeros(h, w, CvType.CV_32F)
            accumW = w; accumH = h
        }
        val accum = accumulator!!

        // Convert current binary to float and add
        val floatFrame = Mat()
        binaryMat.convertTo(floatFrame, CvType.CV_32F, 1.0 / 255.0)  // 0..1
        // Decay existing accumulator
        Core.multiply(accum, Scalar(1.0 - 1.0 / ACCUM_FRAMES), accum)
        // Add this frame
        Core.add(accum, floatFrame, accum)
        floatFrame.release()

        // Threshold: pixel has been "on" enough recently
        val stable = Mat()
        Imgproc.threshold(
            accum, stable,
            ACCUM_THRESHOLD.toDouble() / ACCUM_FRAMES.toDouble(),
            255.0,
            Imgproc.THRESH_BINARY
        )
        stable.convertTo(stable, CvType.CV_8U)
        return stable
    }

    // ── CLAHE normalisation ──────────────────────────────────────────────────

    /**
     * Apply CLAHE (Contrast Limited Adaptive Histogram Equalisation) on the
     * L channel of the image, restricted to [mask] pixels.
     * This corrects uneven lighting so ΔE computation is consistent
     * across the entire inspection region.
     */
    private fun applyClaheInsideMask(rgbMat: Mat, mask: Mat): Mat {
        val labMat = Mat()
        Imgproc.cvtColor(rgbMat, labMat, Imgproc.COLOR_RGB2Lab)

        val channels = mutableListOf<Mat>()
        Core.split(labMat, channels)

        val clahe = Imgproc.createCLAHE(2.0, Size(8.0, 8.0))
        val lEq = Mat()
        clahe.apply(channels[0], lEq)

        // Blend: only update L where mask is non-zero
        val lChannel = channels[0]
        lEq.copyTo(lChannel, mask)
        lEq.release()

        Core.merge(channels, labMat)
        channels.forEach { it.release() }

        val result = Mat()
        Imgproc.cvtColor(labMat, result, Imgproc.COLOR_Lab2RGB)
        labMat.release()
        return result
    }

    // ── Bounding-box merger ──────────────────────────────────────────────────

    /**
     * Iteratively merge any pair of boxes that overlap or are within
     * [paddingPx] pixels of each other.  Produces the smallest set of
     * non-overlapping boxes that covers all input boxes.
     */
    private fun mergeOverlappingBoxes(boxes: List<RectF>, paddingPx: Float): List<RectF> {
        if (boxes.isEmpty()) return emptyList()
        val work = boxes.map { RectF(it) }.toMutableList()
        var merged = true
        while (merged) {
            merged = false
            val result = mutableListOf<RectF>()
            val used = BooleanArray(work.size)
            for (i in work.indices) {
                if (used[i]) continue
                var current = work[i]
                for (j in (i + 1) until work.size) {
                    if (used[j]) continue
                    val padded = RectF(
                        work[j].left - paddingPx, work[j].top - paddingPx,
                        work[j].right + paddingPx, work[j].bottom + paddingPx
                    )
                    if (RectF.intersects(current, padded)) {
                        current = RectF(
                            min(current.left, work[j].left),
                            min(current.top, work[j].top),
                            max(current.right, work[j].right),
                            max(current.bottom, work[j].bottom)
                        )
                        used[j] = true
                        merged = true
                    }
                }
                result.add(current)
            }
            work.clear(); work.addAll(result)
        }
        return work
    }

    // ── Mask builder ─────────────────────────────────────────────────────────

    fun buildMask(region: InspectionRegion, width: Int, height: Int): Mat {
        val mask = Mat.zeros(height, width, CvType.CV_8UC1)
        when (region) {
            is InspectionRegion.CircleRegion -> Imgproc.circle(
                mask,
                Point(region.cx.toDouble(), region.cy.toDouble()),
                region.radius.toInt(), Scalar(255.0), -1
            )
            is InspectionRegion.OvalRegion -> {
                val r = region.rect
                Imgproc.ellipse(
                    mask,
                    Point(((r.left + r.right) / 2).toDouble(), ((r.top + r.bottom) / 2).toDouble()),
                    Size(((r.right - r.left) / 2).toDouble(), ((r.bottom - r.top) / 2).toDouble()),
                    0.0, 0.0, 360.0, Scalar(255.0), -1
                )
            }
            is InspectionRegion.SquareRegion -> {
                val r = region.boundingRect
                Imgproc.rectangle(mask,
                    Point(r.left.toDouble(), r.top.toDouble()),
                    Point(r.right.toDouble(), r.bottom.toDouble()),
                    Scalar(255.0), -1)
            }
            is InspectionRegion.RectangleRegion -> {
                val r = region.rect
                Imgproc.rectangle(mask,
                    Point(r.left.toDouble(), r.top.toDouble()),
                    Point(r.right.toDouble(), r.bottom.toDouble()),
                    Scalar(255.0), -1)
            }
            is InspectionRegion.FreehandRegion -> {
                if (region.points.size >= 3) {
                    val pts = MatOfPoint()
                    pts.fromList(region.points.map { Point(it.x.toDouble(), it.y.toDouble()) })
                    Imgproc.fillPoly(mask, listOf(pts), Scalar(255.0))
                    pts.release()
                }
            }
        }
        return mask
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

    private fun bitmapToMat(bitmap: Bitmap): Mat {
        val mat = Mat()
        val rgbBitmap = bitmap.copy(Bitmap.Config.ARGB_8888, false)
        Utils.bitmapToMat(rgbBitmap, mat)
        Imgproc.cvtColor(mat, mat, Imgproc.COLOR_BGRA2RGB)
        return mat
    }

    private fun rgbToLab(color: Int): DoubleArray {
        val r = Color.red(color); val g = Color.green(color); val b = Color.blue(color)
        val tempMat = Mat(1, 1, CvType.CV_8UC3)
        tempMat.put(0, 0, r.toDouble(), g.toDouble(), b.toDouble())
        val labOut = Mat()
        Imgproc.cvtColor(tempMat, labOut, Imgproc.COLOR_RGB2Lab)
        val v = labOut[0, 0]
        tempMat.release(); labOut.release()
        return v
    }

    private fun deltaE(L1: Double, a1: Double, b1: Double,
                       L2: Double, a2: Double, b2: Double): Double {
        return sqrt((L1-L2)*(L1-L2) + (a1-a2)*(a1-a2) + (b1-b2)*(b1-b2))
    }

    /** Convert sensitivity (0–100) to colorTolerance (ΔE in Lab space)
     *  sensitivity 100 → tolerance 5  (very sensitive)
     *  sensitivity 50  → tolerance 15 (default)
     *  sensitivity 0   → tolerance 40 (coarse)
     */
    fun sensitivityToTolerance(sensitivity: Int): Float {
        return (40f - (sensitivity / 100f) * 35f).coerceIn(5f, 40f)
    }

    private fun sampleSpotColor(labMat: Mat, rect: org.opencv.core.Rect): Int {
        val cx = (rect.x + rect.width / 2).coerceIn(0, labMat.cols() - 1)
        val cy = (rect.y + rect.height / 2).coerceIn(0, labMat.rows() - 1)
        val px = labMat[cy, cx]
        val singlePx = Mat(1, 1, CvType.CV_8UC3)
        singlePx.put(0, 0, px[0], px[1], px[2])
        val rgbOut = Mat()
        Imgproc.cvtColor(singlePx, rgbOut, Imgproc.COLOR_Lab2RGB)
        val rgb = rgbOut[0, 0]
        singlePx.release(); rgbOut.release()
        return Color.rgb(
            rgb[0].toInt().coerceIn(0, 255),
            rgb[1].toInt().coerceIn(0, 255),
            rgb[2].toInt().coerceIn(0, 255)
        )
    }
}
