package com.example.trafficobjectdetection.api

import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import com.example.trafficobjectdetection.Constants

object RetrofitClient {
    private const val BASE_URL = Constants.BASE_IP

    val instance: ApiService by lazy {
        Retrofit.Builder()
            .baseUrl(BASE_URL)
            .addConverterFactory(GsonConverterFactory.create())
            .build()
            .create(ApiService::class.java)
    }
}
