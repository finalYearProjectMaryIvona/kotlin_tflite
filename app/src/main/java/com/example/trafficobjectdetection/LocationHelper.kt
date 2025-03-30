package com.example.trafficobjectdetection

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Bundle
import android.util.Log
import android.widget.TextView
import androidx.core.content.ContextCompat
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * Helper class to handle GPS location retrieval and permissions
 */
class LocationHelper(
    private val context: Context,
    private val locationText: TextView? = null
) {
    private val TAG = "LocationHelper"
    private var locationManager: LocationManager? = null
    private var currentLocation: Location? = null
    private val executor = Executors.newSingleThreadScheduledExecutor()

    // Location listener for updates
    private val locationListener = object : LocationListener {
        override fun onLocationChanged(location: Location) {
            currentLocation = location
            updateLocationText()
            Log.d(TAG, "Location updated: ${location.latitude}, ${location.longitude}")
        }

        @Deprecated("Deprecated in Java")
        override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {}

        override fun onProviderEnabled(provider: String) {
            locationText?.text = "GPS Enabled"
        }

        override fun onProviderDisabled(provider: String) {
            locationText?.text = "GPS Disabled"
        }
    }

    companion object {
        const val LOCATION_PERMISSION_REQUEST_CODE = 1001
        val LOCATION_PERMISSIONS = arrayOf(
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION
        )
    }

    /**
     * Start location updates if permissions are granted
     */
    @SuppressLint("MissingPermission")
    fun startLocationUpdates() {
        if (hasLocationPermissions()) {
            locationManager = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager

            // Try to get last known location first for immediate data
            try {
                val lastKnownLocationGPS = locationManager?.getLastKnownLocation(LocationManager.GPS_PROVIDER)
                val lastKnownLocationNetwork = locationManager?.getLastKnownLocation(LocationManager.NETWORK_PROVIDER)

                // Use GPS location if available, otherwise use network location
                val lastKnownLocation = lastKnownLocationGPS ?: lastKnownLocationNetwork

                if (lastKnownLocation != null) {
                    currentLocation = lastKnownLocation
                    updateLocationText()
                    Log.d(TAG, "Using last known location: ${lastKnownLocation.latitude}, ${lastKnownLocation.longitude}")
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error getting last location: ${e.message}")
            }

            // Request location updates
            try {
                if (locationManager?.isProviderEnabled(LocationManager.GPS_PROVIDER) == true) {
                    locationManager?.requestLocationUpdates(
                        LocationManager.GPS_PROVIDER,
                        5000,      // Update interval: 5 seconds
                        10f,       // Min distance: 10 meters
                        locationListener
                    )
                    Log.d(TAG, "GPS provider enabled, requesting updates")
                }

                // Also use network provider as fallback
                if (locationManager?.isProviderEnabled(LocationManager.NETWORK_PROVIDER) == true) {
                    locationManager?.requestLocationUpdates(
                        LocationManager.NETWORK_PROVIDER,
                        5000,
                        10f,
                        locationListener
                    )
                    Log.d(TAG, "Network provider enabled, requesting updates")
                }

                // Set timeout to ensure we get a location within reasonable time
                scheduleFallbackLocation()

                Log.d(TAG, "Location updates requested")
                locationText?.text = "Waiting for GPS..."
            } catch (e: Exception) {
                Log.e(TAG, "Failed to request location updates: ${e.message}")
                locationText?.text = "GPS Error: ${e.message?.take(20)}"
            }
        } else {
            locationText?.text = "GPS Permission Needed"
            Log.d(TAG, "No location permissions")
        }
    }

    /**
     * Schedule a fallback to network location if GPS is taking too long
     */
    private fun scheduleFallbackLocation() {
        executor.schedule({
            if (currentLocation == null) {
                try {
                    @SuppressLint("MissingPermission")
                    val networkLocation = locationManager?.getLastKnownLocation(LocationManager.NETWORK_PROVIDER)
                    if (networkLocation != null) {
                        currentLocation = networkLocation
                        updateLocationText()
                        Log.d(TAG, "Using network location fallback: ${networkLocation.latitude}, ${networkLocation.longitude}")
                    } else {
                        Log.d(TAG, "No network location available as fallback")
                        locationText?.post {
                            locationText.text = "GPS Signal Weak"
                        }
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Error in fallback location: ${e.message}")
                }
            }
        }, 10, TimeUnit.SECONDS)
    }

    /**
     * Stop location updates when no longer needed
     */
    fun stopLocationUpdates() {
        locationManager?.removeUpdates(locationListener)
        executor.shutdown()
    }

    /**
     * Check if we have location permissions
     */
    fun hasLocationPermissions(): Boolean {
        return LOCATION_PERMISSIONS.all {
            ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED
        }
    }

    /**
     * Update the UI with current location information
     */
    private fun updateLocationText() {
        currentLocation?.let { location ->
            locationText?.post {
                locationText.text = "GPS: ${String.format("%.6f", location.latitude)}, ${String.format("%.6f", location.longitude)}"
            }
        }
    }

    /**
     * Get the current location as a formatted string for reporting
     */
    fun getLocationString(): String {
        return currentLocation?.let {
            "${it.latitude},${it.longitude}"
        } ?: "unknown,unknown"
    }

    /**
     * Get GPS latitude as double
     */
    fun getLatitude(): Double? {
        return currentLocation?.latitude
    }

    /**
     * Get GPS longitude as double
     */
    fun getLongitude(): Double? {
        return currentLocation?.longitude
    }
}