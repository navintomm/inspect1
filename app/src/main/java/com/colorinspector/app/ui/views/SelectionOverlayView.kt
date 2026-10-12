package com.colorinspector.app.ui.views

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.*
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import com.colorinspector.app.model.InspectionRegion
import com.colorinspector.app.model.SelectionShape
import kotlin.math.*

/**
 * Transparent overlay drawn on top of the camera preview.
 *
 * Responsibilities:
 *  - Dim the area OUTSIDE the selected shape (darkened scrim).
 *  - Draw the shape outline with handles.
 *  - Handle touch gestures: drag-to-move, handle-drag to resize.
 *  - Freehand drawing mode.
 *  - Emit the current [InspectionRegion] via [onRegionChanged].
 */
@SuppressLint("ClickableViewAccessibility")
class SelectionOverlayView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null
) : View(context, attrs) {

    // ── Callbacks ─────────────────────────────────────────────────────────────
    var onRegionChanged: ((InspectionRegion) -> Unit)? = null

    // ── Shape ─────────────────────────────────────────────────────────────────
    var currentShape: SelectionShape = SelectionShape.RECTANGLE
        set(value) {
            field = value
            if (value != SelectionShape.FREEHAND) resetShapeToCenter()
            else clearFreehand()
            invalidate()
        }

    // ── Geometric shape state ─────────────────────────────────────────────────
    private var shapeRect = RectF()   // bounding rect for all non-freehand shapes

    // ── Freehand state ────────────────────────────────────────────────────────
    private val freehandPoints = mutableListOf<PointF>()
    private val freehandHistory = mutableListOf<List<PointF>>()  // for undo
    var isFreehandComplete = false
        private set

    // ── Paints ────────────────────────────────────────────────────────────────
    private val scrimPaint = Paint().apply {
        color = 0xAA000000.toInt()
        style = Paint.Style.FILL
    }
    private val shapePaint = Paint().apply {
        color = 0xFFB39DDB.toInt()    // violet
        style = Paint.Style.STROKE
        strokeWidth = 3f
        isAntiAlias = true
    }
    private val handlePaint = Paint().apply {
        color = 0xFFB39DDB.toInt()
        style = Paint.Style.FILL
        isAntiAlias = true
    }
    private val freehandPaint = Paint().apply {
        color = 0xFFB39DDB.toInt()
        style = Paint.Style.STROKE
        strokeWidth = 3f
        strokeJoin = Paint.Join.ROUND
        strokeCap = Paint.Cap.ROUND
        isAntiAlias = true
    }
    private val freehandFillPaint = Paint().apply {
        color = 0x33B39DDB.toInt()
        style = Paint.Style.FILL
        isAntiAlias = true
    }

    // ── Touch handling ────────────────────────────────────────────────────────
    private enum class DragMode { NONE, MOVE, RESIZE_TL, RESIZE_TR, RESIZE_BL, RESIZE_BR,
        RESIZE_T, RESIZE_B, RESIZE_L, RESIZE_R }

    private var dragMode = DragMode.NONE
    private var lastTouchX = 0f
    private var lastTouchY = 0f
    private val handleRadius = 22f
    private val handleTouchRadius = 32f

    // ── Freehand touch ────────────────────────────────────────────────────────
    private val freehandPath = Path()

    // =========================================================================
    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        if (w > 0 && h > 0 && shapeRect.isEmpty) resetShapeToCenter()
    }

    private fun resetShapeToCenter() {
        if (width == 0 || height == 0) return
        val cx = width / 2f; val cy = height / 2f
        val hw = width * 0.35f; val hh = height * 0.25f
        shapeRect = RectF(cx - hw, cy - hh, cx + hw, cy + hh)
        emitRegion()
        invalidate()
    }

    private fun clearFreehand() {
        freehandPoints.clear()
        freehandHistory.clear()
        freehandPath.reset()
        isFreehandComplete = false
    }

    fun clearFreehandAndRedraw() { clearFreehand(); invalidate() }

    fun undoFreehand() {
        if (freehandHistory.isNotEmpty()) {
            freehandPoints.clear()
            freehandPoints.addAll(freehandHistory.removeLast())
            rebuildFreehandPath()
            isFreehandComplete = false
            invalidate()
        }
    }

    fun completeFreehand() {
        if (freehandPoints.size >= 3) {
            isFreehandComplete = true
            rebuildFreehandPath()
            emitRegion()
            invalidate()
        }
    }

    private fun rebuildFreehandPath() {
        freehandPath.reset()
        if (freehandPoints.isEmpty()) return
        freehandPath.moveTo(freehandPoints[0].x, freehandPoints[0].y)
        for (i in 1 until freehandPoints.size) {
            freehandPath.lineTo(freehandPoints[i].x, freehandPoints[i].y)
        }
        if (isFreehandComplete) freehandPath.close()
    }

    // =========================================================================
    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (currentShape == SelectionShape.FREEHAND) {
            drawFreehand(canvas)
        } else {
            drawGeometricShape(canvas)
        }
    }

    private fun drawGeometricShape(canvas: Canvas) {
        // Scrim – draw full rect then cut out shape
        val fullRect = RectF(0f, 0f, width.toFloat(), height.toFloat())
        val scrimPath = Path().apply { addRect(fullRect, Path.Direction.CW) }

        when (currentShape) {
            SelectionShape.CIRCLE -> {
                val r = minOf(shapeRect.width(), shapeRect.height()) / 2f
                val cx = shapeRect.centerX(); val cy = shapeRect.centerY()
                scrimPath.addCircle(cx, cy, r, Path.Direction.CCW)
            }
            SelectionShape.OVAL -> scrimPath.addOval(shapeRect, Path.Direction.CCW)
            SelectionShape.SQUARE, SelectionShape.RECTANGLE ->
                scrimPath.addRect(shapeRect, Path.Direction.CCW)
            else -> {}
        }
        scrimPath.fillType = Path.FillType.EVEN_ODD
        canvas.drawPath(scrimPath, scrimPaint)

        // Shape outline
        when (currentShape) {
            SelectionShape.CIRCLE -> {
                val r = minOf(shapeRect.width(), shapeRect.height()) / 2f
                canvas.drawCircle(shapeRect.centerX(), shapeRect.centerY(), r, shapePaint)
            }
            SelectionShape.OVAL -> canvas.drawOval(shapeRect, shapePaint)
            SelectionShape.SQUARE, SelectionShape.RECTANGLE ->
                canvas.drawRect(shapeRect, shapePaint)
            else -> {}
        }

        // Corner & edge handles
        drawHandles(canvas)
    }

    private fun drawHandles(canvas: Canvas) {
        val corners = getHandlePoints()
        corners.values.forEach { p -> canvas.drawCircle(p.x, p.y, handleRadius, handlePaint) }
    }

    private fun getHandlePoints(): Map<DragMode, PointF> {
        val r = shapeRect
        return mapOf(
            DragMode.RESIZE_TL to PointF(r.left, r.top),
            DragMode.RESIZE_TR to PointF(r.right, r.top),
            DragMode.RESIZE_BL to PointF(r.left, r.bottom),
            DragMode.RESIZE_BR to PointF(r.right, r.bottom),
            DragMode.RESIZE_T  to PointF(r.centerX(), r.top),
            DragMode.RESIZE_B  to PointF(r.centerX(), r.bottom),
            DragMode.RESIZE_L  to PointF(r.left, r.centerY()),
            DragMode.RESIZE_R  to PointF(r.right, r.centerY())
        )
    }

    private fun drawFreehand(canvas: Canvas) {
        if (freehandPoints.isEmpty()) return
        if (isFreehandComplete) {
            // Draw scrim + fill
            val fullRect = RectF(0f, 0f, width.toFloat(), height.toFloat())
            val scrimPath = Path().apply { addRect(fullRect, Path.Direction.CW) }
            scrimPath.addPath(freehandPath)
            scrimPath.fillType = Path.FillType.EVEN_ODD
            canvas.drawPath(scrimPath, scrimPaint)
            canvas.drawPath(freehandPath, freehandFillPaint)
        }
        canvas.drawPath(freehandPath, freehandPaint)
    }

    // =========================================================================
    // Touch
    // =========================================================================
    override fun onTouchEvent(event: MotionEvent): Boolean {
        return if (currentShape == SelectionShape.FREEHAND) handleFreehandTouch(event)
        else handleShapeTouch(event)
    }

    private fun handleShapeTouch(event: MotionEvent): Boolean {
        when (event.action) {
            MotionEvent.ACTION_DOWN -> {
                lastTouchX = event.x; lastTouchY = event.y
                dragMode = hitTestHandle(event.x, event.y)
                if (dragMode == DragMode.NONE && shapeRect.contains(event.x, event.y)) {
                    dragMode = DragMode.MOVE
                }
            }
            MotionEvent.ACTION_MOVE -> {
                val dx = event.x - lastTouchX
                val dy = event.y - lastTouchY
                applyDrag(dx, dy)
                lastTouchX = event.x; lastTouchY = event.y
                emitRegion()
                invalidate()
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> dragMode = DragMode.NONE
        }
        return true
    }

    private fun hitTestHandle(x: Float, y: Float): DragMode {
        getHandlePoints().forEach { (mode, p) ->
            if (hypot(x - p.x, y - p.y) <= handleTouchRadius) return mode
        }
        return DragMode.NONE
    }

    private fun applyDrag(dx: Float, dy: Float) {
        val minSize = 80f
        when (dragMode) {
            DragMode.MOVE -> shapeRect.offset(dx, dy)
            DragMode.RESIZE_TL -> {
                if (currentShape == SelectionShape.SQUARE) {
                    val d = (dx + dy) / 2f
                    shapeRect.left += d; shapeRect.top += d
                } else {
                    shapeRect.left = min(shapeRect.left + dx, shapeRect.right - minSize)
                    shapeRect.top  = min(shapeRect.top  + dy, shapeRect.bottom - minSize)
                }
            }
            DragMode.RESIZE_TR -> {
                if (currentShape == SelectionShape.SQUARE) {
                    val d = (dx - dy) / 2f
                    shapeRect.right += d; shapeRect.top -= d
                } else {
                    shapeRect.right = max(shapeRect.right + dx, shapeRect.left + minSize)
                    shapeRect.top   = min(shapeRect.top  + dy, shapeRect.bottom - minSize)
                }
            }
            DragMode.RESIZE_BL -> {
                if (currentShape == SelectionShape.SQUARE) {
                    val d = (-dx + dy) / 2f
                    shapeRect.left -= d; shapeRect.bottom += d
                } else {
                    shapeRect.left   = min(shapeRect.left   + dx, shapeRect.right  - minSize)
                    shapeRect.bottom = max(shapeRect.bottom + dy, shapeRect.top + minSize)
                }
            }
            DragMode.RESIZE_BR -> {
                if (currentShape == SelectionShape.SQUARE) {
                    val d = (dx + dy) / 2f
                    shapeRect.right  += d; shapeRect.bottom += d
                } else {
                    shapeRect.right  = max(shapeRect.right  + dx, shapeRect.left + minSize)
                    shapeRect.bottom = max(shapeRect.bottom + dy, shapeRect.top  + minSize)
                }
            }
            DragMode.RESIZE_T -> shapeRect.top    = min(shapeRect.top    + dy, shapeRect.bottom - minSize)
            DragMode.RESIZE_B -> shapeRect.bottom = max(shapeRect.bottom + dy, shapeRect.top    + minSize)
            DragMode.RESIZE_L -> shapeRect.left   = min(shapeRect.left   + dx, shapeRect.right  - minSize)
            DragMode.RESIZE_R -> shapeRect.right  = max(shapeRect.right  + dx, shapeRect.left   + minSize)
            DragMode.NONE -> {}
        }
        // For SQUARE keep it square after resize
        if (currentShape == SelectionShape.SQUARE) enforceSquare()
        // For CIRCLE keep rect square (equal width/height)
        if (currentShape == SelectionShape.CIRCLE) enforceSquare()
    }

    private fun enforceSquare() {
        val side = max(shapeRect.width(), shapeRect.height())
        val cx = shapeRect.centerX(); val cy = shapeRect.centerY()
        shapeRect = RectF(cx - side / 2, cy - side / 2, cx + side / 2, cy + side / 2)
    }

    private fun handleFreehandTouch(event: MotionEvent): Boolean {
        when (event.action) {
            MotionEvent.ACTION_DOWN -> {
                if (isFreehandComplete) { clearFreehand(); invalidate() }
                // Save snapshot for undo
                freehandHistory.add(freehandPoints.toList())
                freehandPoints.add(PointF(event.x, event.y))
                freehandPath.moveTo(event.x, event.y)
                invalidate()
            }
            MotionEvent.ACTION_MOVE -> {
                freehandPoints.add(PointF(event.x, event.y))
                freehandPath.lineTo(event.x, event.y)
                invalidate()
            }
            MotionEvent.ACTION_UP -> {
                completeFreehand()
            }
        }
        return true
    }

    // =========================================================================
    // Region emission
    // =========================================================================
    private fun emitRegion() {
        val region = currentRegion() ?: return
        onRegionChanged?.invoke(region)
    }

    fun currentRegion(): InspectionRegion? {
        return when (currentShape) {
            SelectionShape.CIRCLE -> {
                val r = minOf(shapeRect.width(), shapeRect.height()) / 2f
                InspectionRegion.CircleRegion(shapeRect.centerX(), shapeRect.centerY(), r)
            }
            SelectionShape.OVAL -> InspectionRegion.OvalRegion(RectF(shapeRect))
            SelectionShape.SQUARE -> {
                val h = minOf(shapeRect.width(), shapeRect.height()) / 2f
                InspectionRegion.SquareRegion(shapeRect.centerX(), shapeRect.centerY(), h)
            }
            SelectionShape.RECTANGLE -> InspectionRegion.RectangleRegion(RectF(shapeRect))
            SelectionShape.FREEHAND -> {
                if (freehandPoints.size >= 3) InspectionRegion.FreehandRegion(freehandPoints.toList())
                else null
            }
        }
    }
}
