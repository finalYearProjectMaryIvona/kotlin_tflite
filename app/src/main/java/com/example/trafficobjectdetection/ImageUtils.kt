package com.example.trafficobjectdetection

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.Rect
import android.widget.ImageView
import java.io.IOException

/**
 * Utility functions for image loading , for home page image
 */
object ImageUtils {

    /**
     * Load an image from assets folder and apply it to an ImageView
     * @param context The context
     * @param imageName The filename in assets folder (e.g., "logo.png")
     * @param imageView The ImageView to set the image to
     * @param makeCircular Whether to make the image circular
     * @return true if successful, false otherwise
     */
    fun loadImageFromAssets(
        context: Context,
        imageName: String,
        imageView: ImageView,
        makeCircular: Boolean = false
    ): Boolean {
        try {
            // Open assets file
            context.assets.open(imageName).use { inputStream ->
                // Decode the bitmap
                val bitmap = BitmapFactory.decodeStream(inputStream)

                // Apply circular crop if requested
                val finalBitmap = if (makeCircular) {
                    getCircularBitmap(bitmap)
                } else {
                    bitmap
                }

                // Set to ImageView on main thread
                imageView.post {
                    imageView.setImageBitmap(finalBitmap)
                }

                return true
            }
        } catch (e: IOException) {
            e.printStackTrace()
            return false
        }
    }

    /**
     * Convert a bitmap to a circular bitmap
     * @param bitmap The source bitmap
     * @return A circular bitmap
     */
    private fun getCircularBitmap(bitmap: Bitmap): Bitmap {
        val output = Bitmap.createBitmap(
            bitmap.width,
            bitmap.height,
            Bitmap.Config.ARGB_8888
        )

        val canvas = Canvas(output)

        val paint = Paint().apply {
            isAntiAlias = true
            color = android.graphics.Color.BLACK
        }

        val rect = Rect(0, 0, bitmap.width, bitmap.height)
        canvas.drawCircle(
            bitmap.width / 2f,
            bitmap.height / 2f,
            bitmap.width.coerceAtMost(bitmap.height) / 2f,
            paint
        )

        paint.xfermode = PorterDuffXfermode(PorterDuff.Mode.SRC_IN)
        canvas.drawBitmap(bitmap, rect, rect, paint)

        return output
    }
}