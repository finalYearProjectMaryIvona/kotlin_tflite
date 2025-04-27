package com.example.trafficobjectdetection

import android.net.Uri
import android.os.Bundle
import android.util.Log
import android.widget.MediaController
import androidx.appcompat.app.AppCompatActivity
import com.example.trafficobjectdetection.databinding.ActivityVideoBinding
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream

/**
 * VideoActivity - Simple video player for showing demo video and app information
 */
class VideoActivity : AppCompatActivity() {

    private lateinit var binding: ActivityVideoBinding
    private val TEST_VIDEO_PATH = "tutorial1.mp4"

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityVideoBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // Initialize video player with controls
        setupVideoPlayer()

        // Set app explanation text
        setupExplanationText()
    }

    /**
     * Set up the video player with media controls
     */
    private fun setupVideoPlayer() {
        try {
            // Add media controls (play, pause, seek)
            val mediaController = MediaController(this)
            mediaController.setAnchorView(binding.videoView)
            binding.videoView.setMediaController(mediaController)

            // Copy video from assets to enable playback
            val videoFile = copyVideoFromAssets(TEST_VIDEO_PATH)
            val videoUri = Uri.fromFile(videoFile)

            // Set the video source
            binding.videoView.setVideoURI(videoUri)

            // Add completion listener to loop video if needed
            binding.videoView.setOnCompletionListener {
                Log.d("VideoActivity", "Video playback completed")
                // Uncomment to loop video automatically
                // binding.videoView.start()
            }

            // Start the video
            binding.videoView.start()

        } catch (e: Exception) {
            Log.e("VideoActivity", "Error setting up video player: ${e.message}")
        }
    }

    /**
     * Set the explanation text content
     */
    private fun setupExplanationText() {
        binding.appExplanationText.text = """
            Traffic Detection App
            
            This application uses real-time camera detection to identify and track vehicles on the road. 
            The main features include:
            
            • Live detection of cars, buses, trucks, motorcycles and bicycles
            • GPS location tracking to provide geographic context
            • Direction and movement analysis
            • Session-based tracking for analytics
            
            Use the live camera mode to detect objects in real-time or watch this demo video to see 
            examples of detection in action.
            
            Developed using TensorFlow Lite for on-device machine learning detection.
            
            You must give camera and location permissions for the app to work.
            Try and place your camera enough of a distance away from the road that you get a clear view of the tracking
            coming and going. It will perform best on a clear day because heavy rain will distort the view.
            
            Enabling public mode as soon as you start recording will allow other users to see the data recorded but
            if you choose not to only you can view the session on the web app.
            
            The "save image" switch will save bus images to the phones gallery for your own examination
             but it is not necessary and the images will still be sent to the database be sent to the database.
             
             The above video is an example of a data collecting session using the camera.
            
        """.trimIndent()
    }

    /**
     * Copy video file from assets to cache directory for playback
     */
    private fun copyVideoFromAssets(filename: String): File {
        val file = File(cacheDir, filename)
        if (!file.exists()) {
            try {
                val inputStream: InputStream = assets.open(filename)
                val outputStream = FileOutputStream(file)
                inputStream.copyTo(outputStream)
                inputStream.close()
                outputStream.close()
                Log.d("VideoActivity", "Successfully copied video file to ${file.absolutePath}")
            } catch (e: Exception) {
                Log.e("VideoActivity", "Error copying video file: ${e.message}")
            }
        }
        return file
    }

    override fun onPause() {
        super.onPause()
        // Pause video when leaving the activity
        if (binding.videoView.isPlaying) {
            binding.videoView.pause()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        // Release resources
        binding.videoView.stopPlayback()
    }
}