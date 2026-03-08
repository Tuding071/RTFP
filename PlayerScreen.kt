package com.rtfp.player

import android.content.Context
import android.util.AttributeSet
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import `is`.xyz.mpv.BaseMPVView

class SimpleMPVView(context: Context, attrs: AttributeSet? = null) : BaseMPVView(context, attrs) {
    override fun initOptions() {
        // Minimal init - just enable hardware decoding
        mpv.setOptionString("hwdec", "auto")
    }

    override fun postInitOptions() {
        // Nothing needed
    }

    override fun observeProperties() {
        // Nothing needed
    }
}

@Composable
fun PlayerScreen(videoPath: String? = null) {
    val context = LocalContext.current
    
    DisposableEffect(Unit) {
        onDispose {}
    }
    
    AndroidView(
        factory = { ctx ->
            SimpleMPVView(ctx).apply {
                initialize(ctx.filesDir.path, ctx.cacheDir.path)
            }
        },
        update = { view ->
            videoPath?.let { view.playFile(it) }
        },
        modifier = Modifier.fillMaxSize()
    )
}
