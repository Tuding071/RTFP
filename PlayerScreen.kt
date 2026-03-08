package com.rtfp.player

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.viewmodel.compose.viewModel

/**
 * Main player screen composable
 * Combines MPVPlayerView with PlayerOverlay
 */
@Composable
fun PlayerScreen(
    initialVideoPath: String? = null,
    viewModel: PlayerViewModel = viewModel()
) {
    val context = LocalContext.current
    var mpvPlayerView by remember { mutableStateOf<MPVPlayerView?>(null) }
    var showUrlDialog by remember { mutableStateOf(false) }
    
    // File picker launcher
    val filePickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        uri?.let {
            mpvPlayerView?.playFile(it.toString())
        }
    }
    
    DisposableEffect(Unit) {
        onDispose {
            mpvPlayerView?.cleanup()
        }
    }
    
    Box(modifier = Modifier.fillMaxSize()) {
        // MPV Player View
        AndroidView(
            factory = { ctx ->
                MPVPlayerView(ctx).apply {
                    mpvPlayerView = this
                    try {
                        initializePlayer(
                            configDir = ctx.filesDir.path,
                            cacheDir = ctx.cacheDir.path
                        )
                        // Play initial video if provided
                        initialVideoPath?.let { path ->
                            playFile(path)
                        }
                    } catch (e: Exception) {
                        viewModel.setError("Failed to initialize player: ${e.message}")
                    }
                }
            },
            modifier = Modifier.fillMaxSize()
        )
        
        // Player Overlay (gestures and UI)
        PlayerOverlay(
            viewModel = viewModel,
            modifier = Modifier.fillMaxSize()
        )
    }
    
    // URL input dialog
    if (showUrlDialog) {
        var urlInput by remember { mutableStateOf("") }
        
        AlertDialog(
            onDismissRequest = { showUrlDialog = false },
            title = { Text("Enter URL") },
            text = {
                TextField(
                    value = urlInput,
                    onValueChange = { urlInput = it },
                    placeholder = { Text("https://...") }
                )
            },
            confirmButton = {
                Button(onClick = {
                    if (urlInput.isNotBlank()) {
                        mpvPlayerView?.playFile(urlInput)
                        showUrlDialog = false
                    }
                }) {
                    Text("Play")
                }
            },
            dismissButton = {
                Button(onClick = { showUrlDialog = false }) {
                    Text("Cancel")
                }
            }
        )
    }
}
