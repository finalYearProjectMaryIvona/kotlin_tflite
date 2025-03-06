package com.example.trafficobjectdetection

import android.graphics.*
import android.media.Image
import java.io.ByteArrayOutputStream

class YuvToJpegConverter {
    fun convert(image: Image): ByteArray? {
        return try {
            val width = image.width
            val height = image.height

            // Get image planes
            val planes = image.planes
            if (planes.size < 3) return null

            val yBuffer = planes[0].buffer // Y plane
            val uBuffer = planes[1].buffer // U plane
            val vBuffer = planes[2].buffer // V plane

            val ySize = yBuffer.remaining()
            val uSize = uBuffer.remaining()
            val vSize = vBuffer.remaining()

            // Create a NV21 formatted byte array
            val nv21 = ByteArray(width * height * 3 / 2)

            // Copy Y plane
            yBuffer.get(nv21, 0, ySize)

            // Interleave UV planes
            var pos = width * height
            for (row in 0 until height / 2) {
                for (col in 0 until width / 2) {
                    val vuPos = row * planes[1].rowStride + col * planes[1].pixelStride
                    nv21[pos++] = vBuffer.get(vuPos) // V
                    nv21[pos++] = uBuffer.get(vuPos) // U
                }
            }

            // Convert YUV to JPEG
            val yuvImage = YuvImage(nv21, ImageFormat.NV21, width, height, null)
            val outputStream = ByteArrayOutputStream()
            yuvImage.compressToJpeg(Rect(0, 0, width, height), 90, outputStream)

            outputStream.toByteArray()
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }
}
