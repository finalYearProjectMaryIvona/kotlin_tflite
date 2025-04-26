package com.example.trafficobjectdetection

import android.content.Context
import android.graphics.Bitmap
import android.os.SystemClock
import com.example.trafficobjectdetection.MetaData.extractNamesFromLabelFile
import com.example.trafficobjectdetection.MetaData.extractNamesFromMetadata
import org.tensorflow.lite.DataType
import org.tensorflow.lite.Interpreter
import org.tensorflow.lite.gpu.CompatibilityList
import org.tensorflow.lite.gpu.GpuDelegate
import org.tensorflow.lite.support.common.FileUtil
import org.tensorflow.lite.support.common.ops.CastOp
import org.tensorflow.lite.support.common.ops.NormalizeOp
import org.tensorflow.lite.support.image.ImageProcessor
import org.tensorflow.lite.support.image.TensorImage
import org.tensorflow.lite.support.tensorbuffer.TensorBuffer

/**
 * This the main class for handling the object detection using TFLite
 * Sets up the TFLiteInterpreter, processes the images, integrates with th tracker
 */
class Detector(
    private val context: Context,
    private val modelPath: String,
    private val labelPath: String?,
    private val detectorListener: DetectorListener,
    private val message: (String) -> Unit
) {
    private var interpreter: Interpreter
    private var labels = mutableListOf<String>()
    private var tensorWidth = 0
    private var tensorHeight = 0
    private var numChannel = 0
    private var numElements = 0

    // Tracker instance to handle object tracking
    private val tracker = Tracker(maxDisappeared = 4, object : TrackerListener {
        override fun onTrackingCleared() {
            detectorListener.onEmptyDetect() // Clears UI when tracking is empty
        }
    })

    // Image processor for normalizing and casting image data
    private val imageProcessor = ImageProcessor.Builder()
        .add(NormalizeOp(INPUT_MEAN, INPUT_STANDARD_DEVIATION))
        .add(CastOp(INPUT_IMAGE_TYPE))
        .build()

    /**
     * Initilise the detecter by loading the model, setting up the interpreter
     * and getting the metadata like input dimensions and labels
     */
    init {
        val compatList = CompatibilityList()
        val options = Interpreter.Options().apply {
            if (compatList.isDelegateSupportedOnThisDevice) {
                //use gpu delagate if available
                val delegateOptions = compatList.bestOptionsForThisDevice
                this.addDelegate(GpuDelegate(delegateOptions))
            } else {
                this.setNumThreads(4) // Set CPU threads if GPU is not available
            }
        }

        //load the model file
        val model = FileUtil.loadMappedFile(context, modelPath)
        interpreter = Interpreter(model, options)

        // Extract input and output tensor shapes
        val inputShape = interpreter.getInputTensor(0)?.shape()
        val outputShape = interpreter.getOutputTensor(0)?.shape()

        // Load labels from metadata or file
        labels.addAll(extractNamesFromMetadata(model))
        if (labels.isEmpty()) {
            if (labelPath == null) {
                message("Model does not contain metadata, provide LABELS_PATH in Constants.kt")
                labels.addAll(MetaData.TEMP_CLASSES)
            } else {
                labels.addAll(extractNamesFromLabelFile(context, labelPath))
            }
        }

        //configuring the tensor input shapes
        if (inputShape != null) {
            tensorWidth = inputShape[1]
            tensorHeight = inputShape[2]

            // If input shape is in format of [1, 3, ..., ...]
            if (inputShape[1] == 3) {
                tensorWidth = inputShape[2]
                tensorHeight = inputShape[3]
            }
        }
        //set output dimensions
        if (outputShape != null) {
            numChannel = outputShape[1]
            numElements = outputShape[2]
        }
    }

    // Restart interpreter
    //optionally with GPU acceleration
    fun restart(isGpu: Boolean) {
        interpreter.close()
        val options = if (isGpu) {
            val compatList = CompatibilityList()
            Interpreter.Options().apply {
                if (compatList.isDelegateSupportedOnThisDevice) {
                    val delegateOptions = compatList.bestOptionsForThisDevice
                    this.addDelegate(GpuDelegate(delegateOptions))
                } else {
                    this.setNumThreads(4)
                }
            }
        } else {
            Interpreter.Options().apply {
                this.setNumThreads(4)
            }
        }

        val model = FileUtil.loadMappedFile(context, modelPath)
        interpreter = Interpreter(model, options)
    }

    //close the interpreter
    fun close() {
        interpreter.close()
    }

    // Runs object detection on image frame
    //processes a bitmap image (frame)
    fun detect(frame: Bitmap) {
        if (tensorWidth == 0 || tensorHeight == 0 || numChannel == 0 || numElements == 0) {
            return
        }

        var inferenceTime = SystemClock.uptimeMillis()

        // Resize input image to match model input dimensions
        val resizedBitmap = Bitmap.createScaledBitmap(frame, tensorWidth, tensorHeight, false)
        val tensorImage = TensorImage(INPUT_IMAGE_TYPE)
        tensorImage.load(resizedBitmap)
        val processedImage = imageProcessor.process(tensorImage)
        //preparing the output buffer
        val output = TensorBuffer.createFixedSize(intArrayOf(1, numChannel, numElements), OUTPUT_IMAGE_TYPE)
        interpreter.run(processedImage.buffer, output.buffer)
        //process detection results
        val detectedBoxes = bestBox(output.floatArray)
        inferenceTime = SystemClock.uptimeMillis() - inferenceTime

        //handle empty detection results
        if (detectedBoxes == null || detectedBoxes.isEmpty()) {
            detectorListener.onEmptyDetect()
            return
        }

        // Track objects across the frames
        tracker.update(detectedBoxes)

        // Get the formatted bounding boxes with IDs
        val trackedBoxes = tracker.update(detectedBoxes)

        // Send to the UI
        detectorListener.onDetect(trackedBoxes, inferenceTime)
    }

    // Extracts bounding boxes from the raw model output
    private fun bestBox(array: FloatArray): List<BoundingBox>? {
        val boundingBoxes = mutableListOf<BoundingBox>()

        for (c in 0 until numElements) {
            var maxConf = CONFIDENCE_THRESHOLD
            var maxIdx = -1
            var j = 4
            var arrayIdx = c + numElements * j

            // Find class with highest confidence
            while (j < numChannel) {
                if (array[arrayIdx] > maxConf) {
                    maxConf = array[arrayIdx]
                    maxIdx = j - 4
                }
                j++
                arrayIdx += numElements
            }

            // If confidence is high, set bounding box
            if (maxConf > CONFIDENCE_THRESHOLD && maxIdx >= 0 && maxIdx < labels.size) {
                val clsName = labels[maxIdx]

                // Only process target classes
                if (clsName.lowercase() in Constants.TARGET_CLASSES) {
                    val cx = array[c]
                    val cy = array[c + numElements]
                    val w = array[c + numElements * 2]
                    val h = array[c + numElements * 3]
                    val x1 = cx - (w / 2F)
                    val y1 = cy - (h / 2F)
                    val x2 = cx + (w / 2F)
                    val y2 = cy + (h / 2F)

                    if (x1 in 0f..1f && y1 in 0f..1f && x2 in 0f..1f && y2 in 0f..1f) {
                        boundingBoxes.add(
                            BoundingBox(
                                x1 = x1, y1 = y1, x2 = x2, y2 = y2,
                                cx = cx, cy = cy, w = w, h = h,
                                cnf = maxConf, cls = maxIdx, clsName = clsName
                            )
                        )
                    }
                }
            }
        }

        if (boundingBoxes.isEmpty()) return null

        return applyNMS(boundingBoxes) // Non-Maximum Suppression
    }

    //Apply Non maximum suppression to remove the old bounding boxes
    //keeps the box with the highest confidence and removes overlapping boxes
    private fun applyNMS(boxes: List<BoundingBox>): MutableList<BoundingBox> {
        val sortedBoxes = boxes.sortedByDescending { it.cnf }.toMutableList()
        val selectedBoxes = mutableListOf<BoundingBox>()
        //take box with highest confidence
        while (sortedBoxes.isNotEmpty()) {
            val first = sortedBoxes.first()
            selectedBoxes.add(first)
            sortedBoxes.remove(first)

            //remove the other boxes that overlap
            val iterator = sortedBoxes.iterator()
            while (iterator.hasNext()) {
                val nextBox = iterator.next()
                val iou = calculateIoU(first, nextBox)
                if (iou >= IOU_THRESHOLD) {
                    iterator.remove()
                }
            }
        }

        return selectedBoxes
    }

    //calculate the intersection over union between 2 bounding box
    //basically seeing if there are overlapping of nms
    private fun calculateIoU(box1: BoundingBox, box2: BoundingBox): Float {
        val x1 = maxOf(box1.x1, box2.x1)
        val y1 = maxOf(box1.y1, box2.y1)
        val x2 = minOf(box1.x2, box2.x2)
        val y2 = minOf(box1.y2, box2.y2)
        val intersectionArea = maxOf(0F, x2 - x1) * maxOf(0F, y2 - y1)
        val box1Area = box1.w * box1.h
        val box2Area = box2.w * box2.h
        //calculate IOU
        return intersectionArea / (box1Area + box2Area - intersectionArea)
    }

    //Interface for receiving detection results
    interface DetectorListener {
        //when no objects detected
        fun onEmptyDetect()
        //called when they are detected
        fun onDetect(boundingBoxes: List<BoundingBox>, inferenceTime: Long)
    }

    companion object {
        //constants for image preprocessing
        private const val INPUT_MEAN = 0f
        private const val INPUT_STANDARD_DEVIATION = 255f
        private val INPUT_IMAGE_TYPE = DataType.FLOAT32
        private val OUTPUT_IMAGE_TYPE = DataType.FLOAT32
        //detection thresholds
        private const val CONFIDENCE_THRESHOLD = 0.45F
        private const val IOU_THRESHOLD = 0.5F
    }

    //data class for tracking objects across frames
    data class TrackedObject(
        var box: BoundingBox,
        var direction: String = "",
        var lastPosition: Pair<Float, Float>? = null
    )

    //internal tracker for tracking objects between frames
    private inner class ObjectTracker {
        private var nextObjectId = 0
        private val objects = mutableMapOf<Int, TrackedObject>()
        private val maxDisappeared = 4
        private val disappeared = mutableMapOf<Int, Int>()
        private val MERGE_IOU_THRESHOLD = 0.7f // Threshold for merging overlapping boxes

        fun update(detectedBoxes: List<BoundingBox>): List<BoundingBox> {
            // Handle no detections
            if (detectedBoxes.isEmpty()) {
                val objectIds = ArrayList(disappeared.keys)
                for (objectId in objectIds) {
                    disappeared[objectId] = disappeared[objectId]!! + 1
                    if (disappeared[objectId]!! > maxDisappeared) {
                        objects.remove(objectId)
                        disappeared.remove(objectId)
                    }
                }
                return emptyList()
            }

            // If not tracking any objects, register all detections
            if (objects.isEmpty()) {
                detectedBoxes.forEach { box ->
                    objects[nextObjectId] = TrackedObject(box)
                    disappeared[nextObjectId] = 0
                    nextObjectId++
                }
            } else {
                // Match existing objects with new detections
                val matches = findMatches(detectedBoxes)

                // Update matched objects
                matches.forEach { (objectId, boxIndex) ->
                    val trackedObject = objects[objectId]!!
                    trackedObject.lastPosition = Pair(trackedObject.box.cx, trackedObject.box.cy)
                    trackedObject.box = detectedBoxes[boxIndex]

                    // Update direction
                    trackedObject.lastPosition?.let { lastPos ->
                        val dx = trackedObject.box.cx - lastPos.first
                        val dy = trackedObject.box.cy - lastPos.second
                        trackedObject.direction = when {
                            dx < -0.02f && dy < -0.02f -> "Down Left"
                            dx > 0.02f && dy < -0.02f -> "Down Right"
                            dx < -0.02f && dy > 0.02f -> "Up Left"
                            dx > 0.02f && dy > 0.02f -> "Up Right"
                            dx < -0.02f -> "Left"
                            dx > 0.02f -> "Right"
                            dy < -0.02f -> "Down"
                            dy > 0.02f -> "Up"
                            else -> ""
                        }
                    }
                    disappeared[objectId] = 0
                }

                // Register new detections
                val matched = matches.values.toSet()
                for (i in detectedBoxes.indices) {
                    if (i !in matched) {
                        objects[nextObjectId] = TrackedObject(detectedBoxes[i])
                        disappeared[nextObjectId] = 0
                        nextObjectId++
                    }
                }
            }

            // Merge overlapping boxes
            val mergedObjects = mutableMapOf<Int, TrackedObject>()
            val objectsToProcess = objects.toList() // Create a copy for safe iteration
            val processedIds = mutableSetOf<Int>()

            for ((id1, obj1) in objectsToProcess) {
                if (id1 !in processedIds) {
                    var bestBox = obj1.box
                    var bestConf = obj1.box.cnf
                    var direction = obj1.direction

                    for ((id2, obj2) in objectsToProcess) {
                        if (id1 != id2 && id2 !in processedIds) {
                            val iou = calculateIoU(obj1.box, obj2.box)
                            if (iou > MERGE_IOU_THRESHOLD) {
                                // Merge boxes by taking the one with higher confidence
                                if (obj2.box.cnf > bestConf) {
                                    bestBox = obj2.box
                                    bestConf = obj2.box.cnf
                                    direction = obj2.direction
                                }
                                processedIds.add(id2)
                            }
                        }
                    }

                    processedIds.add(id1)
                    mergedObjects[id1] = TrackedObject(bestBox, direction, obj1.lastPosition)
                }
            }

            // Update objects safely
            objects.clear()
            objects.putAll(mergedObjects)

            // Return tracked boxes with IDs and directions
            return objects.map { (id, tracked) ->
                tracked.box.copy(
                    clsName = "${tracked.box.clsName}\nID $id\n${tracked.direction}"
                )
            }
        }

        //find matches between exisiting tracked objects
        private fun findMatches(detectedBoxes: List<BoundingBox>): Map<Int, Int> {
            val matches = mutableMapOf<Int, Int>()
            val usedDetections = mutableSetOf<Int>()

            objects.forEach { (objectId, trackedObject) ->
                var bestMatch = -1
                var bestIoU = 0f

                for (i in detectedBoxes.indices) {
                    if (i !in usedDetections) {
                        val iou = calculateIoU(trackedObject.box, detectedBoxes[i])
                        if (iou > bestIoU && iou > 0.5f) {
                            bestIoU = iou
                            bestMatch = i
                        }
                    }
                }

                if (bestMatch >= 0) {
                    matches[objectId] = bestMatch
                    usedDetections.add(bestMatch)
                }
            }

            return matches
        }
    }
}