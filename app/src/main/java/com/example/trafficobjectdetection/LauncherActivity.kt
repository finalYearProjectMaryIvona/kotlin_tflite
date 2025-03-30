package com.example.trafficobjectdetection

import android.content.Intent
import android.os.Bundle
import android.util.Log
import androidx.appcompat.app.AppCompatActivity
import com.example.trafficobjectdetection.databinding.ActivityLauncherBinding

/**
 * Opening page of app where you can open the live camera or the video
 */
class LauncherActivity : AppCompatActivity() {

    private lateinit var binding: ActivityLauncherBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityLauncherBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // Live Camera Detection button
        binding.btnLiveDetection.setOnClickListener {
            startActivity(Intent(this, MainActivity::class.java))
        }

        // Video button
        binding.btnVideoAnalysis.setOnClickListener {
            startActivity(Intent(this, VideoActivity::class.java))
        }

        // Load logo from assets
        val logoLoaded = ImageUtils.loadImageFromAssets(
            context = this,
            imageName = "sample_bus_logo.png",
            imageView = binding.appLogo,
            makeCircular = true // Apply circular cropping to match rounded background
        )

        if (!logoLoaded) {
            Log.d("LauncherActivity", "Could not load logo from assets, using default resource")
            // Keep the default drawable as fallback
        }
    }
}