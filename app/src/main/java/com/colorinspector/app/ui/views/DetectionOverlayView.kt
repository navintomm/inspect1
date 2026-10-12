package com.colorinspector.app.ui.views

import android.content.Context
import android.graphics.*
import android.util.AttributeSet
import android.view.View
import com.colorinspector.app.model.DetectedSpot
import com.colorinspector.app.model.InspectionRegion

/**
 * Transparent overlay that draws bounding boxes around detected spots.
 * Rendered on top of both the camera preview and the selection overlay.
 */
class DetectionOverlayView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null
) : View(context, attrs) {

    var spots: List<DetectedSpot> = emptyList()
        set(value) { field = value; invalidate() }

    // Scale factors to map from bitmap-space → view-space
    var scaleX2: Float = 1f
    var scaleY2: Float = 1f
    var offsetX: Float = 0f
    var offsetY: Float = 0f

    private val boxPaint = Paint().apply {
        color = 0xFFB39DDB.toInt()   // violet
        style = Paint.Style.STROKE
        strokeWidth = 3f
        isAntiAlias = true
    }
    private val labelBgPaint = Paint().apply {
        color = 0xCCB39DDB.toInt()
        style = Paint.Style.FILL
    }
    private val labelTextPaint = Paint().apply {
        color = Color.WHITE
        textSize = 30f
        isAntiAlias = true
        typeface = Typeface.DEFAULT_BOLD
    }
    private val cornerPaint = Paint().apply {
        color = 0xFF7E57C2.toInt()
        style = Paint.Style.STROKE
        strokeWidth = 5f
        strokeCap = Paint.Cap.ROUND
        isAntiAlias = true
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        spots.forEachIndexed { index, spot ->
            val r = spot.boundsInPreview
            val left   = r.left   * scaleX2 + offsetX
            val top    = r.top    * scaleY2 + offsetY
            val right  = r.right  * scaleX2 + offsetX
            val bottom = r.bottom * scaleY2 + offsetY

            // Main box
            canvas.drawRect(left, top, right, bottom, boxPaint)

            // Corner accents (fancy bounding box corners)
            val cornerLen = minOf((right - left), (bottom - top)) * 0.2f
            drawCornerAccent(canvas, left, top, cornerLen, 1f, 1f)
            drawCornerAccent(canvas, right, top, cornerLen, -1f, 1f)
            drawCornerAccent(canvas, left, bottom, cornerLen, 1f, -1f)
            drawCornerAccent(canvas, right, bottom, cornerLen, -1f, -1f)

            // Label
            val label = "Spot ${index + 1}"
            val textW = labelTextPaint.measureText(label)
            val labelH = 36f
            val labelBg = RectF(left, top - labelH - 4, left + textW + 16, top - 4)
            canvas.drawRoundRect(labelBg, 6f, 6f, labelBgPaint)
            canvas.drawText(label, left + 8, top - 10, labelTextPaint)
        }
    }

    private fun drawCornerAccent(
        canvas: Canvas, x: Float, y: Float,
        len: Float, dx: Float, dy: Float
    ) {
        canvas.drawLine(x, y, x + dx * len, y, cornerPaint)
        canvas.drawLine(x, y, x, y + dy * len, cornerPaint)
    }
}
