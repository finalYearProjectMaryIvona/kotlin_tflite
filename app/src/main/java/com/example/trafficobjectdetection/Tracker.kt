package com.example.trafficobjectdetection

import android.graphics.RectF
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.hypot

class Tracker {
    private val trackedObjects = ConcurrentHashMap<Int, TrackedObject>()
    private var nextObjectId = 1
    private val maxDistanceThreshold = 50f

    fun update(detectedBoxes: List<BoundingBox>): Map<Int, BoundingBox> {
        val newTrackedObjects = ConcurrentHashMap<Int, TrackedObject>()

        detectedBoxes.forEach { box ->
            val existingId = findClosestMatch(box)
            if (existingId != null) {
                newTrackedObjects[existingId] = TrackedObject(box)
            } else {
                newTrackedObjects[nextObjectId] = TrackedObject(box)
                nextObjectId++
            }
        }

        trackedObjects.clear()
        trackedObjects.putAll(newTrackedObjects)

        return trackedObjects.mapValues { it.value.boundingBox }
    }

    private fun findClosestMatch(newBox: BoundingBox): Int? {
        var bestMatchId: Int? = null
        var bestDistance = Float.MAX_VALUE

        trackedObjects.forEach { (id, trackedObj) ->
            val distance = calculateDistance(newBox, trackedObj.boundingBox)
            if (distance < maxDistanceThreshold && distance < bestDistance) {  // 50 pixels threshold
                bestMatchId = id
                bestDistance = distance
            }
        }
        return bestMatchId
    }

    private fun calculateDistance(box1: BoundingBox, box2: BoundingBox): Float {
        val centerX1 = (box1.x1 + box1.x2) / 2
        val centerY1 = (box1.y1 + box1.y2) / 2
        val centerX2 = (box2.x1 + box2.x2) / 2
        val centerY2 = (box2.y1 + box2.y2) / 2
        return Math.sqrt(((centerX1 - centerX2) * (centerX1 - centerX2) +
                (centerY1 - centerY2) * (centerY1 - centerY2)).toDouble()).toFloat()
    }
}

data class TrackedObject(val boundingBox: BoundingBox)
