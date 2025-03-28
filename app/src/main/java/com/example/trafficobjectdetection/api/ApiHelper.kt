package com.example.trafficobjectdetection.api

import android.util.Log
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.util.UUID
import java.util.concurrent.TimeUnit

/**
 * Helper methods for sending data to your backend API
 */
object ApiHelper {
    // Global server IP address and port (REPLACE WITH YOUR ipv4)
    private const val SERVER_ADDRESS = "PUTYOURIPV4HERE:5000"

    // Your API endpoint for tracking data - update with your actual server address
    // Your API endpoint for tracking data
    private const val TRACKING_API_URL = "http://$SERVER_ADDRESS/tracking"

    // Your API endpoint for uploading images
    private const val UPLOAD_IMAGE_API_URL = "http://$SERVER_ADDRESS/upload-image"

    // Endpoint for bus images
    private const val BUS_IMAGE_API_URL = "http://$SERVER_ADDRESS/bus-image"

    // Store a global session ID that can be accessed by any component
    private var globalSessionId: String = UUID.randomUUID().toString()

    // OkHttpClient with longer timeouts for image uploads
    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    /**
     * Get the server address (for checking connections)
     */
    fun getServerAddress(): String {
        return SERVER_ADDRESS
    }

    /**
     * Get the current global session ID
     * @return String containing the UUID for the current API session
     */
    fun getSessionId(): String {
        return globalSessionId
    }

    /**
     * Reset the global session ID (for example when starting a new tracking session)
     * @return The new session ID
     */
    fun resetSessionId(): String {
        globalSessionId = UUID.randomUUID().toString()
        Log.d("ApiHelper", "Reset global session ID: $globalSessionId")
        return globalSessionId
    }

    /**
     * Send tracking log without image
     */
    fun sendTrackingLog(deviceId: String, timestamp: String, location: String, objectType: String = "", direction: String = "", sessionId: String = "") {
        // Use provided sessionId or fall back to global one
        val actualSessionId = if (sessionId.isNotEmpty()) sessionId else globalSessionId

        val json = JSONObject().apply {
            put("device_id", deviceId)
            put("timestamp", timestamp)
            put("location", location)
            put("object_type", objectType)
            put("direction", direction)
            put("session_id", actualSessionId)
        }

        Log.d("API", "Preparing to send tracking log: ${json.toString()}")
        Log.d("API", "Target URL: $TRACKING_API_URL")

        val requestBody = json.toString().toRequestBody("application/json".toMediaTypeOrNull())

        val request = Request.Builder()
            .url(TRACKING_API_URL)
            .post(requestBody)
            .build()

        // Execute request in a background thread to avoid NetworkOnMainThreadException
        Thread {
            try {
                client.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) {
                        Log.e("API", "Failed to send tracking log: ${response.code}")
                        val errorBody = response.body?.string() ?: "Unknown error"
                        Log.e("API", "Error response: $errorBody")
                    } else {
                        val responseBody = response.body?.string() ?: "Empty response"
                        Log.d("API", "Successfully sent tracking log. Session ID: $actualSessionId, Response: $responseBody")
                    }
                }
            } catch (e: Exception) {
                Log.e("API", "Error sending tracking log: ${e.message}", e)
                e.printStackTrace()
            }
        }.start()
    }

    /**
     * Send bus image to API
     * This is a specialized method for sending bus images to be stored
     * in the bus_images collection
     */
    fun sendBusImage(imageBase64: String, timestamp: String, location: String = "", sessionId: String = "") {
        // Use provided sessionId or fall back to global one
        val actualSessionId = if (sessionId.isNotEmpty()) sessionId else globalSessionId

        val json = JSONObject().apply {
            put("session_id", actualSessionId)
            put("timestamp", timestamp)
            put("location", location)
            put("image_data", imageBase64)
        }

        Log.d("API", "Preparing to send bus image: session=${actualSessionId}, timestamp=${timestamp}")
        Log.d("API", "Target URL: $BUS_IMAGE_API_URL")
        Log.d("API", "Image data length: ${imageBase64.length}")

        val requestBody = json.toString().toRequestBody("application/json".toMediaTypeOrNull())

        val request = Request.Builder()
            .url(BUS_IMAGE_API_URL)
            .post(requestBody)
            .build()

        // Execute request in a background thread to avoid NetworkOnMainThreadException
        Thread {
            try {
                client.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) {
                        Log.e("API", "Failed to send bus image: ${response.code}")
                        val errorBody = response.body?.string() ?: "Unknown error"
                        Log.e("API", "Error response: $errorBody")
                    } else {
                        val responseBody = response.body?.string() ?: "Empty response"
                        Log.d("API", "Successfully sent bus image. Session ID: $actualSessionId, Response: $responseBody")
                    }
                }
            } catch (e: Exception) {
                Log.e("API", "Error sending bus image: ${e.message}", e)
                e.printStackTrace()
            }
        }.start()
    }

    /**
     * Send vehicle data with image to API
     * Using explicit cast to fix type mismatch
     */
    fun sendVehicleDataWithImage(data: Map<String, Any>, cacheDir: File): Boolean {
        var success = false

        Thread {
            try {
                // Use explicit cast to handle type safely
                val dataMap = data as Map<*, *>

                // Extract and decode the Base64 image
                val imageBase64 = dataMap["image_data"] as? String ?: run {
                    Log.e("API", "No image data found in payload")
                    return@Thread
                }

                Log.d("API", "Preparing to send vehicle data with image to URL: $UPLOAD_IMAGE_API_URL")

                // Write the base64 image to a temporary file
                val tempFile = File.createTempFile("vehicle_", ".jpg", cacheDir)
                val imageBytes = android.util.Base64.decode(imageBase64, android.util.Base64.DEFAULT)
                FileOutputStream(tempFile).use { it.write(imageBytes) }

                // Create JSON data without the image
                val dataWithoutImage = HashMap<String, Any>()
                for ((key, value) in dataMap) {
                    if (key != "image_data" && key is String) {
                        dataWithoutImage[key] = value ?: ""
                    }
                }

                // Ensure session ID is included
                if (!dataWithoutImage.containsKey("session_id")) {
                    dataWithoutImage["session_id"] = globalSessionId
                }

                val jsonData = JSONObject(dataWithoutImage as Map<*, *>?).toString()
                Log.d("API", "Image upload JSON data: $jsonData")

                // Create multipart request with image and JSON data
                val requestBody = MultipartBody.Builder()
                    .setType(MultipartBody.FORM)
                    .addFormDataPart("data", jsonData)
                    .addFormDataPart(
                        "image",
                        "vehicle_${dataMap["vehicle_id"] ?: "unknown"}.jpg",
                        tempFile.asRequestBody("image/jpeg".toMediaTypeOrNull())
                    )
                    .build()

                val request = Request.Builder()
                    .url(UPLOAD_IMAGE_API_URL)
                    .post(requestBody)
                    .build()

                // Execute request
                client.newCall(request).execute().use { response ->
                    // Delete the temporary file
                    tempFile.delete()

                    if (!response.isSuccessful) {
                        Log.e("API", "Failed to send vehicle data with image: ${response.code}")
                        val errorBody = response.body?.string() ?: "Unknown error"
                        Log.e("API", "Error response: $errorBody")
                    } else {
                        val responseBody = response.body?.string() ?: "Empty response"
                        Log.d("API", "Successfully sent vehicle data with image. Session ID: ${dataWithoutImage["session_id"]}")
                        Log.d("API", "Response: $responseBody")
                        success = true
                    }
                }
            } catch (e: Exception) {
                Log.e("API", "Error sending vehicle data with image: ${e.message}", e)
                e.printStackTrace()
            }
        }.start()

        return success
    }

    /**
     * Ensure a data map contains a session ID
     */
    fun ensureSessionId(data: Map<String, Any>): Map<String, Any> {
        val result = data.toMutableMap()
        if (!result.containsKey("session_id")) {
            result["session_id"] = globalSessionId
        }
        return result
    }
}