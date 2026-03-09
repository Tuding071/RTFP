package com.rtfp.player

import android.content.Intent
import android.content.pm.ActivityInfo
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat

class MainActivity : ComponentActivity() {
    
    private var videoPath: String? = null
    private var shouldFinishOnBack = true
    
    // Register file picker
    private val filePickerLauncher = registerForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        uri?.let {
            // ✅ CRITICAL FIX: Take persistent permission!
            try {
                contentResolver.takePersistableUriPermission(
                    it,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION
                )
            } catch (e: SecurityException) {
                // Some file providers don't support persistent permissions
                // but the URI might still work temporarily
                e.printStackTrace()
            }
            
            videoPath = it.toString()
            // Recreate to show PlayerScreen with selected video
            recreate()
        }
    }
    
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
                if (videoPath == null) {
                    // Show file picker UI
                    FilePickerScreen(
                        onBrowseClicked = {
                            // Launch system file picker
                            filePickerLauncher.launch("video/*")
                        },
                        onExitClicked = {
                            finish()
                        }
                    )
                } else {
                    // Show player screen
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
    
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        // New video opened - recreate activity with new intent
        setIntent(intent)
        recreate()
    }
    
    private fun extractVideoPath(intent: Intent?): String? {
        return when (intent?.action) {
            Intent.ACTION_VIEW -> intent.data?.toString()
            Intent.ACTION_SEND -> intent.getParcelableExtra<Uri>(Intent.EXTRA_STREAM)?.toString()
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

@Composable
fun FilePickerScreen(
    onBrowseClicked: () -> Unit,
    onExitClicked: () -> Unit
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFF1A1A1A)), // Dark grey background
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Text(
                text = "RTFP Video Player",
                color = Color.White,
                fontSize = 24.sp
            )
            
            Spacer(modifier = Modifier.height(16.dp))
            
            Text(
                text = "Select a video to play",
                color = Color.White.copy(alpha = 0.7f),
                fontSize = 16.sp
            )
            
            Spacer(modifier = Modifier.height(32.dp))
            
            Button(
                onClick = onBrowseClicked,
                colors = ButtonDefaults.buttonColors(
                    containerColor = Color(0xFF2A2A2A)
                )
            ) {
                Text(
                    text = "📁 Select Video File",
                    color = Color.White,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
                )
            }
            
            Spacer(modifier = Modifier.height(16.dp))
            
            Button(
                onClick = onExitClicked,
                colors = ButtonDefaults.buttonColors(
                    containerColor = Color(0xFF2A2A2A)
                )
            ) {
                Text(
                    text = "Exit",
                    color = Color.White,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
                )
            }
        }
    }
}
