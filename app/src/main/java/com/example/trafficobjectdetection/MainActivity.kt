package com.example.trafficobjectdetection

import android.Manifest
import android.content.ContentValues
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Matrix
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.MediaStore
import android.util.Log
import android.view.View
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
import com.example.trafficobjectdetection.api.ApiHelper
import com.example.trafficobjectdetection.databinding.ActivityMainBinding
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
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

    // Tracker for object detection
    private val tracker = Tracker(maxDisappeared = 15, object : TrackerListener {
        override fun onTrackingCleared() {
            runOnUiThread {
                binding.overlay.clear() // Clear UI when tracking stops
            }
        }
    })

    // Vehicle tracker for logging exit events
    private lateinit var vehicleTracker: VehicleTracker

    // Background thread for executing camera tasks
    private lateinit var cameraExecutor: ExecutorService

    // Permission request for saving images in test mode
    private val storagePermissionRequest = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val storageGranted = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            true // Android 10+ uses scoped storage, no explicit permission needed
        } else {
            permissions[Manifest.permission.WRITE_EXTERNAL_STORAGE] == true
        }

        if (storageGranted) {
            toast("Storage permission granted, test mode enabled")
            vehicleTracker.testMode = true
            binding.switchTestMode.isChecked = true
            createTestDirectory()
        } else {
            toast("Storage permission denied. Images will be saved to app internal storage.")
            vehicleTracker.testMode = true // Still enable test mode, but use internal storage
            binding.switchTestMode.isChecked = true
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // Initialize the Vehicle Tracker with context for test mode
        vehicleTracker = VehicleTracker(
            serverReporter = object : ServerReporter {
                override fun sendData(data: Map<String, Any>) {
                    // In normal mode, this would send to the database
                    if (!vehicleTracker.testMode) {
                        // Create a copy with session ID if not already present
                        val dataWithSessionId = data.toMutableMap().apply {
                            if (!containsKey("session_id")) {
                                put("session_id", vehicleTracker.getSessionId())
                            }
                        }

                        if (dataWithSessionId.containsKey("image_data")) {
                            Log.d("VehicleDatabase", "Sending data with image to database. Session ID: ${dataWithSessionId["session_id"]}")
                            uploadVehicleDataWithImage(dataWithSessionId)
                        } else {
                            Log.d("VehicleDatabase", "Sending data to database: ${dataWithSessionId.keys}. Session ID: ${dataWithSessionId["session_id"]}")
                            uploadVehicleData(dataWithSessionId)
                        }
                    }
                }
            },
            context = this // Pass context for test mode
        )

        // Initialize a single-thread executor for running camera tasks
        cameraExecutor = Executors.newSingleThreadExecutor()

        // Initialize the object detector in a background thread
        cameraExecutor.execute {
            detector = Detector(baseContext, MODEL_PATH, LABELS_PATH, this) {
                toast(it) // Display any errors as toast messages
            }
        }

        // Initialize the vehicle tracker
        vehicleTracker.initialize()

        // Log the session ID when the activity starts
        Log.d("MainActivity", "Initialized with session ID: ${vehicleTracker.getSessionId()}")

        // Check if the required permissions are granted, if yes, start the camera
        if (allPermissionsGranted()) {
            startCamera()
        } else {
            ActivityCompat.requestPermissions(this, REQUIRED_PERMISSIONS, REQUEST_CODE_PERMISSIONS)
        }

        // Setup UI listeners
        bindListeners()

        // Setup test mode switch
        setupTestModeSwitch()
    }

    // Create test directory for saving images
    private fun createTestDirectory() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            try {
                val directory = File(
                    Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES),
                    "TrafficDetection"
                )

                if (!directory.exists()) {
                    val success = directory.mkdirs()
                    if (success) {
                        Log.d("MainActivity", "Successfully created directory: ${directory.absolutePath}")
                    } else {
                        Log.e("MainActivity", "Failed to create directory: ${directory.absolutePath}")
                    }
                } else {
                    Log.d("MainActivity", "Directory already exists: ${directory.absolutePath}")
                }
            } catch (e: Exception) {
                Log.e("MainActivity", "Error creating directory: ${e.message}")
            }
        }

        // Also create a directory in app's internal storage as fallback
        try {
            val internalDir = File(filesDir, "TrafficDetection")
            if (!internalDir.exists()) {
                internalDir.mkdirs()
            }
        } catch (e: Exception) {
            Log.e("MainActivity", "Error creating internal directory: ${e.message}")
        }

        // Test write access by writing a small test file
        testWriteAccess()
    }

    // Test if we can write files
    private fun testWriteAccess() {
        try {
            // Test internal storage first (should always work)
            val internalFile = File(filesDir, "test_write.txt")
            FileOutputStream(internalFile).use { it.write("test".toByteArray()) }
            Log.d("MainActivity", "Successfully wrote to internal storage")

            // For Android 10+, test MediaStore API
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val contentValues = ContentValues().apply {
                    put(MediaStore.MediaColumns.DISPLAY_NAME, "test_write.txt")
                    put(MediaStore.MediaColumns.MIME_TYPE, "text/plain")
                    put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOCUMENTS)
                }

                val uri = contentResolver.insert(MediaStore.Files.getContentUri("external"), contentValues)
                uri?.let { outputUri ->
                    contentResolver.openOutputStream(outputUri)?.use {
                        it.write("test".toByteArray())
                    }
                    // Delete the test file
                    contentResolver.delete(outputUri, null, null)
                    Log.d("MainActivity", "Successfully wrote to MediaStore")
                }
            } else {
                // For older versions, test direct file access
                val directory = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES)
                val file = File(directory, "test_write.txt")
                FileOutputStream(file).use { it.write("test".toByteArray()) }
                file.delete()
                Log.d("MainActivity", "Successfully wrote to external storage")
            }
        } catch (e: IOException) {
            Log.e("MainActivity", "Write test failed: ${e.message}")
            toast("Warning: Write access test failed. Images may not save correctly.")
        }
    }

    // Setup the test mode switch
    private fun setupTestModeSwitch() {
        // Make the test mode switch visible
        binding.switchTestMode.visibility = View.VISIBLE
        binding.testModeLabel.visibility = View.VISIBLE

        binding.switchTestMode.setOnCheckedChangeListener { _, isChecked ->
            if (isChecked) {
                // Enable test mode - check for storage permission first
                if (hasStoragePermission()) {
                    vehicleTracker.testMode = true
                    toast("Test mode enabled - cup/bus images will be saved")
                    createTestDirectory()
                } else {
                    // Request storage permission
                    requestStoragePermission()
                }
            } else {
                // Disable test mode
                vehicleTracker.testMode = false
                toast("Test mode disabled")
            }
        }
    }

    // Check if we have storage permission
    private fun hasStoragePermission(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            // Android 10+ uses scoped storage
            true
        } else {
            ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.WRITE_EXTERNAL_STORAGE
            ) == PackageManager.PERMISSION_GRANTED
        }
    }

    // Request storage permission
    private fun requestStoragePermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            // Android 10+ uses scoped storage, no permission needed
            vehicleTracker.testMode = true
            binding.switchTestMode.isChecked = true
            createTestDirectory()
        } else {
            storagePermissionRequest.launch(arrayOf(Manifest.permission.WRITE_EXTERNAL_STORAGE))
        }
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

            // Pass the current frame to the vehicle tracker for potential capture
            vehicleTracker.setCurrentFrame(rotatedBitmap)

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

    /**
     * Upload vehicle data to your database
     */
    private fun uploadVehicleData(data: Map<String, Any>) {
        // Execute in background thread
        cameraExecutor.execute {
            try {
                // Example implementation - replace with your actual database API call
                Log.d("Database", "Would upload vehicle data: ${JSONObject(data)}")

                // Here's where you'd implement your actual database upload
                // For example with Firebase:
                // val databaseRef = FirebaseDatabase.getInstance().reference
                // databaseRef.child("vehicles").push().setValue(data)

                // Or with a custom API:
                val deviceId = data["vehicle_id"]?.toString() ?: ""
                val timestamp = data["exit_timestamp"]?.toString() ?: ""
                val location = "${data["exit_position_x"]},${data["exit_position_y"]}"
                val objectType = data["vehicle_type"]?.toString() ?: ""
                val direction = data["direction"]?.toString() ?: ""
                val sessionId = data["session_id"]?.toString() ?: ""

                ApiHelper.sendTrackingLog(deviceId, timestamp, location, objectType, direction, sessionId)

            } catch (e: Exception) {
                Log.e("Database", "Error uploading vehicle data: ${e.message}")
            }
        }
    }

    /**
     * Upload vehicle data with image to your database
     */
    private fun uploadVehicleDataWithImage(data: Map<String, Any>) {
        // Execute in background thread
        cameraExecutor.execute {
            try {
                Log.d("Database", "Would upload vehicle data with image")

                // Here you'd implement your actual database upload with image
                // For example, using our ApiHelper:
                ApiHelper.sendVehicleDataWithImage(data, cacheDir)

            } catch (e: Exception) {
                Log.e("Database", "Error uploading vehicle data with image: ${e.message}")
            }
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
        vehicleTracker.clear() // Clear vehicle tracking data
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
        private val REQUIRED_PERMISSIONS = mutableListOf(
            Manifest.permission.CAMERA
        ).apply {
            // Add storage permission for Android 9 and below
            if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.P) {
                add(Manifest.permission.WRITE_EXTERNAL_STORAGE)
            }
        }.toTypedArray()
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
                // Process vehicle tracking (entry and exit events)
                vehicleTracker.processDetections(boundingBoxes)

                binding.overlay.setResults(boundingBoxes)
                binding.overlay.invalidate()
            }
        }
    }
}