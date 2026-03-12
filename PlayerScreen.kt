package com.rtfp.player

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.AttributeSet
import android.util.Log
import android.view.MotionEvent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.pointerInteropFilter
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import `is`.xyz.mpv.BaseMPVView
import `is`.xyz.mpv.MPV
import kotlinx.coroutines.*
import java.io.File
import kotlin.math.abs
import kotlin.math.sign

class SimpleMPVView(context: Context, attrs: AttributeSet? = null) : BaseMPVView(context, attrs) {
    
    override fun initOptions() {
        mpv.setOptionString("hwdec", "no")
        mpv.setOptionString("vo", "gpu")
        mpv.setOptionString("profile", "fast")
        mpv.setOptionString("keepaspect", "yes")
    }

    override fun postInitOptions() {
        // Performance
        mpv.setOptionString("vd-lavc-threads", "8")
        mpv.setOptionString("demuxer-lavf-threads", "8")
        mpv.setOptionString("cache-initial", "0.5")
        mpv.setOptionString("untimed", "yes")
        
        // Seeking
        mpv.setOptionString("hr-seek", "yes")
        mpv.setOptionString("hr-seek-framedrop", "no")
        
        // Fast decoding
        mpv.setOptionString("vd-lavc-fast", "yes")
        mpv.setOptionString("vd-lavc-skiploopfilter", "all")
        mpv.setOptionString("vd-lavc-skipidct", "all")
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
    
    // Lifecycle observer for auto-pause with position saving
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_PAUSE -> {
                    mpvView?.let { view ->
                        savedPosition = view.mpv.getPropertyDouble("time-pos")
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
        // MPV Player
        AndroidView(
            factory = { ctx ->
                SimpleMPVView(ctx).apply {
                    mpvView = this
                    mpvInstance = this.mpv
                    
                    // Initialize with proper paths
                    val filesDir = ctx.filesDir.path
                    val cacheDir = File(ctx.cacheDir, "mpv").apply { mkdirs() }.path
                    initialize(filesDir, cacheDir)
                    
                    videoPath?.let { path ->
                        playFile(path)
                        
                        // Wait for video to load and get dimensions
                        coroutineScope.launch {
                            var attempts = 0
                            var duration = 0.0
                            
                            // Keep checking until we get valid duration
                            while (duration <= 0 && attempts < 50) { // 5 seconds max
                                delay(100)
                                duration = mpv.getPropertyDouble("duration") ?: 0.0
                                attempts++
                                Log.d("PlayerDebug", "Checking duration: $duration (attempt $attempts)")
                            }
                            
                            val width = mpv.getPropertyInt("width") ?: 0
                            val height = mpv.getPropertyInt("height") ?: 0
                            
                            Log.d("PlayerDebug", "Video loaded - Width: $width, Height: $height, Duration: $duration")
                            
                            if (width > 0 && height > 0 && duration > 0) {
                                onVideoLoaded(width, height)
                                isVideoLoaded = true
                            }
                        }
                    }
                }
            },
            modifier = Modifier.fillMaxSize()
        )
        
        // UI Overlay - Only show when video is loaded AND we have a valid MPV instance
        if (isVideoLoaded && mpvInstance != null) {
            PlayerOverlay(
                mpv = mpvInstance!!,
                modifier = Modifier.fillMaxSize()
            )
        }
    }
}

// Settings data class
data class PlayerSettings(
    var seekThrottleMs: Int = 50,
    var horizontalPixelsPerMs: Float = 3f / 50f, // 3 pixels per 50ms = 0.06 pixels/ms
    var verticalPixelsPerMs: Float = 3f / 50f
)

@OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class, androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
fun PlayerOverlay(
    mpv: MPV,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    
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
    var seekStartY by remember { mutableStateOf(0f) }
    var seekStartPosition by remember { mutableStateOf(0.0) }
    var wasPlayingBeforeSeek by remember { mutableStateOf(false) }
    var seekDirection by remember { mutableStateOf("") }
    var isSeekInProgress by remember { mutableStateOf(false) }
    
    // Settings state
    var showSettings by remember { mutableStateOf(false) }
    var settings by remember { mutableStateOf(PlayerSettings()) }
    
    // Settings input dialog state
    var showSeekThrottleDialog by remember { mutableStateOf(false) }
    var showHorizontalPixelDialog by remember { mutableStateOf(false) }
    var showVerticalPixelDialog by remember { mutableStateOf(false) }
    var tempInputValue by remember { mutableStateOf("") }
    
    // NEW: Throttling for horizontal/vertical swipe
    var lastSeekTime by remember { mutableStateOf(0L) }
    var lastHorizontalUpdateTime by remember { mutableStateOf(0L) }
    var lastVerticalUpdateTime by remember { mutableStateOf(0L) }
    
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
    
    var showVideoInfo by remember { mutableStateOf(true) }
    var fileName by remember { mutableStateOf("RTFP") }
    
    var userInteracting by remember { mutableStateOf(false) }
    
    var showPlaybackFeedback by remember { mutableStateOf(false) }
    var playbackFeedbackText by remember { mutableStateOf("") }
    
    var showQuickSeekFeedback by remember { mutableStateOf(false) }
    var quickSeekFeedbackText by remember { mutableStateOf("") }
    
    // Track if duration is valid for seekbar
    var isDurationValid by remember { mutableStateOf(false) }
    
    // FIXED: Auto-hide seekbar with proper lifecycle - no more job leaks
    LaunchedEffect(showSeekbar, userInteracting, showSettings) {
        if (showSeekbar && !userInteracting && !showSettings) {
            delay(4000)
            if (!userInteracting && !showSettings) {
                showSeekbar = false
                showVideoInfo = false
            }
        }
    }
    
    // Wait for valid duration before starting
    LaunchedEffect(Unit) {
        // Wait for duration to be available
        var duration = 0.0
        var attempts = 0
        while (duration <= 1.0 && attempts < 30) { // Wait until duration > 1 second
            duration = mpv.getPropertyDouble("duration") ?: 0.0
            if (duration > 1.0) {
                Log.d("PlayerDebug", "Duration finally available: $duration")
                seekbarDuration = duration.toFloat()
                totalTime = formatTimeSimple(duration)
                isDurationValid = true
                break
            }
            delay(100)
            attempts++
        }
    }
    
    // Utility functions
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
        // No need to schedule manually - LaunchedEffect handles it
    }
    
    fun showPlaybackFeedback(text: String) {
        showPlaybackFeedback = true
        playbackFeedbackText = text
        coroutineScope.launch {
            delay(1000)
            showPlaybackFeedback = false
        }
    }
    
    // For HORIZONTAL SWIPE - smooth seeking with adjustable sensitivity
    fun performHorizontalSmoothSeek(targetPosition: Double) {
        if (isSeekInProgress) return
        isSeekInProgress = true
        // NO ROUNDING - smooth for horizontal swipe
        mpv.command("seek", targetPosition.toString(), "absolute", "exact")
        coroutineScope.launch {
            delay(settings.seekThrottleMs.toLong())
            isSeekInProgress = false
        }
    }
    
    // For VERTICAL SWIPE - smooth seeking with adjustable sensitivity
    fun performVerticalSmoothSeek(targetPosition: Double) {
        if (isSeekInProgress) return
        isSeekInProgress = true
        // NO ROUNDING - smooth for vertical swipe
        mpv.command("seek", targetPosition.toString(), "absolute", "exact")
        coroutineScope.launch {
            delay(settings.seekThrottleMs.toLong())
            isSeekInProgress = false
        }
    }
    
    // For PROGRESS BAR - 1-second increments
    fun performStepSeek(targetPosition: Double) {
        if (isSeekInProgress) return
        isSeekInProgress = true
        // Round to nearest second for progress bar
        val roundedPosition = (targetPosition + 0.5).toInt().toDouble()
        mpv.command("seek", roundedPosition.toString(), "absolute", "exact")
        coroutineScope.launch {
            delay(settings.seekThrottleMs.toLong())
            isSeekInProgress = false
        }
    }
    
    fun getFreshPosition(): Float {
        return (mpv.getPropertyDouble("time-pos") ?: 0.0).toFloat()
    }
    
    fun handleTap() {
        val currentPaused = mpv.getPropertyBoolean("pause") ?: false
        if (currentPaused) {
            coroutineScope.launch {
                val currentPos = mpv.getPropertyDouble("time-pos") ?: 0.0
                mpv.command("seek", currentPos.toString(), "absolute", "exact")
                delay(100)
                mpv.setPropertyBoolean("pause", false)
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
        
        if (wasPlayingBeforeSeek) {
            mpv.setPropertyBoolean("pause", true)
        }
        
        // Reset throttling timers
        lastSeekTime = 0L
        lastHorizontalUpdateTime = 0L
    }
    
    fun startVerticalSeeking(startY: Float) {
        isVerticalSwipe = true
        cancelAutoHide()
        seekStartY = startY
        seekStartPosition = mpv.getPropertyDouble("time-pos") ?: 0.0
        wasPlayingBeforeSeek = mpv.getPropertyBoolean("pause") == false
        isSeeking = true
        showSeekTime = true
        showSeekbar = true
        showVideoInfo = true
        
        if (wasPlayingBeforeSeek) {
            mpv.setPropertyBoolean("pause", true)
        }
        
        // Reset throttling timers
        lastSeekTime = 0L
        lastVerticalUpdateTime = 0L
    }
    
    fun handleHorizontalSeeking(currentX: Float) {
        if (!isSeeking) return
        
        val deltaX = currentX - seekStartX
        // Use adjustable pixel to time ratio
        val timeDeltaSeconds = deltaX / settings.horizontalPixelsPerMs / 1000f
        val newPositionSeconds = seekStartPosition + timeDeltaSeconds
        val duration = mpv.getPropertyDouble("duration") ?: 0.0
        val clampedPosition = newPositionSeconds.coerceIn(0.0, duration)
        
        seekDirection = if (deltaX > 0) "+" else "-"
        seekTargetTime = formatTimeSimple(clampedPosition)
        
        val now = System.currentTimeMillis()
        
        // Update UI every 16ms (60fps) for smooth visual feedback
        if (now - lastHorizontalUpdateTime > 16) {
            currentTime = formatTimeSimple(clampedPosition)
            // UPDATE SEEKBAR POSITION IN REAL-TIME
            seekbarPosition = clampedPosition.toFloat()
            lastHorizontalUpdateTime = now
        }
        
        // Throttle MPV seeks to adjustable setting
        if (now - lastSeekTime > settings.seekThrottleMs) {
            performHorizontalSmoothSeek(clampedPosition)
            lastSeekTime = now
        }
    }
    
    fun handleVerticalSeeking(currentY: Float) {
        if (!isSeeking) return
        
        val deltaY = currentY - seekStartY
        // Use adjustable pixel to time ratio (inverted: moving up increases time)
        val timeDeltaSeconds = -deltaY / settings.verticalPixelsPerMs / 1000f
        val newPositionSeconds = seekStartPosition + timeDeltaSeconds
        val duration = mpv.getPropertyDouble("duration") ?: 0.0
        val clampedPosition = newPositionSeconds.coerceIn(0.0, duration)
        
        seekDirection = if (deltaY < 0) "+" else "-"
        seekTargetTime = formatTimeSimple(clampedPosition)
        
        val now = System.currentTimeMillis()
        
        // Update UI every 16ms (60fps) for smooth visual feedback
        if (now - lastVerticalUpdateTime > 16) {
            currentTime = formatTimeSimple(clampedPosition)
            // UPDATE SEEKBAR POSITION IN REAL-TIME
            seekbarPosition = clampedPosition.toFloat()
            lastVerticalUpdateTime = now
        }
        
        // Throttle MPV seeks to adjustable setting
        if (now - lastSeekTime > settings.seekThrottleMs) {
            performVerticalSmoothSeek(clampedPosition)
            lastSeekTime = now
        }
    }
    
    fun endHorizontalSeeking() {
        if (isSeeking) {
            val currentPos = mpv.getPropertyDouble("time-pos") ?: seekStartPosition
            performHorizontalSmoothSeek(currentPos)
            
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
        }
    }
    
    fun endVerticalSeeking() {
        if (isSeeking) {
            val currentPos = mpv.getPropertyDouble("time-pos") ?: seekStartPosition
            performVerticalSmoothSeek(currentPos)
            
            if (wasPlayingBeforeSeek) {
                coroutineScope.launch {
                    delay(100)
                    mpv.setPropertyBoolean("pause", false)
                }
            }
            
            isSeeking = false
            showSeekTime = false
            seekStartY = 0f
            seekStartPosition = 0.0
            wasPlayingBeforeSeek = false
            seekDirection = ""
        }
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
            endVerticalSeeking()
            isVerticalSwipe = false
        } else if (touchDuration < 150) {
            handleTap()
        }
        isHorizontalSwipe = false
        isVerticalSwipe = false
        isLongTap = false
    }
    
    // Get filename
    LaunchedEffect(Unit) {
        val intent = (context as? android.app.Activity)?.intent
        fileName = when {
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
        
        showVideoInfo = true
        showSeekbar = true
    }
    
    // Speed control backup
    LaunchedEffect(isSpeedingUp) {
        if (isSpeedingUp) {
            mpv.setPropertyDouble("speed", 2.0)
        } else {
            mpv.setPropertyDouble("speed", 1.0)
        }
    }
    
    // Update time
    LaunchedEffect(Unit) {
        // Wait for duration to be valid
        var duration = mpv.getPropertyDouble("duration") ?: 0.0
        while (duration <= 1.0) {
            delay(100)
            duration = mpv.getPropertyDouble("duration") ?: 0.0
        }
        
        // Now start updating
        while (isActive) {
            val currentPos = mpv.getPropertyDouble("time-pos") ?: 0.0
            val currentDuration = mpv.getPropertyDouble("duration") ?: duration
            
            if (!isSeeking && !isDragging) {
                // Update both together
                currentTime = formatTimeSimple(currentPos)
                seekbarPosition = currentPos.toFloat()
            }
            
            totalTime = formatTimeSimple(currentDuration)
            seekbarDuration = currentDuration.toFloat()
            
            delay(100)
        }
    }
    
    // Progress bar handlers
    fun handleProgressBarDrag(newPosition: Float) {
        cancelAutoHide()
        if (!isSeeking) {
            isSeeking = true
            wasPlayingBeforeSeek = mpv.getPropertyBoolean("pause") == false
            showSeekTime = true
            showSeekbar = true
            showVideoInfo = true
            
            if (wasPlayingBeforeSeek) {
                mpv.setPropertyBoolean("pause", true)
            }
        }
        isDragging = true
        val oldPosition = seekbarPosition
        seekbarPosition = newPosition
        seekDirection = if (newPosition > oldPosition) "+" else "-"
        val targetPosition = newPosition.toDouble()
        seekTargetTime = formatTimeSimple(targetPosition)
        currentTime = formatTimeSimple(targetPosition)
        
        // Use step seek (1-second increments) for progress bar
        performStepSeek(targetPosition)
    }
    
    fun handleDragFinished() {
        isDragging = false
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
    }
    
    val videoInfoTextAlpha = if (isSeeking || isDragging) 0.0f else 1.0f
    val videoInfoBackgroundAlpha = if (isSeeking || isDragging) 0.0f else 0.8f
    val timeDisplayTextAlpha = if (isSeeking || isDragging) 0.0f else 1.0f
    val timeDisplayBackgroundAlpha = if (isSeeking || isDragging) 0.0f else 0.8f
    
    Box(modifier = modifier.fillMaxSize()) {
        // Settings button - placed OUTSIDE the gesture area
        Box(
            modifier = Modifier
                .align(Alignment.TopEnd)
                .padding(top = 20.dp, end = 60.dp)
                .background(Color.DarkGray.copy(alpha = 0.8f))
                .clickable { 
                    showSettings = !showSettings
                    if (showSettings) {
                        showSeekbar = true
                        showVideoInfo = true
                        cancelAutoHide()
                    }
                }
                .padding(horizontal = 16.dp, vertical = 6.dp)
                .pointerInteropFilter { 
                    // Consume all touch events to prevent them from reaching the gesture area
                    when (it.action) {
                        MotionEvent.ACTION_DOWN -> true
                        MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> true
                        else -> false
                    }
                }
        ) {
            Text(
                text = "Settings",
                style = TextStyle(
                    color = Color.White,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Medium
                )
            )
        }
        
        // Settings panel - also OUTSIDE the gesture area
        if (showSettings) {
            Box(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(top = 60.dp, end = 60.dp)
                    .width(250.dp)
                    .background(Color.DarkGray.copy(alpha = 0.95f))
                    .pointerInteropFilter { 
                        // Consume all touch events to prevent them from reaching the gesture area
                        true
                    }
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    // Seek Throttle setting
                    SettingsItem(
                        title = "Seek Throttle",
                        value = "${settings.seekThrottleMs} ms",
                        onClick = {
                            tempInputValue = settings.seekThrottleMs.toString()
                            showSeekThrottleDialog = true
                        }
                    )
                    
                    // Horizontal drag sensitivity
                    SettingsItem(
                        title = "Horizontal Sensitivity",
                        value = "${String.format("%.3f", settings.horizontalPixelsPerMs)} px/ms",
                        onClick = {
                            tempInputValue = settings.horizontalPixelsPerMs.toString()
                            showHorizontalPixelDialog = true
                        }
                    )
                    
                    // Vertical drag sensitivity
                    SettingsItem(
                        title = "Vertical Sensitivity",
                        value = "${String.format("%.3f", settings.verticalPixelsPerMs)} px/ms",
                        onClick = {
                            tempInputValue = settings.verticalPixelsPerMs.toString()
                            showVerticalPixelDialog = true
                        }
                    )
                }
            }
        }
        
        // Gesture area (main player controls) - placed AFTER settings button so it doesn't block it
        Box(
            modifier = Modifier
                .fillMaxSize()
                .pointerInteropFilter { event ->
                    // Only process gestures if not touching settings area
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
                                    "vertical" -> startVerticalSeeking(event.y)
                                }
                            } else if (isHorizontalSwipe) {
                                handleHorizontalSeeking(event.x)
                            } else if (isVerticalSwipe) {
                                handleVerticalSeeking(event.y)
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
        )
        
        // Seekbar (UI element)
        if (showSeekbar) {
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
                            // Show static progress bar while loading
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
        
        // Video title
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
        
        // Feedback
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
        
        // Settings input dialogs
        if (showSeekThrottleDialog) {
            SettingsInputDialog(
                title = "Seek Throttle (ms)",
                initialValue = tempInputValue,
                onDismiss = { showSeekThrottleDialog = false },
                onSave = { value ->
                    value.toIntOrNull()?.let {
                        settings = settings.copy(seekThrottleMs = it)
                    }
                    showSeekThrottleDialog = false
                }
            )
        }
        
        if (showHorizontalPixelDialog) {
            SettingsInputDialog(
                title = "Horizontal Sensitivity (px/ms)",
                initialValue = tempInputValue,
                onDismiss = { showHorizontalPixelDialog = false },
                onSave = { value ->
                    value.toFloatOrNull()?.let {
                        settings = settings.copy(horizontalPixelsPerMs = it)
                    }
                    showHorizontalPixelDialog = false
                }
            )
        }
        
        if (showVerticalPixelDialog) {
            SettingsInputDialog(
                title = "Vertical Sensitivity (px/ms)",
                initialValue = tempInputValue,
                onDismiss = { showVerticalPixelDialog = false },
                onSave = { value ->
                    value.toFloatOrNull()?.let {
                        settings = settings.copy(verticalPixelsPerMs = it)
                    }
                    showVerticalPixelDialog = false
                }
            )
        }
    }
}

@Composable
fun SettingsItem(
    title: String,
    value: String,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onClick() }
            .background(Color.Black.copy(alpha = 0.3f))
            .padding(horizontal = 12.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(
            text = title,
            style = TextStyle(
                color = Color.White,
                fontSize = 14.sp,
                fontWeight = FontWeight.Medium
            )
        )
        Text(
            text = value,
            style = TextStyle(
                color = Color.White.copy(alpha = 0.8f),
                fontSize = 14.sp
            )
        )
    }
}

@Composable
fun SettingsInputDialog(
    title: String,
    initialValue: String,
    onDismiss: () -> Unit,
    onSave: (String) -> Unit
) {
    var inputText by remember { mutableStateOf(initialValue) }
    
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.5f))
            .clickable { onDismiss() }
    ) {
        Box(
            modifier = Modifier
                .align(Alignment.Center)
                .width(300.dp)
                .background(Color.DarkGray)
                .padding(16.dp)
        ) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                Text(
                    text = title,
                    style = TextStyle(
                        color = Color.White,
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Bold
                    )
                )
                
                androidx.compose.foundation.layout.Spacer(modifier = Modifier.height(8.dp))
                
                androidx.compose.foundation.text.BasicTextField(
                    value = inputText,
                    onValueChange = { inputText = it },
                    textStyle = TextStyle(
                        color = Color.White,
                        fontSize = 16.sp
                    ),
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(Color.Black.copy(alpha = 0.3f))
                        .padding(12.dp)
                )
                
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .background(Color.Gray)
                            .clickable { onDismiss() }
                            .padding(vertical = 8.dp)
                    ) {
                        Text(
                            text = "Cancel",
                            color = Color.White,
                            modifier = Modifier.align(Alignment.Center)
                        )
                    }
                    
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .background(Color.White)
                            .clickable { onSave(inputText) }
                            .padding(vertical = 8.dp)
                    ) {
                        Text(
                            text = "Save",
                            color = Color.Black,
                            modifier = Modifier.align(Alignment.Center)
                        )
                    }
                }
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
    var hasPassedThreshold by remember { mutableStateOf(false) }
    
    val movementThresholdPx = with(LocalDensity.current) { 25.dp.toPx() }
    val safeDuration = if (duration > 0) duration else 1f
    val safePosition = position.coerceIn(0f, safeDuration)
    val progressFraction = (safePosition / safeDuration).coerceIn(0f, 1f)
    
    Box(modifier = modifier.height(48.dp)) {
        // Background bar
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(4.dp)
                .align(Alignment.CenterStart)
                .background(Color.Gray.copy(alpha = 0.6f))
        )
        
        // Progress bar
        Box(
            modifier = Modifier
                .fillMaxWidth(fraction = progressFraction)
                .height(4.dp)
                .align(Alignment.CenterStart)
                .background(Color.White)
        )
        
        // Drag handle area
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(48.dp)
                .align(Alignment.CenterStart)
                .pointerInput(Unit) {
                    detectDragGestures(
                        onDragStart = { offset ->
                            // Save starting position and X coordinate
                            dragStartX = offset.x
                            savedPositionAtTouch = getFreshPosition().coerceIn(0f, safeDuration)
                            hasPassedThreshold = false
                        },
                        onDrag = { change, _ ->
                            change.consume()
                            val currentX = change.position.x
                            val totalMovementX = currentX - dragStartX
                            
                            if (!hasPassedThreshold) {
                                // Check if we've passed the threshold in either direction
                                if (abs(totalMovementX) > movementThresholdPx) {
                                    hasPassedThreshold = true
                                } else {
                                    return@detectDragGestures
                                }
                            }
                            
                            // Calculate movement beyond threshold
                            val thresholdDirection = totalMovementX.sign
                            val movementBeyondThreshold = abs(totalMovementX) - movementThresholdPx
                            val effectiveMovement = if (movementBeyondThreshold > 0) {
                                thresholdDirection * movementBeyondThreshold
                            } else {
                                0f
                            }
                            
                            // Calculate new position using only movement beyond threshold
                            val deltaPosition = (effectiveMovement / size.width) * safeDuration
                            val newPosition = (savedPositionAtTouch + deltaPosition)
                                .coerceIn(0f, safeDuration)
                            
                            // Round to nearest second for 1-second increments (PROGRESS BAR ONLY)
                            val roundedPosition = (newPosition + 0.5f).toInt().toFloat()
                            
                            // Update if changed
                            if (abs(roundedPosition - safePosition) > 0.1f) {
                                onValueChange(roundedPosition)
                            }
                        },
                        onDragEnd = {
                            hasPassedThreshold = false
                            onValueChangeFinished()
                        },
                        onDragCancel = {
                            hasPassedThreshold = false
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
