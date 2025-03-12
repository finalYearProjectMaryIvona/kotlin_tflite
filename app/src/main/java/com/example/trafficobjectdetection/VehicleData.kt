package com.example.trafficobjectdetection

import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap

/**
 * VehicleTracker - Tracks vehicles across frames and reports entry/exit events
 * with corrected coordinate system for proper direction tracking.
 */
class VehicleTracker(private val serverReporter: ServerReporter? = null) {

    // Store tracking data for vehicles that have been detected
    private val vehicles = ConcurrentHashMap<String, VehicleData>()

    // List of vehicle-type classes to track
    private val vehicleClasses = setOf("car", "truck", "bus", "motorcycle", "bicycle", "cup")

    // Coroutine scope for async operations
    private val scope = CoroutineScope(Dispatchers.IO)

    // Track whether this tracker has been initialized
    private var isInitialized = false

    /**
     * Initialize the tracker if not already initialized
     */
    fun initialize() {
        if (!isInitialized) {
            isInitialized = true
            Log.d("VehicleTracker", "Vehicle tracker initialized")
        }
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
            val className = box.clsName.split(" ")[0]

            // Only process vehicle classes
            if (className in vehicleClasses) {
                // Extract the tracking ID if available
                val idPart = box.clsName.split(" ").find { it.contains("#") } ?: ""
                val id = idPart.replace("#", "")

                // Create a unique vehicle key
                val vehicleKey = "$className-$id"

                // Check if this is a new vehicle
                if (!vehicles.containsKey(vehicleKey)) {
                    // New vehicle detected - record entry data
                    val vehicleData = VehicleData(
                        id = id,
                        className = className,
                        entryTime = System.currentTimeMillis(),
                        entryPosition = Pair(box.cx, box.cy),
                        confidence = box.cnf
                    )

                    vehicles[vehicleKey] = vehicleData
                    reportVehicleEntry(vehicleData)
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

                        // Report vehicle exit
                        reportVehicleExit(vehicleData)
                    }
                }
            }
        }

        // Clean up vehicles that haven't been updated in a while
        cleanupStaleVehicles()
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
        val entryData = mapOf(
            "event" to "entry",
            "vehicle_type" to vehicleData.className,
            "vehicle_id" to vehicleData.id,
            "timestamp" to vehicleData.entryTime,
            "position_x" to vehicleData.entryPosition.first,
            "position_y" to vehicleData.entryPosition.second,
            "confidence" to vehicleData.confidence
        )

        // Log the entry
        Log.d("VehicleTracker", "ENTRY: $entryData")

        // Send to server if reporter is available
        serverReporter?.sendData(entryData)
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

        // Calculate direction based on entry and exit positions, with fixed orientation
        val direction = calculateDirectionFixed(vehicleData.entryPosition, exitPos)

        val exitData = mapOf(
            "event" to "exit",
            "vehicle_type" to vehicleData.className,
            "vehicle_id" to vehicleData.id,
            "entry_timestamp" to vehicleData.entryTime,
            "exit_timestamp" to vehicleData.exitTime,
            "entry_position_x" to vehicleData.entryPosition.first,
            "entry_position_y" to vehicleData.entryPosition.second,
            "exit_position_x" to exitPos.first,
            "exit_position_y" to exitPos.second,
            "direction" to direction,
            "time_in_frame_ms" to (vehicleData.exitTime - vehicleData.entryTime),
            "confidence" to vehicleData.confidence
        )

        // Log the exit
        Log.d("VehicleTracker", "EXIT: $exitData")

        // Send to server asynchronously if reporter is available
        scope.launch {
            serverReporter?.sendData(exitData)
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
    }

    /**
     * Clear all tracked vehicle data and reset the tracker
     * This should be called when tracking needs to be restarted,
     * such as when switching between camera and video modes.
     */
    fun clear() {
        vehicles.clear()
        isInitialized = false
        Log.d("VehicleTracker", "Vehicle tracker cleared and reset")
    }

    /**
     * Data class to store vehicle tracking information
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
        var exitReported: Boolean = false
    )
}

/**
 * Interface for server communication
 * Implement this to communicate with your specific server
 */
interface ServerReporter {
    fun sendData(data: Map<String, Any>)
}