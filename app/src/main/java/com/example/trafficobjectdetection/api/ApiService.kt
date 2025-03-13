package com.example.trafficobjectdetection.api

import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import retrofit2.http.Body
import retrofit2.http.POST
import retrofit2.Call

// Define API interface
interface ApiService {
    @POST("logs")
    fun sendLog(@Body logData: LogData): Call<ResponseData>
}

// Data class for sending log data
data class LogData(
    val deviceId: String,
    val timestamp: String,
    val location: String
)

// Response data class
data class ResponseData(
    val message: String
)
