package com.rtfp.player

import android.content.Context
import android.util.AttributeSet
import android.util.Log
import `is`.xyz.mpv.BaseMPVView

/**
 * Custom MPV player view extending BaseMPVView from mpv-android-lib
 * Handles MPV initialization and configuration
 */
class MPVPlayerView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : BaseMPVView(context, attrs) {

    companion object {
        private const val TAG = "MPVPlayerView"
    }

    override fun initOptions() {
        // Set options before mpv.init() is called
        try {
            mpv.setOptionString("hwdec", "auto")
            mpv.setOptionString("vo", "gpu")
            mpv.setOptionString("profile", "fast")
            mpv.setOptionString("keep-open", "yes")
            Log.d(TAG, "MPV pre-init options set")
        } catch (e: Exception) {
            Log.e(TAG, "Error setting pre-init options", e)
        }
    }

    override fun postInitOptions() {
        // Set options after mpv.init() is called
        try {
            // Performance optimizations
            mpv.setOptionString("vd-lavc-threads", "8")
            mpv.setOptionString("audio-channels", "auto")
            mpv.setOptionString("demuxer-lavf-threads", "4")
            mpv.setOptionString("cache-initial", "0.5")
            mpv.setOptionString("video-sync", "display-resample")
            mpv.setOptionString("untimed", "yes")
            
            // Seeking optimization
            mpv.setOptionString("hr-seek", "yes")
            mpv.setOptionString("hr-seek-framedrop", "no")
            
            // Fast decoding
            mpv.setOptionString("vd-lavc-fast", "yes")
            mpv.setOptionString("vd-lavc-skiploopfilter", "all")
            mpv.setOptionString("vd-lavc-skipidct", "all")
            mpv.setOptionString("vd-lavc-assemble", "yes")
            
            // GPU optimization
            mpv.setOptionString("gpu-dumb-mode", "yes")
            mpv.setOptionString("opengl-pbo", "yes")
            
            // Network optimization
            mpv.setOptionString("stream-lavf-o", "reconnect=1:reconnect_at_eof=1:reconnect_streamed=1")
            mpv.setOptionString("network-timeout", "30")
            
            // Audio optimization
            mpv.setOptionString("audio-client-name", "RTFP-Player")
            mpv.setOptionString("audio-samplerate", "auto")
            
            // Subtitle settings
            mpv.setOptionString("sub-auto", "fuzzy")
            
            // Video aspect
            mpv.setOptionString("deband", "no")
            mpv.setOptionString("video-aspect-override", "no")
            
            Log.d(TAG, "MPV post-init options set")
        } catch (e: Exception) {
            Log.e(TAG, "Error setting post-init options", e)
        }
    }

    override fun observeProperties() {
        // Required abstract method - observe MPV properties
        // This is called by the library to set up property observers
    }

    /**
     * Initialize the player with config and cache directories
     */
    fun initializePlayer(configDir: String, cacheDir: String) {
        try {
            initialize(configDir = configDir, cacheDir = cacheDir)
            Log.d(TAG, "MPV player initialized successfully")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to initialize MPV player", e)
            throw e
        }
    }

    /**
     * Get MPV instance for property access and commands
     */
    fun getMPV() = mpv

    /**
     * Cleanup resources
     */
    fun cleanup() {
        try {
            mpv.destroy()
            Log.d(TAG, "MPV player destroyed")
        } catch (e: Exception) {
            Log.e(TAG, "Error destroying MPV player", e)
        }
    }
}
