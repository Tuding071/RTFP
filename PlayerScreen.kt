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

@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
fun PlayerOverlay(
    mpv: MPV,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    val density = LocalDensity.current
    
    // Core state
    var currentTime by remember { mutableStateOf("00:00") }
    var totalTime by remember { mutableStateOf("00:00") }
    var videoDuration by remember { mutableStateOf(0.0) }
    var seekbarPosition by remember { mutableStateOf(0f) }
    
    // UI visibility
    var showSeekbar by remember { mutableStateOf(true) }
    var showVideoInfo by remember { mutableStateOf(true) }
    var fileName by remember { mutableStateOf("RTFP") }
    var showSeekTime by remember { mutableStateOf(false) }
    
    // Drag state - SEEK BAR AREA
    var isDraggingSeekbar by remember { mutableStateOf(false) }
    var hasCrossedDeadzone by remember { mutableStateOf(false) }
    var dragAccumulatedPixels by remember { mutableStateOf(0f) }
    var dragStartPosition by remember { mutableStateOf(0.0) }
    var wasPlayingBeforeDrag by remember { mutableStateOf(false) }
    var dragTargetTime by remember { mutableStateOf("00:00") }
    var lastSeekedSecond by remember { mutableStateOf(0) }
    var dragStartX by remember { mutableStateOf(0f) }
    
    // Full screen gesture states
    var isHorizontalSwipe by remember { mutableStateOf(false) }
    var isHorizontalSeeking by remember { mutableStateOf(false) }
    var isVerticalSwipe by remember { mutableStateOf(false) }
    var seekStartX by remember { mutableStateOf(0f) }
    var seekStartPosition by remember { mutableStateOf(0.0) }
    var seekDirection by remember { mutableStateOf("") }
    var seekTargetTime by remember { mutableStateOf("00:00") }
    var lastSeekTime by remember { mutableStateOf(0L) }
    var lastHorizontalUpdateTime by remember { mutableStateOf(0L) }
    var lastFeedbackUpdateTime by remember { mutableStateOf(0L) }
    
    // Touch tracking
    var touchStartX by remember { mutableStateOf(0f) }
    var touchStartY by remember { mutableStateOf(0f) }
    var touchStartTime by remember { mutableStateOf(0L) }
    var isTouching by remember { mutableStateOf(false) }
    var isLongTap by remember { mutableStateOf(false) }
    var isSpeedingUp by remember { mutableStateOf(false) }
    
    // Feedback states
    var showPlaybackFeedback by remember { mutableStateOf(false) }
    var playbackFeedbackText by remember { mutableStateOf("") }
    var showQuickSeekFeedback by remember { mutableStateOf(false) }
    var quickSeekFeedbackText by remember { mutableStateOf("") }
    
    // Thresholds
    val deadzoneThresholdPx = with(density) { 50.dp.toPx() }
    val longTapThreshold = 300L
    val horizontalSwipeThreshold = 30f
    val verticalSwipeThreshold = 40f
    val maxVerticalMovement = 50f
    val maxHorizontalMovement = 50f
    val quickSeekAmount = 5
    
    // Throttle
    val seekThrottleMs = 33L
    val uiUpdateThrottleMs = 16L
    val feedbackThrottleMs = 100L // 10fps feedback updates
    
    // Wait for valid duration
    LaunchedEffect(Unit) {
        while (videoDuration <= 1.0) {
            videoDuration = mpv.getPropertyDouble("duration") ?: 0.0
            if (videoDuration > 1.0) {
                totalTime = formatTimeSimple(videoDuration)
            }
            delay(100)
        }
    }
    
    // Auto-hide seekbar
    LaunchedEffect(showSeekbar, isDraggingSeekbar, isHorizontalSwipe, isVerticalSwipe) {
        if (showSeekbar && !isDraggingSeekbar && !isHorizontalSwipe && !isVerticalSwipe) {
            delay(4000)
            if (!isDraggingSeekbar && !isHorizontalSwipe && !isVerticalSwipe) {
                showSeekbar = false
                showVideoInfo = false
            }
        }
    }
    
    // Update time when not seeking/dragging
    LaunchedEffect(Unit) {
        while (isActive) {
            if (!isHorizontalSeeking && !isDraggingSeekbar) {
                val currentPos = mpv.getPropertyDouble("time-pos") ?: 0.0
                currentTime = formatTimeSimple(currentPos)
                seekbarPosition = currentPos.toFloat()
            }
            delay(100)
        }
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
    
    // Speed control
    LaunchedEffect(isSpeedingUp) {
        mpv.setPropertyDouble("speed", if (isSpeedingUp) 2.0 else 1.0)
    }
    
    // ============= HELPER FUNCTIONS =============
    fun showPlaybackFeedback(text: String) {
        showPlaybackFeedback = true
        playbackFeedbackText = text
        coroutineScope.launch {
            delay(1000)
            showPlaybackFeedback = false
        }
    }
    
    fun performQuickSeek(seconds: Int) {
        quickSeekFeedbackText = if (seconds > 0) "+$seconds" else "$seconds"
        showQuickSeekFeedback = true
        coroutineScope.launch {
            delay(1000)
            showQuickSeekFeedback = false
        }
        mpv.command("seek", seconds.toString(), "relative", "exact")
    }
    
    // ============= HORIZONTAL SWIPE FUNCTIONS =============
    fun performSmoothSeek(targetPosition: Double) {
        if (isHorizontalSeeking) return
        isHorizontalSeeking = true
        mpv.command("seek", targetPosition.toString(), "absolute", "exact")
        coroutineScope.launch {
            delay(seekThrottleMs)
            isHorizontalSeeking = false
        }
    }
    
    fun startHorizontalSeeking(startX: Float) {
        isHorizontalSwipe = true
        seekStartX = startX
        seekStartPosition = mpv.getPropertyDouble("time-pos") ?: 0.0
        wasPlayingBeforeDrag = mpv.getPropertyBoolean("pause") == false
        showSeekTime = true
        showSeekbar = true
        showVideoInfo = true
        
        // Set initial target time
        seekTargetTime = formatTimeSimple(seekStartPosition)
        seekDirection = ""
        
        if (wasPlayingBeforeDrag) {
            mpv.setPropertyBoolean("pause", true)
        }
        
        lastSeekTime = 0L
        lastHorizontalUpdateTime = 0L
        lastFeedbackUpdateTime = 0L
    }
    
    fun handleHorizontalSeeking(currentX: Float) {
        if (!isHorizontalSwipe) return
        
        val deltaX = currentX - seekStartX
        val pixelsPerSecond = 2f / 0.007f
        val timeDeltaSeconds = deltaX / pixelsPerSecond
        val newPositionSeconds = seekStartPosition + timeDeltaSeconds
        val duration = mpv.getPropertyDouble("duration") ?: 0.0
        val clampedPosition = newPositionSeconds.coerceIn(0.0, duration)
        
        val now = System.currentTimeMillis()
        
        // Update UI smoothly (progress bar only)
        if (now - lastHorizontalUpdateTime > uiUpdateThrottleMs) {
            currentTime = formatTimeSimple(clampedPosition)
            seekbarPosition = clampedPosition.toFloat()
            lastHorizontalUpdateTime = now
        }
        
        // Perform seek with throttling
        if (now - lastSeekTime > seekThrottleMs) {
            performSmoothSeek(clampedPosition)
            lastSeekTime = now
        }
        
        // Update feedback text at a slower rate (10fps)
        if (now - lastFeedbackUpdateTime > feedbackThrottleMs) {
            val newTimeString = formatTimeSimple(clampedPosition)
            if (newTimeString != seekTargetTime) {
                seekTargetTime = newTimeString
                seekDirection = if (deltaX > 0) "+" else "-"
            }
            lastFeedbackUpdateTime = now
        }
    }
    
    fun endHorizontalSeeking() {
        if (isHorizontalSwipe) {
            val currentPos = mpv.getPropertyDouble("time-pos") ?: seekStartPosition
            performSmoothSeek(currentPos)
            
            if (wasPlayingBeforeDrag) {
                coroutineScope.launch {
                    delay(100)
                    mpv.setPropertyBoolean("pause", false)
                }
            }
            
            isHorizontalSwipe = false
            isHorizontalSeeking = false
            showSeekTime = false
            seekStartX = 0f
            seekStartPosition = 0.0
            wasPlayingBeforeDrag = false
            seekDirection = ""
        }
    }
    
    // ============= VERTICAL SWIPE FUNCTIONS =============
    fun startVerticalSwipe(startY: Float) {
        isVerticalSwipe = true
        val deltaY = startY - touchStartY
        
        if (deltaY < 0) {
            performQuickSeek(quickSeekAmount)
        } else {
            performQuickSeek(-quickSeekAmount)
        }
    }
    
    fun endVerticalSwipe() {
        isVerticalSwipe = false
    }
    
    // ============= NEW SEEKBAR DRAG LOGIC =============
    fun calculatePixelThreshold(touchAreaWidth: Float): Float {
        return if (videoDuration > 0) {
            touchAreaWidth / videoDuration.toFloat()
        } else {
            Float.MAX_VALUE
        }
    }
    
    fun startSeekbarDrag(startX: Float, seekbarWidth: Float) {
        isDraggingSeekbar = true
        hasCrossedDeadzone = false
        dragAccumulatedPixels = 0f
        dragStartPosition = mpv.getPropertyDouble("time-pos") ?: 0.0
        wasPlayingBeforeDrag = mpv.getPropertyBoolean("pause") == false
        dragStartX = startX
        
        lastSeekedSecond = (dragStartPosition + 0.5).toInt()
        dragTargetTime = formatTimeSimple(dragStartPosition)
        
        showSeekbar = true
        showVideoInfo = true
        showSeekTime = true
        
        if (wasPlayingBeforeDrag) {
            mpv.setPropertyBoolean("pause", true)
        }
        
        lastSeekTime = 0L
    }
    
    fun handleSeekbarDrag(currentX: Float, seekbarWidth: Float) {
        if (!isDraggingSeekbar) return
        
        val deltaX = currentX - dragStartX
        val totalDragDistance = abs(deltaX)
        
        if (!hasCrossedDeadzone) {
            if (totalDragDistance > deadzoneThresholdPx) {
                hasCrossedDeadzone = true
                dragAccumulatedPixels = 0f
                dragStartX = currentX - (deadzoneThresholdPx * sign(deltaX))
            } else {
                val threshold = calculatePixelThreshold(seekbarWidth)
                val previewPosition = dragStartPosition + (deltaX / threshold)
                seekbarPosition = previewPosition.toFloat().coerceIn(0f, videoDuration.toFloat())
                currentTime = formatTimeSimple(seekbarPosition.toDouble())
                return
            }
        }
        
        val threshold = calculatePixelThreshold(seekbarWidth)
        val movementDelta = currentX - dragStartX
        dragAccumulatedPixels += movementDelta
        dragStartX = currentX
        
        val thresholdCrossings = (dragAccumulatedPixels / threshold).toInt()
        
        if (thresholdCrossings != 0) {
            val secondsToSeek = thresholdCrossings
            val newSecond = lastSeekedSecond + secondsToSeek
            val maxSecond = videoDuration.toInt()
            val clampedSecond = newSecond.coerceIn(0, maxSecond)
            
            if (clampedSecond != lastSeekedSecond) {
                val now = System.currentTimeMillis()
                
                if (now - lastSeekTime >= seekThrottleMs) {
                    mpv.command("seek", clampedSecond.toString(), "absolute", "exact")
                    lastSeekTime = now
                    
                    lastSeekedSecond = clampedSecond
                    
                    // Update target time when threshold crossed
                    dragTargetTime = formatTimeSimple(clampedSecond.toDouble())
                    
                    dragAccumulatedPixels -= (secondsToSeek * threshold)
                }
            }
        }
        
        // Update progress bar position smoothly for visual feedback
        val newPosition = (lastSeekedSecond + (dragAccumulatedPixels / threshold)).toDouble()
            .coerceIn(0.0, videoDuration)
        seekbarPosition = newPosition.toFloat()
        currentTime = formatTimeSimple(newPosition)
    }
    
    fun finishSeekbarDrag() {
        if (isDraggingSeekbar) {
            val finalPosition = lastSeekedSecond.toDouble()
            mpv.command("seek", finalPosition.toString(), "absolute", "exact")
            
            if (wasPlayingBeforeDrag) {
                coroutineScope.launch {
                    delay(100)
                    mpv.setPropertyBoolean("pause", false)
                }
            }
            
            isDraggingSeekbar = false
            hasCrossedDeadzone = false
            dragAccumulatedPixels = 0f
            wasPlayingBeforeDrag = false
            showSeekTime = false
        }
    }
    
    fun cancelSeekbarDrag() {
        if (isDraggingSeekbar) {
            mpv.command("seek", dragStartPosition.toString(), "absolute", "exact")
            seekbarPosition = dragStartPosition.toFloat()
            currentTime = formatTimeSimple(dragStartPosition)
            
            if (wasPlayingBeforeDrag) {
                coroutineScope.launch {
                    delay(100)
                    mpv.setPropertyBoolean("pause", false)
                }
            }
            
            isDraggingSeekbar = false
            hasCrossedDeadzone = false
            dragAccumulatedPixels = 0f
            wasPlayingBeforeDrag = false
            showSeekTime = false
        }
    }
    
    // ============= TOUCH HANDLING =============
    fun checkForSwipeDirection(currentX: Float, currentY: Float): String {
        if (isHorizontalSwipe || isVerticalSwipe || isLongTap || isDraggingSeekbar) return ""
        
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
    
    fun startLongTapDetection() {
        isTouching = true
        touchStartTime = System.currentTimeMillis()
        coroutineScope.launch {
            delay(longTapThreshold)
            if (isTouching && !isHorizontalSwipe && !isVerticalSwipe && !isDraggingSeekbar) {
                isLongTap = true
                isSpeedingUp = true
                mpv.setPropertyDouble("speed", 2.0)
            }
        }
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
            showSeekbar = true
            showVideoInfo = true
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
            endVerticalSwipe()
            isVerticalSwipe = false
        } else if (isDraggingSeekbar) {
            finishSeekbarDrag()
            isDraggingSeekbar = false
        } else if (touchDuration < 150) {
            handleTap()
        }
        isHorizontalSwipe = false
        isVerticalSwipe = false
        isLongTap = false
    }
    
    // ============= ALPHA VALUES =============
    val isAnySeeking = isHorizontalSeeking || isDraggingSeekbar || isHorizontalSwipe
    
    val videoInfoTextAlpha = if (isAnySeeking) 0.0f else 1.0f
    val videoInfoBackgroundAlpha = if (isAnySeeking) 0.0f else 0.8f
    val timeDisplayTextAlpha = if (isAnySeeking) 0.0f else 1.0f
    val timeDisplayBackgroundAlpha = if (isAnySeeking) 0.0f else 0.8f
    
    BoxWithConstraints(modifier = modifier.fillMaxSize()) {
        val screenWidth = constraints.maxWidth.toFloat()
        
        // ============= GESTURE AREA WITH IGNORE ZONES (RESTORED) =============
        Box(modifier = Modifier.fillMaxSize()) {
            // Top 5% ignore area (for status bar)
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .fillMaxHeight(0.05f)
                    .align(Alignment.TopStart)
            )
            
            // Bottom 95% area containing left/right ignore zones and center gesture area
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .fillMaxHeight(0.95f)
                    .align(Alignment.BottomStart)
            ) {
                // Left 5% ignore area
                Box(
                    modifier = Modifier
                        .fillMaxWidth(0.05f)
                        .fillMaxHeight()
                        .align(Alignment.CenterStart)
                )
                
                // Center 90% gesture area
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
                                    if (!isHorizontalSwipe && !isVerticalSwipe && !isLongTap && !isDraggingSeekbar) {
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
                
                // Right 5% ignore area
                Box(
                    modifier = Modifier
                        .fillMaxWidth(0.05f)
                        .fillMaxHeight()
                        .align(Alignment.CenterEnd)
                )
            }
        }
        
        // Seekbar Area
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
                    // Time display
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
                    
                    // Progress Bar with drag handling
                    Box(modifier = Modifier.fillMaxWidth().height(48.dp)) {
                        if (videoDuration > 1.0) {
                            BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
                                val seekbarWidth = constraints.maxWidth.toFloat()
                                
                                // Progress bar visual
                                Box(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .height(4.dp)
                                        .align(Alignment.CenterStart)
                                        .background(Color.Gray.copy(alpha = 0.6f))
                                )
                                
                                Box(
                                    modifier = Modifier
                                        .fillMaxWidth(fraction = (seekbarPosition / videoDuration.toFloat()).coerceIn(0f, 1f))
                                        .height(4.dp)
                                        .align(Alignment.CenterStart)
                                        .background(Color.White)
                                )
                                
                                // Touch area
                                Box(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .height(48.dp)
                                        .align(Alignment.CenterStart)
                                        .pointerInput(Unit) {
                                            detectDragGestures(
                                                onDragStart = { offset ->
                                                    startSeekbarDrag(offset.x, seekbarWidth)
                                                },
                                                onDrag = { change, _ ->
                                                    change.consume()
                                                    handleSeekbarDrag(change.position.x, seekbarWidth)
                                                },
                                                onDragEnd = {
                                                    if (isDraggingSeekbar) {
                                                        finishSeekbarDrag()
                                                    }
                                                },
                                                onDragCancel = {
                                                    if (isDraggingSeekbar) {
                                                        cancelSeekbarDrag()
                                                    }
                                                }
                                            )
                                        }
                                )
                            }
                        } else {
                            // Loading state
                            Box(modifier = Modifier.fillMaxSize()) {
                                Box(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .height(4.dp)
                                        .align(Alignment.CenterStart)
                                        .background(Color.Gray.copy(alpha = 0.6f))
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
        
        // Feedback displays
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
                // Show during horizontal swipe OR when showSeekTime is true
                (isHorizontalSwipe || showSeekTime || isDraggingSeekbar) -> Text(
                    text = if (isHorizontalSwipe) "$seekTargetTime $seekDirection" else dragTargetTime,
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

// Utility functions
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
