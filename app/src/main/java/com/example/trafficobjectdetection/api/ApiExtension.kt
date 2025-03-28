package com.example.trafficobjectdetection.api

import android.util.Log
import org.json.JSONObject

/**
 * Sends tracking log to the server.
 * This function is called from the Tracker class.
 */
fun sendTrackingLog(deviceId: String, timestamp: String, location: String, objectType: String = "", direction: String = "", sessionId: String = "") {
    try {
        ApiHelper.sendTrackingLog(deviceId, timestamp, location, objectType, direction, sessionId)
    } catch (e: Exception) {
        Log.e("API", "Error in sendTrackingLog extension: ${e.message}")
    }
}