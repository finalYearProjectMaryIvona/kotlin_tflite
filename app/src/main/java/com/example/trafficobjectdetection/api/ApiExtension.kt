package com.example.trafficobjectdetection.api

import android.util.Log

/**
 * Sends tracking log to the server.
 * This function is called from the Tracker class.
 */
fun sendTrackingLog(deviceId: String, timestamp: String, location: String, objectType: String = "", direction: String = "", sessionId: String = "") {
    try {
        ApiHelper.sendTrackingLog(deviceId, timestamp, location, objectType, direction, sessionId)
    } catch (e: Exception) {
        Log.e("API", "Error in sendTrackingLog extension: ${e.message}")
        e.printStackTrace()
    }
}

/**
 * Sends a bus image to the server.
 * This extension function makes it easier to send bus images from anywhere in the code.
 */
fun sendBusImage(imageBase64: String, timestamp: String, location: String = "", sessionId: String = "") {
    try {
        Log.d("ApiExtension", "Sending bus image, base64 length: ${imageBase64.length}")
        ApiHelper.sendBusImage(imageBase64, timestamp, location, sessionId)
        Log.d("ApiExtension", "Bus image sent successfully")
    } catch (e: Exception) {
        Log.e("API", "Error in sendBusImage extension: ${e.message}")
        e.printStackTrace()
    }
}