package com.rtfp.player

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.AttributeSet
import android.util.Log
import android.view.MotionEvent
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

// ==================== MPV VIEW ====================

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

// ==================== DATA CLASSES ====================

private data class DragState(
    val startX: Float = 0f,
    val savedPosition: Float = 0f,
    val hasPassedThreshold: Boolean = false
)

private data class SeekState(
    val lastSeekTime: Long = 0L,
    val lastHorizontalUpdateTime: Long = 0L,
    val isSeekInProgress: Boolean = false
)

private data class TouchState(
    val startTime: Long = 0L,
    val startX: Float = 0f,
    val startY: Float = 0f,
    val isTouching: Boolean = false,
    val isLongTap: Boolean = false,
    val isHorizontalSwipe: Boolean = false,
    val isVerticalSwipe: Boolean = false
)

// ==================== MAIN PLAYER SCREEN ====================

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
    
    // Lifecycle observer
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
        AndroidView(
            factory = { ctx ->
                SimpleMPVView(ctx).apply {
                    mpvView = this
                    mpvInstance = this.mpv
                    
                    val filesDir = ctx.filesDir.path
                    val cacheDir = File(ctx.cacheDir, "mpv").apply { mkdirs() }.path
                    initialize(filesDir, cacheDir)
                    
                    videoPath?.let { path ->
                        playFile(path)
                        
                        coroutineScope.launch {
                            var attempts = 0
                            var duration = 0.0
                            
                            while (duration <= 0 && attempts < 50) {
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
        
        if (isVideoLoaded && mpvInstance != null) {
            PlayerOverlay(
                mpv = mpvInstance!!,
                modifier = Modifier.fillMaxSize()
            )
        }
    }
}

// ==================== PLAYER OVERLAY ====================

@OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class, androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
fun PlayerOverlay(
    mpv: MPV,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    
    // UI State
    var currentTime by remember { mutableStateOf("00:00") }
    var totalTime by remember { mutableStateOf("00:00") }
    var seekTargetTime by remember { mutableStateOf("00:00") }
    var showSeekTime by remember { mutableStateOf(false) }
    var isSpeedingUp by remember { mutableStateOf(false) }
    var showSeekbar by remember { mutableStateOf(true) }
    
    // Seekbar State
    var seekbarPosition by remember { mutableStateOf(0f) }
    var seekbarDuration by remember { mutableStateOf(1f) }
    var isDragging by remember { mutableStateOf(false) }
    
    // Seek State
    var isSeeking by remember { mutableStateOf(false) }
    var seekStartX by remember { mutableStateOf(0f) }
    var seekStartPosition by remember { mutableStateOf(0.0) }
    var wasPlayingBeforeSeek by remember { mutableStateOf(false) }
    var seekDirection by remember { mutableStateOf("") }
    var seekState by remember { mutableStateOf(SeekState()) }
    
    // Touch State
    var touchState by remember { mutableStateOf(TouchState()) }
    
    // UI State
    var showVideoInfo by remember { mutableStateOf(true) }
    var fileName by remember { mutableStateOf("RTFP") }
    var userInteracting by remember { mutableStateOf(false) }
    var showPlaybackFeedback by remember { mutableStateOf(false) }
    var playbackFeedbackText by remember { mutableStateOf("") }
    var showQuickSeekFeedback by remember { mutableStateOf(false) }
    var quickSeekFeedbackText by remember { mutableStateOf("") }
    
    // Duration validity
    var isDurationValid by remember { mutableStateOf(false) }
    
    // Constants
    val seekThrottleMs = 16L
    val longTapThreshold = 300L
    val horizontalSwipeThreshold = 30f
    val verticalSwipeThreshold = 40f
    val maxVerticalMovement = 50f
    val maxHorizontalMovement = 50f
    val quickSeekAmount = 5
    
    // Auto-hide seekbar
    LaunchedEffect(showSeekbar, userInteracting) {
        if (showSeekbar && !userInteracting) {
            delay(4000)
            if (!userInteracting) {
                showSeekbar = false
                showVideoInfo = false
            }
        }
    }
    
    // Wait for duration
    LaunchedEffect(Unit) {
        var duration = 0.0
        var attempts = 0
        while (duration <= 1.0 && attempts < 30) {
            duration = mpv.getPropertyDouble("duration") ?: 0.0
            if (duration > 1.0) {
                Log.d("PlayerDebug", "Duration available: $duration")
                seekbarDuration = duration.toFloat()
                totalTime = formatTimeSimple(duration)
                isDurationValid = true
                break
            }
            delay(100)
            attempts++
        }
    }
    
    // Get filename
    LaunchedEffect(Unit) {
        fileName = getFileName(context, mpv)
        showVideoInfo = true
        showSeekbar = true
    }
    
    // Update time
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
    
    // Speed control
    LaunchedEffect(isSpeedingUp) {
        mpv.setPropertyDouble("speed", if (isSpeedingUp) 2.0 else 1.0)
    }
    
    // ==================== HELPER FUNCTIONS ====================
    
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
        if (seekState.isSeekInProgress) return
        
        val now = System.currentTimeMillis()
        if (now - seekState.lastSeekTime < 33) return
        
        seekState = seekState.copy(isSeekInProgress = true, lastSeekTime = now)
        mpv.command("seek", targetPosition.toString(), "absolute", "exact")
        
        coroutineScope.launch {
            delay(seekThrottleMs)
            seekState = seekState.copy(isSeekInProgress = false)
        }
    }
    
    fun performStepSeek(targetPosition: Double) {
        if (seekState.isSeekInProgress) return
        
        seekState = seekState.copy(isSeekInProgress = true)
        val roundedPosition = (targetPosition + 0.5).toInt().toDouble()
        mpv.command("seek", roundedPosition.toString(), "absolute", "exact")
        
        coroutineScope.launch {
            delay(seekThrottleMs)
            seekState = seekState.copy(isSeekInProgress = false)
        }
    }
    
    fun getFreshPosition(): Float = (mpv.getPropertyDouble("time-pos") ?: 0.0).toFloat()
    
    fun performQuickSeek(seconds: Int) {
        quickSeekFeedbackText = if (seconds > 0) "+$seconds" else "$seconds"
        showQuickSeekFeedback = true
        coroutineScope.launch {
            delay(1000)
            showQuickSeekFeedback = false
        }
        mpv.command("seek", seconds.toString(), "relative", "exact")
    }
    
    fun handleTap() {
        val currentPaused = mpv.getPropertyBoolean("pause") == true
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
        touchState = touchState.copy(
            isTouching = true,
            startTime = System.currentTimeMillis()
        )
        
        coroutineScope.launch {
            delay(longTapThreshold)
            if (touchState.isTouching && !touchState.isHorizontalSwipe && !touchState.isVerticalSwipe) {
                touchState = touchState.copy(isLongTap = true)
                isSpeedingUp = true
            }
        }
    }
    
    fun checkForSwipeDirection(currentX: Float, currentY: Float): String {
        if (touchState.isHorizontalSwipe || touchState.isVerticalSwipe || touchState.isLongTap) return ""
        
        val deltaX = abs(currentX - touchState.startX)
        val deltaY = abs(currentY - touchState.startY)
        
        return when {
            deltaX > horizontalSwipeThreshold && deltaX > deltaY && deltaY < maxVerticalMovement -> "horizontal"
            deltaY > verticalSwipeThreshold && deltaY > deltaX && deltaX < maxHorizontalMovement -> "vertical"
            else -> ""
        }
    }
    
    fun startHorizontalSeeking(startX: Float) {
        touchState = touchState.copy(isHorizontalSwipe = true)
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
        
        seekState = SeekState()
    }
    
    fun startVerticalSwipe(startY: Float) {
        touchState = touchState.copy(isVerticalSwipe = true)
        cancelAutoHide()
        val deltaY = startY - touchState.startY
        performQuickSeek(if (deltaY < 0) quickSeekAmount else -quickSeekAmount)
    }
    
    fun handleHorizontalSeeking(currentX: Float) {
        if (!isSeeking) return
        
        val deltaX = currentX - seekStartX
        val pixelsPerSecond = 2f / 0.007f
        val timeDeltaSeconds = deltaX / pixelsPerSecond
        val newPositionSeconds = seekStartPosition + timeDeltaSeconds
        val duration = mpv.getPropertyDouble("duration") ?: 0.0
        val clampedPosition = newPositionSeconds.coerceIn(0.0, duration)
        
        seekDirection = if (deltaX > 0) "+" else "-"
        seekTargetTime = formatTimeSimple(clampedPosition)
        
        val now = System.currentTimeMillis()
        
        if (now - seekState.lastHorizontalUpdateTime > 16) {
            currentTime = formatTimeSimple(clampedPosition)
            seekbarPosition = clampedPosition.toFloat()
            seekState = seekState.copy(lastHorizontalUpdateTime = now)
        }
        
        performSmoothSeek(clampedPosition)
    }
    
    fun endHorizontalSeeking() {
        if (isSeeking) {
            val currentPos = mpv.getPropertyDouble("time-pos") ?: seekStartPosition
            performSmoothSeek(currentPos)
            
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
    
    fun endVerticalSwipe() {
        touchState = touchState.copy(isVerticalSwipe = false)
    }
    
    fun endTouch() {
        val touchDuration = System.currentTimeMillis() - touchState.startTime
        
        if (touchState.isLongTap) {
            isSpeedingUp = false
        } else if (touchState.isHorizontalSwipe) {
            endHorizontalSeeking()
        } else if (touchState.isVerticalSwipe) {
            endVerticalSwipe()
        } else if (touchDuration < 150) {
            handleTap()
        }
        
        touchState = TouchState()
        isHorizontalSwipe = false
        isVerticalSwipe = false
        isLongTap = false
    }
    
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
        seekTargetTime = formatTimeSimple(newPosition.toDouble())
        currentTime = formatTimeSimple(newPosition.toDouble())
        
        performStepSeek(newPosition.toDouble())
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
    
    // Alpha values
    val videoInfoTextAlpha = if (isSeeking || isDragging) 0.0f else 1.0f
    val videoInfoBackgroundAlpha = if (isSeeking || isDragging) 0.0f else 0.8f
    val timeDisplayTextAlpha = if (isSeeking || isDragging) 0.0f else 1.0f
    val timeDisplayBackgroundAlpha = if (isSeeking || isDragging) 0.0f else 0.8f
    
    // ==================== UI ====================
    
    Box(modifier = modifier.fillMaxSize()) {
        // Gesture area
        Box(modifier = Modifier.fillMaxSize()) {
            Box(modifier = Modifier.fillMaxWidth().fillMaxHeight(0.05f).align(Alignment.TopStart))
            
            Box(modifier = Modifier.fillMaxWidth().fillMaxHeight(0.95f).align(Alignment.BottomStart)) {
                Box(modifier = Modifier.fillMaxWidth(0.05f).fillMaxHeight().align(Alignment.CenterStart))
                
                Box(
                    modifier = Modifier
                        .fillMaxWidth(0.9f)
                        .fillMaxHeight()
                        .align(Alignment.Center)
                        .pointerInteropFilter { event ->
                            when (event.action) {
                                MotionEvent.ACTION_DOWN -> {
                                    touchState = touchState.copy(
                                        startX = event.x,
                                        startY = event.y
                                    )
                                    startLongTapDetection()
                                    true
                                }
                                MotionEvent.ACTION_MOVE -> {
                                    if (!touchState.isHorizontalSwipe && !touchState.isVerticalSwipe && !touchState.isLongTap) {
                                        when (checkForSwipeDirection(event.x, event.y)) {
                                            "horizontal" -> startHorizontalSeeking(event.x)
                                            "vertical" -> startVerticalSwipe(event.y)
                                        }
                                    } else if (touchState.isHorizontalSwipe) {
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
                )
                
                Box(modifier = Modifier.fillMaxWidth(0.05f).fillMaxHeight().align(Alignment.CenterEnd))
            }
        }
        
        // Seekbar
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
                            OptimizedDraggableProgressBar(
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
    }
}

// ==================== OPTIMIZED PROGRESS BAR ====================

@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
fun OptimizedDraggableProgressBar(
    position: Float,
    duration: Float,
    onValueChange: (Float) -> Unit,
    onValueChangeFinished: () -> Unit,
    getFreshPosition: () -> Float,
    modifier: Modifier = Modifier
) {
    val safeDuration = remember(duration) { if (duration > 0) duration else 1f }
    
    val progressFraction = remember(position, safeDuration) {
        (position.coerceIn(0f, safeDuration) / safeDuration).coerceIn(0f, 1f)
    }
    
    val dragState = remember { mutableStateOf(DragState()) }
    val movementThresholdPx = with(LocalDensity.current) { 25.dp.toPx() }
    
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
                .pointerInput(movementThresholdPx, safeDuration) {
                    detectDragGestures(
                        onDragStart = { offset ->
                            dragState.value = DragState(
                                startX = offset.x,
                                savedPosition = getFreshPosition().coerceIn(0f, safeDuration),
                                hasPassedThreshold = false
                            )
                        },
                        onDrag = { change, _ ->
                            change.consume()
                            val currentState = dragState.value
                            val currentX = change.position.x
                            val totalMovementX = currentX - currentState.startX
                            
                            if (!currentState.hasPassedThreshold) {
                                if (abs(totalMovementX) <= movementThresholdPx) {
                                    return@detectDragGestures
                                }
                                dragState.value = currentState.copy(hasPassedThreshold = true)
                            }
                            
                            val movementBeyondThreshold = maxOf(0f, abs(totalMovementX) - movementThresholdPx)
                            val effectiveMovement = totalMovementX.sign * movementBeyondThreshold
                            
                            val newPosition = (currentState.savedPosition + 
                                (effectiveMovement / size.width) * safeDuration)
                                .coerceIn(0f, safeDuration)
                            
                            // Round to nearest second for progress bar
                            val roundedSeconds = (newPosition + 0.5f).toInt()
                            val roundedPosition = roundedSeconds.toFloat()
                            
                            if (abs(roundedPosition - position) > 0.1f) {
                                onValueChange(roundedPosition)
                            }
                        },
                        onDragEnd = {
                            dragState.value = DragState()
                            onValueChangeFinished()
                        },
                        onDragCancel = {
                            dragState.value = DragState()
                            onValueChangeFinished()
                        }
                    )
                }
        )
    }
}

// ==================== UTILITY FUNCTIONS ====================

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

private fun getFileName(context: Context, mpv: MPV): String {
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
                if (displayNameIndex != -1) {
                    cursor.getString(displayNameIndex)?.substringBeforeLast(".")
                } else {
                    uri.lastPathSegment?.substringBeforeLast(".")
                }
            } else null
        }
    } catch (e: Exception) {
        null
    }
}

private fun getBestAvailableFileName(context: Context, mpv: MPV): String {
    val mediaTitle = mpv.getPropertyString("media-title")
    if (!mediaTitle.isNullOrBlank() && mediaTitle != "Video") {
        return mediaTitle.substringBeforeLast(".")
    }
    
    val mpvPath = mpv.getPropertyString("path")
    if (!mpvPath.isNullOrBlank()) {
        return mpvPath.substringAfterLast("/").substringBeforeLast(".").ifEmpty { "RTFP" }
    }
    
    return "RTFP"
}
