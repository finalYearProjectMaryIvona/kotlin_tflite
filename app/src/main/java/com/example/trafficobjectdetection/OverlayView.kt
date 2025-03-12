package com.example.trafficobjectdetection

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View
import androidx.core.content.ContextCompat

class OverlayView(context: Context?, attrs: AttributeSet?) : View(context, attrs) {
    private var results = listOf<BoundingBox>()
    private var boxPaint = Paint()
    private var textBackgroundPaint = Paint()
    private var textPaint = Paint()
    private var directionPaint = Paint()
    private var bounds = Rect()

    init {
        initPaints()
    }

    fun clear() {
        results = listOf()
        textPaint.reset()
        textBackgroundPaint.reset()
        boxPaint.reset()
        directionPaint.reset()
        invalidate()
        initPaints()
    }

    private fun initPaints() {
        textBackgroundPaint.apply {
            color = Color.BLACK
            style = Paint.Style.FILL
            textSize = 50f
            alpha = 180
        }

        textPaint.apply {
            color = Color.WHITE
            style = Paint.Style.FILL
            textSize = 50f
        }

        boxPaint.apply {
            color = ContextCompat.getColor(context!!, R.color.bounding_box_color)
            strokeWidth = 8F
            style = Paint.Style.STROKE
        }

        directionPaint.apply {
            color = Color.YELLOW
            style = Paint.Style.FILL
            textSize = 40f
            alpha = 200
        }
    }

    override fun draw(canvas: Canvas) {
        super.draw(canvas)

        results.forEach { box ->
            val left = box.x1 * width
            val top = box.y1 * height
            val right = box.x2 * width
            val bottom = box.y2 * height

            // Get color based on class type
            val classType = box.clsName.lowercase().split(" ")[0]
            val classColor = Constants.CLASS_COLORS[classType] ?: Triple(255, 0, 0) // Default to red

            // Set box color for this specific class
            boxPaint.color = Color.rgb(classColor.first, classColor.second, classColor.third)

            // Draw bounding box
            canvas.drawRect(left, top, right, bottom, boxPaint)

            // Split text into lines (class name, ID, direction)
            val textLines = box.clsName.split("\n")

            var yOffset = top
            textLines.forEachIndexed { index, line ->
                // Measure text
                textPaint.getTextBounds(line, 0, line.length, bounds)
                val textWidth = bounds.width()
                val textHeight = bounds.height()

                // Draw background rectangle
                val bgRect = RectF(
                    left,
                    yOffset,
                    left + textWidth + PADDING * 2,
                    yOffset + textHeight + PADDING
                )

                canvas.drawRect(bgRect, textBackgroundPaint)

                // Draw text
                val paint = if (index == 2 && line.isNotEmpty()) directionPaint else textPaint
                canvas.drawText(
                    line,
                    left + PADDING,
                    yOffset + textHeight,
                    paint
                )

                yOffset += textHeight + PADDING
            }
        }
    }

    fun setResults(boundingBoxes: List<BoundingBox>) {
        results = boundingBoxes
        invalidate()
    }

    companion object {
        private const val PADDING = 20f
    }
}