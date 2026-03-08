package com.rtfp.player

import android.content.Context
import android.util.AttributeSet
import android.widget.FrameLayout
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import is.xyz.mpv.MPVView
import is.xyz.mpv.MPVLib

class SimpleMPVView(context: Context, attrs: AttributeSet? = null) : MPVView(context, attrs) {
    
    private var isPaused = false
    var onPositionUpdate: ((Long) -> Unit)? = null
    
    init {
        // Set up options
        mpv?.setOptionString("hwdec", "auto")
        mpv?.setOptionString("keep-open", "yes")  // Stay on last frame when paused
        
        // Listen for position updates
        mpv?.observeProperty(MPVLib.MPV_PROPERTY_TIME_POS, 1)
        mpv?.setOnPropertyChangeListener { property, value ->
            if (property == MPVLib.MPV_PROPERTY_TIME_POS) {
                (value as? Double)?.let {
                    onPositionUpdate?.invoke((it * 1000).toLong())
                }
            }
        }
    }
    
    fun pause() {
        if (!isPaused) {
            mpv?.command("set", "pause", "yes")
            isPaused = true
        }
    }
    
    fun play() {
        if (isPaused) {
            mpv?.command("set", "pause", "no")
            isPaused = false
        }
    }
    
    fun togglePlay() {
        if (isPaused) play() else pause()
    }
    
    fun isPlaying(): Boolean = !isPaused
    
    override fun onPause() {
        super.onPause()
        pause()
    }
    
    override fun onResume() {
        super.onResume()
        // Don't auto-play - stay paused
    }
}

@Composable
fun PlayerScreen(
    videoPath: String? = null,
    initialPosition: Long = 0,
    onPositionChange: (Long) -> Unit
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    
    val mpvView = remember { mutableStateOf<SimpleMPVView?>(null) }
    
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_PAUSE -> {
                    mpvView.value?.pause()
                }
                Lifecycle.Event.ON_DESTROY -> {
                    mpvView.value?.mpv?.command("quit")
                }
                else -> {}
            }
        }
        
        lifecycleOwner.lifecycle.addObserver(observer)
        
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
        }
    }
    
    AndroidView(
        factory = { ctx ->
            SimpleMPVView(ctx).apply {
                layoutParams = FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    FrameLayout.LayoutParams.MATCH_PARENT
                )
                initialize(ctx.filesDir.path, ctx.cacheDir.path)
                onPositionUpdate = onPositionChange
                mpvView.value = this
            }
        },
        update = { view ->
            videoPath?.let { path ->
                view.playFile(path)
                if (initialPosition > 0) {
                    view.mpv?.command("seek", (initialPosition / 1000.0).toString(), "absolute")
                }
                view.pause() // Start paused
            }
        },
        modifier = Modifier.fillMaxSize()
    )
}
