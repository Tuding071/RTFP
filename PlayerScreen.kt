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
        mpv.setOptionString("demuxer-lavf-threads", "4")
        mpv.setOptionString("cache-initial", "0.5")
        mpv.setOptionString("video-sync", "display-resample")
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
    var seekStartPosition by remember { mutableStateOf(0.0) }
    var wasPlayingBeforeSeek by remember { mutableStateOf(false) }
    var seekDirection by remember { mutableStateOf("") }
    var isSeekInProgress by remember { mutableStateOf(false) }
    val seekThrottleMs = 50L
    
    var touchStartTime by remember { mutableStateOf(0L) }
    var touchStartX by remember { mutableStateOf(0f) }
    var touchStartY by remember { mutableStateOf(0f) }
    var isTouching by remember { mutableStateOf(false) }
    var isLongTap by remember { mutableStateOf(false) }
    var isHorizontalSwipe by remember { mutableStateOf(false) }
    var isVerticalSwipe by remember { mutableStateOf(false) }
    var longTapJob by remember { mutableStateOf<Job?>(null) }
    
    val longTapThreshold = 300L
    val horizontalSwipeThreshold = 30f
    val verticalSwipeThreshold = 40f
    val maxVerticalMovement = 50f
    val maxHorizontalMovement = 50f
    val quickSeekAmount = 5
    
    var showVideoInfo by remember { mutableStateOf(true) }
    var fileName by remember { mutableStateOf("RTFP") }
    
    var userInteracting by remember { mutableStateOf(false) }
    var hideSeekbarJob by remember { mutableStateOf<Job?>(null) }
    
    var showPlaybackFeedback by remember { mutableStateOf(false) }
    var playbackFeedbackText by remember { mutableStateOf("") }
    var playbackFeedbackJob by remember { mutableStateOf<Job?>(null) }
    
    var showQuickSeekFeedback by remember { mutableStateOf(false) }
    var quickSeekFeedbackText by remember { mutableStateOf("") }
    var quickSeekFeedbackJob by remember { mutableStateOf<Job?>(null) }
    
    // Wait for valid duration before starting
    LaunchedEffect(Unit) {
        // Wait for duration to be available
        var duration = 0.0
        var attempts = 0
        while (duration <= 0 && attempts < 30) { // 3 seconds max
            duration = mpv.getPropertyDouble("duration") ?: 0.0
            if (duration > 0) {
                Log.d("PlayerDebug", "Duration finally available: $duration")
                seekbarDuration = duration.toFloat()
                totalTime = formatTimeSimple(duration)
                break
            }
            delay(100)
            attempts++
        }
    }
    
    // Utility functions
    fun scheduleSeekbarHide() {
        if (userInteracting) return
        hideSeekbarJob?.cancel()
        hideSeekbarJob = coroutineScope.launch {
            delay(4000)
            showSeekbar = false
            showVideoInfo = false
        }
    }
    
    fun cancelAutoHide() {
        userInteracting = true
        hideSeekbarJob?.cancel()
        coroutineScope.launch {
            delay(100)
            userInteracting = false
        }
    }
    
    fun showSeekbarWithTimeout() {
        showSeekbar = true
        showVideoInfo = true
        scheduleSeekbarHide()
    }
    
    fun showPlaybackFeedback(text: String) {
        playbackFeedbackJob?.cancel()
        showPlaybackFeedback = true
        playbackFeedbackText = text
        playbackFeedbackJob = coroutineScope.launch {
            delay(1000)
            showPlaybackFeedback = false
        }
    }
    
    fun performRealTimeSeek(targetPosition: Double) {
        if (isSeekInProgress) return
        isSeekInProgress = true
        mpv.command("seek", targetPosition.toString(), "absolute", "exact")
        coroutineScope.launch {
            delay(seekThrottleMs)
            isSeekInProgress = false
        }
    }
    
    fun getFreshPosition(): Float {
        return (mpv.getPropertyDouble("time-pos") ?: 0.0).toFloat()
    }
    
    fun performQuickSeek(seconds: Int) {
        val currentPos = mpv.getPropertyDouble("time-pos") ?: 0.0
        val duration = mpv.getPropertyDouble("duration") ?: 0.0
        
        quickSeekFeedbackText = if (seconds > 0) "+$seconds" else "$seconds"
        showQuickSeekFeedback = true
        quickSeekFeedbackJob?.cancel()
        quickSeekFeedbackJob = coroutineScope.launch {
            delay(1000)
            showQuickSeekFeedback = false
        }
        
        mpv.command("seek", seconds.toString(), "relative", "exact")
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
        longTapJob?.cancel()
        longTapJob = coroutineScope.launch {
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
        val pixelsPerSecond = 2f / 0.032f
        val timeDeltaSeconds = deltaX / pixelsPerSecond
        val newPositionSeconds = seekStartPosition + timeDeltaSeconds
        val duration = mpv.getPropertyDouble("duration") ?: 0.0
        val clampedPosition = newPositionSeconds.coerceIn(0.0, duration)
        
        seekDirection = if (deltaX > 0) "+" else "-"
        seekTargetTime = formatTimeSimple(clampedPosition)
        currentTime = formatTimeSimple(clampedPosition)
        performRealTimeSeek(clampedPosition)
    }
    
    fun endHorizontalSeeking() {
        if (isSeeking) {
            val currentPos = mpv.getPropertyDouble("time-pos") ?: seekStartPosition
            performRealTimeSeek(currentPos)
            
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
            scheduleSeekbarHide()
        }
    }
    
    fun endVerticalSwipe() {
        isVerticalSwipe = false
        scheduleSeekbarHide()
    }
    
    fun endTouch() {
        val touchDuration = System.currentTimeMillis() - touchStartTime
        isTouching = false
        longTapJob?.cancel()
        
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
        delay(4000)
        scheduleSeekbarHide()
    }
    
    // Speed control backup
    LaunchedEffect(isSpeedingUp) {
        if (isSpeedingUp) {
            mpv.setPropertyDouble("speed", 2.0)
        } else {
            mpv.setPropertyDouble("speed", 1.0)
        }
    }
    
    // Update time - FIXED: Wait for valid duration
    LaunchedEffect(Unit) {
        // Wait for duration to be valid
        var duration = mpv.getPropertyDouble("duration") ?: 0.0
        while (duration <= 0) {
            delay(100)
            duration = mpv.getPropertyDouble("duration") ?: 0.0
        }
        
        Log.d("PlayerDebug", "Starting time updates with duration: $duration")
        
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
        performRealTimeSeek(targetPosition)
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
        scheduleSeekbarHide()
    }
    
    val videoInfoTextAlpha = if (isSeeking || isDragging) 0.0f else 1.0f
    val videoInfoBackgroundAlpha = if (isSeeking || isDragging) 0.0f else 0.8f
    val timeDisplayTextAlpha = if (isSeeking || isDragging) 0.0f else 1.0f
    val timeDisplayBackgroundAlpha = if (isSeeking || isDragging) 0.0f else 0.8f
    
    Box(modifier = modifier.fillMaxSize()) {
        // Gesture area
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
                )
                
                Box(
                    modifier = Modifier
                        .fillMaxWidth(0.05f)
                        .fillMaxHeight()
                        .align(Alignment.CenterEnd)
                )
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
                        SimpleDraggableProgressBar(
                            position = seekbarPosition,
                            duration = seekbarDuration,
                            onValueChange = { handleProgressBarDrag(it) },
                            onValueChangeFinished = { handleDragFinished() },
                            getFreshPosition = { getFreshPosition() },
                            modifier = Modifier.fillMaxSize().height(48.dp)
                        )
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
    var dragStartPosition by remember { mutableStateOf(0f) }
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
                            dragStartX = offset.x
                            dragStartPosition = getFreshPosition().coerceIn(0f, safeDuration)
                            hasPassedThreshold = false
                        },
                        onDrag = { change, _ ->
                            change.consume()
                            val currentX = change.position.x
                            val totalMovementX = abs(currentX - dragStartX)
                            
                            if (!hasPassedThreshold) {
                                if (totalMovementX > movementThresholdPx) {
                                    hasPassedThreshold = true
                                } else {
                                    return@detectDragGestures
                                }
                            }
                            
                            // Always calculate from ORIGINAL drag start
                            val deltaX = currentX - dragStartX
                            val deltaPosition = (deltaX / size.width) * safeDuration
                            val newPosition = (dragStartPosition + deltaPosition)
                                .coerceIn(0f, safeDuration)
                            
                            // Only update if significantly different
                            if (abs(newPosition - safePosition) > 0.1f) {
                                onValueChange(newPosition)
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
