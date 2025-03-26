package com.example.trafficobjectdetection

import com.example.trafficobjectdetection.api.sendTrackingLog
import java.text.SimpleDateFormat
import java.util.Collections
import java.util.Date
import java.util.Locale
import kotlin.math.sqrt

class Tracker(private val maxDisappeared: Int = 50, private val listener: TrackerListener) {
    private var nextObjectId = 1
    private val objects = Collections.synchronizedMap(LinkedHashMap<Int, TrackedObject>())
    private val disappeared = Collections.synchronizedMap(LinkedHashMap<Int, Int>())

    // Store objects that were already sent to server, store deviceIds in set
    private val loggedObjects = Collections.synchronizedSet(mutableSetOf<Int>())

    // Track last assigned ID for each class type
    private val idRegistry = Collections.synchronizedMap(mutableMapOf<String, Int>())

    data class TrackedObject(
        val id: Int,  // Add unique ID for each tracked object
        @Volatile var centroid: Pair<Float, Float>, // Center of the bounding box
        @Volatile var boundingBox: BoundingBox,
        val className: String, // Object class ("Car", "Person", ...)
        @Volatile var direction: String = "", // Direction of object
        @Volatile var lastPosition: Pair<Float, Float>? = null, // Last known position for tracking direction
        @Volatile var velocity: Pair<Float, Float> = Pair(0f, 0f) // Simple velocity tracking
    )

    fun update(detectedBoxes: List<BoundingBox>): List<BoundingBox> {
        synchronized(this) {
            // Handle case when no detections are present
            if (detectedBoxes.isEmpty()) {
                val objectIds = ArrayList(disappeared.keys)
                for (objectId in objectIds) {
                    disappeared[objectId] = disappeared[objectId]!! + 1
                    if (disappeared[objectId]!! > maxDisappeared) {
                        deregister(objectId)
                        // Reset tracking when object leaves screen
                        // loggedObjects.remove(objectId)
                    }
                }
                // If no objects remain, notify the detectorListener to clear UI
                if (objects.isEmpty()) listener.onTrackingCleared()

                return objects.map { (_, tracked) ->
                    tracked.boundingBox.copy(
                        clsName = "${tracked.className} #${tracked.id}\n${tracked.direction}"
                    )
                }
            }

            // Calculate centroids for current detections
            val inputCentroids = detectedBoxes.map { box ->
                Pair((box.x1 + box.x2) / 2, (box.y1 + box.y2) / 2)
            }

            // Register new objects if we're not tracking any yet
            if (objects.isEmpty()) {
                for (i in detectedBoxes.indices) {
                    register(detectedBoxes[i], inputCentroids[i])
                }
            } else {
                // Match existing objects with new detections
                val objectIds = ArrayList(objects.keys)
                val objectCentroids = objects.values.map { it.centroid }

                // Calculate distances between existing objects and new detections
                val distances = calculateDistanceMatrix(objectCentroids, inputCentroids)

                // Find best matches using minimum distances
                val usedRows = mutableSetOf<Int>()
                val usedCols = mutableSetOf<Int>()

                // Sort distances and match closest pairs
                val pairs = mutableListOf<Pair<Int, Int>>()

                val sortedDistances = distances.flatMapIndexed { row, cols ->
                    cols.mapIndexed { col, dist -> Triple(row, col, dist) }
                }.sortedBy { it.third }

                // Use a more generous distance threshold
                val maxDistThreshold = MAX_DISTANCE_THRESHOLD * 1.5f

                for (dist in sortedDistances) {
                    val row = dist.first
                    val col = dist.second

                    if (!usedRows.contains(row) && !usedCols.contains(col) &&
                        dist.third < maxDistThreshold
                    ) {
                        pairs.add(Pair(row, col))
                        usedRows.add(row)
                        usedCols.add(col)
                    }
                }

                // Update matched objects
                for ((row, col) in pairs) {
                    val objectId = objectIds[row]
                    val trackedObject = objects[objectId]

                    // Keep same ID if same class
                    if (trackedObject != null && trackedObject.className == detectedBoxes[col].clsName) {
                        // Store last position to calculate velocity
                        trackedObject.lastPosition = trackedObject.centroid
                        val newCentroid = inputCentroids[col]

                        // Calculate velocity (simple but effective)
                        if (trackedObject.lastPosition != null) {
                            val lastPos = trackedObject.lastPosition!!
                            val dx = newCentroid.first - lastPos.first
                            val dy = newCentroid.second - lastPos.second

                            // Update velocity with smoothing (80% new, 20% old velocity)
                            trackedObject.velocity = Pair(
                                0.8f * dx + 0.2f * trackedObject.velocity.first,
                                0.8f * dy + 0.2f * trackedObject.velocity.second
                            )
                        }

                        trackedObject.centroid = newCentroid
                        trackedObject.boundingBox = detectedBoxes[col]
                        trackedObject.direction = calculateDirection(
                            trackedObject.lastPosition ?: trackedObject.centroid,
                            trackedObject.centroid
                        )
                        disappeared[objectId] = 0

                        // Send data of tracked object to server only if deviceId has not been seen already
                        if (!loggedObjects.contains(trackedObject.id)) {
                            val deviceId = trackedObject.id.toString()
                            val timestamp = getCurrentTime()
                            val location = "${trackedObject.centroid.first},${trackedObject.centroid.second}"  // position on screen, not yet gps
                            val objectType = trackedObject.className
                            val direction = trackedObject.direction

                            sendTrackingLog(deviceId, timestamp, location, objectType, direction)

                            // Add deviceId into loggedObjects
                            loggedObjects.add(trackedObject.id)
                        }
                    } else {
                        register(detectedBoxes[col], inputCentroids[col])
                    }
                }

                // Handle unmatched objects and detections
                val unusedRows = (0 until objectIds.size).filter { !usedRows.contains(it) }
                val unusedCols = (0 until inputCentroids.size).filter { !usedCols.contains(it) }

                // For unmatched objects, apply velocity to keep the box moving in the expected direction
                // This is key to preventing the "lag" effect
                for (row in unusedRows) {
                    val objectId = objectIds[row]
                    val trackedObj = objects[objectId]

                    // Apply velocity to update position - this keeps the box moving even when not matched
                    if (trackedObj != null && (trackedObj.velocity.first != 0f || trackedObj.velocity.second != 0f)) {
                        val newX = trackedObj.centroid.first + trackedObj.velocity.first
                        val newY = trackedObj.centroid.second + trackedObj.velocity.second

                        // Update the centroid
                        trackedObj.centroid = Pair(newX, newY)

                        // Update the bounding box to match the new centroid
                        val width = trackedObj.boundingBox.w
                        val height = trackedObj.boundingBox.h

                        trackedObj.boundingBox = trackedObj.boundingBox.copy(
                            x1 = newX - width/2,
                            y1 = newY - height/2,
                            x2 = newX + width/2,
                            y2 = newY + height/2,
                            cx = newX,
                            cy = newY
                        )
                    }

                    // Increment disappeared counter
                    disappeared[objectId] = disappeared[objectId]!! + 1
                    if (disappeared[objectId]!! > maxDisappeared) {
                        deregister(objectId)
                    }
                }

                // Register new objects for unmatched detections
                for (col in unusedCols) {
                    register(detectedBoxes[col], inputCentroids[col])
                }
            }

            return objects.map { (_, tracked) ->
                tracked.boundingBox.copy(
                    clsName = "${tracked.className} #${tracked.id}\n${tracked.direction}"
                )
            }
        }
    }

    // Get current time
    private fun getCurrentTime(): String {
        val dateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())
        return dateFormat.format(Date())
    }

    private fun register(boundingBox: BoundingBox, centroid: Pair<Float, Float>) {
        // Get class name
        val className = boundingBox.clsName

        // Get next id for this class type, start at 1 if first time seen
        val classId = idRegistry.getOrDefault(className, 0) + 1
        idRegistry[className] = classId  // Update counter

        objects[nextObjectId] = TrackedObject(classId, centroid, boundingBox, className)
        disappeared[nextObjectId] = 0
        nextObjectId++
    }

    private fun deregister(objectId: Int) {
        objects.remove(objectId)
        disappeared.remove(objectId)

        // Notify listener when no objects remain
        if (objects.isEmpty()) {
            listener.onTrackingCleared()
        }
    }

    private fun calculateDistanceMatrix(
        existing: List<Pair<Float, Float>>,
        new: List<Pair<Float, Float>>
    ): Array<FloatArray> {
        return Array(existing.size) { i ->
            FloatArray(new.size) { j ->
                calculateDistance(existing[i], new[j])
            }
        }
    }

    private fun calculateDistance(p1: Pair<Float, Float>, p2: Pair<Float, Float>): Float {
        val dx = p1.first - p2.first
        val dy = p1.second - p2.second
        return sqrt(dx * dx + dy * dy)
    }

    private fun calculateDirection(
        old: Pair<Float, Float>,
        new: Pair<Float, Float>
    ): String {
        val dx = new.first - old.first
        val dy = new.second - old.second

        return when {
            dx < -DIRECTION_THRESHOLD && dy < -DIRECTION_THRESHOLD -> "Down Left"
            dx > DIRECTION_THRESHOLD && dy < -DIRECTION_THRESHOLD -> "Down Right"
            dx < -DIRECTION_THRESHOLD && dy > DIRECTION_THRESHOLD -> "Up Left"
            dx > DIRECTION_THRESHOLD && dy > DIRECTION_THRESHOLD -> "Up Right"
            dx < -DIRECTION_THRESHOLD -> "Left"
            dx > DIRECTION_THRESHOLD -> "Right"
            dy < -DIRECTION_THRESHOLD -> "Down"
            dy > DIRECTION_THRESHOLD -> "Up"
            else -> ""
        }
    }

    companion object {
        private const val MAX_DISTANCE_THRESHOLD = 100f
        private const val DIRECTION_THRESHOLD = 0.02f
    }
}

// Tracker Listener Interface
interface TrackerListener {
    fun onTrackingCleared()
}