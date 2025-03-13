package com.example.trafficobjectdetection.api

import retrofit2.Call
import retrofit2.Callback
import retrofit2.Response

// Function to send logs from anywhere in the app
fun sendTrackingLog(deviceId: String, timestamp: String, location: String, objectType: String, direction: String) {
    val logData = LogData(deviceId, timestamp, location, objectType, direction)
    val call = RetrofitClient.instance.sendLog(logData)

    call.enqueue(object : Callback<ResponseData> {
        override fun onResponse(call: Call<ResponseData>, response: Response<ResponseData>) {
            if (response.isSuccessful) {
                println("Log sent successfully: ${response.body()?.message}")
            } else {
                println("Failed to send log: ${response.errorBody()?.string()}")
            }
        }

        override fun onFailure(call: Call<ResponseData>, t: Throwable) {
            println("Error: ${t.message}")
        }
    })
}
