package com.rtfp.player

import android.content.Intent
import android.content.pm.ActivityInfo
import android.os.Bundle
import android.view.View
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.background
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat

class MainActivity : ComponentActivity() {
    
    private var videoPath: String? = null
    
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        // Get video path from intent
        videoPath = extractVideoPath(intent)
        
        // Set up fullscreen immersive mode
        setupFullscreen()
        
        // Screen timeout is now managed dynamically inside PlayerScreen
        // based on playback state (playing / paused / finished), not fixed on here.
        
        // Handle back button - always finish (destroy) the activity
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                finish() // Destroy activity completely
            }
        })
        
        setContent {
            MaterialTheme {
                // Simple background color - Dark Gray
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(Color.DarkGray)
                ) {
                    if (videoPath == null) {
                        // Centered text instruction
                        Text(
                            text = "RTFP\nReal-Time Frame Player\n\nUse any file manager to play a video",
                            modifier = Modifier.align(Alignment.Center),
                            color = Color.White,
                            fontSize = 20.sp,
                            fontFamily = FontFamily.Default,
                            lineHeight = 28.sp
                        )
                    } else {
                        PlayerScreen(
                            videoPath = videoPath,
                            onVideoLoaded = { width, height ->
                                setOrientationForVideo(width, height)
                            }
                        )
                    }
                }
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
            Intent.ACTION_SEND -> {
                // File share (has a content:// or file:// URI attached)
                val streamUri = intent.getParcelableExtra<android.net.Uri>(Intent.EXTRA_STREAM)
                if (streamUri != null) {
                    streamUri.toString()
                } else {
                    // Link share (m3u8, http/https URL shared as plain text)
                    val text = intent.getStringExtra(Intent.EXTRA_TEXT)
                    text?.let { extractUrlFromText(it) }
                }
            }
            else -> null
        }
    }
    
    private fun extractUrlFromText(text: String): String? {
        // Many apps prepend a title/caption before the URL, so pull out the URL itself
        val urlRegex = Regex("""https?://\S+""")
        return urlRegex.find(text)?.value ?: text.trim().takeIf { it.isNotBlank() }
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
