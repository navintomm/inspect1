package com.colorinspector.app.model

import android.graphics.PointF
import android.graphics.RectF

/**
 * Represents the shape type for inspection area selection.
 */
enum class SelectionShape {
    CIRCLE,
    OVAL,
    SQUARE,
    RECTANGLE,
    FREEHAND
}

/**
 * Encapsulates a selected inspection region.
 */
sealed class InspectionRegion {

    /** Axis-aligned bounding rect used for all geometric shapes */
    abstract val boundingRect: RectF

    data class CircleRegion(
        val cx: Float,
        val cy: Float,
        val radius: Float
    ) : InspectionRegion() {
        override val boundingRect: RectF
            get() = RectF(cx - radius, cy - radius, cx + radius, cy + radius)
    }

    data class OvalRegion(
        val rect: RectF
    ) : InspectionRegion() {
        override val boundingRect: RectF get() = RectF(rect)
    }

    data class SquareRegion(
        val cx: Float,
        val cy: Float,
        val halfSide: Float
    ) : InspectionRegion() {
        override val boundingRect: RectF
            get() = RectF(cx - halfSide, cy - halfSide, cx + halfSide, cy + halfSide)
    }

    data class RectangleRegion(
        val rect: RectF
    ) : InspectionRegion() {
        override val boundingRect: RectF get() = RectF(rect)
    }

    data class FreehandRegion(
        val points: List<PointF>
    ) : InspectionRegion() {
        override val boundingRect: RectF
            get() {
                if (points.isEmpty()) return RectF()
                var left = points[0].x; var top = points[0].y
                var right = left; var bottom = top
                points.forEach { p ->
                    if (p.x < left) left = p.x
                    if (p.x > right) right = p.x
                    if (p.y < top) top = p.y
                    if (p.y > bottom) bottom = p.y
                }
                return RectF(left, top, right, bottom)
            }
    }
}

/**
 * Scale an InspectionRegion from one coordinate space to another (e.g. view → bitmap).
 * Every coordinate is multiplied by scaleX / scaleY independently.
 */
fun InspectionRegion.scaledTo(scaleX: Float, scaleY: Float): InspectionRegion {
    return when (this) {
        is InspectionRegion.CircleRegion -> {
            // Uniform scale for radius (use average to handle minor aspect-ratio differences)
            val avgScale = (scaleX + scaleY) / 2f
            InspectionRegion.CircleRegion(cx * scaleX, cy * scaleY, radius * avgScale)
        }
        is InspectionRegion.OvalRegion -> {
            InspectionRegion.OvalRegion(
                RectF(rect.left * scaleX, rect.top * scaleY,
                      rect.right * scaleX, rect.bottom * scaleY)
            )
        }
        is InspectionRegion.SquareRegion -> {
            val avgScale = (scaleX + scaleY) / 2f
            InspectionRegion.SquareRegion(cx * scaleX, cy * scaleY, halfSide * avgScale)
        }
        is InspectionRegion.RectangleRegion -> {
            InspectionRegion.RectangleRegion(
                RectF(rect.left * scaleX, rect.top * scaleY,
                      rect.right * scaleX, rect.bottom * scaleY)
            )
        }
        is InspectionRegion.FreehandRegion -> {
            InspectionRegion.FreehandRegion(
                points.map { PointF(it.x * scaleX, it.y * scaleY) }
            )
        }
    }
}

/**
 * Detected colour spot with its bounding box (in camera-preview coordinates).
 */
data class DetectedSpot(
    val id: Int,
    val boundsInPreview: RectF,
    val dominantColor: Int
)

/**
 * Snapshot of a single detection frame result.
 */
data class DetectionResult(
    val spots: List<DetectedSpot>,
    val frameTimestamp: Long = System.currentTimeMillis()
)

/**
 * App-wide state machine phases.
 */
enum class InspectionPhase {
    IDLE,
    AREA_SELECTION,
    BACKGROUND_SAMPLING,
    DETECTING,
    PAUSED,
    RESULTS
}

/**
 * Sensitivity / threshold parameters exposed to the UI.
 */
data class DetectionParams(
    /** Colour distance threshold (0–255 in L*a*b* ΔE space) */
    val colorTolerance: Float = 15f,
    /** Sensitivity slider value 0–100; maps inversely to colorTolerance */
    val sensitivity: Int = 50,
    /** Minimum spot area in pixels (preview-coord pixels squared) */
    val minSpotAreaPx: Int = 50,
    /** Morphological kernel size for noise removal */
    val morphKernelSize: Int = 3
)
