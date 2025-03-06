package com.example.trafficobjectdetection

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Matrix
import android.os.Bundle
import android.util.Log
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.AspectRatio
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.example.trafficobjectdetection.Constants.LABELS_PATH
import com.example.trafficobjectdetection.Constants.MODEL_PATH
import com.example.trafficobjectdetection.databinding.ActivityMainBinding
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

        // Initialize a single-thread executor for running camera tasks
        cameraExecutor = Executors.newSingleThreadExecutor()

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

    // Function to initialize and start the camera
    private fun startCamera() {
        val cameraProviderFuture = ProcessCameraProvider.getInstance(this)
        cameraProviderFuture.addListener({
            cameraProvider = cameraProviderFuture.get()
            bindCameraUseCases()
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