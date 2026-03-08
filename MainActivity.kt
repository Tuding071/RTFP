package com.rtfp.player

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.core.content.ContextCompat

/**
 * Main Activity - Entry point for RTFP Player
 * Handles intents (ACTION_VIEW, ACTION_SEND) and permissions
 */
class MainActivity : ComponentActivity() {
    
    private var pendingVideoUri: Uri? = null
    
    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val allGranted = permissions.values.all { it }
        if (allGranted) {
            pendingVideoUri?.let { uri ->
                loadVideo(uri)
                pendingVideoUri = null
            }
        } else {
            Toast.makeText(
                this,
                "Storage permission is required to play videos",
                Toast.LENGTH_LONG
            ).show()
        }
    }
    
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        // Check and request permissions
        if (checkPermissions()) {
            handleIntent(intent)
        } else {
            requestPermissions()
        }
    }
    
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        if (checkPermissions()) {
            handleIntent(intent)
        } else {
            requestPermissions()
        }
    }
    
    private fun handleIntent(intent: Intent?) {
        val videoPath = when (intent?.action) {
            Intent.ACTION_VIEW -> {
                // Opened with "Open with" from file manager
                intent.data?.toString()
            }
            Intent.ACTION_SEND -> {
                // Shared from another app
                intent.getParcelableExtra<Uri>(Intent.EXTRA_STREAM)?.toString()
            }
            else -> null
        }
        
        setContent {
            MaterialTheme {
                Surface {
                    PlayerScreen(initialVideoPath = videoPath)
                }
            }
        }
    }
    
    private fun loadVideo(uri: Uri) {
        val videoPath = uri.toString()
        setContent {
            MaterialTheme {
                Surface {
                    PlayerScreen(initialVideoPath = videoPath)
                }
            }
        }
    }
    
    private fun checkPermissions(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            // Android 13+ - No storage permission needed for file access via intents
            true
        } else {
            // Android 11-12 - Check READ_EXTERNAL_STORAGE
            ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.READ_EXTERNAL_STORAGE
            ) == PackageManager.PERMISSION_GRANTED
        }
    }
    
    private fun requestPermissions() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            // Android 13+ - No permission needed
            handleIntent(intent)
        } else {
            // Android 11-12 - Request READ_EXTERNAL_STORAGE
            val permissions = arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE)
            
            // Store pending URI if exists
            when (intent?.action) {
                Intent.ACTION_VIEW -> pendingVideoUri = intent.data
                Intent.ACTION_SEND -> pendingVideoUri = intent.getParcelableExtra(Intent.EXTRA_STREAM)
            }
            
            permissionLauncher.launch(permissions)
        }
    }
    
    override fun onDestroy() {
        super.onDestroy()
        // Cleanup is handled in PlayerScreen's DisposableEffect
    }
}
