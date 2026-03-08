package com.rtfp.player

import android.view.ViewGroup
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import `is`.xyz.mpv.MPVView

@Composable
fun PlayerScreen(videoPath: String? = null) {
    val context = LocalContext.current
    
    DisposableEffect(Unit) {
        onDispose {
            // Cleanup handled by MPVView
        }
    }
    
    AndroidView(
        factory = { ctx ->
            MPVView(ctx, null).apply {
                layoutParams = ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT
                )
            }
        },
        update = { view ->
            videoPath?.let {
                view.playFile(it)
            }
        },
        modifier = Modifier.fillMaxSize()
    )
}
