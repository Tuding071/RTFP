package com.rtfp.player

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.AttributeSet
import android.util.Log
import android.view.MotionEvent
import android.view.WindowManager
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.pointerInteropFilter
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import `is`.xyz.mpv.BaseMPVView
import `is`.xyz.mpv.MPV
import kotlinx.coroutines.*
import org.json.JSONObject
import java.io.File
import kotlin.math.abs
import kotlin.math.sign

// ===== prompt modes =====
private const val PROMPT_NONE = 0
private const val PROMPT_RESUME = 1
private const val PROMPT_REPLAY = 2

// ===== seek mode (speed hacks ON only while scrubbing, OFF for normal playback) =====
private fun safeSetProp(mpv: MPV, name: String, value: String) {
    try {
        mpv.setPropertyString(name, value)
    } catch (e: Exception) {
        Log.d("PlayerDebug", "setProperty failed: $name=$value")
    }
}

private fun applySeekMode(mpv: MPV, enabled: Boolean) {
    if (enabled) {
        safeSetProp(mpv, "untimed", "yes")
        safeSetProp(mpv, "vd-lavc-fast", "yes")
        safeSetProp(mpv, "vd-lavc-skiploopfilter", "all")
        safeSetProp(mpv, "vd-lavc-skipidct", "all")
    } else {
        safeSetProp(mpv, "untimed", "no")
        safeSetProp(mpv, "vd-lavc-fast", "no")
        safeSetProp(mpv, "vd-lavc-skiploopfilter", "default")
        safeSetProp(mpv, "vd-lavc-skipidct", "default")
    }
}

// ===== resume position storage (one entry per file/link, max 200, oldest dropped) =====
private object ResumeStore {
    private const val PREFS = "rtfp_resume"
    private const val ENTRIES = "entries"
    private const val MAX_ENTRIES = 200
    private const val MIN_SAVE_SECONDS = 10.0

    private fun load(context: Context): JSONObject {
        val raw = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(ENTRIES, null)
        return try {
            if (raw != null) JSONObject(raw) else JSONObject()
        } catch (e: Exception) {
            JSONObject()
        }
    }

    private fun write(context: Context, obj: JSONObject) {
        // commit() = written immediately, safe even if the app is swiped away right after
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putString(ENTRIES, obj.toString()).commit()
    }

    fun get(context: Context, key: String): Double? {
        val entry = load(context).optJSONObject(key) ?: return null
        val pos = entry.optDouble("p", -1.0)
        return if (pos >= MIN_SAVE_SECONDS) pos else null
    }

    fun save(context: Context, key: String, position: Double) {
        if (position < MIN_SAVE_SECONDS) {
            remove(context, key)
            return
        }
        val obj = load(context)
        obj.put(key, JSONObject().put("p", position).put("t", System.currentTimeMillis()))
        while (obj.length() > MAX_ENTRIES) {
            var oldestKey: String? = null
            var oldestTime = Long.MAX_VALUE
            val keys = obj.keys()
            while (keys.hasNext()) {
                val k = keys.next()
                val t = obj.optJSONObject(k)?.optLong("t", 0L) ?: 0L
                if (t < oldestTime) {
                    oldestTime = t
                    oldestKey = k
                }
            }
            if (oldestKey == null) break
            obj.remove(oldestKey)
        }
        write(context, obj)
    }

    fun remove(context: Context, key: String) {
        val obj = load(context)
        if (obj.has(key)) {
            obj.remove(key)
            write(context, obj)
        }
    }

    // Links: URL (query stripped when the path looks like a file, e.g. .m3u8/.mp4)
    // Files: display name + duration (content:// URIs can change between opens)
    fun makeKey(context: Context, videoPath: String?, mpv: MPV, duration: Double): String? {
        if (videoPath == null) return null
        val isLink = videoPath.startsWith("http://", true) || videoPath.startsWith("https://", true)
        return if (isLink) {
            val noFragment = videoPath.substringBefore("#")
            val base = noFragment.substringBefore("?")
            val lastSegment = base.substringAfterLast("/")
            "u|" + if (lastSegment.contains('.')) base else noFragment
        } else {
            if (duration <= 1.0) null
            else "f|" + resolveDisplayName(context, mpv) + "|" + duration.toInt()
        }
    }
}

class SimpleMPVView(context: Context, attrs: AttributeSet? = null) : BaseMPVView(context, attrs) {
    
    override fun initOptions() {
        mpv.setOptionString("hwdec", "no")
        mpv.setOptionString("vo", "gpu")
        mpv.setOptionString("profile", "fast")
        mpv.setOptionString("keepaspect", "yes")
        // stay on the last frame at the end (needed for the Replay screen)
        mpv.setOptionString("keep-open", "yes")
    }

    override fun postInitOptions() {
        // Performance
        mpv.setOptionString("vd-lavc-threads", "8")
        mpv.setOptionString("demuxer-lavf-threads", "8")
        mpv.setOptionString("cache-initial", "0.5")
        
        // Seeking
        mpv.setOptionString("hr-seek", "yes")
        mpv.setOptionString("hr-seek-framedrop", "yes")
        
        // these four start at NORMAL values; seek mode turns them on while scrubbing
        mpv.setOptionString("untimed", "no")
        mpv.setOptionString("vd-lavc-fast", "no")
        mpv.setOptionString("vd-lavc-skiploopfilter", "default")
        mpv.setOptionString("vd-lavc-skipidct", "default")
        mpv.setOptionString("vd-lavc-assemble", "yes")
        
        // GPU
        mpv.setOptionString("gpu-dumb-mode", "yes")
        mpv.setOptionString("opengl-pbo", "yes")
        mpv.setOptionString("opengl-early-flush", "yes")
        
        // Audio
        mpv.setOptionString("audio-channels", "auto")
        mpv.setOptionString("audio-samplerate", "auto")
        
        // Video
        mpv.setOptionString("deband", "no")
        mpv.setOptionString("video-aspect-override", "no")

        // Network streaming (HLS/M3U8) - tolerate slow connections
        mpv.setOptionString("network-timeout", "60")
        mpv.setOptionString("cache", "yes")
        mpv.setOptionString("cache-secs", "30")
    }

    override fun observeProperties() {}
}

@Composable
fun PlayerScreen(
    videoPath: String? = null,
    onVideoLoaded: (width: Int, height: Int) -> Unit = { _, _ -> }
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    var mpvView by remember { mutableStateOf<SimpleMPVView?>(null) }
    var mpvInstance by remember { mutableStateOf<MPV?>(null) }
    var isVideoLoaded by remember { mutableStateOf(false) }
    var savedPosition by remember { mutableStateOf<Double?>(null) }
    val coroutineScope = rememberCoroutineScope()

    // ===== resume / replay state =====
    var promptMode by remember { mutableStateOf(PROMPT_NONE) }
    var resumePosition by remember { mutableStateOf(0.0) }
    var resumeKey by remember { mutableStateOf<String?>(null) }
    var trackingEnabled by remember { mutableStateOf(false) }
    var ignoreEndUntil by remember { mutableStateOf(0L) }

    // Saves current position (or clears the entry when within 5s of the end)
    fun saveProgress() {
        val key = resumeKey ?: return
        if (!trackingEnabled || promptMode != PROMPT_NONE) return
        val m = mpvInstance ?: return
        val pos = m.getPropertyDouble("time-pos") ?: return
        val dur = m.getPropertyDouble("duration") ?: 0.0
        if (dur > 1.0 && pos >= dur - 5.0) {
            ResumeStore.remove(context, key)
        } else {
            ResumeStore.save(context, key, pos)
        }
    }

    fun resumeChoice() {
        val m = mpvInstance ?: return
        m.command("seek", resumePosition.toString(), "absolute", "exact")
        m.setPropertyBoolean("pause", false)
        promptMode = PROMPT_NONE
        trackingEnabled = true
    }

    fun startOver() {
        val m = mpvInstance ?: return
        resumeKey?.let { ResumeStore.remove(context, it) }
        ignoreEndUntil = System.currentTimeMillis() + 1500
        m.command("seek", "0", "absolute", "exact")
        m.setPropertyBoolean("pause", false)
        promptMode = PROMPT_NONE
        trackingEnabled = true
    }

    fun handleEnded() {
        if (System.currentTimeMillis() < ignoreEndUntil) return
        if (promptMode != PROMPT_NONE) return
        resumeKey?.let { ResumeStore.remove(context, it) }
        trackingEnabled = false
        promptMode = PROMPT_REPLAY
    }
    
    // Loading indicator alpha, same blink style as buffering
    val loadingAlpha = remember { Animatable(0f) }
    
    LaunchedEffect(isVideoLoaded) {
        if (!isVideoLoaded) {
            while (!isVideoLoaded) {
                loadingAlpha.animateTo(1f, animationSpec = tween(300))
                delay(400)
                loadingAlpha.animateTo(0f, animationSpec = tween(300))
            }
        } else {
            loadingAlpha.snapTo(0f)
        }
    }

    // once loaded, look up saved position and decide: prompt or play
    LaunchedEffect(isVideoLoaded) {
        if (!isVideoLoaded) return@LaunchedEffect
        val m = mpvInstance ?: return@LaunchedEffect
        val isLink = videoPath?.startsWith("http", ignoreCase = true) == true

        var duration = m.getPropertyDouble("duration") ?: 0.0
        if (!isLink) {
            var tries = 0
            while (duration <= 1.0 && tries < 30) {
                delay(100)
                duration = m.getPropertyDouble("duration") ?: 0.0
                tries++
            }
        }

        val key = ResumeStore.makeKey(context, videoPath, m, duration)
        resumeKey = key
        val saved = if (key != null) ResumeStore.get(context, key) else null

        if (saved != null && (duration <= 1.0 || saved < duration - 5.0)) {
            resumePosition = saved
            m.setPropertyBoolean("pause", true)
            promptMode = PROMPT_RESUME
        } else {
            m.setPropertyBoolean("pause", false)
            trackingEnabled = true
        }
    }

    // backup save every 5s while playing
    LaunchedEffect(trackingEnabled, mpvInstance) {
        val m = mpvInstance ?: return@LaunchedEffect
        while (trackingEnabled && isActive) {
            delay(5000)
            val paused = m.getPropertyBoolean("pause") ?: false
            if (!paused) saveProgress()
        }
    }
    
    // Screen timeout management: keep screen on while actively playing, but
    // allow it to sleep after 15 minutes of being paused or finished with no interaction.
    DisposableEffect(isVideoLoaded, mpvInstance) {
        var screenOnJob: Job? = null
        val activity = context as? android.app.Activity
        val idleTimeoutMs = 15 * 60 * 1000L // 15 minutes
        
        if (isVideoLoaded && mpvInstance != null && activity != null) {
            val mpv = mpvInstance!!
            
            screenOnJob = coroutineScope.launch {
                var wasIdle = false
                
                while (isActive) {
                    delay(1000)
                    
                    val paused = mpv.getPropertyBoolean("pause") ?: false
                    val position = mpv.getPropertyDouble("time-pos") ?: 0.0
                    val duration = mpv.getPropertyDouble("duration") ?: 0.0
                    val isFinished = duration > 0 && position >= duration - 0.5
                    val shouldAllowIdle = paused || isFinished
                    
                    if (shouldAllowIdle && !wasIdle) {
                        // Just entered paused/finished state — start 15-min countdown
                        wasIdle = true
                        delay(idleTimeoutMs)
                        
                        // Re-check state hasn't changed during the wait
                        val stillPaused = mpv.getPropertyBoolean("pause") ?: false
                        val stillPosition = mpv.getPropertyDouble("time-pos") ?: 0.0
                        val stillDuration = mpv.getPropertyDouble("duration") ?: 0.0
                        val stillFinished = stillDuration > 0 && stillPosition >= stillDuration - 0.5
                        
                        if (stillPaused || stillFinished) {
                            activity.window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                        }
                    } else if (!shouldAllowIdle) {
                        // Playing again — ensure screen-on flag is restored, reset idle tracking
                        wasIdle = false
                        activity.window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                    }
                }
            }
        }
        
        onDispose {
            screenOnJob?.cancel()
        }
    }
    
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_PAUSE -> {
                    mpvView?.let { view ->
                        // make sure seek mode is never left on if the app is backgrounded mid-drag
                        applySeekMode(view.mpv, false)
                        savedPosition = view.mpv.getPropertyDouble("time-pos")
                        // main save point (back / home / recents all pass through here)
                        saveProgress()
                        view.mpv.setPropertyBoolean("pause", true)
                    }
                }
                Lifecycle.Event.ON_RESUME -> {
                    mpvView?.let { view ->
                        savedPosition?.let { pos ->
                            view.mpv.command("seek", pos.toString(), "absolute", "exact")
                        }
                    }
                }
                else -> {}
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            mpvView?.mpv?.destroy()
        }
    }
    
    Box(modifier = Modifier.fillMaxSize()) {
        AndroidView(
            factory = { ctx ->
                SimpleMPVView(ctx).apply {
                    mpvView = this
                    mpvInstance = this.mpv
                    
                    val filesDir = ctx.filesDir.path
                    val cacheDir = File(ctx.cacheDir, "mpv").apply { mkdirs() }.path
                    initialize(filesDir, cacheDir)
                    
                    videoPath?.let { path ->
                        // start paused so nothing plays before we know about a saved position
                        mpv.setPropertyBoolean("pause", true)
                        playFile(path)
                        
                        coroutineScope.launch {
                            var width = 0
                            var height = 0
                            
                            while (width <= 0 || height <= 0) {
                                delay(100)
                                width = mpv.getPropertyInt("width") ?: 0
                                height = mpv.getPropertyInt("height") ?: 0
                            }
                            
                            Log.d("PlayerDebug", "Video loaded - Width: $width, Height: $height")
                            
                            onVideoLoaded(width, height)
                            isVideoLoaded = true
                        }
                    }
                }
            },
            modifier = Modifier.fillMaxSize()
        )
        
        // Loading indicator - shown before the player overlay even exists
        if (!isVideoLoaded) {
            Text(
                text = "Loading",
                style = TextStyle(
                    color = Color.White.copy(alpha = loadingAlpha.value),
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Medium
                ),
                modifier = Modifier
                    .align(Alignment.Center)
                    .background(Color.DarkGray.copy(alpha = 0.8f * loadingAlpha.value))
                    .padding(horizontal = 12.dp, vertical = 4.dp)
            )
        }
        
        // UI Overlay - Only show when video is loaded AND we have a valid MPV instance
        if (isVideoLoaded && mpvInstance != null) {
            PlayerOverlay(
                mpv = mpvInstance!!,
                promptMode = promptMode,
                resumePosition = resumePosition,
                onResumeChoice = { resumeChoice() },
                onStartOver = { startOver() },
                onEnded = { handleEnded() },
                modifier = Modifier.fillMaxSize()
            )
        }
    }
}

@OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class, androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
fun PlayerOverlay(
    mpv: MPV,
    promptMode: Int,
    resumePosition: Double,
    onResumeChoice: () -> Unit,
    onStartOver: () -> Unit,
    onEnded: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()

    // always-fresh values for use inside long-running effects
    val currentPromptMode by rememberUpdatedState(promptMode)
    val currentOnEnded by rememberUpdatedState(onEnded)
    var promptStartX by remember { mutableStateOf(0f) }
    val promptSwipeThreshold = 150f
    
    var currentTime by remember { mutableStateOf("00:00") }
    var totalTime by remember { mutableStateOf("00:00") }
    var seekTargetTime by remember { mutableStateOf("00:00") }
    var showSeekTime by remember { mutableStateOf(false) }
    var isSpeedingUp by remember { mutableStateOf(false) }
    var showSeekbar by remember { mutableStateOf(true) }
    
    var seekbarPosition by remember { mutableStateOf(0f) }
    var seekbarDuration by remember { mutableStateOf(1f) }
    var isDragging by remember { mutableStateOf(false) }
    
    var isSeeking by remember { mutableStateOf(false) }
    var seekStartX by remember { mutableStateOf(0f) }
    var seekStartPosition by remember { mutableStateOf(0.0) }
    var wasPlayingBeforeSeek by remember { mutableStateOf(false) }
    var seekDirection by remember { mutableStateOf("") }
    var isSeekInProgress by remember { mutableStateOf(false) }
    val seekThrottleMs = 11L

    // NEW: seek-mode setup gate. Seeks are blocked until the options have settled.
    val seekModeSwitchDelayMs = 50L
    var seekReady by remember { mutableStateOf(false) }
    var seekSetupJob by remember { mutableStateOf<Job?>(null) }
    
    var lastSeekTime by remember { mutableStateOf(0L) }
    var lastHorizontalUpdateTime by remember { mutableStateOf(0L) }
    
    var touchStartTime by remember { mutableStateOf(0L) }
    var touchStartX by remember { mutableStateOf(0f) }
    var touchStartY by remember { mutableStateOf(0f) }
    var isTouching by remember { mutableStateOf(false) }
    var isLongTap by remember { mutableStateOf(false) }
    var isHorizontalSwipe by remember { mutableStateOf(false) }
    var isVerticalSwipe by remember { mutableStateOf(false) }
    
    val longTapThreshold = 300L
    val horizontalSwipeThreshold = 30f
    val verticalSwipeThreshold = 40f
    val maxVerticalMovement = 50f
    val maxHorizontalMovement = 50f
    val quickSeekAmount = 5
    
    var showVideoInfo by remember { mutableStateOf(true) }
    var fileName by remember { mutableStateOf("RTFP") }
    
    var userInteracting by remember { mutableStateOf(false) }
    
    var showPlaybackFeedback by remember { mutableStateOf(false) }
    var playbackFeedbackText by remember { mutableStateOf("") }
    
    var showQuickSeekFeedback by remember { mutableStateOf(false) }
    var quickSeekFeedbackText by remember { mutableStateOf("") }
    
    var isDurationValid by remember { mutableStateOf(false) }
    
    // Buffering indicator state — inferred from time-pos not advancing during normal playback
    var isBuffering by remember { mutableStateOf(false) }
    val bufferingAlpha = remember { Animatable(0f) }
    var seekGraceUntil by remember { mutableStateOf(0L) }

    // small grace period whenever a prompt closes (avoids a false "Buffering" blink)
    LaunchedEffect(promptMode) {
        if (promptMode == PROMPT_NONE) {
            seekGraceUntil = System.currentTimeMillis() + 1000
        }
    }
    
    LaunchedEffect(showSeekbar, userInteracting) {
        if (showSeekbar && !userInteracting) {
            delay(4000)
            if (!userInteracting) {
                showSeekbar = false
                showVideoInfo = false
            }
        }
    }
    
    LaunchedEffect(Unit) {
        var duration = mpv.getPropertyDouble("duration") ?: 0.0
        while (duration <= 1.0) {
            delay(100)
            duration = mpv.getPropertyDouble("duration") ?: 0.0
        }
        Log.d("PlayerDebug", "Duration finally available: $duration")
        seekbarDuration = duration.toFloat()
        totalTime = formatTimeSimple(duration)
        isDurationValid = true
    }
    
    // Stall detector: watches time-pos, flags buffering if it's stuck while it should be moving.
    // Ignores pause, active seeking/dragging, prompts, and a brief grace period after any seek completes.
    // Also detects end of video (eof-reached, works because of keep-open) -> Replay prompt.
    LaunchedEffect(Unit) {
        var lastPosition = -1.0
        var lastChangeTime = System.currentTimeMillis()
        
        while (isActive) {
            delay(300)
            
            val now = System.currentTimeMillis()

            if (currentPromptMode == PROMPT_NONE && !isSeeking && !isDragging && now >= seekGraceUntil) {
                val eof = mpv.getPropertyBoolean("eof-reached") ?: false
                if (eof) {
                    isBuffering = false
                    currentOnEnded()
                    continue
                }
            }

            val paused = mpv.getPropertyBoolean("pause") ?: false
            val inGracePeriod = now < seekGraceUntil
            
            if (paused || isSeeking || isDragging || inGracePeriod || currentPromptMode != PROMPT_NONE) {
                lastPosition = mpv.getPropertyDouble("time-pos") ?: lastPosition
                lastChangeTime = now
                if (isBuffering) isBuffering = false
                continue
            }
            
            val currentPosition = mpv.getPropertyDouble("time-pos") ?: lastPosition
            
            if (currentPosition != lastPosition) {
                lastPosition = currentPosition
                lastChangeTime = now
                if (isBuffering) isBuffering = false
            } else {
                if (now - lastChangeTime > 700) {
                    if (!isBuffering) isBuffering = true
                }
            }
        }
    }
    
    // Blink loop: fade in, hold briefly, fade out, repeat continuously while buffering
    LaunchedEffect(isBuffering) {
        if (isBuffering) {
            while (isBuffering) {
                bufferingAlpha.animateTo(1f, animationSpec = tween(300))
                delay(400)
                bufferingAlpha.animateTo(0f, animationSpec = tween(300))
            }
        } else {
            bufferingAlpha.snapTo(0f)
        }
    }
    
    fun cancelAutoHide() {
        userInteracting = true
        coroutineScope.launch {
            delay(100)
            userInteracting = false
        }
    }
    
    fun showSeekbarWithTimeout() {
        showSeekbar = true
        showVideoInfo = true
    }
    
    fun showPlaybackFeedback(text: String) {
        showPlaybackFeedback = true
        playbackFeedbackText = text
        coroutineScope.launch {
            delay(1000)
            showPlaybackFeedback = false
        }
    }
    
    fun performSmoothSeek(targetPosition: Double) {
        // NEW: blocked until seek-mode setup has finished
        if (!seekReady) return
        if (isSeekInProgress) return
        isSeekInProgress = true
        mpv.command("seek", targetPosition.toString(), "absolute", "exact")
        coroutineScope.launch {
            delay(seekThrottleMs)
            isSeekInProgress = false
        }
    }
    
    fun performStepSeek(targetPosition: Double) {
        // NEW: blocked until seek-mode setup has finished
        if (!seekReady) return
        if (isSeekInProgress) return
        isSeekInProgress = true
        val roundedPosition = (targetPosition + 0.5).toInt().toDouble()
        mpv.command("seek", roundedPosition.toString(), "absolute", "exact")
        coroutineScope.launch {
            delay(seekThrottleMs)
            isSeekInProgress = false
        }
    }

    // NEW: begin seek mode. Order: (already paused) -> options ON -> wait -> clean re-seek -> allow seeks
    fun beginSeekMode(startPos: Double) {
        seekReady = false
        if (wasPlayingBeforeSeek) {
            mpv.setPropertyBoolean("pause", true)
        }
        applySeekMode(mpv, true)
        seekSetupJob?.cancel()
        seekSetupJob = coroutineScope.launch {
            delay(seekModeSwitchDelayMs)
            // only continue if the user is still scrubbing (not released during the delay)
            if (isSeeking) {
                mpv.command("seek", startPos.toString(), "absolute", "exact")
                seekReady = true
            }
        }
    }

    // NEW: end seek mode. Cancels any pending setup, closes the gate, options OFF
    fun endSeekMode() {
        seekSetupJob?.cancel()
        seekSetupJob = null
        seekReady = false
        applySeekMode(mpv, false)
    }
    
    fun getFreshPosition(): Float {
        return (mpv.getPropertyDouble("time-pos") ?: 0.0).toFloat()
    }
    
    fun performQuickSeek(seconds: Int) {
        val currentPos = mpv.getPropertyDouble("time-pos") ?: 0.0
        val duration = mpv.getPropertyDouble("duration") ?: 0.0
        
        quickSeekFeedbackText = if (seconds > 0) "+$seconds" else "$seconds"
        showQuickSeekFeedback = true
        coroutineScope.launch {
            delay(1000)
            showQuickSeekFeedback = false
        }
        
        mpv.command("seek", seconds.toString(), "relative", "exact")
        seekGraceUntil = System.currentTimeMillis() + 500
    }
    
    fun handleTap() {
        val currentPaused = mpv.getPropertyBoolean("pause") ?: false
        if (currentPaused) {
            coroutineScope.launch {
                val currentPos = mpv.getPropertyDouble("time-pos") ?: 0.0
                mpv.command("seek", currentPos.toString(), "absolute", "exact")
                delay(100)
                mpv.setPropertyBoolean("pause", false)
                seekGraceUntil = System.currentTimeMillis() + 500
            }
            showPlaybackFeedback("Resume")
        } else {
            mpv.setPropertyBoolean("pause", true)
            showPlaybackFeedback("Pause")
        }
        if (showSeekbar) {
            showSeekbar = false
            showVideoInfo = false
        } else {
            showSeekbarWithTimeout()
        }
    }
    
    fun startLongTapDetection() {
        isTouching = true
        touchStartTime = System.currentTimeMillis()
        coroutineScope.launch {
            delay(longTapThreshold)
            if (isTouching && !isHorizontalSwipe && !isVerticalSwipe) {
                isLongTap = true
                isSpeedingUp = true
                mpv.setPropertyDouble("speed", 2.0)
            }
        }
    }
    
    fun checkForSwipeDirection(currentX: Float, currentY: Float): String {
        if (isHorizontalSwipe || isVerticalSwipe || isLongTap) return ""
        
        val deltaX = abs(currentX - touchStartX)
        val deltaY = abs(currentY - touchStartY)
        
        if (deltaX > horizontalSwipeThreshold && deltaX > deltaY && deltaY < maxVerticalMovement) {
            return "horizontal"
        }
        
        if (deltaY > verticalSwipeThreshold && deltaY > deltaX && deltaX < maxHorizontalMovement) {
            return "vertical"
        }
        
        return ""
    }
    
    fun startHorizontalSeeking(startX: Float) {
        isHorizontalSwipe = true
        cancelAutoHide()
        seekStartX = startX
        seekStartPosition = mpv.getPropertyDouble("time-pos") ?: 0.0
        wasPlayingBeforeSeek = mpv.getPropertyBoolean("pause") == false
        isSeeking = true
        showSeekTime = true
        showSeekbar = true
        showVideoInfo = true
        
        // NEW: pause first -> seek options on -> 50ms wait -> clean re-seek (all inside beginSeekMode)
        beginSeekMode(seekStartPosition)
        
        lastSeekTime = 0L
        lastHorizontalUpdateTime = 0L
    }
    
    fun startVerticalSwipe(startY: Float) {
        isVerticalSwipe = true
        cancelAutoHide()
        val deltaY = startY - touchStartY
        
        if (deltaY < 0) {
            seekDirection = "+"
            performQuickSeek(quickSeekAmount)
        } else {
            seekDirection = "-"
            performQuickSeek(-quickSeekAmount)
        }
    }
    
    fun handleHorizontalSeeking(currentX: Float) {
        if (!isSeeking) return
        
        val deltaX = currentX - seekStartX
        val pixelsPerSecond = 3f / 0.016f
        val timeDeltaSeconds = deltaX / pixelsPerSecond
        val newPositionSeconds = seekStartPosition + timeDeltaSeconds
        val duration = mpv.getPropertyDouble("duration") ?: 0.0
        val clampedPosition = newPositionSeconds.coerceIn(0.0, duration)
        
        seekDirection = if (deltaX > 0) "+" else "-"
        seekTargetTime = formatTimeSimple(clampedPosition)
        
        val now = System.currentTimeMillis()
        
        if (now - lastHorizontalUpdateTime > 16) {
            currentTime = formatTimeSimple(clampedPosition)
            seekbarPosition = clampedPosition.toFloat()
            lastHorizontalUpdateTime = now
        }
        
        if (now - lastSeekTime > 11) {
            performSmoothSeek(clampedPosition)
            lastSeekTime = now
        }
    }
    
    fun endHorizontalSeeking() {
        if (isSeeking) {
            // NEW: closes the gate, cancels pending setup, options OFF
            endSeekMode()
            // then one clean exact re-seek to settle on a fully decoded frame
            val currentPos = mpv.getPropertyDouble("time-pos") ?: seekStartPosition
            mpv.command("seek", currentPos.toString(), "absolute", "exact")
            
            if (wasPlayingBeforeSeek) {
                coroutineScope.launch {
                    delay(100)
                    mpv.setPropertyBoolean("pause", false)
                }
            }
            
            isSeeking = false
            showSeekTime = false
            seekStartX = 0f
            seekStartPosition = 0.0
            wasPlayingBeforeSeek = false
            seekDirection = ""
            seekGraceUntil = System.currentTimeMillis() + 500
        }
    }
    
    fun endVerticalSwipe() {
        isVerticalSwipe = false
    }
    
    fun endTouch() {
        val touchDuration = System.currentTimeMillis() - touchStartTime
        isTouching = false
        
        if (isLongTap) {
            isLongTap = false
            isSpeedingUp = false
            mpv.setPropertyDouble("speed", 1.0)
        } else if (isHorizontalSwipe) {
            endHorizontalSeeking()
            isHorizontalSwipe = false
        } else if (isVerticalSwipe) {
            endVerticalSwipe()
            isVerticalSwipe = false
        } else if (touchDuration < 150) {
            handleTap()
        }
        isHorizontalSwipe = false
        isVerticalSwipe = false
        isLongTap = false
    }
    
    LaunchedEffect(Unit) {
        fileName = resolveDisplayName(context, mpv)
        showVideoInfo = true
        showSeekbar = true
    }
    
    LaunchedEffect(isSpeedingUp) {
        if (isSpeedingUp) {
            mpv.setPropertyDouble("speed", 2.0)
        } else {
            mpv.setPropertyDouble("speed", 1.0)
        }
    }
    
    LaunchedEffect(Unit) {
        var duration = mpv.getPropertyDouble("duration") ?: 0.0
        while (duration <= 1.0) {
            delay(100)
            duration = mpv.getPropertyDouble("duration") ?: 0.0
        }
        
        while (isActive) {
            val currentPos = mpv.getPropertyDouble("time-pos") ?: 0.0
            val currentDuration = mpv.getPropertyDouble("duration") ?: duration
            
            if (!isSeeking && !isDragging) {
                currentTime = formatTimeSimple(currentPos)
                seekbarPosition = currentPos.toFloat()
            }
            
            totalTime = formatTimeSimple(currentDuration)
            seekbarDuration = currentDuration.toFloat()
            
            delay(100)
        }
    }
    
    fun handleProgressBarDrag(newPosition: Float) {
        cancelAutoHide()
        if (!isSeeking) {
            isSeeking = true
            wasPlayingBeforeSeek = mpv.getPropertyBoolean("pause") == false
            showSeekTime = true
            showSeekbar = true
            showVideoInfo = true
            
            // NEW: pause first -> seek options on -> 50ms wait -> clean re-seek (all inside beginSeekMode)
            val startPos = mpv.getPropertyDouble("time-pos") ?: 0.0
            beginSeekMode(startPos)
        }
        isDragging = true
        val oldPosition = seekbarPosition
        seekbarPosition = newPosition
        seekDirection = if (newPosition > oldPosition) "+" else "-"
        val targetPosition = newPosition.toDouble()
        seekTargetTime = formatTimeSimple(targetPosition)
        currentTime = formatTimeSimple(targetPosition)
        
        performStepSeek(targetPosition)
    }
    
    fun handleDragFinished() {
        isDragging = false
        // NEW: closes the gate, cancels pending setup, options OFF
        endSeekMode()
        // then one clean exact re-seek to settle on a fully decoded frame
        val settlePos = mpv.getPropertyDouble("time-pos")
        if (settlePos != null) {
            mpv.command("seek", settlePos.toString(), "absolute", "exact")
        }
        if (wasPlayingBeforeSeek) {
            coroutineScope.launch {
                delay(100)
                mpv.setPropertyBoolean("pause", false)
            }
        }
        isSeeking = false
        showSeekTime = false
        wasPlayingBeforeSeek = false
        seekDirection = ""
        seekGraceUntil = System.currentTimeMillis() + 500
    }
    
    val videoInfoTextAlpha = if (isSeeking || isDragging) 0.0f else 1.0f
    val videoInfoBackgroundAlpha = if (isSeeking || isDragging) 0.0f else 0.8f
    val timeDisplayTextAlpha = if (isSeeking || isDragging) 0.0f else 1.0f
    val timeDisplayBackgroundAlpha = if (isSeeking || isDragging) 0.0f else 0.8f
    
    Box(modifier = modifier.fillMaxSize()) {
        Box(modifier = Modifier.fillMaxSize()) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .fillMaxHeight(0.05f)
                    .align(Alignment.TopStart)
            )
            
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .fillMaxHeight(0.95f)
                    .align(Alignment.BottomStart)
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth(0.05f)
                        .fillMaxHeight()
                        .align(Alignment.CenterStart)
                )
                
                Box(
                    modifier = Modifier
                        .fillMaxWidth(0.9f)
                        .fillMaxHeight()
                        .align(Alignment.Center)
                        .pointerInteropFilter { event ->
                            // while a prompt is showing, only left/right swipes are handled
                            if (promptMode != PROMPT_NONE) {
                                when (event.action) {
                                    MotionEvent.ACTION_DOWN -> {
                                        promptStartX = event.x
                                        true
                                    }
                                    MotionEvent.ACTION_UP -> {
                                        val dx = event.x - promptStartX
                                        if (dx > promptSwipeThreshold && promptMode == PROMPT_RESUME) {
                                            onResumeChoice()
                                        } else if (dx < -promptSwipeThreshold) {
                                            onStartOver()
                                        }
                                        true
                                    }
                                    else -> true
                                }
                            } else {
                                when (event.action) {
                                    MotionEvent.ACTION_DOWN -> {
                                        touchStartX = event.x
                                        touchStartY = event.y
                                        startLongTapDetection()
                                        true
                                    }
                                    MotionEvent.ACTION_MOVE -> {
                                        if (!isHorizontalSwipe && !isVerticalSwipe && !isLongTap) {
                                            when (checkForSwipeDirection(event.x, event.y)) {
                                                "horizontal" -> startHorizontalSeeking(event.x)
                                                "vertical" -> startVerticalSwipe(event.y)
                                            }
                                        } else if (isHorizontalSwipe) {
                                            handleHorizontalSeeking(event.x)
                                        }
                                        true
                                    }
                                    MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                                        endTouch()
                                        true
                                    }
                                    else -> false
                                }
                            }
                        }
                )
                
                Box(
                    modifier = Modifier
                        .fillMaxWidth(0.05f)
                        .fillMaxHeight()
                        .align(Alignment.CenterEnd)
                )
            }
        }
        
        // seekbar hidden while a prompt is showing
        if (showSeekbar && promptMode == PROMPT_NONE) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(70.dp)
                    .align(Alignment.BottomStart)
                    .padding(horizontal = 60.dp)
                    .offset(y = 3.dp)
            ) {
                Column(
                    modifier = Modifier.fillMaxSize(),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Box(modifier = Modifier.fillMaxWidth().wrapContentHeight()) {
                        Row(
                            modifier = Modifier.align(Alignment.CenterStart),
                            horizontalArrangement = Arrangement.spacedBy(2.dp)
                        ) {
                            Text(
                                text = "$currentTime / $totalTime",
                                style = TextStyle(
                                    color = Color.White.copy(alpha = timeDisplayTextAlpha),
                                    fontSize = 14.sp,
                                    fontWeight = FontWeight.Medium
                                ),
                                modifier = Modifier
                                    .background(Color.DarkGray.copy(alpha = timeDisplayBackgroundAlpha))
                                    .padding(horizontal = 12.dp, vertical = 4.dp)
                            )
                        }
                    }
                    Box(modifier = Modifier.fillMaxWidth().height(48.dp)) {
                        if (isDurationValid) {
                            SimpleDraggableProgressBar(
                                position = seekbarPosition,
                                duration = seekbarDuration,
                                onValueChange = { handleProgressBarDrag(it) },
                                onValueChangeFinished = { handleDragFinished() },
                                getFreshPosition = { getFreshPosition() },
                                modifier = Modifier.fillMaxSize().height(48.dp)
                            )
                        } else {
                            Box(modifier = Modifier.fillMaxSize()) {
                                Box(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .height(4.dp)
                                        .align(Alignment.CenterStart)
                                        .background(Color.Gray.copy(alpha = 0.6f))
                                )
                                Box(
                                    modifier = Modifier
                                        .fillMaxWidth(fraction = 0f)
                                        .height(4.dp)
                                        .align(Alignment.CenterStart)
                                        .background(Color.White)
                                )
                            }
                        }
                    }
                }
            }
        }
        
        if (showVideoInfo) {
            Text(
                text = fileName,
                style = TextStyle(
                    color = Color.White.copy(alpha = videoInfoTextAlpha),
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Medium
                ),
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .offset(x = 60.dp, y = 20.dp)
                    .background(Color.DarkGray.copy(alpha = videoInfoBackgroundAlpha))
                    .padding(horizontal = 16.dp, vertical = 6.dp)
            )
        }
        
        // Buffering indicator - dead center, driven by actual playback-position stall detection
        if (isBuffering && promptMode == PROMPT_NONE) {
            Text(
                text = "Buffering",
                style = TextStyle(
                    color = Color.White.copy(alpha = bufferingAlpha.value),
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Medium
                ),
                modifier = Modifier
                    .align(Alignment.Center)
                    .background(Color.DarkGray.copy(alpha = 0.8f * bufferingAlpha.value))
                    .padding(horizontal = 12.dp, vertical = 4.dp)
            )
        }

        // Resume / Replay prompt - same center style as Buffering, but solid (no blink)
        if (promptMode != PROMPT_NONE) {
            val promptText = if (promptMode == PROMPT_RESUME) {
                "Resume at ${formatTimeSimple(resumePosition)}?\nSwipe right to resume\nSwipe left to start over"
            } else {
                "Replay?\nSwipe left to start over"
            }
            Text(
                text = promptText,
                style = TextStyle(
                    color = Color.White,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Medium,
                    textAlign = TextAlign.Center
                ),
                modifier = Modifier
                    .align(Alignment.Center)
                    .background(Color.DarkGray.copy(alpha = 0.8f))
                    .padding(horizontal = 12.dp, vertical = 4.dp)
            )
        }
        
        Box(modifier = Modifier.align(Alignment.TopCenter).offset(y = 80.dp)) {
            when {
                isSpeedingUp -> Text(
                    text = "2X",
                    style = TextStyle(color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.Medium),
                    modifier = Modifier.background(Color.DarkGray.copy(alpha = 0.8f)).padding(horizontal = 12.dp, vertical = 4.dp)
                )
                showQuickSeekFeedback -> Text(
                    text = quickSeekFeedbackText,
                    style = TextStyle(color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.Medium),
                    modifier = Modifier.background(Color.DarkGray.copy(alpha = 0.8f)).padding(horizontal = 12.dp, vertical = 4.dp)
                )
                showSeekTime -> Text(
                    text = if (seekDirection.isNotEmpty()) "$seekTargetTime $seekDirection" else seekTargetTime,
                    style = TextStyle(color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.Medium),
                    modifier = Modifier.background(Color.DarkGray.copy(alpha = 0.8f)).padding(horizontal = 12.dp, vertical = 4.dp)
                )
                showPlaybackFeedback -> Text(
                    text = playbackFeedbackText,
                    style = TextStyle(color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.Medium),
                    modifier = Modifier.background(Color.DarkGray.copy(alpha = 0.8f)).padding(horizontal = 12.dp, vertical = 4.dp)
                )
            }
        }
    }
}

@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
fun SimpleDraggableProgressBar(
    position: Float,
    duration: Float,
    onValueChange: (Float) -> Unit,
    onValueChangeFinished: () -> Unit,
    getFreshPosition: () -> Float,
    modifier: Modifier = Modifier
) {
    var dragStartX by remember { mutableStateOf(0f) }
    var savedPositionAtTouch by remember { mutableStateOf(0f) }
    
    val safeDuration = if (duration > 0) duration else 1f
    val safePosition = position.coerceIn(0f, safeDuration)
    val progressFraction = (safePosition / safeDuration).coerceIn(0f, 1f)
    
    Box(modifier = modifier.height(48.dp)) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(4.dp)
                .align(Alignment.CenterStart)
                .background(Color.Gray.copy(alpha = 0.6f))
        )
        
        Box(
            modifier = Modifier
                .fillMaxWidth(fraction = progressFraction)
                .height(4.dp)
                .align(Alignment.CenterStart)
                .background(Color.White)
        )
        
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(48.dp)
                .align(Alignment.CenterStart)
                .pointerInput(Unit) {
                    detectDragGestures(
                        onDragStart = { offset ->
                            dragStartX = offset.x
                            savedPositionAtTouch = getFreshPosition().coerceIn(0f, safeDuration)
                        },
                        onDrag = { change, _ ->
                            change.consume()
                            val currentX = change.position.x
                            val totalMovementX = currentX - dragStartX
                            
                            val deltaPosition = (totalMovementX / size.width) * safeDuration
                            val newPosition = (savedPositionAtTouch + deltaPosition)
                                .coerceIn(0f, safeDuration)
                            
                            val roundedPosition = (newPosition + 0.5f).toInt().toFloat()
                            
                            if (abs(roundedPosition - safePosition) > 0.1f) {
                                onValueChange(roundedPosition)
                            }
                        },
                        onDragEnd = {
                            onValueChangeFinished()
                        },
                        onDragCancel = {
                            onValueChangeFinished()
                        }
                    )
                }
        )
    }
}

private fun formatTimeSimple(seconds: Double): String {
    val totalSeconds = seconds.toInt()
    val hours = totalSeconds / 3600
    val minutes = (totalSeconds % 3600) / 60
    val secs = totalSeconds % 60
    return if (hours > 0) {
        String.format("%02d:%02d:%02d", hours, minutes, secs)
    } else {
        String.format("%02d:%02d", minutes, secs)
    }
}

// shared by the resume key and the title so both use the same name
private fun resolveDisplayName(context: Context, mpv: MPV): String {
    val intent = (context as? android.app.Activity)?.intent
    return when {
        intent?.action == Intent.ACTION_SEND -> {
            getFileNameFromUri(intent.getParcelableExtra(Intent.EXTRA_STREAM), context, mpv)
        }
        intent?.action == Intent.ACTION_VIEW -> {
            getFileNameFromUri(intent.data, context, mpv)
        }
        else -> {
            getBestAvailableFileName(context, mpv)
        }
    }
}

private fun getFileNameFromUri(uri: Uri?, context: Context, mpv: MPV): String {
    if (uri == null) return getBestAvailableFileName(context, mpv)
    return when {
        uri.scheme == "file" -> uri.lastPathSegment?.substringBeforeLast(".") ?: getBestAvailableFileName(context, mpv)
        uri.scheme == "content" -> getDisplayNameFromContentUri(uri, context) ?: getBestAvailableFileName(context, mpv)
        else -> getBestAvailableFileName(context, mpv)
    }
}

private fun getDisplayNameFromContentUri(uri: Uri, context: Context): String? {
    return try {
        context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) {
                val displayNameIndex = cursor.getColumnIndex("_display_name")
                val displayName = if (displayNameIndex != -1) {
                    cursor.getString(displayNameIndex)?.substringBeforeLast(".")
                } else null
                displayName ?: uri.lastPathSegment?.substringBeforeLast(".")
            } else null
        }
    } catch (e: Exception) {
        null
    }
}

private fun getBestAvailableFileName(context: Context, mpv: MPV): String {
    val mediaTitle = mpv.getPropertyString("media-title")
    if (mediaTitle != null && mediaTitle != "Video" && mediaTitle.isNotBlank()) {
        return mediaTitle.substringBeforeLast(".")
    }
    val mpvPath = mpv.getPropertyString("path")
    if (mpvPath != null && mpvPath.isNotBlank()) {
        return mpvPath.substringAfterLast("/").substringBeforeLast(".").ifEmpty { "RTFP" }
    }
    return "RTFP"
}
