package com.example.trafficobjectdetection.api

import android.util.Log
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID
import java.util.concurrent.TimeUnit

/**
 * Helper class for making API calls to the server
 */
object ApiHelper {
    private const val TAG = "ApiHelper"

    // Server URL (replace with your actual server IP address)
    private const val BASE_URL = "http://PUTYOURIPV4HERE:5000"

    // Store a global session ID that can be accessed by any component
    private var globalSessionId: String = UUID.randomUUID().toString()

    // Configure OkHttp client with longer timeouts for image uploads
    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(60, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

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
        Log.d(TAG, "Reset global session ID: $globalSessionId")
        return globalSessionId
    }

    /**
     * Format timestamp to YYYY-MM-DD HH:MM:SS format
     */
    private fun formatTimestamp(timestamp: Any?): String {
        if (timestamp == null) {
            return SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date())
        }

        // If it's already in the proper format, return it
        if (timestamp is String && timestamp.matches(Regex("\\d{4}-\\d{2}-\\d{2} \\d{2}:\\d{2}:\\d{2}"))) {
            return timestamp
        }

        // Format based on type
        return try {
            when (timestamp) {
                is Long -> SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date(timestamp))
                is String -> SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(
                    SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.getDefault()).parse(timestamp) ?: Date()
                )
                else -> SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date())
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error formatting timestamp: ${e.message}")
            SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date())
        }
    }

    /**
     * Ensure location data is properly formatted
     */
    private fun formatLocation(location: String?): String {
        if (location.isNullOrEmpty() || location == "null,null" || location.contains("undefined")) {
            return "0,0"
        }
        return location
    }

    /**
     * Sends tracking log to the server with GPS and user data
     */
    fun sendTrackingLog(deviceId: String, timestamp: String, location: String, objectType: String,
                        direction: String, sessionId: String, gpsLocation: String = "",
                        userId: String = "", isPublic: Boolean = false) {
        try {
            // Use provided sessionId or fall back to global one
            val actualSessionId = if (sessionId.isNotEmpty()) sessionId else globalSessionId
            val formattedTimestamp = formatTimestamp(timestamp)
            // We don't need to format location anymore
            // val formattedLocation = formatLocation(location)

            Log.d(TAG, "Sending tracking log for $objectType ID:$deviceId, Direction:$direction, GPS:$gpsLocation, UserId:$userId, Public:$isPublic")

            val jsonBody = JSONObject().apply {
                put("device_id", deviceId)
                put("timestamp", formattedTimestamp)
                // Remove the location field
                // put("location", formattedLocation)
                put("object_type", objectType)
                put("direction", direction)
                put("session_id", actualSessionId)

                // Add GPS location if available
                if (gpsLocation.isNotEmpty() && gpsLocation != "unknown,unknown") {
                    put("gps_location", gpsLocation)

                    // Also add individual lat/lng if possible
                    val parts = gpsLocation.split(",")
                    if (parts.size == 2) {
                        try {
                            put("gps_latitude", parts[0].toDouble())
                            put("gps_longitude", parts[1].toDouble())
                        } catch (e: Exception) {
                            Log.e(TAG, "Error parsing GPS coordinates: ${e.message}")
                        }
                    }
                }

                // Add user information
                put("user_id", userId)
                put("is_public", isPublic)
            }

            val mediaType = "application/json; charset=utf-8".toMediaTypeOrNull()
            val requestBody = jsonBody.toString().toRequestBody(mediaType)

            val request = Request.Builder()
                .url("$BASE_URL/tracking")
                .post(requestBody)
                .build()

            // Execute in background thread
            Thread {
                try {
                    val response = client.newCall(request).execute()
                    if (response.isSuccessful) {
                        Log.d(TAG, "Successfully sent tracking log with timestamp: $formattedTimestamp")
                    } else {
                        Log.e(TAG, "Error sending tracking log: ${response.code} - ${response.message}")
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Exception sending tracking log: ${e.message}")
                }
            }.start()
        } catch (e: Exception) {
            Log.e(TAG, "Error creating tracking log request: ${e.message}", e)
        }
    }

    /**
     * Sends a bus image to the server with GPS and user data
     */
    fun sendBusImage(imageBase64: String, timestamp: String, location: String, sessionId: String,
                     gpsLocation: String = "", userId: String = "", isPublic: Boolean = false,
                     deviceId: String = "") {
        try {
            // Use provided sessionId or fall back to global one
            val actualSessionId = if (sessionId.isNotEmpty()) sessionId else globalSessionId
            val formattedTimestamp = formatTimestamp(timestamp)
            val formattedLocation = formatLocation(location)

            Log.d(TAG, "Sending bus image, base64 length: ${imageBase64.length}, UserId:$userId, Public:$isPublic")

            val jsonBody = JSONObject().apply {
                put("image_data", imageBase64)
                put("timestamp", formattedTimestamp)
                put("location", formattedLocation)
                put("session_id", actualSessionId)

                // Add device ID if available
                if (deviceId.isNotEmpty()) {
                    put("device_id", deviceId)
                }

                // Add GPS location if available
                if (gpsLocation.isNotEmpty() && gpsLocation != "unknown,unknown") {
                    put("gps_location", gpsLocation)

                    // Also add individual lat/lng if possible
                    val parts = gpsLocation.split(",")
                    if (parts.size == 2) {
                        try {
                            put("gps_latitude", parts[0].toDouble())
                            put("gps_longitude", parts[1].toDouble())
                        } catch (e: Exception) {
                            Log.e(TAG, "Error parsing GPS coordinates: ${e.message}")
                        }
                    }
                }

                // Add user information
                if (userId.isNotEmpty()) {
                    put("user_id", userId)
                    put("is_public", isPublic)
                }
            }

            val mediaType = "application/json; charset=utf-8".toMediaTypeOrNull()
            val requestBody = jsonBody.toString().toRequestBody(mediaType)

            val request = Request.Builder()
                .url("$BASE_URL/bus-image")
                .post(requestBody)
                .build()

            // Execute in background thread
            Thread {
                try {
                    val response = client.newCall(request).execute()
                    if (response.isSuccessful) {
                        Log.d(TAG, "Successfully sent bus image with timestamp: $formattedTimestamp")
                    } else {
                        Log.e(TAG, "Error sending bus image: ${response.code} - ${response.message}")
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Exception sending bus image: ${e.message}")
                }
            }.start()
        } catch (e: Exception) {
            Log.e(TAG, "Error creating bus image request: ${e.message}", e)
        }
    }

    /**
     * Sends a bus image to the server with device ID and user data
     */
    fun sendBusImageWithDeviceId(imageBase64: String, timestamp: String, location: String,
                                 sessionId: String, deviceId: String, gpsLocation: String = "",
                                 userId: String = "", isPublic: Boolean = false) {
        try {
            // Use provided sessionId or fall back to global one
            val actualSessionId = if (sessionId.isNotEmpty()) sessionId else globalSessionId
            val formattedTimestamp = formatTimestamp(timestamp)

            Log.d(TAG, "Sending bus image, base64 length: ${imageBase64.length}, device ID: $deviceId, user ID: $userId, public: $isPublic")

            val jsonBody = JSONObject().apply {
                put("image_data", imageBase64)
                put("timestamp", formattedTimestamp)
                put("session_id", actualSessionId)
                put("device_id", deviceId)

                // Add GPS location if available
                if (gpsLocation.isNotEmpty() && gpsLocation != "unknown,unknown") {
                    put("gps_location", gpsLocation)

                    // Also add individual lat/lng if possible
                    val parts = gpsLocation.split(",")
                    if (parts.size == 2) {
                        try {
                            put("gps_latitude", parts[0].toDouble())
                            put("gps_longitude", parts[1].toDouble())
                        } catch (e: Exception) {
                            Log.e(TAG, "Error parsing GPS coordinates: ${e.message}")
                        }
                    }
                }

                // Add user data
                if (userId.isNotEmpty()) {
                    put("user_id", userId)
                    put("is_public", isPublic)
                }
            }

            val mediaType = "application/json; charset=utf-8".toMediaTypeOrNull()
            val requestBody = jsonBody.toString().toRequestBody(mediaType)

            val request = Request.Builder()
                .url("$BASE_URL/bus-image")
                .post(requestBody)
                .build()

            // Execute in background thread
            Thread {
                try {
                    val response = client.newCall(request).execute()
                    if (response.isSuccessful) {
                        Log.d(TAG, "Successfully sent bus image with device ID: $deviceId and timestamp: $formattedTimestamp")
                    } else {
                        Log.e(TAG, "Error sending bus image: ${response.code} - ${response.message}")
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Exception sending bus image: ${e.message}")
                }
            }.start()
        } catch (e: Exception) {
            Log.e(TAG, "Error creating bus image request: ${e.message}", e)
        }
    }

    /**
     * Sends vehicle data with image to the server
     * Used for sending exit events with captured images
     */
    fun sendVehicleDataWithImage(data: Map<String, Any>, cacheDir: File) {
        try {
            Log.d(TAG, "Sending vehicle data with image")

            // Extract image Base64 data
            val imageBase64 = data["image_data"] as? String
            if (imageBase64 == null) {
                Log.e(TAG, "No image data provided")
                return
            }

            // Ensure session ID is included
            val dataWithSessionId = data.toMutableMap()
            if (!dataWithSessionId.containsKey("session_id")) {
                dataWithSessionId["session_id"] = globalSessionId
            }

            // Ensure timestamp is properly formatted
            if (dataWithSessionId.containsKey("timestamp")) {
                dataWithSessionId["timestamp"] = formatTimestamp(dataWithSessionId["timestamp"])
            } else if (dataWithSessionId.containsKey("exit_timestamp")) {
                dataWithSessionId["timestamp"] = formatTimestamp(dataWithSessionId["exit_timestamp"])
            } else {
                dataWithSessionId["timestamp"] = formatTimestamp(null)
            }

            // Ensure location is properly formatted
            val location = formatLocation(
                if (dataWithSessionId.containsKey("location")) {
                    dataWithSessionId["location"] as? String
                } else {
                    val x = dataWithSessionId["exit_position_x"] ?: dataWithSessionId["position_x"]
                    val y = dataWithSessionId["exit_position_y"] ?: dataWithSessionId["position_y"]
                    if (x != null && y != null) "${x},${y}" else null
                }
            )
            dataWithSessionId["location"] = location

            // Create JSON with all data except image
            val dataWithoutImage = dataWithSessionId.filter { it.key != "image_data" }
            val jsonString = JSONObject(dataWithoutImage).toString()

            // Prepare multipart request
            val jsonPart = RequestBody.create(
                "application/json; charset=utf-8".toMediaTypeOrNull(),
                jsonString
            )

            // Add parts to multipart builder
            val requestBody = MultipartBody.Builder()
                .setType(MultipartBody.FORM)
                .addFormDataPart("data", null, jsonPart)
                .build()

            // Build request
            val request = Request.Builder()
                .url("$BASE_URL/upload-image")
                .post(requestBody)
                .build()

            // Execute in background thread
            Thread {
                try {
                    val response = client.newCall(request).execute()
                    if (response.isSuccessful) {
                        Log.d(TAG, "Successfully sent vehicle data with image, timestamp: ${dataWithSessionId["timestamp"]}")
                    } else {
                        Log.e(TAG, "Error sending vehicle data with image: ${response.code} - ${response.message}")
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Exception sending vehicle data with image: ${e.message}")
                }
            }.start()
        } catch (e: Exception) {
            Log.e(TAG, "Error creating vehicle data with image request: ${e.message}", e)
        }
    }

    /**
     * Sends a bus entry image to the server with GPS and user data
     */
    fun sendBusEntryImage(imageBase64: String, timestamp: String, location: String, sessionId: String,
                          deviceId: String, gpsLocation: String = "", userId: String = "",
                          isPublic: Boolean = false) {
        try {
            // Use provided sessionId or fall back to global one
            val actualSessionId = if (sessionId.isNotEmpty()) sessionId else globalSessionId
            val formattedTimestamp = formatTimestamp(timestamp)

            Log.d(TAG, "Sending bus ENTRY image, base64 length: ${imageBase64.length}, device ID: $deviceId, UserId:$userId, Public:$isPublic")

            val jsonBody = JSONObject().apply {
                put("image_data", imageBase64)
                put("timestamp", formattedTimestamp)
                put("session_id", actualSessionId)
                put("device_id", deviceId)
                put("event_type", "entry") // Specify this is an entry event

                // Add GPS location if available
                if (gpsLocation.isNotEmpty() && gpsLocation != "unknown,unknown") {
                    put("gps_location", gpsLocation)

                    // Also add individual lat/lng if possible
                    val parts = gpsLocation.split(",")
                    if (parts.size == 2) {
                        try {
                            put("gps_latitude", parts[0].toDouble())
                            put("gps_longitude", parts[1].toDouble())
                        } catch (e: Exception) {
                            Log.e(TAG, "Error parsing GPS coordinates: ${e.message}")
                        }
                    }
                }

                // Add user information
                if (userId.isNotEmpty()) {
                    put("user_id", userId)
                    put("is_public", isPublic)
                }
            }

            val mediaType = "application/json; charset=utf-8".toMediaTypeOrNull()
            val requestBody = jsonBody.toString().toRequestBody(mediaType)

            val request = Request.Builder()
                .url("$BASE_URL/bus-image")
                .post(requestBody)
                .build()

            // Execute in background thread
            Thread {
                try {
                    val response = client.newCall(request).execute()
                    if (response.isSuccessful) {
                        Log.d(TAG, "Successfully sent bus ENTRY image with device ID: $deviceId and timestamp: $formattedTimestamp")
                    } else {
                        Log.e(TAG, "Error sending bus ENTRY image: ${response.code} - ${response.message}")
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Exception sending bus ENTRY image: ${e.message}")
                }
            }.start()
        } catch (e: Exception) {
            Log.e(TAG, "Error creating bus ENTRY image request: ${e.message}", e)
        }
    }

    /**
     * Sends a bus exit image to the server with GPS and user data
     */
    fun sendBusExitImage(imageBase64: String, timestamp: String, location: String, sessionId: String,
                         deviceId: String, gpsLocation: String = "", userId: String = "",
                         isPublic: Boolean = false) {
        try {
            // Use provided sessionId or fall back to global one
            val actualSessionId = if (sessionId.isNotEmpty()) sessionId else globalSessionId
            val formattedTimestamp = formatTimestamp(timestamp)

            Log.d(TAG, "Sending bus EXIT image, base64 length: ${imageBase64.length}, device ID: $deviceId, UserId:$userId, Public:$isPublic")

            val jsonBody = JSONObject().apply {
                put("image_data", imageBase64)
                put("timestamp", formattedTimestamp)
                put("session_id", actualSessionId)
                put("device_id", deviceId)
                put("event_type", "exit") // Specify this is an exit event

                // Add GPS location if available
                if (gpsLocation.isNotEmpty() && gpsLocation != "unknown,unknown") {
                    put("gps_location", gpsLocation)

                    // Also add individual lat/lng if possible
                    val parts = gpsLocation.split(",")
                    if (parts.size == 2) {
                        try {
                            put("gps_latitude", parts[0].toDouble())
                            put("gps_longitude", parts[1].toDouble())
                        } catch (e: Exception) {
                            Log.e(TAG, "Error parsing GPS coordinates: ${e.message}")
                        }
                    }
                }

                // Add user information
                if (userId.isNotEmpty()) {
                    put("user_id", userId)
                    put("is_public", isPublic)
                }
            }

            val mediaType = "application/json; charset=utf-8".toMediaTypeOrNull()
            val requestBody = jsonBody.toString().toRequestBody(mediaType)

            val request = Request.Builder()
                .url("$BASE_URL/bus-image")
                .post(requestBody)
                .build()

            // Execute in background thread
            Thread {
                try {
                    val response = client.newCall(request).execute()
                    if (response.isSuccessful) {
                        Log.d(TAG, "Successfully sent bus EXIT image with device ID: $deviceId and timestamp: $formattedTimestamp")
                    } else {
                        Log.e(TAG, "Error sending bus EXIT image: ${response.code} - ${response.message}")
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Exception sending bus EXIT image: ${e.message}")
                }
            }.start()
        } catch (e: Exception) {
            Log.e(TAG, "Error creating bus EXIT image request: ${e.message}", e)
        }
    }

    /**
     * Send user login or create new user
     * Returns user ID if successful, null otherwise
     */
    fun sendUserLogin(email: String, callback: (userId: String?) -> Unit) {
        try {
            val jsonBody = JSONObject().apply {
                put("email", email)
            }

            val mediaType = "application/json; charset=utf-8".toMediaTypeOrNull()
            val requestBody = jsonBody.toString().toRequestBody(mediaType)

            val request = Request.Builder()
                .url("$BASE_URL/login")
                .post(requestBody)
                .build()

            // Execute in background thread
            Thread {
                try {
                    val response = client.newCall(request).execute()
                    if (response.isSuccessful) {
                        val responseBody = response.body?.string() ?: ""
                        try {
                            val jsonResponse = JSONObject(responseBody)
                            val userId = jsonResponse.optString("user_id", "")
                            Log.d(TAG, "Login successful, user ID: $userId")
                            callback(userId)
                        } catch (e: Exception) {
                            Log.e(TAG, "Error parsing login response: ${e.message}")
                            callback(null)
                        }
                    } else {
                        Log.e(TAG, "Error logging in: ${response.code} - ${response.message}")
                        callback(null)
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Exception during login: ${e.message}")
                    callback(null)
                }
            }.start()
        } catch (e: Exception) {
            Log.e(TAG, "Error creating login request: ${e.message}", e)
            callback(null)
        }
    }
}