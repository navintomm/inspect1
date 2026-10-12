package com.colorinspector.app.ui.views

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View

/**
 * A custom View that renders a grid of colour swatches.
 *
 * Set [colors] and [columns] before attaching, then listen via [onColorTapped].
 * Draws a thin white border ring and a violet highlight ring on the selected swatch.
 */
class ColorSwatchGridView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    /** Full list of colours to display, row-major order */
    var colors: List<Int> = emptyList()
        set(value) { field = value; requestLayout(); invalidate() }

    /** Number of columns in the grid */
    var columns: Int = 10
        set(value) { field = value; requestLayout(); invalidate() }

    /** Index of the currently selected swatch (-1 = none) */
    var selectedIndex: Int = -1
        set(value) { field = value; invalidate() }

    /** Callback fired when the user taps a swatch */
    var onColorTapped: ((color: Int, index: Int) -> Unit)? = null

    // ─── Drawing resources ────────────────────────────────────────────────────
    private val fillPaint   = Paint(Paint.ANTI_ALIAS_FLAG)
    private val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = Color.parseColor("#E0E0E0")
        strokeWidth = 1.5f
    }
    private val selectedPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = Color.parseColor("#7B1FA2")
        strokeWidth = 3f
    }
    private val whiteBorderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = Color.WHITE
        strokeWidth = 3f
    }

    private val swatchRect = RectF()
    private val GAP = 4f      // dp gap between swatches (we work in px after scaling)
    private val CORNER = 4f   // corner radius in px

    // Computed in onMeasure
    private var cellSize = 0f

    // ─── Measure ─────────────────────────────────────────────────────────────
    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val availableWidth = MeasureSpec.getSize(widthMeasureSpec) -
                paddingStart - paddingEnd
        val cols = columns.coerceAtLeast(1)
        cellSize = (availableWidth - GAP * (cols - 1)) / cols
        val rows = if (colors.isEmpty()) 0 else (colors.size + cols - 1) / cols
        val h = (cellSize * rows + GAP * (rows - 1)).toInt() +
                paddingTop + paddingBottom
        setMeasuredDimension(
            MeasureSpec.getSize(widthMeasureSpec),
            resolveSize(h, heightMeasureSpec)
        )
    }

    // ─── Draw ─────────────────────────────────────────────────────────────────
    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val cols = columns.coerceAtLeast(1)
        colors.forEachIndexed { idx, color ->
            val col = idx % cols
            val row = idx / cols
            val left  = paddingStart + col * (cellSize + GAP)
            val top   = paddingTop  + row * (cellSize + GAP)
            swatchRect.set(left, top, left + cellSize, top + cellSize)

            // Fill
            fillPaint.color = color
            canvas.drawRoundRect(swatchRect, CORNER, CORNER, fillPaint)

            // Thin grey border (helps white swatches stand out)
            canvas.drawRoundRect(swatchRect, CORNER, CORNER, borderPaint)

            // Selected highlight: white ring then violet ring
            if (idx == selectedIndex) {
                canvas.drawRoundRect(swatchRect, CORNER, CORNER, whiteBorderPaint)
                val sel = RectF(
                    swatchRect.left   + 2f,
                    swatchRect.top    + 2f,
                    swatchRect.right  - 2f,
                    swatchRect.bottom - 2f
                )
                canvas.drawRoundRect(sel, CORNER - 1f, CORNER - 1f, selectedPaint)
            }
        }
    }

    // ─── Touch ────────────────────────────────────────────────────────────────
    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (event.action != MotionEvent.ACTION_UP) return true
        val cols = columns.coerceAtLeast(1)
        val x = event.x - paddingStart
        val y = event.y - paddingTop
        val col = (x / (cellSize + GAP)).toInt().coerceIn(0, cols - 1)
        val row = (y / (cellSize + GAP)).toInt()
        val idx = row * cols + col
        if (idx in colors.indices) {
            selectedIndex = idx
            onColorTapped?.invoke(colors[idx], idx)
        }
        return true
    }
}
