package com.example.trafficobjectdetection

/**
 * Constants used throughout the application
 */
object Constants {
    // IPv4 PUTYOURIPV4HERE
    const val BASE_IP = "http://PUTYOURIPV4HERE:5000"

    // Path to the TFLite model file in the assets folder
    const val MODEL_PATH = "model.tflite"

    // Path to the labels file in the assets folder
    const val LABELS_PATH = "labels.txt"

    // List of target classes to detect - only road vehicles (plus cup for testing)
    val TARGET_CLASSES = setOf(
        "car",
        "bicycle",
        "bus",
        "truck",
        "motorcycle"
    )

    // Detection settings
    const val CONFIDENCE_THRESHOLD = 0.35f
    const val IOU_THRESHOLD = 0.45f

    // Tracking settings
    const val MAX_DISAPPEARED_FRAMES = 25
    const val DIRECTION_THRESHOLD = 0.015f

    // Class colors for bounding boxes (RGB values)
    val CLASS_COLORS = mapOf(
        "car" to Triple(255, 0, 0),       // Red
        "bicycle" to Triple(0, 255, 0),   // Green
        "bus" to Triple(0, 0, 255),       // Blue
        "truck" to Triple(255, 165, 0),   // Orange
        "motorcycle" to Triple(128, 0, 128), // Purple
    )
}