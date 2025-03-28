package com.example.trafficobjectdetection

import android.graphics.Bitmap
import android.net.Uri
import android.os.Bundle
import android.util.Log
import android.widget.MediaController
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.example.trafficobjectdetection.Constants.LABELS_PATH
import com.example.trafficobjectdetection.Constants.MODEL_PATH
import com.example.trafficobjectdetection.databinding.ActivityVideoBinding
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

class VideoActivity : AppCompatActivity(), Detector.DetectorListener {

    private lateinit var binding: ActivityVideoBinding
    private val TEST_VIDEO_PATH = "test_video2.mp4"
    private var frameProcessor: SequentialFrameProcessor? = null
    private val isProcessingFrame = AtomicBoolean(false)
    private val lastFrameTimestamp = AtomicLong(0)
    private var detector: Detector? = null
    // Executor for background processing
    private lateinit var processingExecutor: ExecutorService

    // Object tracker for keeping track of detected objects
    private val tracker = Tracker(maxDisappeared = 30, object : TrackerListener {
        override fun onTrackingCleared() {
            runOnUiThread {
                // Clear UI overlay when tracking is reset
                binding.overlay.clear()
            }
        }
    })

    // Vehicle tracker with a server reporter to send tracking data
    private lateinit var vehicleTracker: VehicleTracker

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityVideoBinding.inflate(layoutInflater)
        setContentView(binding.root)

        vehicleTracker = VehicleTracker(
            context = this,
            serverReporter = object : ServerReporter {
                override fun sendData(data: Map<String, Any>) {
                    val dataWithSessionId = data.toMutableMap().apply {
                        if (!containsKey("session_id")) {
                            put("session_id", vehicleTracker.getSessionId())
                        }
                    }

                    Log.d("VehicleDatabase", dataWithSessionId.toString())
                }
            }
        )


        // Initialise the processing executor for background tasks
        processingExecutor = Executors.newSingleThreadExecutor()
        processingExecutor.execute {
            detector = Detector(baseContext, MODEL_PATH, LABELS_PATH, this) {
                // Show toast message for errors
                toast(it)
            }
        }

        //initialise vehicle tracker
        vehicleTracker.initialize()

        // Log the session ID when the activity starts
        Log.d("VideoActivity", "Initialized with session ID: ${vehicleTracker.getSessionId()}")

        //set up video player
        initVideoView()
        // Start processing when the button is clicked
        binding.btnStartProcessing.setOnClickListener {
            resetDetection()
            startVideoProcessing()
        }
    }

    // Reset detection by stopping video processing and clearing previous results
    private fun resetDetection() {
        stopVideoProcessing()
        processingExecutor.execute {
            tracker.update(emptyList())
            vehicleTracker.clear()
        }
        runOnUiThread { binding.overlay.clear() }
    }

    // Initialize the video view with media controls
    private fun initVideoView() {
        val mediaController = MediaController(this)
        mediaController.setAnchorView(binding.videoView)
        binding.videoView.setMediaController(mediaController)
        binding.videoView.setOnCompletionListener { binding.videoView.start() }
    }

    // Start processing the video frame-by-frame
    private fun startVideoProcessing() {
        val videoFile = copyVideoFromAssets(TEST_VIDEO_PATH)
        runOnUiThread {
            val videoUri = Uri.fromFile(videoFile)
            binding.videoView.setVideoURI(videoUri)
            binding.videoView.start()
            binding.overlay.bringToFront()
        }

        frameProcessor = SequentialFrameProcessor(this)
        frameProcessor?.startProcessing(
            TEST_VIDEO_PATH,
            object : SequentialFrameProcessor.FrameCallback {
                override fun onFrameAvailable(bitmap: Bitmap, frameNumber: Int, timestamp: Long) {
                    if (isProcessingFrame.get()) {
                        return  // Skip if still processing the previous frame
                    }
                    isProcessingFrame.set(true)

                    processingExecutor.execute {
                        synchronized(tracker) {
                            // Run object detection
                            detector?.detect(bitmap)
                        }
                        isProcessingFrame.set(false)
                    }
                }

                override fun onError(exception: Exception) {
                    toast("Video Processing Error: ${exception.message}")
                }
            },
            10 // Lower FPS to reduce frame skipping
        )
    }

    // Stop video processing and clear the overlay
    private fun stopVideoProcessing() {
        frameProcessor?.stopProcessing()
        binding.videoView.stopPlayback()
        binding.overlay.clear()
    }

    // Copy video file from assets to cache directory for processing
    private fun copyVideoFromAssets(filename: String): File {
        val file = File(cacheDir, filename)
        if (!file.exists()) {
            val inputStream: InputStream = assets.open(filename)
            val outputStream = FileOutputStream(file)
            inputStream.copyTo(outputStream)
            inputStream.close()
            outputStream.close()
        }
        return file
    }

    // Display a toast message on the UI thread
    private fun toast(message: String) {
        runOnUiThread {
            Toast.makeText(baseContext, message, Toast.LENGTH_LONG).show()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        detector?.close()
        processingExecutor.shutdown()
        vehicleTracker.clear()
        stopVideoProcessing()
    }

    // when objects are detected in a frame
    override fun onDetect(boundingBoxes: List<BoundingBox>, inferenceTime: Long) {
        runOnUiThread {
            // Display inference time
            binding.inferenceTime.text = "${inferenceTime}ms"
            if (boundingBoxes.isEmpty()) {
                binding.overlay.clear()
            } else {
                vehicleTracker.processDetections(boundingBoxes)
                val trackedBoxes = synchronized(tracker) {
                    tracker.update(boundingBoxes)
                }
                binding.overlay.setResults(trackedBoxes)
                binding.overlay.invalidate()
            }
        }
    }

    // when no objects are detected in a frame
    override fun onEmptyDetect() {
        runOnUiThread {
            binding.overlay.clear()
        }
    }

    companion object {
        private const val TAG = "VideoActivity"
    }
}