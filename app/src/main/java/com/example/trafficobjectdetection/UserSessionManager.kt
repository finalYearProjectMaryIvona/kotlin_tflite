package com.example.trafficobjectdetection

import android.content.Context
import android.util.Log

/**
 * Singleton manager for user session data
 * Manages user authentication state and session settings throughout the app
 */
object UserSessionManager {
    private const val TAG = "UserSessionManager"
    private const val PREFS_NAME = "TrafficDetectionPrefs"

    // Keys for SharedPreferences
    private const val KEY_USER_ID = "userId"
    private const val KEY_USER_EMAIL = "userEmail"
    private const val KEY_IS_LOGGED_IN = "isLoggedIn"
    private const val KEY_MAKE_SESSIONS_PUBLIC = "makeSessionsPublic"

    // In-memory cache of user data
    private var userId: String = ""
    private var userEmail: String = ""
    private var makeSessionsPublic: Boolean = false

    /**
     * Initialize the session manager with stored preferences
     * Should be called when the app starts, e.g., in Application class
     */
    fun initialize(context: Context) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        userId = prefs.getString(KEY_USER_ID, "") ?: ""
        userEmail = prefs.getString(KEY_USER_EMAIL, "") ?: ""
        makeSessionsPublic = prefs.getBoolean(KEY_MAKE_SESSIONS_PUBLIC, false)

        Log.d(TAG, "UserSessionManager initialized. User ID: $userId, Public Sessions: $makeSessionsPublic")
    }

    /**
     * Set user ID and save to preferences
     */
    fun setUserId(id: String, context: Context? = null) {
        userId = id
        context?.let { saveToPreferences(it) }
    }

    /**
     * Set user email and save to preferences
     */
    fun setUserEmail(email: String, context: Context? = null) {
        userEmail = email
        context?.let { saveToPreferences(it) }
    }

    /**
     * Toggle whether user sessions should be public by default
     */
    fun setMakeSessionsPublic(isPublic: Boolean, context: Context? = null) {
        makeSessionsPublic = isPublic
        context?.let { saveToPreferences(it) }
        Log.d(TAG, "Session visibility set to public: $isPublic")
    }

    /**
     * Check if user is logged in
     */
    fun isLoggedIn(): Boolean {
        return userId.isNotEmpty()
    }

    /**
     * Get current user ID
     */
    fun getUserId(): String {
        return userId
    }

    /**
     * Get current user email
     */
    fun getUserEmail(): String {
        return userEmail
    }

    /**
     * Check if sessions should be public by default
     */
    fun shouldMakeSessionsPublic(): Boolean {
        return makeSessionsPublic
    }

    /**
     * Save current state to SharedPreferences
     */
    private fun saveToPreferences(context: Context) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        with(prefs.edit()) {
            putString(KEY_USER_ID, userId)
            putString(KEY_USER_EMAIL, userEmail)
            putBoolean(KEY_IS_LOGGED_IN, userId.isNotEmpty())
            putBoolean(KEY_MAKE_SESSIONS_PUBLIC, makeSessionsPublic)
            apply()
        }
    }

    /**
     * Clear user session (logout)
     */
    fun logout(context: Context) {
        userId = ""
        userEmail = ""
        makeSessionsPublic = false

        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        with(prefs.edit()) {
            remove(KEY_USER_ID)
            remove(KEY_USER_EMAIL)
            putBoolean(KEY_IS_LOGGED_IN, false)
            putBoolean(KEY_MAKE_SESSIONS_PUBLIC, false)
            apply()
        }

        Log.d(TAG, "User logged out")
    }
}