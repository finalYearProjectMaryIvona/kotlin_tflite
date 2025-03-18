package com.example.trafficobjectdetection

import android.content.Intent
import android.os.Bundle
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
    }
}