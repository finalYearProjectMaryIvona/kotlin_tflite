package com.example.trafficobjectdetection

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.util.Log
import android.util.Patterns
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.example.trafficobjectdetection.databinding.ActivityLoginBinding
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit


/**
 * Login Activity for handling user authentication
 */
class LoginActivity : AppCompatActivity() {

    private lateinit var binding: ActivityLoginBinding
    private val executor = Executors.newSingleThreadExecutor()

    // Constants
    companion object {
        private const val TAG = "LoginActivity"
        private const val PREFS_NAME = "TrafficDetectionPrefs"
        private const val KEY_USER_ID = "userId"
        private const val KEY_USER_EMAIL = "userEmail"
        private const val KEY_IS_LOGGED_IN = "isLoggedIn"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityLoginBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // Check if user is already logged in
        if (isUserLoggedIn()) {
            proceedToLauncherActivity()
            return
        }

        // Login button click handler
        binding.btnLogin.setOnClickListener {
            val email = binding.etEmail.text.toString().trim()

            if (validateEmail(email)) {
                binding.progressBar.visibility = View.VISIBLE
                loginUser(email)
            } else {
                binding.etEmail.error = "Please enter a valid email address"
            }
        }

        // Skip button for testing - remove in production
        binding.btnSkipLogin.setOnClickListener {
            // Generate a random user ID for testing
            val testUserId = UUID.randomUUID().toString()
            saveUserSession(testUserId, "test@example.com")
            proceedToLauncherActivity()
        }
    }

    /**
     * Validate email format
     */
    private fun validateEmail(email: String): Boolean {
        return email.isNotEmpty() && Patterns.EMAIL_ADDRESS.matcher(email).matches()
    }

    /**
     * Login user with email
     */
    private fun loginUser(email: String) {
        // Set loading state
        binding.btnLogin.isEnabled = false

        executor.execute {
            try {
                // In a real app, you would verify the user with your backend
                // This is a simplified version that just creates or fetches a user ID

                val userId = getUserIdForEmail(email)

                // Save user session info
                saveUserSession(userId, email)

                // Update UI on main thread
                runOnUiThread {
                    binding.progressBar.visibility = View.GONE
                    binding.btnLogin.isEnabled = true
                    proceedToLauncherActivity()
                }

            } catch (e: Exception) {
                Log.e(TAG, "Login failed", e)
                runOnUiThread {
                    binding.progressBar.visibility = View.GONE
                    binding.btnLogin.isEnabled = true
                    Toast.makeText(this, "Login failed: ${e.message}", Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    /**
     * Get user ID for email - either existing or create new
     * Communicates with the backend to login or create a new user
     */
    private fun getUserIdForEmail(email: String): String {
        // Implementation that calls our backend API
        try {
            val client = OkHttpClient.Builder()
                .connectTimeout(10, TimeUnit.SECONDS)
                .readTimeout(10, TimeUnit.SECONDS)
                .build()

            val jsonBody = JSONObject().apply {
                put("email", email)
            }

            val mediaType = "application/json; charset=utf-8".toMediaTypeOrNull()
            val requestBody = jsonBody.toString().toRequestBody(mediaType)

            // Get the server IP from a constant or configuration
            val serverBaseUrl = "http://PUTYOURIPV4HERE:5000"

            val request = Request.Builder()
                .url("$serverBaseUrl/login")
                .post(requestBody)
                .build()

            // Make a synchronous call (this is in a background thread already)
            val response = client.newCall(request).execute()
            val responseBody = response.body?.string() ?: ""

            if (response.isSuccessful) {
                try {
                    val jsonResponse = JSONObject(responseBody)
                    val userId = jsonResponse.getString("user_id")
                    Log.d(TAG, "Login successful with server, user ID: $userId")
                    return userId
                } catch (e: Exception) {
                    Log.e(TAG, "Error parsing login response: ${e.message}", e)
                    // Fall back to UUID on parse error
                    return UUID.randomUUID().toString()
                }
            } else {
                Log.e(TAG, "Server error on login: ${response.code} - ${responseBody}")
                // Fall back to UUID on server error
                return UUID.randomUUID().toString()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Network error getting user ID: ${e.message}", e)
            // Fall back to UUID for network errors
            return UUID.randomUUID().toString()
        }
    }

    /**
     * Save user session to SharedPreferences
     */
    private fun saveUserSession(userId: String, email: String) {
        val sharedPreferences = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        with(sharedPreferences.edit()) {
            putString(KEY_USER_ID, userId)
            putString(KEY_USER_EMAIL, email)
            putBoolean(KEY_IS_LOGGED_IN, true)
            apply()
        }

        // Also set the user ID in ApiHelper for use in API calls
        UserSessionManager.setUserId(userId)
        UserSessionManager.setUserEmail(email)

        Log.d(TAG, "User session saved: $userId, $email")
    }

    /**
     * Check if user is already logged in
     */
    private fun isUserLoggedIn(): Boolean {
        val sharedPreferences = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val isLoggedIn = sharedPreferences.getBoolean(KEY_IS_LOGGED_IN, false)

        if (isLoggedIn) {
            // Also restore user session data for the app
            val userId = sharedPreferences.getString(KEY_USER_ID, "") ?: ""
            val email = sharedPreferences.getString(KEY_USER_EMAIL, "") ?: ""

            UserSessionManager.setUserId(userId)
            UserSessionManager.setUserEmail(email)

            Log.d(TAG, "User already logged in: $userId, $email")
        }

        return isLoggedIn
    }

    /**
     * Proceed to the launcher activity
     */
    private fun proceedToLauncherActivity() {
        val intent = Intent(this, LauncherActivity::class.java)
        startActivity(intent)
        finish() // Close login activity
    }

    override fun onDestroy() {
        super.onDestroy()
        executor.shutdown()
    }
}