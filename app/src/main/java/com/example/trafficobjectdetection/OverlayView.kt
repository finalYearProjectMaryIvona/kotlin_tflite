package com.example.trafficobjectdetection
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.util.AttributeSet
import android.view.View
import androidx.core.content.ContextCompat
import com.example.trafficobjectdetection.R

// Custom view to overlay the bounding boxes and labels
class OverlayView(context: Context?, attrs: AttributeSet?) : View(context, attrs) {
    //store the bounding boxes in a list
    private var results = listOf<BoundingBox>()
    //paint the boxes
    private var boxPaint = Paint()
    private var textBackgroundPaint = Paint()
    private var textPaint = Paint()
    //rect bont for the text backrounf
    private var bounds = Rect()

    init {
        //set up the paint
        initPaints()
    }
    //clear detections ftom overlay
    fun clear() {
        results = listOf()
        textPaint.reset()
        textBackgroundPaint.reset()
        boxPaint.reset()
        invalidate()
        initPaints()
    }

    //set up the paint styles and colours
    private fun initPaints() {
        //text background
        textBackgroundPaint.color = Color.BLACK
        textBackgroundPaint.style = Paint.Style.FILL
        textBackgroundPaint.textSize = 50f
        //text
        textPaint.color = Color.WHITE
        textPaint.style = Paint.Style.FILL
        textPaint.textSize = 50f
        //bounding boxes
        boxPaint.color = ContextCompat.getColor(context!!, R.color.bounding_box_color)
        boxPaint.strokeWidth = 8F
        boxPaint.style = Paint.Style.STROKE
    }

    //draw the bounding boxes and lables
    override fun draw(canvas: Canvas) {
        super.draw(canvas)

        //each bounding box
        results.forEach {
            //scale coordinates to match view size
            val left = it.x1 * width
            val top = it.y1 * height
            val right = it.x2 * width
            val bottom = it.y2 * height

            //object class name
            canvas.drawRect(left, top, right, bottom, boxPaint)
            val drawableText = it.clsName

            //measure text bounds
            textBackgroundPaint.getTextBounds(drawableText, 0, drawableText.length, bounds)
            val textWidth = bounds.width()
            val textHeight = bounds.height()
            //drae the text backround rect
            canvas.drawRect(
                left,
                top,
                left + textWidth + BOUNDING_RECT_TEXT_PADDING,
                top + textHeight + BOUNDING_RECT_TEXT_PADDING,
                textBackgroundPaint
            )
            canvas.drawText(drawableText, left, top + bounds.height(), textPaint)

        }
    }

    //Update the bounding boxes and refresh the view
    fun setResults(boundingBoxes: List<BoundingBox>) {
        results = boundingBoxes
        invalidate()
    }

    companion object {
        private const val BOUNDING_RECT_TEXT_PADDING = 8
    }
}