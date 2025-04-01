package com.example.trafficobjectdetection

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.media.MediaScannerConnection
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.util.Base64
import android.util.Log
import android.widget.Toast
import com.example.trafficobjectdetection.api.ApiHelper
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeoutOrNull

/**
 * VehicleTracker - Tracks vehicles across frames and reports entry/exit events
 * with image capture for buses and cups (for testing).
 */
class VehicleTracker(
    private val serverReporter: ServerReporter? = null,
    private val context: Context? = null
) {

    // Store tracking data for vehicles that have been detected
    private val vehicles = ConcurrentHashMap<String, VehicleData>()

    // Track which class+id combinations have been reported to avoid duplicate reports
    private val reportedVehicles = ConcurrentHashMap<String, Long>()

    // Track which specific bus images have been sent to avoid duplicates
    private val reportedBusImages = ConcurrentHashMap<String, Long>()

    // Cooldown time in milliseconds before sending another image of the same bus
    private val BUS_IMAGE_COOLDOWN = 5000L // 5 seconds

    // Minimum distance (in normalized coordinates) a bus needs to move before sending another image
    private val MIN_DISTANCE_FOR_NEW_IMAGE = 0.1f

    // List of vehicle-type classes to track
    private val vehicleClasses = setOf("car", "truck", "bus", "motorcycle", "bicycle", "cup")

    // Classes that should have images captured
    private val captureImageClasses = setOf("bus")

    // Coroutine scope for async operations
    private val scope = CoroutineScope(Dispatchers.IO)

    // Track whether this tracker has been initialized
    private var isInitialized = false

    // Current frame bitmap for capturing
    private var currentFrameBitmap: Bitmap? = null

    // Test mode flag - when true, images will be saved locally
    var testMode = false

    // Session ID for grouping tracked objects
    private val sessionId = ApiHelper.getSessionId()

    // Flag to indicate if we should only capture entry/exit images (true) or all frames (false)
    private val captureOnlyEntryExit = true

    private var locationHelper: LocationHelper? = null

    private var userId: String = ""
    private var isSessionPublic: Boolean = false

    /**
     * Set the user ID for this tracking session
     */
    fun setUserId(id: String) {
        userId = id
        Log.d("VehicleTracker", "User ID set: $userId")
    }

    /**
     * Set whether this session should be public
     */
    fun setSessionPublic(isPublic: Boolean) {
        isSessionPublic = isPublic
        Log.d("VehicleTracker", "Session visibility set to public: $isPublic")
    }
    /**
     * Initialize the tracker if not already initialized
     */
    fun initialize() {
        if (!isInitialized) {
            isInitialized = true
            Log.d("VehicleTracker", "Vehicle tracker initialized with session ID: $sessionId")
        }
    }

    /**
     * Set the current frame bitmap that can be captured when vehicles are detected
     */
    fun setCurrentFrame(bitmap: Bitmap) {
        currentFrameBitmap = bitmap
    }

    /**
     * Process a list of detected bounding boxes to track vehicles
     * and report entry/exit events.
     */
    fun processDetections(boxes: List<BoundingBox>) {
        // Make sure tracker is initialized
        initialize()

        // Process each detected object
        boxes.forEach { box ->
            // Extract the base class name (without tracking ID)
            val className = box.clsName.split(" ")[0].lowercase()

            // Only process vehicle classes
            if (className in vehicleClasses) {
                // Extract the tracking ID if available
                val idPart = box.clsName.split(" ").find { it.contains("#") } ?: ""
                val id = idPart.replace("#", "")

                // Create a unique vehicle key
                val vehicleKey = "$className-$id"

                // Create a report key combining class and ID
                val reportKey = "$className-$id"

                // Check if this vehicle has been reported recently (within 10 seconds)
                val hasBeenReportedRecently = reportedVehicles.containsKey(reportKey) &&
                        (System.currentTimeMillis() - reportedVehicles[reportKey]!!) < 10000

                // Special handling for bus class
                if (className == "bus" && currentFrameBitmap != null) {
                    if (!vehicles.containsKey(vehicleKey)) {
                        // New vehicle detected - record entry data
                        val vehicleData = VehicleData(
                            id = id,
                            className = className,
                            entryTime = System.currentTimeMillis(),
                            entryPosition = Pair(box.cx, box.cy),
                            confidence = box.cnf
                        )

                        // Capture entry image
                        if (className in captureImageClasses && currentFrameBitmap != null) {
                            vehicleData.capturedImage = captureVehicleImage(currentFrameBitmap!!, box)

                            // In test mode, save the image locally
                            if (testMode && context != null) {
                                saveImageForTesting(vehicleData, "entry")
                            }

                            // Send entry image to server
                            if (!reportedVehicles.containsKey(reportKey)) {
                                sendBusImageToServer(box, className, "entry")
                                Log.d("VehicleTracker", "Captured and sent ENTRY image for $className ID: $id")
                                reportedVehicles[reportKey] = System.currentTimeMillis()
                            }
                        }

                        vehicles[vehicleKey] = vehicleData
                    } else {
                        // Update existing vehicle data
                        val vehicleData = vehicles[vehicleKey]!!
                        vehicleData.lastPosition = Pair(box.cx, box.cy)
                        vehicleData.lastUpdateTime = System.currentTimeMillis()
                        vehicleData.confidence = box.cnf

                        // Only for exit events
                        if (isNearEdge(box) && !vehicleData.exitReported) {
                            vehicleData.exitPosition = Pair(box.cx, box.cy)
                            vehicleData.exitTime = System.currentTimeMillis()
                            vehicleData.exitReported = true

                            // Capture exit image
                            if (currentFrameBitmap != null) {
                                vehicleData.capturedImage = captureVehicleImage(currentFrameBitmap!!, box)

                                // Test mode saving
                                if (testMode && context != null) {
                                    saveImageForTesting(vehicleData, "exit")
                                }

                                // Send exit image only once
                                sendBusImageToServer(box, className, "exit")
                                Log.d("VehicleTracker", "Captured and sent EXIT image for $className ID: $id")
                            }
                        }
                    }
                }

                // Handle other vehicles (cars, cups, etc.) - similar logic but for other vehicle types
                else if (className in vehicleClasses && !hasBeenReportedRecently) {
                    if (!vehicles.containsKey(vehicleKey)) {
                        // New vehicle detected - record entry data
                        val vehicleData = VehicleData(
                            id = id,
                            className = className,
                            entryTime = System.currentTimeMillis(),
                            entryPosition = Pair(box.cx, box.cy),
                            confidence = box.cnf
                        )

                        // Capture image if this is a class we want to photograph
                        if (className in captureImageClasses && currentFrameBitmap != null) {
                            vehicleData.capturedImage = captureVehicleImage(currentFrameBitmap!!, box)

                            // In test mode, save the image locally
                            if (testMode && context != null) {
                                saveImageForTesting(vehicleData, "entry")
                            }
                        }

                        vehicles[vehicleKey] = vehicleData

                        // Only send a single entry to avoid duplicates - this is the key change
                        // Adding a small delay to allow GPS to be acquired
                        scope.launch(Dispatchers.IO) {
                            // Wait a tiny bit before sending to allow GPS to catch up
                            delay(200)
                            reportVehicleEntry(vehicleData)
                        }

                        // Mark this vehicle as reported
                        reportedVehicles[reportKey] = System.currentTimeMillis()
                    } else {
                        // Update existing vehicle data
                        val vehicleData = vehicles[vehicleKey]!!
                        vehicleData.lastPosition = Pair(box.cx, box.cy)
                        vehicleData.lastUpdateTime = System.currentTimeMillis()
                        vehicleData.confidence = box.cnf

                        // Check if the vehicle is at the edge and likely to exit
                        if (isNearEdge(box) && !vehicleData.exitReported) {
                            vehicleData.exitPosition = Pair(box.cx, box.cy)
                            vehicleData.exitTime = System.currentTimeMillis()
                            vehicleData.exitReported = true

                            // Capture exit image if this class should have images
                            if (className in captureImageClasses && currentFrameBitmap != null) {
                                vehicleData.capturedImage = captureVehicleImage(currentFrameBitmap!!, box)

                                // Send exit image to server if it's a bus
                                if (captureOnlyEntryExit && className == "bus") {
                                    sendBusImageToServer(box, className, "exit")
                                    Log.d("VehicleTracker", "Captured and sent EXIT image for $className ID: $id")
                                }
                            }

                            // Report vehicle exit
                            reportVehicleExit(vehicleData)

                            // Mark this vehicle as reported
                            reportedVehicles[reportKey] = System.currentTimeMillis()
                        }
                    }
                }
            }
        }

        // Clean up vehicles that haven't been updated in a while
        cleanupStaleVehicles()
    }

    /**
     * Set the LocationHelper for GPS data
     * This allows the tracker to get real GPS coordinates
     */
    fun setLocationHelper(helper: LocationHelper) {
        locationHelper = helper
        Log.d("VehicleTracker", "LocationHelper set for GPS data")
    }

    /**
     * Check if a bus image has been sent from a nearby position
     */
    private fun hasNearbyBusImageBeenSent(cx: Float, cy: Float, id: String): Boolean {
        // Look through all reported bus images and check if any are close to this position
        for (key in reportedBusImages.keys) {
            if (key.startsWith("bus-$id-")) {
                try {
                    // Extract the position from the key (format: "bus-id-x,y")
                    val positionPart = key.substringAfter("bus-$id-")
                    val parts = positionPart.split(",")
                    if (parts.size == 2) {
                        val savedX = parts[0].toFloat()
                        val savedY = parts[1].toFloat()

                        // Calculate distance in normalized coordinates
                        val dx = cx - savedX
                        val dy = cy - savedY
                        val distance = kotlin.math.sqrt((dx * dx + dy * dy).toDouble()).toFloat()

                        // If the distance is less than threshold, consider it close enough
                        if (distance < MIN_DISTANCE_FOR_NEW_IMAGE) {
                            return true
                        }
                    }
                } catch (e: Exception) {
                    Log.e("VehicleTracker", "Error parsing position from key: $key", e)
                }
            }
        }
        return false
    }

    /**
     * Send bus image to server for storage in bus_images collection
     */
    private fun sendBusImageToServer(box: BoundingBox, className: String, eventType: String = "continuous") {
        // Extract ID from box.clsName (it contains something like "bus #1")
        val idPart = box.clsName.split(" ").find { it.contains("#") } ?: ""
        val deviceId = idPart.replace("#", "")

        // Don't capture if no bitmap available
        if (currentFrameBitmap == null) {
            Log.e("VehicleTracker", "Cannot send bus image: currentFrameBitmap is null")
            return
        }

        // Capture the image
        val capturedBitmap = captureVehicleImage(currentFrameBitmap!!, box)

        // Generate location string from the bounding box center
        val location = "${box.cx},${box.cy}"
        val timestamp = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date())
        val imageBase64 = bitmapToBase64(capturedBitmap)
        val sessionId = ApiHelper.getSessionId()

        // Wait for GPS data with timeout
        scope.launch(Dispatchers.IO) {
            // Try to get GPS data with waiting
            var gpsLocation = locationHelper?.getLocationString() ?: ""
            var gpsLatitude = locationHelper?.getLatitude()
            var gpsLongitude = locationHelper?.getLongitude()

            // Wait up to 2 seconds for GPS data if not available
            var waitTime = 0
            val maxWaitTime = 2000 // 2 seconds max wait

            while ((gpsLocation.isEmpty() || gpsLocation == "unknown,unknown" ||
                        gpsLatitude == null || gpsLongitude == null) &&
                waitTime < maxWaitTime) {
                delay(500)  // Wait 500ms between checks
                waitTime += 500

                // Try to get GPS data again
                gpsLocation = locationHelper?.getLocationString() ?: ""
                gpsLatitude = locationHelper?.getLatitude()
                gpsLongitude = locationHelper?.getLongitude()

                Log.d("VehicleTracker", "Waiting for GPS data, time waited: ${waitTime}ms: $gpsLocation")
            }

            Log.d("VehicleTracker", "Final GPS data: $gpsLocation")

            // Now send data with whatever GPS info we have (even if it's still empty)
            if (deviceId.isNotEmpty()) {
                when (eventType) {
                    "entry" -> ApiHelper.sendBusEntryImage(
                        imageBase64,
                        timestamp,
                        "",
                        sessionId,
                        deviceId,
                        gpsLocation,
                        userId,
                        isSessionPublic
                    )
                    "exit" -> ApiHelper.sendBusExitImage(
                        imageBase64,
                        timestamp,
                        "",
                        sessionId,
                        deviceId,
                        gpsLocation,
                        userId,
                        isSessionPublic
                    )
                    else -> ApiHelper.sendBusImageWithDeviceId(
                        imageBase64,
                        timestamp,
                        "",
                        sessionId,
                        deviceId,
                        gpsLocation,
                        userId,
                        isSessionPublic
                    )
                }
            } else {
                // Fall back to the standard method if no device ID
                ApiHelper.sendBusImage(
                    imageBase64,
                    timestamp,
                    "",
                    sessionId,
                    gpsLocation,
                    userId,
                    isSessionPublic
                )
            }

            // Save image locally if in test mode
            if (testMode && context != null) {
                val vehicleData = VehicleData(
                    id = deviceId.ifEmpty { "unknown" },
                    className = className,
                    entryTime = System.currentTimeMillis(),
                    entryPosition = Pair(box.cx, box.cy),
                    capturedImage = capturedBitmap
                )
                saveImageForTesting(vehicleData, "bus_${eventType}")
            }

            Log.d("VehicleTracker", "Sent bus $eventType image to server with GPS: $gpsLocation")
        }
    }

    /**
     * Save captured image to local storage for testing
     * Uses proper Android 10+ scoped storage approach
     */
    private fun saveImageForTesting(vehicleData: VehicleData, event: String) {
        if (vehicleData.capturedImage == null || context == null) return

        scope.launch(Dispatchers.IO) {
            try {
                val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
                val fileName = "${vehicleData.className}_${vehicleData.id}_${event}_$timestamp.jpg"
                val bitmap = vehicleData.capturedImage!!

                // Different ways to save images based on Android version
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    // Android 10+ (API 29+) - Use MediaStore API
                    val contentValues = ContentValues().apply {
                        put(MediaStore.MediaColumns.DISPLAY_NAME, fileName)
                        put(MediaStore.MediaColumns.MIME_TYPE, "image/jpeg")
                        put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/TrafficDetection")
                    }

                    val contentResolver = context.contentResolver
                    val uri = contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, contentValues)

                    uri?.let { outputUri ->
                        contentResolver.openOutputStream(outputUri)?.use { outputStream ->
                            bitmap.compress(Bitmap.CompressFormat.JPEG, 90, outputStream)
                            showSavedToast(fileName)
                            Log.d("VehicleTracker", "Successfully saved image to MediaStore: $fileName")
                        }
                    }
                } else {
                    // Before Android 10 - traditional file access
                    val directory = File(
                        Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES),
                        "TrafficDetection"
                    )

                    // Create directory if it doesn't exist
                    if (!directory.exists() && !directory.mkdirs()) {
                        Log.e("VehicleTracker", "Failed to create directory: ${directory.absolutePath}")
                        return@launch
                    }

                    val file = File(directory, fileName)
                    try {
                        FileOutputStream(file).use { outputStream ->
                            bitmap.compress(Bitmap.CompressFormat.JPEG, 90, outputStream)
                            outputStream.flush()
                        }

                        // Make image visible in gallery
                        MediaScannerConnection.scanFile(
                            context,
                            arrayOf(file.toString()),
                            null,
                            null
                        )

                        showSavedToast(fileName)
                        Log.d("VehicleTracker", "Successfully saved image to file: ${file.absolutePath}")
                    } catch (e: Exception) {
                        Log.e("VehicleTracker", "File output stream error: ${e.message}")
                    }
                }

                // Alternative approach - save to app-specific files
                saveToInternalStorage(bitmap, fileName)

            } catch (e: Exception) {
                Log.e("VehicleTracker", "Error saving test image: ${e.message}")
                e.printStackTrace()
            }
        }
    }

    /**
     * Save to app-specific internal storage as a fallback
     */
    private fun saveToInternalStorage(bitmap: Bitmap, fileName: String) {
        try {
            context?.let { ctx ->
                // Save to app's private files directory
                val filesDir = ctx.filesDir
                val imageFile = File(filesDir, fileName)

                FileOutputStream(imageFile).use { fos ->
                    bitmap.compress(Bitmap.CompressFormat.JPEG, 90, fos)
                    fos.flush()
                }

                Log.d("VehicleTracker", "Saved to internal storage: ${imageFile.absolutePath}")
                showSavedToast("$fileName (internal)")
            }
        } catch (e: Exception) {
            Log.e("VehicleTracker", "Failed to save to internal storage: ${e.message}")
        }
    }

    /**
     * Show a toast message on the main thread
     */
    private fun showSavedToast(fileName: String) {
        context?.let { ctx ->
            scope.launch(Dispatchers.Main) {
                Toast.makeText(
                    ctx,
                    "Saved ${fileName}",
                    Toast.LENGTH_SHORT
                ).show()
            }
        }
    }

    /**
     * Capture an image of the vehicle
     * @param frameBitmap The full frame
     * @param box The bounding box of the vehicle
     * @return A cropped bitmap of the vehicle, or the full frame if cropping fails
     */
    private fun captureVehicleImage(frameBitmap: Bitmap, box: BoundingBox): Bitmap {
        try {
            // Convert normalized coordinates to pixel values
            val frameWidth = frameBitmap.width
            val frameHeight = frameBitmap.height

            val x1 = (box.x1 * frameWidth).toInt().coerceIn(0, frameWidth - 1)
            val y1 = (box.y1 * frameHeight).toInt().coerceIn(0, frameHeight - 1)
            val x2 = (box.x2 * frameWidth).toInt().coerceIn(x1 + 1, frameWidth)
            val y2 = (box.y2 * frameHeight).toInt().coerceIn(y1 + 1, frameHeight)

            val width = x2 - x1
            val height = y2 - y1

            // Crop the image to the bounding box
            val croppedBitmap = Bitmap.createBitmap(frameBitmap, x1, y1, width, height)
            Log.d("VehicleTracker", "Successfully cropped image to ${width}x${height}")
            return croppedBitmap
        } catch (e: Exception) {
            Log.e("VehicleTracker", "Failed to crop image: ${e.message}")
            // Return a copy of the full frame if cropping fails
            return frameBitmap.copy(frameBitmap.config ?: Bitmap.Config.ARGB_8888, true)
        }
    }

    /**
     * Convert bitmap to Base64 string for database storage
     */
    private fun bitmapToBase64(bitmap: Bitmap): String {
        val outputStream = ByteArrayOutputStream()
        // Compress to JPEG with 85% quality for better size/quality balance
        bitmap.compress(Bitmap.CompressFormat.JPEG, 85, outputStream)
        val byteArray = outputStream.toByteArray()
        return Base64.encodeToString(byteArray, Base64.DEFAULT)
    }

    /**
     * Check if a detected object is near the edge of the frame
     */
    private fun isNearEdge(box: BoundingBox): Boolean {
        val edgeThreshold = 0.1f
        return box.x1 < edgeThreshold || // Left edge
                box.x2 > (1.0f - edgeThreshold) || // Right edge
                box.y1 < edgeThreshold || // Top edge
                box.y2 > (1.0f - edgeThreshold) // Bottom edge
    }

    /**
     * Report vehicle entry to server
     */
    private fun reportVehicleEntry(vehicleData: VehicleData) {
        scope.launch(Dispatchers.IO) {
            // Format timestamp
            val timestamp = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date(vehicleData.entryTime))

            // Try to get GPS data with waiting
            var gpsLocation = locationHelper?.getLocationString() ?: ""
            var gpsLatitude = locationHelper?.getLatitude()
            var gpsLongitude = locationHelper?.getLongitude()

            // Wait up to 2 seconds for GPS data if not available
            var waitTime = 0
            val maxWaitTime = 2000 // 2 seconds max wait

            while ((gpsLocation.isEmpty() || gpsLocation == "unknown,unknown" ||
                        gpsLatitude == null || gpsLongitude == null) &&
                waitTime < maxWaitTime) {
                delay(500)  // Wait 500ms between checks
                waitTime += 500

                // Try to get GPS data again
                gpsLocation = locationHelper?.getLocationString() ?: ""
                gpsLatitude = locationHelper?.getLatitude()
                gpsLongitude = locationHelper?.getLongitude()

                Log.d("VehicleTracker", "Waiting for GPS data for ${vehicleData.className}, time waited: ${waitTime}ms: $gpsLocation")
            }

            // Skip sending if we don't have GPS data and user ID
            if ((gpsLocation.isEmpty() || gpsLocation == "unknown,unknown" ||
                        gpsLatitude == null || gpsLongitude == null || userId.isEmpty())) {
                Log.d("VehicleTracker", "Skipping entry for ${vehicleData.className} - missing GPS or user data")
                return@launch
            }

            val entryData = mutableMapOf(
                "event" to "entry",
                "session_id" to ApiHelper.getSessionId(),
                "vehicle_type" to vehicleData.className,
                "vehicle_id" to vehicleData.id,
                "timestamp" to timestamp,
                "confidence" to vehicleData.confidence,
                "gps_location" to gpsLocation,
                "user_id" to userId,
                "is_public" to isSessionPublic
            )

            // Add lat/long separately if available
            gpsLatitude?.let { lat ->
                entryData["gps_latitude"] = lat
            }

            gpsLongitude?.let { lng ->
                entryData["gps_longitude"] = lng
            }

            // Log the entry details
            Log.d("VehicleTracker", "ENTRY DATA with GPS $gpsLocation: $entryData")

            // Send to server if reporter is available and not in test mode
            if (!testMode) {
                serverReporter?.sendData(entryData)
            }
        }
    }

    /**
     * Report vehicle exit to server
     */
    private fun reportVehicleExit(vehicleData: VehicleData) {
        // Get the exit position safely
        val exitPos = vehicleData.exitPosition
        if (exitPos == null) {
            Log.e("VehicleTracker", "Cannot report exit: exitPosition is null for vehicle ${vehicleData.id}")
            return
        }

        scope.launch(Dispatchers.IO) {
            // Format timestamps
            val entryTimestamp = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date(vehicleData.entryTime))
            val exitTimestamp = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date(vehicleData.exitTime))

            // Calculate direction
            val direction = calculateDirectionFixed(vehicleData.entryPosition, exitPos)

            // Try to get GPS data with waiting
            var gpsLocation = locationHelper?.getLocationString() ?: ""
            var gpsLatitude = locationHelper?.getLatitude()
            var gpsLongitude = locationHelper?.getLongitude()

            // Wait up to 2 seconds for GPS data if not available
            var waitTime = 0
            val maxWaitTime = 2000 // 2 seconds max wait

            while ((gpsLocation.isEmpty() || gpsLocation == "unknown,unknown" ||
                        gpsLatitude == null || gpsLongitude == null) &&
                waitTime < maxWaitTime) {
                delay(500)  // Wait 500ms between checks
                waitTime += 500

                // Try to get GPS data again
                gpsLocation = locationHelper?.getLocationString() ?: ""
                gpsLatitude = locationHelper?.getLatitude()
                gpsLongitude = locationHelper?.getLongitude()

                Log.d("VehicleTracker", "Waiting for GPS data for exit ${vehicleData.className}, time waited: ${waitTime}ms: $gpsLocation")
            }

            // Skip sending if we don't have GPS data and user ID
            if ((gpsLocation.isEmpty() || gpsLocation == "unknown,unknown" ||
                        gpsLatitude == null || gpsLongitude == null || userId.isEmpty())) {
                Log.d("VehicleTracker", "Skipping exit for ${vehicleData.className} - missing GPS or user data")
                return@launch
            }

            val exitData = mutableMapOf<String, Any>(
                "event" to "exit",
                "session_id" to ApiHelper.getSessionId(),
                "vehicle_type" to vehicleData.className,
                "vehicle_id" to vehicleData.id,
                "entry_timestamp" to entryTimestamp,
                "exit_timestamp" to exitTimestamp,
                "timestamp" to exitTimestamp, // Adding standard timestamp field
                "entry_position_x" to vehicleData.entryPosition.first,
                "entry_position_y" to vehicleData.entryPosition.second,
                "exit_position_x" to exitPos.first,
                "exit_position_y" to exitPos.second,
                "direction" to direction,
                "time_in_frame_ms" to (vehicleData.exitTime - vehicleData.entryTime),
                "confidence" to vehicleData.confidence,
                "gps_location" to gpsLocation,
                "user_id" to userId,
                "is_public" to isSessionPublic
            )

            // Add lat/long separately if available
            gpsLatitude?.let { lat ->
                exitData["gps_latitude"] = lat
            }

            gpsLongitude?.let { lng ->
                exitData["gps_longitude"] = lng
            }

            // Log all the exit data
            Log.d("VehicleTracker", "EXIT DATA with GPS $gpsLocation: $exitData")

            // Send to server if reporter is available and not in test mode
            if (!testMode) {
                serverReporter?.sendData(exitData)
            }
        }
    }

    /**
     * Calculate movement direction from start to end position
     * with corrected orientation for camera coordinates
     */
    private fun calculateDirectionFixed(start: Pair<Float, Float>, end: Pair<Float, Float>): String {
        // In camera preview, coordinate system may be rotated compared to screen
        // Swap dx and dy to correct the orientation issues
        val dy = end.first - start.first    // X in image is actually Y motion
        val dx = end.second - start.second  // Y in image is actually X motion

        return when {
            dx < -0.1f && dy < -0.1f -> "northwest"
            dx > 0.1f && dy < -0.1f -> "northeast"
            dx < -0.1f && dy > 0.1f -> "southwest"
            dx > 0.1f && dy > 0.1f -> "southeast"
            dx < -0.1f -> "west"
            dx > 0.1f -> "east"
            dy < -0.1f -> "north"
            dy > 0.1f -> "south"
            else -> "stationary"
        }
    }

    /**
     * Remove vehicles that haven't been updated recently
     */
    private fun cleanupStaleVehicles() {
        val currentTime = System.currentTimeMillis()
        val staleThreshold = 5000 // 5 seconds

        // Remove vehicles that haven't been seen for a while and have already reported exit
        vehicles.entries.removeIf { (_, data) ->
            (currentTime - data.lastUpdateTime > staleThreshold) && data.exitReported
        }

        // Remove old entries from reportedVehicles (after 20 seconds)
        reportedVehicles.entries.removeIf { (_, timestamp) ->
            currentTime - timestamp > 20000 // 20 seconds
        }

        // Remove old entries from reportedBusImages (after 30 seconds)
        reportedBusImages.entries.removeIf { (_, timestamp) ->
            currentTime - timestamp > 30000 // 30 seconds
        }
    }

    /**
     * Clear all tracked vehicle data and reset the tracker
     * This should be called when tracking needs to be restarted,
     * such as when switching between camera and video modes.
     */
    fun clear() {
        vehicles.clear()
        reportedVehicles.clear()
        reportedBusImages.clear()
        isInitialized = false
        Log.d("VehicleTracker", "Vehicle tracker cleared and reset")
    }

    /**
     * Data class to store vehicle tracking information
     * Includes a captured image field
     */
    data class VehicleData(
        val id: String,
        val className: String,
        val entryTime: Long,
        val entryPosition: Pair<Float, Float>,
        var lastPosition: Pair<Float, Float> = entryPosition,
        var exitPosition: Pair<Float, Float>? = null,
        var exitTime: Long = 0,
        var lastUpdateTime: Long = entryTime,
        var confidence: Float = 0f,
        var exitReported: Boolean = false,
        var capturedImage: Bitmap? = null  // Field to store the vehicle image
    )
    /**
     * Get the current session ID
     * This allows other components to access the session ID
     * @return String containing the UUID for the current tracking session
     */
    fun getSessionId(): String {
        return sessionId
    }
}

/**
 * Interface for server communication
 * Implement this to communicate with your specific server
 */
interface ServerReporter {
    fun sendData(data: Map<String, Any>)
}