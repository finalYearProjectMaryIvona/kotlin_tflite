package com.example.trafficobjectdetection

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class DetectorTest {
    private lateinit var detector: Detector
    private val context: Context = ApplicationProvider.getApplicationContext()

    // Use a Fake Listener Instead of MockK
    private val fakeListener = object : Detector.DetectorListener {
        override fun onEmptyDetect() {
            // No-op (Do nothing)
        }

        override fun onDetect(boundingBoxes: List<BoundingBox>, inferenceTime: Long) {
            // No-op (Do nothing)
        }
    }

    @Before
    fun setUp() {
        // Fix the constructor by passing a non-null message lambda
        detector = Detector(context, "model.tflite", "labels.txt", fakeListener) { message ->
            println("Detector message: $message")
        }
    }

    @Test
    fun testDetectorProcessesAnImageWithoutCrashing() {
        val testBitmap: Bitmap = Bitmap.createBitmap(100, 100, Bitmap.Config.ARGB_8888).apply {
            eraseColor(Color.BLACK) // Fill the bitmap with black color
        }

        // Run detection on the fake bitmap
        detector.detect(testBitmap)
    }
}
