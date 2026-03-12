package com.rtfp.player

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import android.util.AttributeSet
import android.util.Log
import android.util.LruCache
import android.view.MotionEvent
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.pointerInteropFilter
import androidx.compose.ui.platform.LocalConfiguration
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
import java.util.concurrent.ConcurrentHashMap

// ==================== MPV VIEW ====================

class SimpleMPVView(context: Context, attrs: AttributeSet? = null) : BaseMPVView(context, attrs) {
    
    override fun initOptions() {
        mpv.setOptionString("hwdec", "no")
        mpv.setOptionString("vo", "gpu")
        mpv.setOptionString("profile", "fast")
        mpv.setOptionString("keepaspect", "yes")
    }

    override fun postInitOptions() {
        mpv.setOptionString("vd-lavc-threads", "8")
        mpv.setOptionString("demuxer-lavf-threads", "8")
        mpv.setOptionString("cache-initial", "0.5")
        mpv.setOptionString("untimed", "yes")
        mpv.setOptionString("hr-seek", "yes")
        mpv.setOptionString("hr-seek-framedrop", "no")
        mpv.setOptionString("vd-lavc-fast", "yes")
        mpv.setOptionString("vd-lavc-skiploopfilter", "all")
        mpv.setOptionString("vd-lavc-skipidct", "all")
        mpv.setOptionString("vd-lavc-assemble", "yes")
        mpv.setOptionString("gpu-dumb-mode", "yes")
        mpv.setOptionString("opengl-pbo", "yes")
        mpv.setOptionString("opengl-early-flush", "yes")
        mpv.setOptionString("audio-channels", "auto")
        mpv.setOptionString("audio-samplerate", "auto")
        mpv.setOptionString("deband", "no")
        mpv.setOptionString("video-aspect-override", "no")
    }

    override fun observeProperties() {}
}

// ==================== THUMBNAIL SYSTEM ====================

class ThumbnailSystem(
    private val context: Context,
    private val videoPath: String,
    private val durationSeconds: Float
) {
    companion object {
        const val PREVIEW_WIDTH = 428  // 240p width
        const val PREVIEW_HEIGHT = 240 // 240p height
        const val GRID_INTERVAL = 1     // Every 1 second for grid
        const val WINDOW_SIZE = 20      // 20 seconds total (-10s, current, +10s)
        const val HIGH_RES_RATE = 24    // 24 thumbnails per second
        val HIGH_RES_INTERVAL = 1.0 / HIGH_RES_RATE  // ~0.0417s
    }
    
    // Tier 1: Disk cache for full grid (every 1 second)
    private val gridCacheDir = File(context.cacheDir, "thumb_grid").apply { mkdirs() }
    
    // Tier 2: Memory cache for rolling window (24fps)
    private val windowCache = LruCache<Int, Bitmap>(WINDOW_SIZE * HIGH_RES_RATE)
    
    // Track what's being generated
    private val generatingPositions = ConcurrentHashMap.newKeySet<Int>()
    
    // Current window center
    private var currentWindowCenter = 0.0
    
    // Coroutine scope
    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    
    // Callback for UI
    var onThumbnailReady: ((Double, Bitmap) -> Unit)? = null
    
    init {
        Log.d("ThumbnailSystem", "Initialized for video: $videoPath")
        startWindowTracking()
        startGridGeneration()
    }
    
    // ========== TIER 1: Grid Generation (Every 1 second) ==========
    
    private fun startGridGeneration() {
        scope.launch {
            val totalSeconds = durationSeconds.toInt()
            Log.d("ThumbnailSystem", "Starting grid generation for $totalSeconds seconds")
            
            // Priority 1: First 30 seconds
            for (second in 0..minOf(30, totalSeconds)) {
                launch { generateGridThumbnail(second) }
                delay(5)
            }
            
            // Priority 2: Last 30 seconds
            for (second in (totalSeconds - 30).coerceAtLeast(0)..totalSeconds) {
                launch { generateGridThumbnail(second) }
                delay(5)
            }
            
            // Priority 3: Fill in the middle
            for (second in 31 until (totalSeconds - 30)) {
                launch { generateGridThumbnail(second) }
                if (second % 10 == 0) delay(10)
            }
        }
    }
    
    private suspend fun generateGridThumbnail(second: Int) {
        val outputFile = File(gridCacheDir, "grid_$second.jpg")
        if (outputFile.exists()) {
            Log.d("ThumbnailSystem", "Grid thumbnail already exists: $second")
            return
        }
        if (!generatingPositions.add(second)) return
        
        Log.d("ThumbnailSystem", "Generating grid thumbnail for second: $second")
        extractThumbnail(second.toDouble())?.let { bitmap ->
            withContext(Dispatchers.IO) {
                outputFile.outputStream().use {
                    bitmap.compress(Bitmap.CompressFormat.JPEG, 85, it)
                }
                bitmap.recycle()
            }
            Log.d("ThumbnailSystem", "Generated grid thumbnail: $second")
        } ?: Log.e("ThumbnailSystem", "Failed to generate grid thumbnail: $second")
        
        generatingPositions.remove(second)
    }
    
    // ========== TIER 2: Rolling Window (24fps, 20 seconds) ==========
    
    private fun startWindowTracking() {
        scope.launch {
            while (true) {
                updateWindow(currentWindowCenter)
                delay(100) // Update every 100ms
            }
        }
    }
    
    fun setCurrentPosition(seconds: Double) {
        currentWindowCenter = seconds
    }
    
    private suspend fun updateWindow(centerSeconds: Double) {
        val startPos = (centerSeconds - WINDOW_SIZE/2).coerceIn(0.0, durationSeconds.toDouble())
        val endPos = (centerSeconds + WINDOW_SIZE/2).coerceIn(0.0, durationSeconds.toDouble())
        
        var pos = startPos
        while (pos <= endPos) {
            val positionKey = (pos * 1000).toInt()
            
            if (windowCache[positionKey] == null && generatingPositions.add(positionKey)) {
                scope.launch {
                    generateHighResThumbnail(pos)?.let { bitmap ->
                        windowCache.put(positionKey, bitmap)
                        Log.d("ThumbnailSystem", "Generated high-res thumbnail: ${pos}s")
                        onThumbnailReady?.invoke(pos, bitmap)
                    }
                    generatingPositions.remove(positionKey)
                }
            }
            
            pos += HIGH_RES_INTERVAL
        }
    }
    
    private suspend fun generateHighResThumbnail(seconds: Double): Bitmap? {
        return extractThumbnail(seconds)
    }
    
    // ========== Core Extraction ==========
    
    private suspend fun extractThumbnail(seconds: Double): Bitmap? {
        return withContext(Dispatchers.Default) {
            var retriever: MediaMetadataRetriever? = null
            try {
                retriever = MediaMetadataRetriever()
                retriever.setDataSource(videoPath)
                
                val timeUs = (seconds * 1000000).toLong()
                
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    val params = MediaMetadataRetriever.BitmapParams()
                    params.preferredConfig = Bitmap.Config.RGB_565
                    retriever.getScaledFrameAtTime(
                        timeUs,
                        MediaMetadataRetriever.OPTION_CLOSEST_SYNC,
                        PREVIEW_WIDTH,
                        PREVIEW_HEIGHT,
                        params
                    )
                } else {
                    retriever.getFrameAtTime(timeUs, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
                        ?.let { fullFrame ->
                            Bitmap.createScaledBitmap(
                                fullFrame, PREVIEW_WIDTH, PREVIEW_HEIGHT, true
                            ).also {
                                if (it != fullFrame) fullFrame.recycle()
                            }
                        }
                }
            } catch (e: Exception) {
                Log.e("ThumbnailSystem", "Error extracting thumbnail at ${seconds}s: ${e.message}")
                null
            } finally {
                retriever?.release()
            }
        }
    }
    
    // ========== Public API ==========
    
    fun getGridThumbnail(seconds: Double): Bitmap? {
        val secondKey = seconds.toInt()
        val file = File(gridCacheDir, "grid_$secondKey.jpg")
        
        return if (file.exists()) {
            Log.d("ThumbnailSystem", "Loading grid thumbnail from disk: $secondKey")
            BitmapFactory.decodeFile(file.path)
        } else {
            getHighResThumbnail(seconds)
        }
    }
    
    fun getHighResThumbnail(seconds: Double): Bitmap? {
        val positionKey = (seconds * 1000).toInt()
        val bitmap = windowCache[positionKey]
        if (bitmap != null) {
            Log.d("ThumbnailSystem", "Loading high-res thumbnail from cache: ${seconds}s")
        }
        return bitmap
    }
    
    fun getThumbnailForPosition(seconds: Double): Bitmap? {
        // Try high-res first (window cache), fallback to grid
        return getHighResThumbnail(seconds) ?: getGridThumbnail(seconds)
    }
    
    fun cleanup() {
        scope.cancel()
        windowCache.evictAll()
    }
}

// ==================== PLAYER SCREEN ====================

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
    var currentVideoPath by remember { mutableStateOf(videoPath) }
    val coroutineScope = rememberCoroutineScope()
    
    LaunchedEffect(Unit) {
        if (videoPath == null) {
            val intent = (context as? android.app.Activity)?.intent
            currentVideoPath = when {
                intent?.action == Intent.ACTION_VIEW -> intent.data?.toString()
                intent?.action == Intent.ACTION_SEND -> 
                    intent.getParcelableExtra<Uri>(Intent.EXTRA_STREAM)?.toString()
                else -> null
            }
        }
    }
    
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
        // LAYER 1: Video surface (bottom)
        AndroidView(
            factory = { ctx ->
                SimpleMPVView(ctx).apply {
                    mpvView = this
                    mpvInstance = this.mpv
                    
                    val filesDir = ctx.filesDir.path
                    val cacheDir = File(ctx.cacheDir, "mpv").apply { mkdirs() }.path
                    initialize(filesDir, cacheDir)
                    
                    currentVideoPath?.let { path ->
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
        
        // LAYER 2 & 3: Overlay (thumbnail + UI)
        if (isVideoLoaded && mpvInstance != null) {
            PlayerOverlay(
                mpv = mpvInstance!!,
                videoPath = currentVideoPath ?: "",
                modifier = Modifier.fillMaxSize()
            )
        }
    }
}

// ==================== PLAYER OVERLAY ====================

@OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)
@Composable
fun PlayerOverlay(
    mpv: MPV,
    videoPath: String,
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
    val seekThrottleMs = 16L
    
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
    
    // Thumbnail preview state
    var showThumbnail by remember { mutableStateOf(false) }
    var thumbnailBitmap by remember { mutableStateOf<Bitmap?>(null) }
    
    // Track if duration is valid
    var isDurationValid by remember { mutableStateOf(false) }
    
    // Thumbnail system
    val thumbnailSystem = remember(videoPath, seekbarDuration) {
        ThumbnailSystem(context, videoPath, seekbarDuration).apply {
            onThumbnailReady = { seconds, bitmap ->
                if (isSeeking && abs(seconds - seekbarPosition) < 0.1) {
                    thumbnailBitmap = bitmap
                    showThumbnail = true
                    Log.d("ThumbnailUI", "Thumbnail ready and showing for: ${seconds}s")
                }
            }
        }
    }
    
    // Force generate some initial thumbnails for testing
    LaunchedEffect(Unit) {
        delay(500)
        thumbnailSystem.getThumbnailForPosition(0.0)
        thumbnailSystem.getThumbnailForPosition(5.0)
        thumbnailSystem.getThumbnailForPosition(10.0)
        thumbnailSystem.getThumbnailForPosition(30.0)
        thumbnailSystem.getThumbnailForPosition(60.0)
        Log.d("ThumbnailUI", "Initial thumbnails requested")
    }
    
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
    
    // Wait for valid duration
    LaunchedEffect(Unit) {
        var duration = 0.0
        var attempts = 0
        while (duration <= 1.0 && attempts < 30) {
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
    }
    
    fun showPlaybackFeedback(text: String) {
        showPlaybackFeedback = true
        playbackFeedbackText = text
        coroutineScope.launch {
            delay(1000)
            showPlaybackFeedback = false
        }
    }
    
    fun performRealTimeSeek(targetPosition: Double) {
        if (isSeekInProgress) return
        isSeekInProgress = true
        
        // Update UI
        seekTargetTime = formatTimeSimple(targetPosition)
        currentTime = formatTimeSimple(targetPosition)
        
        // Try to get thumbnail
        thumbnailSystem.getThumbnailForPosition(targetPosition)?.let {
            thumbnailBitmap = it
            showThumbnail = true
            Log.d("ThumbnailUI", "Showing thumbnail at: ${targetPosition}s")
        }
        
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
        coroutineScope.launch {
            delay(1000)
            showQuickSeekFeedback = false
        }
        
        val newPos = (currentPos + seconds).coerceIn(0.0, duration)
        seekbarPosition = newPos.toFloat()
        currentTime = formatTimeSimple(newPos)
        seekTargetTime = formatTimeSimple(newPos)
        
        // Try to get thumbnail
        thumbnailSystem.getThumbnailForPosition(newPos)?.let {
            thumbnailBitmap = it
            showThumbnail = true
            Log.d("ThumbnailUI", "Showing thumbnail after quick seek: ${newPos}s")
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
        
        // Try to get thumbnail at current position
        thumbnailSystem.getThumbnailForPosition(seekStartPosition)?.let {
            thumbnailBitmap = it
            showThumbnail = true
            Log.d("ThumbnailUI", "Showing thumbnail at start: ${seekStartPosition}s")
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
        val pixelsPerSecond = 4f / 0.032f
        val timeDeltaSeconds = deltaX / pixelsPerSecond
        val newPositionSeconds = seekStartPosition + timeDeltaSeconds
        val duration = mpv.getPropertyDouble("duration") ?: 0.0
        val clampedPosition = newPositionSeconds.coerceIn(0.0, duration)
        
        seekDirection = if (deltaX > 0) "+" else "-"
        seekTargetTime = formatTimeSimple(clampedPosition)
        currentTime = formatTimeSimple(clampedPosition)
        seekbarPosition = clampedPosition.toFloat()
        
        // Update thumbnail
        thumbnailSystem.getThumbnailForPosition(clampedPosition)?.let {
            thumbnailBitmap = it
            showThumbnail = true
            Log.d("ThumbnailUI", "Showing thumbnail at: ${clampedPosition}s")
        }
        
        // Update window center for background generation
        thumbnailSystem.setCurrentPosition(clampedPosition)
    }
    
    fun endHorizontalSeeking() {
        if (isSeeking) {
            val currentPos = mpv.getPropertyDouble("time-pos") ?: seekStartPosition
            
            // Single seek at the end
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
            showThumbnail = false
            Log.d("ThumbnailUI", "Hiding thumbnail")
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
        
        // Show thumbnail
        thumbnailSystem.getThumbnailForPosition(targetPosition)?.let {
            thumbnailBitmap = it
            showThumbnail = true
            Log.d("ThumbnailUI", "Showing thumbnail at: ${targetPosition}s")
        }
        
        // Update window center
        thumbnailSystem.setCurrentPosition(targetPosition)
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
        showThumbnail = false
        wasPlayingBeforeSeek = false
        seekDirection = ""
        Log.d("ThumbnailUI", "Hiding thumbnail")
    }
    
    // Cleanup
    DisposableEffect(Unit) {
        onDispose {
            thumbnailSystem.cleanup()
        }
    }
    
    val videoInfoTextAlpha = if (isSeeking || isDragging) 0.0f else 1.0f
    val videoInfoBackgroundAlpha = if (isSeeking || isDragging) 0.0f else 0.8f
    val timeDisplayTextAlpha = if (isSeeking || isDragging) 0.0f else 1.0f
    val timeDisplayBackgroundAlpha = if (isSeeking || isDragging) 0.0f else 0.8f
    
    Box(modifier = modifier.fillMaxSize()) {
        // LAYER 2: Thumbnail preview (middle layer)
        if (showThumbnail && thumbnailBitmap != null) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = 0.5f)) // Dim background
            ) {
                val screenWidth = LocalConfiguration.current.screenWidthDp.dp
                val previewX = (seekbarPosition / seekbarDuration) * (screenWidth.value - 120f)
                
                Box(
                    modifier = Modifier
                        .offset(
                            x = previewX.dp - 80.dp,
                            y = (-200).dp
                        )
                        .align(Alignment.BottomStart)
                ) {
                    androidx.compose.foundation.Image(
                        bitmap = thumbnailBitmap!!.asImageBitmap(),
                        contentDescription = "Preview",
                        modifier = Modifier
                            .size(160.dp, 90.dp)
                            .border(2.dp, Color.White)
                            .background(Color.Black)
                            .clip(RoundedCornerShape(4.dp))
                    )
                    
                    Text(
                        text = seekTargetTime,
                        style = TextStyle(
                            color = Color.White,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold
                        ),
                        modifier = Modifier
                            .align(Alignment.BottomCenter)
                            .offset(y = (-4).dp)
                            .background(Color.Black.copy(alpha = 0.7f))
                            .padding(horizontal = 4.dp, vertical = 2.dp)
                            .clip(RoundedCornerShape(2.dp))
                    )
                }
            }
        }
        
        // LAYER 3: Player UI (top layer)
        Box(modifier = Modifier.fillMaxSize()) {
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
                        .padding(bottom = 20.dp)
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
}

// ==================== PROGRESS BAR ====================

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
                            hasPassedThreshold = false
                        },
                        onDrag = { change, _ ->
                            change.consume()
                            val currentX = change.position.x
                            val totalMovementX = currentX - dragStartX
                            
                            if (!hasPassedThreshold) {
                                if (abs(totalMovementX) > movementThresholdPx) {
                                    hasPassedThreshold = true
                                } else {
                                    return@detectDragGestures
                                }
                            }
                            
                            val thresholdDirection = totalMovementX.sign
                            val movementBeyondThreshold = abs(totalMovementX) - movementThresholdPx
                            val effectiveMovement = if (movementBeyondThreshold > 0) {
                                thresholdDirection * movementBeyondThreshold
                            } else {
                                0f
                            }
                            
                            val deltaPosition = (effectiveMovement / size.width) * safeDuration
                            val newPosition = (savedPositionAtTouch + deltaPosition)
                                .coerceIn(0f, safeDuration)
                            
                            val roundedPosition = (newPosition + 0.5f).toInt().toFloat()
                            
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

// ==================== UTILITIES ====================

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
