package com.example.trafficobjectdetection

import kotlin.math.sqrt
import com.example.trafficobjectdetection.api.sendTrackingLog

class Tracker(private val maxDisappeared: Int = 50, private val listener: TrackerListener) {
    private var nextObjectId = 1
    private val objects = LinkedHashMap<Int, TrackedObject>()
    private val disappeared = LinkedHashMap<Int, Int>()

    // Track last assigned ID for each class type
    private val idRegistry = mutableMapOf<String, Int>()

    data class TrackedObject(
        val id: Int,  // Add unique ID for each tracked object
        var centroid: Pair<Float, Float>, // Center of the bounding box
        var boundingBox: BoundingBox,
        val className: String, // Object class ("Car", "Person", ...)
        var direction: String = "", // Direction of object
        var lastPosition: Pair<Float, Float>? = null // Last known position for tracking direction
    )

    fun update(detectedBoxes: List<BoundingBox>): List<BoundingBox> {
        // Handle case when no detections are present
        if (detectedBoxes.isEmpty()) {
            val objectIds = ArrayList(disappeared.keys)
            for (objectId in objectIds) {
                disappeared[objectId] = disappeared[objectId]!! + 1
                if (disappeared[objectId]!! > maxDisappeared) {
                    deregister(objectId)
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

            for (dist in sortedDistances) {
                val row = dist.first
                val col = dist.second

                if (!usedRows.contains(row) && !usedCols.contains(col) &&
                    dist.third < MAX_DISTANCE_THRESHOLD
                ) {
                    pairs.add(Pair(row, col))
                    usedRows.add(row)
                    usedCols.add(col)
                }
            }

            // Update matched objects
            for ((row, col) in pairs) {
                val objectId = objectIds[row]
                val trackedObject = objects[objectId]!!

                // Keep same ID if same class
                if (trackedObject.className == detectedBoxes[col].clsName) {
                    trackedObject.lastPosition = trackedObject.centroid
                    trackedObject.centroid = inputCentroids[col]
                    trackedObject.boundingBox = detectedBoxes[col]
                    trackedObject.direction = calculateDirection(trackedObject.lastPosition ?: trackedObject.centroid, trackedObject.centroid)
                    disappeared[objectId] = 0
                } else {
                    register(detectedBoxes[col], inputCentroids[col])
                }
            }

            // Handle unmatched objects and detections
            val unusedRows = (0 until objectIds.size).filter { !usedRows.contains(it) }
            val unusedCols = (0 until inputCentroids.size).filter { !usedCols.contains(it) }

            // Mark unmatched objects as disappeared
            for (row in unusedRows) {
                val objectId = objectIds[row]
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

    private fun calculateIoU(box1: BoundingBox, box2: BoundingBox): Float {
        val x1 = maxOf(box1.x1, box2.x1)
        val y1 = maxOf(box1.y1, box2.y1)
        val x2 = minOf(box1.x2, box2.x2)
        val y2 = minOf(box1.y2, box2.y2)

        val intersectionArea = maxOf(0F, x2 - x1) * maxOf(0F, y2 - y1)
        val box1Area = box1.w * box1.h
        val box2Area = box2.w * box2.h

        return intersectionArea / (box1Area + box2Area - intersectionArea)
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

    fun trackVehicle() {
        val deviceId = "12345"
        val timestamp = System.currentTimeMillis().toString()
        val location = "37.7749,-122.4194" // Example: San Francisco

        sendTrackingLog(deviceId, timestamp, location)
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