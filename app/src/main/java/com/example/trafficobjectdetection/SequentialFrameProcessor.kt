package com.example.trafficobjectdetection

import android.content.Context
import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.os.Handler
import android.os.Looper
import android.util.Log
import java.io.IOException
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/**
 * SequentialFrameProcessor - Extracts frames from a video file sequentially at regular intervals
 * and processes them in order to maintain consistency.
 */
class SequentialFrameProcessor(private val context: Context) {
    private val TAG = "SeqFrameProcessor"
    private val mainHandler = Handler(Looper.getMainLooper())
    private val extractionExecutor = Executors.newSingleThreadExecutor()
    private var scheduler: ScheduledExecutorService? = null

    private val isProcessing = AtomicBoolean(false)
    private var frameCallback: FrameCallback? = null

    // Tracking for sequential frame processing
    private val currentFrameNumber = AtomicInteger(0)
    private val processingFrameNumber = AtomicInteger(0)
    private val maxQueuedFrames = 3  // Limit number of frames in queue to avoid OOM

    // Frame metadata
    private var durationMs: Long = 0
    private var frameWidth: Int = 0
    private var frameHeight: Int = 0

    // Interface for receiving frames
    interface FrameCallback {
        fun onFrameAvailable(bitmap: Bitmap, frameNumber: Int, timestamp: Long)
        fun onError(exception: Exception)
    }

    /**
     * Start processing frames from the video file
     */
    fun startProcessing(assetPath: String, callback: FrameCallback, fps: Int = 15) {
        if (isProcessing.getAndSet(true)) {
            stopProcessing()
        }

        frameCallback = callback

        extractionExecutor.execute {
            try {
                // Get video metadata first
                analyzeVideo(assetPath) { duration, width, height ->
                    durationMs = duration
                    frameWidth = width
                    frameHeight = height

                    // Start the frame extraction scheduler
                    startFrameScheduler(assetPath, fps)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error starting video processing", e)
                mainHandler.post { callback.onError(e) }
                stopProcessing()
            }
        }
    }

    private fun analyzeVideo(assetPath: String, onComplete: (duration: Long, width: Int, height: Int) -> Unit) {
        val retriever = MediaMetadataRetriever()

        try {
            context.assets.openFd(assetPath).use { afd ->
                retriever.setDataSource(afd.fileDescriptor, afd.startOffset, afd.length)
            }

            val duration = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLong() ?: 0
            val width = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toInt() ?: 0
            val height = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toInt() ?: 0

            retriever.release()

            if (duration <= 0 || width <= 0 || height <= 0) {
                throw IOException("Invalid video metadata: duration=$duration, width=$width, height=$height")
            }

            Log.d(TAG, "Video analysis: duration=$duration ms, dimensions=${width}x${height}")
            onComplete(duration, width, height)

        } catch (e: Exception) {
            retriever.release()
            throw e
        }
    }

    private fun startFrameScheduler(assetPath: String, fps: Int) {
        scheduler = Executors.newSingleThreadScheduledExecutor()

        // Calculate interval between frames
        val frameIntervalMs = 1000 / fps

        // Reset frame counters
        currentFrameNumber.set(0)
        processingFrameNumber.set(0)

        // Start the scheduling
        scheduler?.scheduleAtFixedRate({
            if (!isProcessing.get()) {
                return@scheduleAtFixedRate
            }

            // Check if we're too far ahead in extraction vs processing
            if (currentFrameNumber.get() - processingFrameNumber.get() >= maxQueuedFrames) {
                // Skip this iteration to allow processing to catch up
                Log.d(TAG, "Skipping frame extraction to allow processing to catch up")
                return@scheduleAtFixedRate
            }

            // Extract next frame
            val frameNumber = currentFrameNumber.getAndIncrement()
            val timestampMs = frameNumber * frameIntervalMs.toLong()

            // Check if we've reached the end of the video
            if (timestampMs >= durationMs) {
                // Loop back to beginning
                currentFrameNumber.set(0)
                return@scheduleAtFixedRate
            }

            extractFrame(assetPath, timestampMs, frameNumber)

        }, 0, frameIntervalMs.toLong(), TimeUnit.MILLISECONDS)
    }

    private fun extractFrame(assetPath: String, timestampMs: Long, frameNumber: Int) {
        extractionExecutor.execute {
            if (!isProcessing.get()) return@execute

            val retriever = MediaMetadataRetriever()

            try {
                context.assets.openFd(assetPath).use { afd ->
                    retriever.setDataSource(afd.fileDescriptor, afd.startOffset, afd.length)
                }

                // Extract the frame
                val timestampUs = timestampMs * 1000L
                val bitmap = retriever.getFrameAtTime(
                    timestampUs,
                    MediaMetadataRetriever.OPTION_CLOSEST_SYNC
                )

                if (bitmap != null) {
                    // Convert to ARGB_8888
                    val processedBitmap = if (bitmap.config != Bitmap.Config.ARGB_8888) {
                        bitmap.copy(Bitmap.Config.ARGB_8888, false).also {
                            bitmap.recycle()
                        }
                    } else {
                        bitmap
                    }

                    // Update processing frame counter when callback is delivered
                    mainHandler.post {
                        if (isProcessing.get()) {
                            frameCallback?.onFrameAvailable(processedBitmap, frameNumber, timestampMs)
                            processingFrameNumber.incrementAndGet()
                        } else {
                            processedBitmap.recycle()
                        }
                    }
                }

                retriever.release()

            } catch (e: Exception) {
                Log.e(TAG, "Error extracting frame at $timestampMs ms", e)
                retriever.release()
            }
        }
    }

    fun stopProcessing() {
        isProcessing.set(false)

        scheduler?.shutdown()
        scheduler = null

        try {
            // Wait for executor to finish current tasks
            extractionExecutor.shutdown()
            extractionExecutor.awaitTermination(500, TimeUnit.MILLISECONDS)
        } catch (e: Exception) {
            Log.e(TAG, "Error shutting down executor", e)
        }
    }
}