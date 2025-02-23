package com.example.trafficobjectdetection

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.example.trafficobjectdetection.Detector
import io.mockk.mockk
import io.mockk.verify
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class DetectorTest {
    private lateinit var detector: Detector
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val mockListener: Detector.DetectorListener = mockk(relaxed = true)

    @Before
    fun setUp() {
        detector = Detector(context, "fake_model.tflite", "fake_labels.txt", mockListener, mockk(relaxed = true))
    }

    @Test
    fun testDetectorProcessesAnImageWithoutCrashing() {
        val testBitmap: Bitmap = Bitmap.createBitmap(100, 100, Bitmap.Config.ARGB_8888).apply {
            eraseColor(Color.BLACK) // Fill the bitmap with black color
        }

        // Run detection on a real bitmap
        detector.detect(testBitmap)

        // Ensure the listener method was called
        verify {
            mockListener.onEmptyDetect()
            mockListener.onDetect(any(), any())
        }
    }
}
