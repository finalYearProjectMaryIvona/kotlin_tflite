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
import java.util.concurrent.TimeUnit

/**
 * Helper methods for sending data to your backend API
 */
object ApiHelper {

    // Your API endpoint for tracking data
    private const val TRACKING_API_URL = "https://your-api-endpoint.com/tracking"

    // Your API endpoint for uploading images
    private const val UPLOAD_IMAGE_API_URL = "https://your-api-endpoint.com/upload-image"

    // OkHttpClient with longer timeouts for image uploads
    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    /**
     * Send tracking log without image
     */
    fun sendTrackingLog(deviceId: String, timestamp: String, location: String, objectType: String = "", direction: String = "") {
        try {
            val json = JSONObject().apply {
                put("device_id", deviceId)
                put("timestamp", timestamp)
                put("location", location)
                put("object_type", objectType)
                put("direction", direction)
            }

            val requestBody = json.toString().toRequestBody("application/json".toMediaTypeOrNull())

            val request = Request.Builder()
                .url(TRACKING_API_URL)
                .post(requestBody)
                .build()

            // Execute request asynchronously
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    Log.e("API", "Failed to send tracking log: ${response.code}")
                } else {
                    Log.d("API", "Successfully sent tracking log")
                }
            }
        } catch (e: Exception) {
            Log.e("API", "Error sending tracking log: ${e.message}")
        }
    }

    /**
     * Send vehicle data with image to API
     * Using explicit cast to fix type mismatch
     */
    fun sendVehicleDataWithImage(data: Map<String, Any>, cacheDir: File): Boolean {
        try {
            // Use explicit cast to handle type safely
            val dataMap = data as Map<*, *>

            // Extract and decode the Base64 image
            val imageBase64 = dataMap["image_data"] as? String ?: return false

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

            val jsonData = JSONObject(dataWithoutImage as Map<*, *>?).toString()

            // Create multipart request with image and JSON data
            val requestBody = MultipartBody.Builder()
                .setType(MultipartBody.FORM)
                .addFormDataPart("data", jsonData)
                .addFormDataPart(
                    "image",
                    "vehicle_${dataMap["vehicle_id"]}.jpg",
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
                    return false
                } else {
                    Log.d("API", "Successfully sent vehicle data with image")
                    return true
                }
            }
        } catch (e: Exception) {
            Log.e("API", "Error sending vehicle data with image: ${e.message}")
            return false
        }
    }
}