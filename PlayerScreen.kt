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
    
    // Drag state - UNIFIED for both progress bar and swipe
    var isDragging by remember { mutableStateOf(false) }
    var hasCrossedDeadzone by remember { mutableStateOf(false) }
    var dragAccumulatedPixels by remember { mutableStateOf(0f) }
    var dragStartPosition by remember { mutableStateOf(0.0) }
    var wasPlayingBeforeDrag by remember { mutableStateOf(false) }
    var dragTargetTime by remember { mutableStateOf("00:00") }
    var lastSeekedSecond by remember { mutableStateOf(0) }
    var dragStartX by remember { mutableStateOf(0f) }
    
    // Throttling
    var lastSeekTime by remember { mutableStateOf(0L) }
    val seekThrottleMs = 50L // 50ms between seeks
    
    // Touch tracking for swipe
    var touchStartX by remember { mutableStateOf(0f) }
    var touchStartY by remember { mutableStateOf(0f) }
    var touchStartTime by remember { mutableStateOf(0L) }
    var isTouching by remember { mutableStateOf(false) }
    var isLongTap by remember { mutableStateOf(false) }
    var isSpeedingUp by remember { mutableStateOf(false) }
    
    // Thresholds
    val deadzoneThresholdPx = with(density) { 25.dp.toPx() }
    val longTapThreshold = 300L
    val horizontalSwipeThreshold = 30f
    val verticalSwipeThreshold = 40f
    val maxVerticalMovement = 50f
    val maxHorizontalMovement = 50f
    val quickSeekAmount = 5
    
    // Feedback states
    var showPlaybackFeedback by remember { mutableStateOf(false) }
    var playbackFeedbackText by remember { mutableStateOf("") }
    var showQuickSeekFeedback by remember { mutableStateOf(false) }
    var quickSeekFeedbackText by remember { mutableStateOf("") }
    
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
    LaunchedEffect(showSeekbar, isDragging) {
        if (showSeekbar && !isDragging) {
            delay(4000)
            if (!isDragging) {
                showSeekbar = false
                showVideoInfo = false
            }
        }
    }
    
    // Update time when not dragging
    LaunchedEffect(Unit) {
        while (isActive) {
            if (!isDragging) {
                val currentPos = mpv.getPropertyDouble("time-pos") ?: 0.0
                currentTime = formatTimeSimple(currentPos)
                seekbarPosition = currentPos.toFloat()
            }
            delay(100) // Update 10 times per second
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
    
    // Helper functions
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
            if (isTouching && !isDragging) {
                isLongTap = true
                isSpeedingUp = true
            }
        }
    }
    
    fun endTouch() {
        val touchDuration = System.currentTimeMillis() - touchStartTime
        isTouching = false
        
        if (isLongTap) {
            isLongTap = false
            isSpeedingUp = false
        } else if (!isDragging && touchDuration < 150) {
            handleTap()
        }
        
        // If we were dragging, finish it
        if (isDragging) {
            finishDragging()
        }
        
        isLongTap = false
    }
    
    // ============= UNIFIED DRAG SEEKING LOGIC =============
    
    /**
     * Calculates how many pixels the user must drag to trigger a 1-second seek
     * Formula: thresholdPixels = totalTouchAreaWidth / videoDurationInSeconds
     */
    fun calculatePixelThreshold(touchAreaWidth: Float): Float {
        return if (videoDuration > 0) {
            touchAreaWidth / videoDuration.toFloat()
        } else {
            Float.MAX_VALUE
        }
    }
    
    /**
     * Start dragging from progress bar or swipe
     */
    fun startDragging(startX: Float, touchAreaWidth: Float) {
        isDragging = true
        hasCrossedDeadzone = false
        dragAccumulatedPixels = 0f
        dragStartPosition = mpv.getPropertyDouble("time-pos") ?: 0.0
        wasPlayingBeforeDrag = mpv.getPropertyBoolean("pause") == false
        dragStartX = startX
        
        // Store the current second we're at for tracking
        lastSeekedSecond = (dragStartPosition + 0.5).toInt()
        dragTargetTime = formatTimeSimple(dragStartPosition)
        
        // Show UI
        showSeekbar = true
        showVideoInfo = true
        
        // Pause if playing
        if (wasPlayingBeforeDrag) {
            mpv.setPropertyBoolean("pause", true)
        }
        
        // Reset throttle
        lastSeekTime = 0L
    }
    
    /**
     * Handle drag movement with two thresholds:
     * 1. Deadzone threshold (25dp) - prevents accidental seeks on tap
     * 2. Dynamic threshold - controls 1-second seek sensitivity based on video duration
     */
    fun handleDrag(currentX: Float, touchAreaWidth: Float) {
        if (!isDragging) return
        
        val deltaX = currentX - dragStartX
        val totalDragDistance = abs(deltaX)
        
        // FIRST THRESHOLD: Deadzone - prevent immediate seeking on touch
        if (!hasCrossedDeadzone) {
            if (totalDragDistance > deadzoneThresholdPx) {
                hasCrossedDeadzone = true
                // Reset accumulator at deadzone crossing point
                dragAccumulatedPixels = 0f
                // Update dragStartX to deadzone crossing point for accurate accumulation
                dragStartX = currentX - (deadzoneThresholdPx * sign(deltaX))
            } else {
                // Still in deadzone, just update progress bar position for visual feedback
                val threshold = calculatePixelThreshold(touchAreaWidth)
                val previewPosition = dragStartPosition + (deltaX / threshold)
                seekbarPosition = previewPosition.toFloat().coerceIn(0f, videoDuration.toFloat())
                currentTime = formatTimeSimple(seekbarPosition.toDouble())
                return
            }
        }
        
        // SECOND THRESHOLD: Dynamic threshold for actual seeking
        val threshold = calculatePixelThreshold(touchAreaWidth)
        
        // Calculate movement since deadzone crossing
        val movementDelta = currentX - dragStartX
        dragAccumulatedPixels += movementDelta
        dragStartX = currentX // Reset for next update
        
        // Calculate how many full thresholds we've crossed
        val thresholdCrossings = (dragAccumulatedPixels / threshold).toInt()
        
        if (thresholdCrossings != 0) {
            // Each threshold crossing = 1 second seek
            val secondsToSeek = thresholdCrossings
            val newSecond = lastSeekedSecond + secondsToSeek
            
            // Clamp to valid range
            val maxSecond = videoDuration.toInt()
            val clampedSecond = newSecond.coerceIn(0, maxSecond)
            
            // Only seek if we actually changed seconds
            if (clampedSecond != lastSeekedSecond) {
                val now = System.currentTimeMillis()
                
                // Throttle seeks
                if (now - lastSeekTime >= seekThrottleMs) {
                    // Perform 1-second jump
                    mpv.command("seek", clampedSecond.toString(), "absolute", "exact")
                    lastSeekTime = now
                    
                    // Update tracking
                    lastSeekedSecond = clampedSecond
                    dragTargetTime = formatTimeSimple(clampedSecond.toDouble())
                    
                    // Remove the consumed pixels from accumulator
                    dragAccumulatedPixels -= (secondsToSeek * threshold)
                }
            }
        }
        
        // Always update progress bar position for smooth visual feedback
        val newPosition = (lastSeekedSecond + (dragAccumulatedPixels / threshold)).toDouble()
            .coerceIn(0.0, videoDuration)
        seekbarPosition = newPosition.toFloat()
        currentTime = formatTimeSimple(newPosition)
    }
    
    /**
     * Finish dragging - restore playback state
     */
    fun finishDragging() {
        if (isDragging) {
            // Ensure final position is set
            val finalPosition = lastSeekedSecond.toDouble()
            mpv.command("seek", finalPosition.toString(), "absolute", "exact")
            
            // Resume if it was playing
            if (wasPlayingBeforeDrag) {
                coroutineScope.launch {
                    delay(100)
                    mpv.setPropertyBoolean("pause", false)
                }
            }
            
            // Reset drag state
            isDragging = false
            hasCrossedDeadzone = false
            dragAccumulatedPixels = 0f
            wasPlayingBeforeDrag = false
        }
    }
    
    /**
     * Cancel drag - restore to start position
     */
    fun cancelDragging() {
        if (isDragging) {
            // Restore to start position
            mpv.command("seek", dragStartPosition.toString(), "absolute", "exact")
            seekbarPosition = dragStartPosition.toFloat()
            currentTime = formatTimeSimple(dragStartPosition)
            
            // Resume if it was playing
            if (wasPlayingBeforeDrag) {
                coroutineScope.launch {
                    delay(100)
                    mpv.setPropertyBoolean("pause", false)
                }
            }
            
            // Reset drag state
            isDragging = false
            hasCrossedDeadzone = false
            dragAccumulatedPixels = 0f
            wasPlayingBeforeDrag = false
        }
    }
    // ============= END UNIFIED DRAG LOGIC =============
    
    // Alpha values for UI elements during drag
    val uiAlpha = if (isDragging) 0f else 1f
    
    BoxWithConstraints(modifier = modifier.fillMaxSize()) {
        val screenWidth = constraints.maxWidth.toFloat()
        val screenHeight = constraints.maxHeight.toFloat()
        
        // Full screen gesture area (for swipe, long press, tap)
        Box(
            modifier = Modifier
                .fillMaxSize()
                .pointerInteropFilter { event ->
                    when (event.action) {
                        MotionEvent.ACTION_DOWN -> {
                            touchStartX = event.x
                            touchStartY = event.y
                            touchStartTime = System.currentTimeMillis()
                            startLongTapDetection()
                            true
                        }
                        MotionEvent.ACTION_MOVE -> {
                            if (!isDragging && !isLongTap) {
                                // Check for swipe gestures
                                val deltaX = abs(event.x - touchStartX)
                                val deltaY = abs(event.y - touchStartY)
                                
                                if (deltaX > horizontalSwipeThreshold && 
                                    deltaX > deltaY && 
                                    deltaY < maxVerticalMovement) {
                                    // Start horizontal drag (using progress bar logic)
                                    startDragging(event.x, screenWidth)
                                } else if (deltaY > verticalSwipeThreshold && 
                                         deltaY > deltaX && 
                                         deltaX < maxHorizontalMovement) {
                                    // Vertical swipe for quick seek
                                    val deltaY = event.y - touchStartY
                                    if (deltaY < 0) {
                                        performQuickSeek(quickSeekAmount)
                                    } else {
                                        performQuickSeek(-quickSeekAmount)
                                    }
                                }
                            } else if (isDragging) {
                                handleDrag(event.x, screenWidth)
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
        
        // Progress Bar Area (bottom) - This is the main seek control
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
                                color = Color.White.copy(alpha = if (isDragging) 0f else 1f),
                                fontSize = 14.sp,
                                fontWeight = FontWeight.Medium
                            ),
                            modifier = Modifier
                                .background(Color.DarkGray.copy(alpha = 0.8f))
                                .padding(horizontal = 12.dp, vertical = 4.dp)
                        )
                    }
                }
                
                // Progress Bar with direct drag handling
                Box(modifier = Modifier.fillMaxWidth().height(48.dp)) {
                    if (videoDuration > 1.0) {
                        BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
                            val progressBarWidth = constraints.maxWidth.toFloat()
                            
                            DynamicThresholdProgressBar(
                                position = seekbarPosition,
                                duration = videoDuration.toFloat(),
                                onDragStart = { xPosition ->
                                    dragStartX = xPosition
                                    startDragging(xPosition, progressBarWidth)
                                },
                                onDrag = { xPosition ->
                                    handleDrag(xPosition, progressBarWidth)
                                },
                                onDragEnd = { finishDragging() },
                                onDragCancel = { cancelDragging() },
                                modifier = Modifier.fillMaxSize().height(48.dp)
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
        
        // Video title
        if (showVideoInfo) {
            Text(
                text = fileName,
                style = TextStyle(
                    color = Color.White.copy(alpha = uiAlpha),
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Medium
                ),
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .offset(x = 60.dp, y = 20.dp)
                    .background(Color.DarkGray.copy(alpha = 0.8f))
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
                isDragging -> Text(
                    text = dragTargetTime,
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

/**
 * Progress Bar with Dynamic Threshold Drag Detection
 * 
 * This looks like a regular progress bar but handles drag gestures
 * with a dynamic threshold based on video duration.
 */
@Composable
fun DynamicThresholdProgressBar(
    position: Float,
    duration: Float,
    onDragStart: (Float) -> Unit,
    onDrag: (Float) -> Unit,
    onDragEnd: () -> Unit,
    onDragCancel: () -> Unit,
    modifier: Modifier = Modifier
) {
    val safeDuration = if (duration > 0) duration else 1f
    val safePosition = position.coerceIn(0f, safeDuration)
    val progressFraction = (safePosition / safeDuration).coerceIn(0f, 1f)
    
    Box(modifier = modifier) {
        // Background bar
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(4.dp)
                .align(Alignment.CenterStart)
                .background(Color.Gray.copy(alpha = 0.6f))
        )
        
        // Progress bar (visual only)
        Box(
            modifier = Modifier
                .fillMaxWidth(fraction = progressFraction)
                .height(4.dp)
                .align(Alignment.CenterStart)
                .background(Color.White)
        )
        
        // Invisible drag handle - captures all gestures on the progress bar
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(48.dp)
                .align(Alignment.CenterStart)
                .pointerInput(Unit) {
                    detectDragGestures(
                        onDragStart = { offset ->
                            onDragStart(offset.x)
                        },
                        onDrag = { change, _ ->
                            change.consume()
                            onDrag(change.position.x)
                        },
                        onDragEnd = {
                            onDragEnd()
                        },
                        onDragCancel = {
                            onDragCancel()
                        }
                    )
                }
        )
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
