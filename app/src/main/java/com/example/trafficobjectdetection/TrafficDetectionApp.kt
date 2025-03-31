package com.example.trafficobjectdetection

import android.app.Application
import android.util.Log

/**
 * Application class for initializing app-wide components
 */
class TrafficDetectionApp : Application() {

    companion object {
        private const val TAG = "TrafficDetectionApp"
    }

    override fun onCreate() {
        super.onCreate()

        // Initialize UserSessionManager
        UserSessionManager.initialize(this)
        Log.d(TAG, "UserSessionManager initialized")

        // Log if user is already logged in
        if (UserSessionManager.isLoggedIn()) {
            Log.d(TAG, "User already logged in: ${UserSessionManager.getUserEmail()}")
        } else {
            Log.d(TAG, "No user logged in")
        }
    }
}