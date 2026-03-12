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
        
        // 20-second window at 24fps
        const val WINDOW_SIZE = 20      // 20 seconds total (-10s, current, +10s)
        const val HIGH_RES_RATE = 24    // 24 thumbnails per second
        val HIGH_RES_INTERVAL = 1.0 / HIGH_RES_RATE  // ~0.0417s
        
        // Memory calculation:
        // 20 seconds × 24fps = 480 thumbnails in window
        // 480 × 205KB = 98.4MB total in memory
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
        startWindowTracking()
        startGridGeneration()
    }
    
    // ========== TIER 1: Grid Generation (Every 1 second) ==========
    
    private fun startGridGeneration() {
        scope.launch {
            val totalSeconds = durationSeconds.toInt()
            
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
        if (outputFile.exists()) return
        if (!generatingPositions.add(second)) return
        
        extractThumbnail(second.toDouble())?.let { bitmap ->
            withContext(Dispatchers.IO) {
                outputFile.outputStream().use {
                    bitmap.compress(Bitmap.CompressFormat.JPEG, 85, it)
                }
                bitmap.recycle()
            }
        }
        
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
                e.printStackTrace()
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
            BitmapFactory.decodeFile(file.path)
        } else {
            getHighResThumbnail(seconds)
        }
    }
    
    fun getHighResThumbnail(seconds: Double): Bitmap? {
        val positionKey = (seconds * 1000).toInt()
        return windowCache[positionKey]
    }
    
    fun onSwipePosition(seconds: Double, callback: (Bitmap?) -> Unit) {
        setCurrentPosition(seconds)
        
        getHighResThumbnail(seconds)?.let {
            callback(it)
        }
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
    var showSeekbar by remember { mutableStateOf(true) }
    var showVideoInfo by remember { mutableStateOf(true) }
    var fileName by remember { mutableStateOf("RTFP") }
    
    var seekbarPosition by remember { mutableStateOf(0f) }
    var seekbarDuration by remember { mutableStateOf(1f) }
    var isDragging by remember { mutableStateOf(false) }
    var isDurationValid by remember { mutableStateOf(false) }
    
    var isSeeking by remember { mutableStateOf(false) }
    var seekStartX by remember { mutableStateOf(0f) }
    var seekStartPosition by remember { mutableStateOf(0.0) }
    var wasPlayingBeforeSeek by remember { mutableStateOf(false) }
    var seekDirection by remember { mutableStateOf("") }
    
    var touchStartTime by remember { mutableStateOf(0L) }
    var touchStartX by remember { mutableStateOf(0f) }
    var touchStartY by remember { mutableStateOf(0f) }
    var isTouching by remember { mutableStateOf(false) }
    var isLongTap by remember { mutableStateOf(false) }
    var isHorizontalSwipe by remember { mutableStateOf(false) }
    var isVerticalSwipe by remember { mutableStateOf(false) }
    
    var showPlaybackFeedback by remember { mutableStateOf(false) }
    var playbackFeedbackText by remember { mutableStateOf("") }
    var showQuickSeekFeedback by remember { mutableStateOf(false) }
    var quickSeekFeedbackText by remember { mutableStateOf("") }
    var isSpeedingUp by remember { mutableStateOf(false) }
    
    var showThumbnail by remember { mutableStateOf(false) }
    var thumbnailBitmap by remember { mutableStateOf<Bitmap?>(null) }
    
    val longTapThreshold = 300L
    val horizontalSwipeThreshold = 30f
    val verticalSwipeThreshold = 40f
    val maxVerticalMovement = 50f
    val maxHorizontalMovement = 50f
    val quickSeekAmount = 5
    
    val thumbnailSystem = remember(videoPath, seekbarDuration) {
        ThumbnailSystem(context, videoPath, seekbarDuration).apply {
            onThumbnailReady = { seconds, bitmap ->
                if (isSeeking && abs(seconds - seekbarPosition) < 0.1) {
                    thumbnailBitmap = bitmap
                    showThumbnail = true
                }
            }
        }
    }
    
    LaunchedEffect(showSeekbar, isSeeking) {
        if (showSeekbar && !isSeeking) {
            delay(4000)
            if (!isSeeking) {
                showSeekbar = false
                showVideoInfo = false
            }
        }
    }
    
    LaunchedEffect(Unit) {
        var duration = 0.0
        var attempts = 0
        while (duration <= 1.0 && attempts < 30) {
            duration = mpv.getPropertyDouble("duration") ?: 0.0
            if (duration > 1.0) {
                seekbarDuration = duration.toFloat()
                totalTime = formatTimeSimple(duration)
                isDurationValid = true
                break
            }
            delay(100)
            attempts++
        }
    }
    
    LaunchedEffect(isDurationValid) {
        if (!isDurationValid) return@LaunchedEffect
        
        while (true) {
            val currentPos = mpv.getPropertyDouble("time-pos") ?: 0.0
            
            if (!isSeeking && !isDragging) {
                currentTime = formatTimeSimple(currentPos)
                seekbarPosition = currentPos.toFloat()
            }
            
            delay(100)
        }
    }
    
    LaunchedEffect(Unit) {
        fileName = getBestAvailableFileName(context, mpv)
        showSeekbar = true
        showVideoInfo = true
    }
    
    LaunchedEffect(isSpeedingUp) {
        mpv.setPropertyDouble("speed", if (isSpeedingUp) 2.0 else 1.0)
    }
    
    fun showTemporaryFeedback(text: String) {
        playbackFeedbackText = text
        showPlaybackFeedback = true
        coroutineScope.launch {
            delay(1000)
            showPlaybackFeedback = false
        }
    }
    
    fun handleTap() {
        val isPaused = mpv.getPropertyBoolean("pause") == true
        if (isPaused) {
            coroutineScope.launch {
                val currentPos = mpv.getPropertyDouble("time-pos") ?: 0.0
                mpv.command("seek", currentPos.toString(), "absolute", "exact")
                delay(50)
                mpv.setPropertyBoolean("pause", false)
            }
            showTemporaryFeedback("Resume")
        } else {
            mpv.setPropertyBoolean("pause", true)
            showTemporaryFeedback("Pause")
        }
        showSeekbar = true
        showVideoInfo = true
    }
    
    fun startHorizontalSeeking(startX: Float) {
        isHorizontalSwipe = true
        isSeeking = true
        seekStartX = startX
        seekStartPosition = mpv.getPropertyDouble("time-pos") ?: 0.0
        wasPlayingBeforeSeek = mpv.getPropertyBoolean("pause") == false
        showSeekTime = true
        showSeekbar = true
        showVideoInfo = true
        
        if (wasPlayingBeforeSeek) {
            mpv.setPropertyBoolean("pause", true)
        }
        
        thumbnailSystem.setCurrentPosition(seekStartPosition)
    }
    
    fun handleHorizontalSeeking(currentX: Float) {
        if (!isSeeking) return
        
        val deltaX = currentX - seekStartX
        val pixelsPerSecond = 4f / 0.032f
        val timeDeltaSeconds = deltaX / pixelsPerSecond
        val newPositionSeconds = seekStartPosition + timeDeltaSeconds
        val clampedPosition = newPositionSeconds.coerceIn(0.0, seekbarDuration.toDouble())
        
        seekDirection = if (deltaX > 0) "+" else "-"
        seekTargetTime = formatTimeSimple(clampedPosition)
        currentTime = formatTimeSimple(clampedPosition)
        seekbarPosition = clampedPosition.toFloat()
        
        thumbnailSystem.onSwipePosition(clampedPosition) { bitmap ->
            if (bitmap != null) {
                thumbnailBitmap = bitmap
                showThumbnail = true
            }
        }
        
        thumbnailSystem.setCurrentPosition(clampedPosition)
    }
    
    fun endHorizontalSeeking() {
        if (isSeeking) {
            mpv.command("seek", seekbarPosition.toString(), "absolute", "exact")
            
            if (wasPlayingBeforeSeek) {
                coroutineScope.launch {
                    delay(100)
                    mpv.setPropertyBoolean("pause", false)
                }
            }
            
            isSeeking = false
            isHorizontalSwipe = false
            showSeekTime = false
            showThumbnail = false
        }
    }
    
    fun handleProgressBarDrag(newPosition: Float) {
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
        
        thumbnailSystem.getGridThumbnail(targetPosition)?.let {
            thumbnailBitmap = it
            showThumbnail = true
        }
        
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
    }
    
    fun startLongTapDetection() {
        isTouching = true
        touchStartTime = System.currentTimeMillis()
        coroutineScope.launch {
            delay(longTapThreshold)
            if (isTouching && !isHorizontalSwipe && !isVerticalSwipe) {
                isLongTap = true
                isSpeedingUp = true
            }
        }
    }
    
    fun startVerticalSwipe(startY: Float) {
        isVerticalSwipe = true
        val deltaY = startY - touchStartY
        val seekAmount = if (deltaY < 0) quickSeekAmount else -quickSeekAmount
        quickSeekFeedbackText = if (seekAmount > 0) "+$seekAmount" else "$seekAmount"
        showQuickSeekFeedback = true
        coroutineScope.launch {
            delay(1000)
            showQuickSeekFeedback = false
        }
        mpv.command("seek", seekAmount.toString(), "relative", "exact")
    }
    
    fun endTouch() {
        val touchDuration = System.currentTimeMillis() - touchStartTime
        isTouching = false
        
        when {
            isLongTap -> {
                isLongTap = false
                isSpeedingUp = false
            }
            isHorizontalSwipe -> endHorizontalSeeking()
            isVerticalSwipe -> isVerticalSwipe = false
            touchDuration < 150 -> handleTap()
        }
        
        isHorizontalSwipe = false
        isVerticalSwipe = false
    }
    
    DisposableEffect(Unit) {
        onDispose {
            thumbnailSystem.cleanup()
        }
    }
    
    val videoInfoAlpha = if (isSeeking || isDragging) 0.0f else 1.0f
    val videoInfoBgAlpha = if (isSeeking || isDragging) 0.0f else 0.8f
    
    Box(modifier = modifier.fillMaxSize()) {
        Box(
            modifier = Modifier
                .fillMaxSize()
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
                                val deltaX = abs(event.x - touchStartX)
                                val deltaY = abs(event.y - touchStartY)
                                
                                when {
                                    deltaX > horizontalSwipeThreshold && deltaX > deltaY -> 
                                        startHorizontalSeeking(event.x)
                                    deltaY > verticalSwipeThreshold && deltaY > deltaX -> 
                                        startVerticalSwipe(event.y)
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
        
        if (showThumbnail && thumbnailBitmap != null) {
            val screenWidth = LocalConfiguration.current.screenWidthDp.dp
            val previewX = (seekbarPosition / seekbarDuration) * (screenWidth.value - 120f)
            
            Box(
                modifier = Modifier
                    .offset(
                        x = previewX.dp - 80.dp,
                        y = (-160).dp
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
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Bold
                    ),
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .offset(y = (-4).dp)
                        .background(Color.Black.copy(alpha = 0.7f))
                        .padding(horizontal = 8.dp, vertical = 4.dp)
                        .clip(RoundedCornerShape(4.dp))
                )
            }
        }
        
        if (showVideoInfo) {
            Text(
                text = fileName,
                style = TextStyle(
                    color = Color.White.copy(alpha = videoInfoAlpha),
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Medium
                ),
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .offset(x = 60.dp, y = 20.dp)
                    .background(Color.DarkGray.copy(alpha = videoInfoBgAlpha))
                    .padding(horizontal = 16.dp, vertical = 6.dp)
            )
        }
        
        if (showSeekbar) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(70.dp)
                    .align(Alignment.BottomStart)
                    .padding(horizontal = 60.dp)
                    .padding(bottom = 20.dp)  // FIXED: Separated padding parameters
            ) {
                Column(
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        Text(
                            text = "$currentTime / $totalTime",
                            style = TextStyle(
                                color = Color.White,
                                fontSize = 14.sp,
                                fontWeight = FontWeight.Medium
                            ),
                            modifier = Modifier
                                .background(Color.DarkGray.copy(alpha = 0.8f))
                                .padding(horizontal = 12.dp, vertical = 4.dp)
                        )
                    }
                    
                    if (isDurationValid) {
                        SimpleDraggableProgressBar(
                            position = seekbarPosition,
                            duration = seekbarDuration,
                            onValueChange = { handleProgressBarDrag(it) },
                            onValueChangeFinished = { handleDragFinished() },
                            getFreshPosition = { 
                                (mpv.getPropertyDouble("time-pos") ?: 0.0).toFloat() 
                            },
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(48.dp)
                        )
                    }
                }
            }
        }
        
        Box(modifier = Modifier.align(Alignment.TopCenter).offset(y = 80.dp)) {
            when {
                isSpeedingUp -> Text(
                    text = "2X",
                    style = TextStyle(color = Color.White, fontSize = 24.sp, fontWeight = FontWeight.Bold),
                    modifier = Modifier.background(Color.Black.copy(alpha = 0.7f)).padding(12.dp)
                )
                showQuickSeekFeedback -> Text(
                    text = quickSeekFeedbackText,
                    style = TextStyle(color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.Bold),
                    modifier = Modifier.background(Color.Black.copy(alpha = 0.7f)).padding(12.dp)
                )
                showSeekTime && !showThumbnail -> Text(
                    text = seekTargetTime,
                    style = TextStyle(color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.Bold),
                    modifier = Modifier.background(Color.Black.copy(alpha = 0.7f)).padding(12.dp)
                )
                showPlaybackFeedback -> Text(
                    text = playbackFeedbackText,
                    style = TextStyle(color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.Bold),
                    modifier = Modifier.background(Color.Black.copy(alpha = 0.7f)).padding(12.dp)
                )
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
                            } else 0f
                            
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
