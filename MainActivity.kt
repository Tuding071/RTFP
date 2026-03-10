package com.rtfp.player

import android.content.Intent
import android.content.pm.ActivityInfo
import android.os.Bundle
import android.view.View
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.activity.compose.setContent
import androidx.compose.material3.MaterialTheme
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat

class MainActivity : ComponentActivity() {
    
    private var videoPath: String? = null
    private var shouldFinishOnBack = true
    
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        // Get video path from intent
        videoPath = extractVideoPath(intent)
        
        // Setup fullscreen immersive mode
        setupFullscreen()
        
        // Keep screen on while activity is visible
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        
        // Handle back button - always finish (destroy) the activity
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                finish() // Destroy activity completely
            }
        })
        
        setContent {
            MaterialTheme {
                PlayerScreen(
                    videoPath = videoPath,
                    onVideoLoaded = { width, height ->
                        setOrientationForVideo(width, height)
                    }
                )
            }
        }
    }
    
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        // New video opened - recreate activity with new intent
        setIntent(intent)
        recreate()
    }
    
    private fun extractVideoPath(intent: Intent?): String? {
        return when (intent?.action) {
            Intent.ACTION_VIEW -> intent.data?.toString()
            Intent.ACTION_SEND -> intent.getParcelableExtra<android.net.Uri>(Intent.EXTRA_STREAM)?.toString()
            else -> null
        }
    }
    
    private fun setupFullscreen() {
        WindowCompat.setDecorFitsSystemWindows(window, false)
        
        val controller = WindowInsetsControllerCompat(window, window.decorView)
        controller.hide(WindowInsetsCompat.Type.systemBars())
        controller.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        
        window.decorView.setOnSystemUiVisibilityChangeListener { visibility ->
            if (visibility and View.SYSTEM_UI_FLAG_FULLSCREEN == 0) {
                controller.hide(WindowInsetsCompat.Type.systemBars())
            }
        }
    }
    
    private fun setOrientationForVideo(width: Int, height: Int) {
        requestedOrientation = if (width > height) {
            ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
        } else {
            ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
        }
    }
    
    override fun onResume() {
        super.onResume()
        setupFullscreen()
    }
    
    override fun onDestroy() {
        super.onDestroy()
        // Clear video path to prevent reuse
        videoPath = null
    }
}
