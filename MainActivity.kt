package com.rtfp.player

import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.material3.MaterialTheme
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    private var currentVideoPath: String? = null
    private var savedPosition: Long = 0
    private var doubleBackToExitPressedOnce = false
    private val handler = Handler(Looper.getMainLooper())
    
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        // Restore state if available
        savedInstanceState?.let {
            savedPosition = it.getLong("position", 0)
            currentVideoPath = it.getString("videoPath")
        }
        
        // Handle intent
        handleIntent(intent)
        
        setContent {
            MaterialTheme {
                PlayerScreen(
                    videoPath = currentVideoPath,
                    initialPosition = savedPosition,
                    onPositionChange = { position ->
                        savedPosition = position
                    }
                )
            }
        }
    }
    
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        // New video = reset position and play from start
        savedPosition = 0
        handleIntent(intent)
    }
    
    private fun handleIntent(intent: Intent) {
        currentVideoPath = when (intent?.action) {
            Intent.ACTION_VIEW -> intent.data?.toString()
            Intent.ACTION_SEND -> intent.getParcelableExtra<android.net.Uri>(Intent.EXTRA_STREAM)?.toString()
            else -> null
        }
    }
    
    override fun onPause() {
        super.onPause()
        // PlayerScreen will handle saving position via onPositionChange callback
    }
    
    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putLong("position", savedPosition)
        outState.putString("videoPath", currentVideoPath)
    }
    
    override fun onBackPressed() {
        if (doubleBackToExitPressedOnce) {
            super.onBackPressed()
            return
        }
        
        doubleBackToExitPressedOnce = true
        
        // Tell PlayerScreen to pause if playing
        // We'll handle this via a callback in PlayerScreen
        
        lifecycleScope.launch {
            delay(200)
            doubleBackToExitPressedOnce = false
        }
    }
    
    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) {
            hideSystemUI()
        }
    }
    
    private fun hideSystemUI() {
        window.decorView.systemUiVisibility = (
            View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
            or View.SYSTEM_UI_FLAG_LAYOUT_STABLE
            or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
            or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
            or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
            or View.SYSTEM_UI_FLAG_FULLSCREEN
        )
    }
}
