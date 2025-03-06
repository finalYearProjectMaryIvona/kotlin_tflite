package com.example.trafficobjectdetection

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Bundle
import android.util.Log
import android.util.Size
import android.view.View
import android.widget.MediaController
import android.widget.Toast
import android.widget.ToggleButton
import android.widget.VideoView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.OptIn
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.AspectRatio
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.ExperimentalGetImage
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.example.trafficobjectdetection.Constants.LABELS_PATH
import com.example.trafficobjectdetection.Constants.MODEL_PATH
import com.example.trafficobjectdetection.databinding.ActivityMainBinding
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

// MainActivity handles camera initialization, image analysis, and object detection
class MainActivity : AppCompatActivity(), Detector.DetectorListener {

    // View binding for easy access to UI elements
    private lateinit var binding: ActivityMainBinding

    // Flag to track if the front camera is used (currently set to false)
    private val isFrontCamera = false

    // Camera-related objects
    private var preview: Preview? = null
    private var imageAnalyzer: ImageAnalysis? = null
    private var camera: Camera? = null
    private var cameraProvider: ProcessCameraProvider? = null

    // Object detection model
    private var detector: Detector? = null

    //toggle button for video/camera
    private lateinit var sourceToggle: ToggleButton
    //video
    private var isUsingVideo = false
    private var isVideoPlaying = false
    private lateinit var videoView: VideoView
    private lateinit var mediaController: MediaController

    private lateinit var videoExecutor: ExecutorService


    private val tracker = Tracker(maxDisappeared = 15, object : TrackerListener {
        override fun onTrackingCleared() {
            runOnUiThread {
                binding.overlay.clear() // Clear UI when tracking stops
            }
        }
    })


    // Background thread for executing camera tasks
    private lateinit var cameraExecutor: ExecutorService

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // Initialize UI elements
        videoView = binding.videoView
        sourceToggle = binding.sourceToggle

        // Set up MediaController for video controls
        mediaController = MediaController(this)
        mediaController.setAnchorView(videoView)
        videoView.setMediaController(mediaController)

        // Loop video on completion
        videoView.setOnCompletionListener {
            if (isUsingVideo) {
                videoView.start()
            }
        }

        // Set error listener
        videoView.setOnErrorListener { _, what, extra ->
            Log.e("VideoView", "Error playing video: what=$what, extra=$extra")
            toast("Error playing video")
            false
        }

        // Initialize a single-thread executor for running camera tasks
        cameraExecutor = Executors.newSingleThreadExecutor()

        videoExecutor = Executors.newSingleThreadExecutor()

        // Initialize object detector
        detector = Detector(baseContext, Constants.MODEL_PATH, Constants.LABELS_PATH, this) {
            toast(it)
        }
        // Initialize the object detector in a background thread
        cameraExecutor.execute {
            detector = Detector(baseContext, MODEL_PATH, LABELS_PATH, this) {
                toast(it) // Display any errors as toast messages
            }
        }

        // Check if the required permissions are granted, if yes, start the camera
        if (allPermissionsGranted()) {
            startCamera()
        } else {
            ActivityCompat.requestPermissions(this, REQUIRED_PERMISSIONS, REQUEST_CODE_PERMISSIONS)
        }

        // Toggle button for switching between camera and video
        sourceToggle.setOnCheckedChangeListener { _, isChecked ->
            isUsingVideo = isChecked
            if (isUsingVideo) {
                startVideoPlayback()
            } else {
                startCamera()
            }
        }

        // Setup UI listeners
        bindListeners()
    }

    // Function to handle UI interactions
    private fun bindListeners() {
        binding.apply {
            isGpu.setOnCheckedChangeListener { buttonView, isChecked ->
                cameraExecutor.submit {
                    detector?.restart(isGpu = isChecked) // Restart detector with GPU setting
                }
                // Change button color based on GPU activation status
                if (isChecked) {
                    buttonView.setBackgroundColor(ContextCompat.getColor(baseContext, R.color.orange))
                } else {
                    buttonView.setBackgroundColor(ContextCompat.getColor(baseContext, R.color.gray))
                }
            }
        }
    }

    private fun startVideoPlayback() {
        try {
            // Stop camera before starting video
            cameraProvider?.unbindAll()

            // Set visibility
            binding.viewFinder.visibility = View.GONE
            videoView.visibility = View.VISIBLE
            binding.overlay.visibility = View.VISIBLE

            // Load video from assets
            val videoFile = copyVideoFromAssets("test_video.mp4")
            val videoUri = Uri.fromFile(videoFile)
            videoView.setVideoURI(videoUri)

            // Start playing video
            videoView.requestFocus()
            videoView.start()
            isVideoPlaying = true

            // Start frame processing thread for object detection
            videoExecutor.execute {
                while (isUsingVideo && isVideoPlaying) {
                    try {
                        val bitmap = captureFrameFromVideoView()
                        bitmap?.let {
                            detector?.detect(it)
                            it.recycle()
                        }
                        Thread.sleep(100) // Process at 10 FPS
                    } catch (e: Exception) {
                        Log.e("VideoProcessing", "Error: ${e.message}")
                        Thread.sleep(100)
                    }
                }
            }
        } catch (e: Exception) {
            Log.e("VideoPlayback", "Error: ${e.message}")
            toast("Error playing video")
        }
    }

    /**
    * Capture the current frame from the VideoView.
    */
    private fun captureFrameFromVideoView(): Bitmap? {
        return try {
            val retriever = MediaMetadataRetriever()
            val videoFile = copyVideoFromAssets("test_video2.mp4")
            retriever.setDataSource(videoFile.absolutePath)

            val bitmap = retriever.getFrameAtTime(
                videoView.currentPosition * 1000L, // Convert ms to µs
                MediaMetadataRetriever.OPTION_CLOSEST_SYNC
            )

            retriever.release()
            bitmap
        } catch (e: Exception) {
            Log.e("VideoProcessing", "Error capturing frame: ${e.message}")
            null
        }
    }

    /**
     * Copy video from assets to internal storage.
     */
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


    // Function to initialize and start the camera
    @OptIn(ExperimentalGetImage::class)
    private fun startCamera() {
        // Stop video playback if it was running
        if (videoView.isPlaying) {
            videoView.stopPlayback()
        }
        isVideoPlaying = false
        // Show camera UI
        binding.viewFinder.visibility = View.VISIBLE
        videoView.visibility = View.GONE
        binding.overlay.visibility = View.VISIBLE

        val cameraProviderFuture = ProcessCameraProvider.getInstance(this)
        cameraProviderFuture.addListener({
            cameraProvider = cameraProviderFuture.get()
            bindCameraUseCases()
            val cameraSelector = CameraSelector.DEFAULT_BACK_CAMERA

            val preview = Preview.Builder().build()
            val imageAnalyzer = ImageAnalysis.Builder()
                .setTargetResolution(Size(640, 480))
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .build()

            imageAnalyzer.setAnalyzer(cameraExecutor) { imageProxy ->
                try {
                    val image = imageProxy.image ?: return@setAnalyzer
                    val yuvToJpegConverter = YuvToJpegConverter()
                    val jpegBytes = yuvToJpegConverter.convert(image)

                    if (jpegBytes != null) {
                        val bitmap = BitmapFactory.decodeByteArray(jpegBytes, 0, jpegBytes.size)
                        detector?.detect(bitmap)
                        bitmap.recycle()
                    }
                } catch (e: Exception) {
                    Log.e("CameraProcessing", "Error: ${e.message}")
                } finally {
                    imageProxy.close()
                }
            }

            cameraProvider?.unbindAll()
            cameraProvider?.bindToLifecycle(this, cameraSelector, preview, imageAnalyzer)
            preview.setSurfaceProvider(binding.viewFinder.surfaceProvider)

        }, ContextCompat.getMainExecutor(this))
    }

    // Function to bind camera use cases (preview and image analysis)
    private fun bindCameraUseCases() {
        val cameraProvider = cameraProvider ?: throw IllegalStateException("Camera initialization failed.")

        val rotation = binding.viewFinder.display.rotation

        // Select the back camera
        val cameraSelector = CameraSelector
            .Builder()
            .requireLensFacing(CameraSelector.LENS_FACING_BACK)
            .build()

        // Configure camera preview
        preview = Preview.Builder()
            .setTargetAspectRatio(AspectRatio.RATIO_4_3)
            .setTargetRotation(rotation)
            .build()

        // Configure image analysis for real-time object detection
        imageAnalyzer = ImageAnalysis.Builder()
            .setTargetAspectRatio(AspectRatio.RATIO_4_3)
            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
            .setTargetRotation(binding.viewFinder.display.rotation)
            .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888)
            .build()

        // Image processing logic
        imageAnalyzer?.setAnalyzer(cameraExecutor) { imageProxy ->
            // Convert ImageProxy to a Bitmap for processing
            val bitmapBuffer =
                Bitmap.createBitmap(
                    imageProxy.width,
                    imageProxy.height,
                    Bitmap.Config.ARGB_8888
                )
            imageProxy.use { bitmapBuffer.copyPixelsFromBuffer(imageProxy.planes[0].buffer) }
            imageProxy.close()

            // Apply transformations (rotation & mirroring for front camera)
            val matrix = Matrix().apply {
                postRotate(imageProxy.imageInfo.rotationDegrees.toFloat())

                if (isFrontCamera) {
                    postScale(
                        -1f,
                        1f,
                        imageProxy.width.toFloat(),
                        imageProxy.height.toFloat()
                    )
                }
            }

            // Create a rotated bitmap
            val rotatedBitmap = Bitmap.createBitmap(
                bitmapBuffer,
                0,
                0,
                bitmapBuffer.width,
                bitmapBuffer.height,
                matrix,
                true
            )

            // Run object detection on the processed image
            detector?.detect(rotatedBitmap)
        }

        // Unbind previous use cases before rebinding
        cameraProvider.unbindAll()

        try {
            // Bind camera lifecycle to the current activity
            camera = cameraProvider.bindToLifecycle(
                this,
                cameraSelector,
                preview,
                imageAnalyzer
            )

            // Attach the preview to the UI
            preview?.surfaceProvider = binding.viewFinder.surfaceProvider
        } catch (exc: Exception) {
            Log.e(TAG, "Use case binding failed", exc)
        }
    }

    // Check if the required permissions are granted
    private fun allPermissionsGranted() = REQUIRED_PERMISSIONS.all {
        ContextCompat.checkSelfPermission(baseContext, it) == PackageManager.PERMISSION_GRANTED
    }

    // Request permissions if not granted
    private val requestPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) {
        if (it[Manifest.permission.CAMERA] == true) { startCamera() }
    }

    // Show toast messages safely from background threads
    private fun toast(message: String) {
        runOnUiThread {
            Toast.makeText(baseContext, message, Toast.LENGTH_LONG).show()
        }
    }

    // Clean up resources when activity is destroyed
    override fun onDestroy() {
        super.onDestroy()
        detector?.close() // Close the object detector
        cameraExecutor.shutdown() // Shutdown the background executor
        videoExecutor.shutdown()
    }

    // Restart camera when returning to the app
    override fun onResume() {
        super.onResume()
        if (allPermissionsGranted()) {
            startCamera()
        } else {
            requestPermissionLauncher.launch(REQUIRED_PERMISSIONS)
        }
    }

    companion object {
        private const val TAG = "Camera" // Log tag
        private const val REQUEST_CODE_PERMISSIONS = 10 // Permission request code
        private val REQUIRED_PERMISSIONS = mutableListOf(Manifest.permission.CAMERA).toTypedArray()
    }

    // Callback function when no object is detected
    override fun onEmptyDetect() {
        runOnUiThread {
            binding.overlay.clear() // Clear overlay view
        }
    }

    // Callback function when objects are detected
    override fun onDetect(boundingBoxes: List<BoundingBox>, inferenceTime: Long) {
        runOnUiThread {
            binding.inferenceTime.text = "${inferenceTime}ms"

            if (boundingBoxes.isEmpty()) {
                binding.overlay.clear() // Clear when no objects are detected
            } else {
                binding.overlay.setResults(boundingBoxes)
                binding.overlay.invalidate()
            }
        }
    }
}