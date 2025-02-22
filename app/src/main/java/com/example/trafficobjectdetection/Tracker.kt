package com.example.trafficobjectdetection

import kotlin.math.sqrt
import java.util.LinkedHashMap

class Tracker(private val maxDisappeared: Int = 30) {
    private var nextObjectId = 0
    private val objects = LinkedHashMap<Int, TrackedObject>()
    private val disappeared = LinkedHashMap<Int, Int>()

    data class TrackedObject(
        var centroid: Pair<Float, Float>,
        var boundingBox: BoundingBox,
        var direction: String = "",
        var lastPosition: Pair<Float, Float>? = null
    )

    fun update(detectedBoxes: List<BoundingBox>): Map<Int, TrackedObject> {
        // Handle case when no detections are present
        if (detectedBoxes.isEmpty()) {
            val objectIds = ArrayList(disappeared.keys)
            for (objectId in objectIds) {
                disappeared[objectId] = disappeared[objectId]!! + 1
                if (disappeared[objectId]!! > maxDisappeared) {
                    deregister(objectId)
                }
            }
            return objects
        }

        // Calculate centroids for current detections
        val inputCentroids = detectedBoxes.map { box ->
            val centroidX = (box.x1 + box.x2) / 2
            val centroidY = (box.y1 + box.y2) / 2
            Pair(centroidX, centroidY)
        }

        // Register new objects if we're not tracking any yet
        if (objects.isEmpty()) {
            for (i in detectedBoxes.indices) {
                register(inputCentroids[i], detectedBoxes[i])
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
            val flatDistances = distances.flatMapIndexed { row, cols ->
                cols.mapIndexed { col, dist -> Triple(row, col, dist) }
            }.sortedBy { it.third }

            for (dist in flatDistances) {
                val row = dist.first
                val col = dist.second

                if (!usedRows.contains(row) && !usedCols.contains(col) &&
                    dist.third < MAX_DISTANCE_THRESHOLD) {
                    pairs.add(Pair(row, col))
                    usedRows.add(row)
                    usedCols.add(col)
                }
            }

            // Update matched objects
            for ((row, col) in pairs) {
                val objectId = objectIds[row]
                val trackedObject = objects[objectId]!!

                // Store last position before updating
                trackedObject.lastPosition = trackedObject.centroid

                // Update position and bounding box
                trackedObject.centroid = inputCentroids[col]
                trackedObject.boundingBox = detectedBoxes[col]

                // Calculate direction
                trackedObject.lastPosition?.let { lastPos ->
                    trackedObject.direction = calculateDirection(
                        lastPos,
                        trackedObject.centroid
                    )
                }

                disappeared[objectId] = 0
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
                register(inputCentroids[col], detectedBoxes[col])
            }
        }

        return objects
    }

    private fun register(centroid: Pair<Float, Float>, boundingBox: BoundingBox) {
        objects[nextObjectId] = TrackedObject(centroid, boundingBox)
        disappeared[nextObjectId] = 0
        nextObjectId++
    }

    private fun deregister(objectId: Int) {
        objects.remove(objectId)
        disappeared.remove(objectId)
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