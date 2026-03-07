// Player.kt
package com.rtfp

import android.Manifest
import android.content.ContentResolver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.OpenableColumns
import android.util.AttributeSet
import android.util.Log
import android.view.MotionEvent
import android.view.Surface
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.Toast
import androidx.activity.compose.setContent
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerId
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.VideoSize
import androidx.media3.exoplayer.ExoPlayer
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.Job
import java.io.BufferedReader
import java.io.File
import java.io.FileOutputStream
import java.io.FileReader
import java.io.PrintWriter
import java.io.StringWriter
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.abs
import android.graphics.Color as AwtColor  // Rename to avoid conflict with Compose Color

// ------------------------------------------------------------------------
// AspectRatioSurfaceView
// ------------------------------------------------------------------------
class AspectRatioSurfaceView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : SurfaceView(context, attrs, defStyleAttr) {

    var videoWidth: Int = 0
    var videoHeight: Int = 0

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        if (videoWidth <= 0 || videoHeight <= 0) {
            super.onMeasure(widthMeasureSpec, heightMeasureSpec)
            return
        }

        val viewWidth = MeasureSpec.getSize(widthMeasureSpec)
        val viewHeight = MeasureSpec.getSize(heightMeasureSpec)

        val aspectRatio = videoWidth.toFloat() / videoHeight.toFloat()

        var newWidth: Int
        var newHeight: Int

        if (viewWidth / aspectRatio <= viewHeight) {
            newWidth = viewWidth
            newHeight = (viewWidth / aspectRatio).toInt()
        } else {
            newHeight = viewHeight
            newWidth = (viewHeight * aspectRatio).toInt()
        }

        setMeasuredDimension(newWidth, newHeight)
    }
}

// ------------------------------------------------------------------------
// PlayerActivity with Compose overlay
// ------------------------------------------------------------------------
class PlayerActivity : AppCompatActivity() {

    // UI state
    private lateinit var surfaceView: AspectRatioSurfaceView
    private var errorLogVisible by mutableStateOf(false)
    private var logText by mutableStateOf("")

    // Player state (make isPlaying public so Compose can access it)
    var isPlaying = false
        private set

    private var exoPlayer: ExoPlayer? = null
    private var videoUri: Uri? = null
    private var nativeLibraryLoaded = false
    private var savedPosition: Long = 0

    // FFmpeg native methods
    private external fun nativeOpenFile(path: String): Long
    private external fun nativeSeekTo(handle: Long, timestampUs: Long): Int
    private external fun nativeGetFrameRGBA(handle: Long): ByteArray?
    private external fun nativeGetWidth(handle: Long): Int
    private external fun nativeGetHeight(handle: Long): Int
    private external fun nativeClose(handle: Long)

    private var ffmpegHandle: Long = 0
    private var isFfmpegMode = false
    private var videoWidth = 0
    private var videoHeight = 0

    // Drag seeking state
    private var isDragging = false
    private var dragStartPositionMs = 0L
    private var currentDragPositionMs = 0L
    private var wasPlayingBeforeSeek = false

    // File logging
    private lateinit var logFile: File
    private val logLock = Any()
    private val isLoggingEnabled = AtomicBoolean(true)

    // Surface attachment state
    private var surfaceAttached = true

    companion object {
        private const val PERMISSION_REQUEST_READ_STORAGE = 100
        private const val TAG = "RTFP"
        private const val LOG_FILE_NAME = "rtfp_log.txt"
    }

    // ------------------------------------------------------------------------
    // Fullscreen helper
    // ------------------------------------------------------------------------
    private fun hideSystemUI() {
        window.addFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.KITKAT) {
            @Suppress("DEPRECATION")
            window.decorView.systemUiVisibility = (
                View.SYSTEM_UI_FLAG_FULLSCREEN
                or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                or View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                )
        }
    }

    // ------------------------------------------------------------------------
    // Native library loading
    // ------------------------------------------------------------------------
    private fun loadNativeLibrary(): Boolean {
        return try {
            val abi = Build.SUPPORTED_ABIS.joinToString(", ")
            logToFile("Device ABIs: $abi")
            System.loadLibrary("ffmpeg_wrapper")
            logToFile("Native library loaded successfully")
            true
        } catch (e: UnsatisfiedLinkError) {
            logToFile("Failed to load native library: ${e.message}")
            val sw = StringWriter()
            val pw = PrintWriter(sw)
            e.printStackTrace(pw)
            logToFile(sw.toString())
            false
        } catch (e: Exception) {
            logToFile("Unexpected error: ${e.message}")
            false
        }
    }

    // ------------------------------------------------------------------------
    // Surface management
    // ------------------------------------------------------------------------
    private fun detachSurface() {
        if (surfaceAttached) {
            exoPlayer?.clearVideoSurface()
            surfaceAttached = false
            logToFile("Surface detached from ExoPlayer")
        }
    }

    private fun reattachSurface() {
        if (!surfaceAttached && surfaceView.holder.surface?.isValid == true) {
            exoPlayer?.setVideoSurface(surfaceView.holder.surface)
            surfaceAttached = true
            logToFile("Surface reattached to ExoPlayer")
        }
    }

    // ------------------------------------------------------------------------
    // Playback control (called from Compose)
    // ------------------------------------------------------------------------
    fun togglePlayPause() {
        exoPlayer?.let {
            if (it.isPlaying) {
                it.pause()
                isPlaying = false
                logToFile("Paused")
            } else {
                it.play()
                isPlaying = true
                logToFile("Played")
            }
        }
    }

    fun setSpeed(speed: Float) {
        exoPlayer?.setPlaybackSpeed(speed)
        logToFile("Speed set to $speed")
    }

    fun seekTo(positionMs: Long) {
        exoPlayer?.seekTo(positionMs)
    }

    fun getCurrentPosition(): Long = exoPlayer?.currentPosition ?: 0
    fun getDuration(): Long = exoPlayer?.duration ?: 0

    // ------------------------------------------------------------------------
    // FFmpeg seek mode
    // ------------------------------------------------------------------------
    fun startFfmpegSeekMode() {
        if (!nativeLibraryLoaded || ffmpegHandle == 0L) {
            logToFile("FFmpeg not ready for seek")
            return
        }
        exoPlayer?.pause()
        detachSurface()
        isFfmpegMode = true
        isDragging = true
        dragStartPositionMs = exoPlayer?.currentPosition ?: 0
        currentDragPositionMs = dragStartPositionMs
        wasPlayingBeforeSeek = isPlaying
        logToFile("FFmpeg seek mode started")
    }

    fun updateFfmpegFrame(targetPositionMs: Long) {
        if (!isFfmpegMode || ffmpegHandle == 0L) return
        val timestampUs = targetPositionMs * 1000
        try {
            val result = nativeSeekTo(ffmpegHandle, timestampUs)
            if (result == 0) {
                val rgba = nativeGetFrameRGBA(ffmpegHandle)
                if (rgba != null && videoWidth > 0 && videoHeight > 0) {
                    val expected = videoWidth * videoHeight * 4
                    if (rgba.size == expected) {
                        val bitmap = Bitmap.createBitmap(videoWidth, videoHeight, Bitmap.Config.ARGB_8888)
                        val buffer = ByteBuffer.wrap(rgba).order(ByteOrder.nativeOrder())
                        bitmap.copyPixelsFromBuffer(buffer)
                        drawBitmapOnSurface(bitmap)
                        bitmap.recycle()
                    }
                }
            }
        } catch (e: Exception) {
            logToFile("FFmpeg frame error: ${e.message}")
        }
    }

    fun endFfmpegSeekMode(finalPositionMs: Long) {
        if (isFfmpegMode) {
            isFfmpegMode = false
            isDragging = false
            reattachSurface()
            exoPlayer?.seekTo(finalPositionMs)
            if (wasPlayingBeforeSeek) {
                exoPlayer?.play()
                isPlaying = true
            }
            logToFile("Seek ended at ${finalPositionMs}ms")
        }
    }

    // ------------------------------------------------------------------------
    // Drawing on surface
    // ------------------------------------------------------------------------
    private fun drawBitmapOnSurface(bitmap: Bitmap) {
        val surface = surfaceView.holder.surface ?: return
        var canvas: Canvas? = null
        try {
            canvas = surface.lockCanvas(null) ?: return
            canvas.drawBitmap(bitmap, null, Rect(0, 0, canvas.width, canvas.height), null)
            surface.unlockCanvasAndPost(canvas)
        } catch (e: Exception) {
            logToFile("Draw error: ${e.message}")
            if (canvas != null) {
                try { surface.unlockCanvasAndPost(canvas) } catch (_: Exception) {}
            }
        }
    }

    // ------------------------------------------------------------------------
    // File logging
    // ------------------------------------------------------------------------
    private fun logToFile(message: String) {
        if (!isLoggingEnabled.get()) return
        synchronized(logLock) {
            try {
                logFile.appendText("${SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())}: $message\n")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to write log", e)
            }
        }
        Log.d(TAG, message)
        runOnUiThread { logText += "$message\n" }
    }

    private fun logToFile(throwable: Throwable) {
        val sw = StringWriter()
        val pw = PrintWriter(sw)
        throwable.printStackTrace(pw)
        logToFile(sw.toString())
    }

    private fun loadPreviousLogs() {
        try {
            if (logFile.exists()) {
                BufferedReader(FileReader(logFile)).use { reader ->
                    reader.forEachLine { line -> logText += "$line\n" }
                }
                logText += "--- End of previous log ---\n"
            } else {
                logText = "No previous log.\n"
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load logs", e)
        }
    }

    // ------------------------------------------------------------------------
    // Permission handling
    // ------------------------------------------------------------------------
    private fun requestStoragePermissionIfNeeded() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_EXTERNAL_STORAGE)
                != PackageManager.PERMISSION_GRANTED
            ) {
                ActivityCompat.requestPermissions(
                    this,
                    arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE),
                    PERMISSION_REQUEST_READ_STORAGE
                )
            }
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == PERMISSION_REQUEST_READ_STORAGE) {
            if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                logToFile("Storage permission granted")
            } else {
                logToFile("Storage permission denied")
                Toast.makeText(this, "Storage permission denied", Toast.LENGTH_SHORT).show()
            }
        }
    }

    // ------------------------------------------------------------------------
    // Intent handling
    // ------------------------------------------------------------------------
    private fun handleIntent(intent: Intent) {
        if (intent.action == Intent.ACTION_VIEW) {
            val uri = intent.data ?: return
            logToFile("Opening URI: $uri")
            openVideo(uri)
        }
    }

    private fun openVideo(uri: Uri) {
        videoUri = uri
        if (exoPlayer == null) setupExoPlayer()

        val mediaItem = MediaItem.fromUri(uri)
        exoPlayer?.setMediaItem(mediaItem)
        exoPlayer?.prepare()
        exoPlayer?.playWhenReady = true
        isPlaying = true
        logToFile("ExoPlayer prepared")

        if (nativeLibraryLoaded) {
            try {
                val file = uriToFile(uri)
                if (file != null && file.exists()) {
                    logToFile("Opening for FFmpeg: ${file.absolutePath}")
                    if (ffmpegHandle != 0L) nativeClose(ffmpegHandle)
                    ffmpegHandle = nativeOpenFile(file.absolutePath)
                    if (ffmpegHandle == 0L) {
                        logToFile("FFmpeg open failed")
                        Toast.makeText(this, "FFmpeg init failed", Toast.LENGTH_SHORT).show()
                    } else {
                        videoWidth = nativeGetWidth(ffmpegHandle)
                        videoHeight = nativeGetHeight(ffmpegHandle)
                        logToFile("FFmpeg ready: ${videoWidth}x${videoHeight}")
                        Toast.makeText(this, "FFmpeg ready", Toast.LENGTH_SHORT).show()
                    }
                } else {
                    logToFile("No local file for FFmpeg")
                }
            } catch (e: Exception) {
                logToFile("FFmpeg setup exception: ${e.message}")
            }
        } else {
            logToFile("Native library not loaded")
        }
    }

    private fun uriToFile(uri: Uri): File? {
        return when (uri.scheme) {
            ContentResolver.SCHEME_FILE -> File(uri.path!!)
            ContentResolver.SCHEME_CONTENT -> {
                try {
                    val cursor = contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
                    cursor?.use {
                        if (it.moveToFirst()) {
                            val name = it.getString(it.getColumnIndex(OpenableColumns.DISPLAY_NAME))
                            val input = contentResolver.openInputStream(uri) ?: return null
                            val out = File(cacheDir, "video_$name")
                            FileOutputStream(out).use { output -> input.copyTo(output) }
                            logToFile("Copied to cache: ${out.absolutePath}")
                            return out
                        }
                    }
                } catch (e: Exception) {
                    logToFile("Copy failed: ${e.message}")
                }
                null
            }
            else -> null
        }
    }

    // ------------------------------------------------------------------------
    // ExoPlayer setup
    // ------------------------------------------------------------------------
    private fun setupExoPlayer() {
        exoPlayer = ExoPlayer.Builder(this).build().apply {
            surfaceView.holder.addCallback(object : SurfaceHolder.Callback {
                override fun surfaceCreated(holder: SurfaceHolder) {
                    logToFile("Surface created")
                    if (surfaceAttached) setVideoSurface(holder.surface)
                }
                override fun surfaceChanged(h: SurfaceHolder, f: Int, w: Int, hh: Int) {
                    logToFile("Surface changed: ${w}x$hh")
                }
                override fun surfaceDestroyed(holder: SurfaceHolder) {
                    logToFile("Surface destroyed")
                    clearVideoSurface()
                    surfaceAttached = false
                }
            })

            addListener(object : Player.Listener {
                override fun onPlaybackStateChanged(state: Int) {
                    logToFile("Playback state: $state")
                }
                override fun onPlayerError(error: PlaybackException) {
                    logToFile("ExoPlayer error: ${error.message}")
                    logToFile(error)
                    Toast.makeText(this@PlayerActivity, "Playback error", Toast.LENGTH_SHORT).show()
                }
                override fun onVideoSizeChanged(videoSize: VideoSize) {
                    logToFile("Video size: ${videoSize.width}x${videoSize.height}")
                    surfaceView.videoWidth = videoSize.width
                    surfaceView.videoHeight = videoSize.height
                    surfaceView.requestLayout()
                }
            })
        }
        surfaceAttached = true
    }

    private fun releasePlayer() {
        exoPlayer?.let {
            savedPosition = it.currentPosition
            it.release()
            exoPlayer = null
            logToFile("ExoPlayer released")
        }
        surfaceAttached = false
    }

    // ------------------------------------------------------------------------
    // Activity lifecycle
    // ------------------------------------------------------------------------
    override fun onCreate(savedInstanceState: Bundle?) {
        logFile = File(cacheDir, LOG_FILE_NAME)

        // Set uncaught exception handler
        val defaultHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            logToFile("*** CRASH *** Thread: ${thread.name}")
            logToFile(throwable)
            defaultHandler?.uncaughtException(thread, throwable)
        }

        super.onCreate(savedInstanceState)
        nativeLibraryLoaded = loadNativeLibrary()
        hideSystemUI()

        if (savedInstanceState != null) {
            savedPosition = savedInstanceState.getLong("position", 0)
            logToFile("Restored position: $savedPosition")
        }

        // Create SurfaceView
        surfaceView = AspectRatioSurfaceView(this).apply {
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
            setBackgroundColor(AwtColor.BLACK)
        }

        // Set Compose content
        setContent {
            MaterialTheme {
                Box(modifier = Modifier.fillMaxSize()) {
                    // AndroidView to host SurfaceView
                    AndroidView(
                        factory = { surfaceView },
                        modifier = Modifier.fillMaxSize()
                    )

                    // Overlay with gestures and UI
                    PlayerOverlay(
                        player = this@PlayerActivity,
                        logText = logText,
                        onToggleLog = { errorLogVisible = !errorLogVisible },
                        logVisible = errorLogVisible,
                        modifier = Modifier.fillMaxSize()
                    )
                }
            }
        }

        loadPreviousLogs()
        requestStoragePermissionIfNeeded()
        handleIntent(intent)
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putLong("position", exoPlayer?.currentPosition ?: savedPosition)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    override fun onPause() {
        super.onPause()
        exoPlayer?.let {
            if (it.isPlaying) {
                it.pause()
                isPlaying = false
            }
            savedPosition = it.currentPosition
        }
        if (ffmpegHandle != 0L) {
            nativeClose(ffmpegHandle)
            ffmpegHandle = 0
        }
    }

    override fun onResume() {
        super.onResume()
        if (savedPosition > 0 && exoPlayer != null) {
            exoPlayer?.seekTo(savedPosition)
        }
        if (videoUri != null && nativeLibraryLoaded && ffmpegHandle == 0L) {
            val file = uriToFile(videoUri!!)
            if (file != null && file.exists()) {
                ffmpegHandle = nativeOpenFile(file.absolutePath)
                if (ffmpegHandle != 0L) {
                    videoWidth = nativeGetWidth(ffmpegHandle)
                    videoHeight = nativeGetHeight(ffmpegHandle)
                }
            }
        }
        if (!surfaceAttached && surfaceView.holder.surface?.isValid == true) {
            reattachSurface()
        }
        hideSystemUI()
    }

    override fun onDestroy() {
        super.onDestroy()
        releasePlayer()
        if (ffmpegHandle != 0L) {
            nativeClose(ffmpegHandle)
            ffmpegHandle = 0
        }
    }
}

// ------------------------------------------------------------------------
// Compose Overlay
// ------------------------------------------------------------------------
@Composable
fun PlayerOverlay(
    player: PlayerActivity,
    logText: String,
    onToggleLog: () -> Unit,
    logVisible: Boolean,
    modifier: Modifier = Modifier
) {
    val scope = rememberCoroutineScope()
    val configuration = LocalConfiguration.current
    val screenWidthPx = with(LocalDensity.current) { configuration.screenWidthDp.dp.toPx() }

    // Gesture state
    var isLongPressing by remember { mutableStateOf(false) }
    var isDragging by remember { mutableStateOf(false) }
    var dragStartX by remember { mutableFloatStateOf(0f) }
    var dragStartPositionMs by remember { mutableLongStateOf(0L) }
    var currentDragPositionMs by remember { mutableLongStateOf(0L) }
    var wasPlayingBeforeDrag by remember { mutableStateOf(false) }

    // UI state
    var showSeekbar by remember { mutableStateOf(true) }
    var showTimeOverlay by remember { mutableStateOf(false) }
    var seekTargetTime by remember { mutableStateOf("") }
    var currentTime by remember { mutableStateOf("00:00") }
    var totalTime by remember { mutableStateOf("00:00") }
    var seekDirection by remember { mutableStateOf("") }
    var feedbackText by remember { mutableStateOf("") }
    var showFeedback by remember { mutableStateOf(false) }

    // Update time periodically
    LaunchedEffect(Unit) {
        while (isActive) {
            if (!isDragging) {
                val pos = player.getCurrentPosition()
                val dur = player.getDuration()
                currentTime = formatTime(pos)
                totalTime = formatTime(dur)
            }
            delay(200)
        }
    }

    // Hide seekbar after inactivity
    LaunchedEffect(showSeekbar) {
        if (showSeekbar) {
            delay(4000)
            showSeekbar = false
        }
    }

    // Long press speed control
    LaunchedEffect(isLongPressing) {
        if (isLongPressing) {
            player.setSpeed(2.0f)
        } else {
            player.setSpeed(1.0f)
        }
    }

    Box(modifier = modifier) {
        // Gesture area
        Box(
            modifier = Modifier
                .fillMaxSize()
                .pointerInput(Unit) {
                    val longPressTimeout = 300L
                    val swipeThreshold = 100f
                    var downTime = 0L
                    var downX = 0f
                    var downY = 0f
                    var longPressJob: Job? = null
                    var dragActive = false
                    var verticalSwipeActive = false

                    awaitPointerEventScope {
                        while (true) {
                            val event = awaitPointerEvent()
                            event.changes.forEach { change ->
                                when {
                                    change.pressed && change.id == PointerId(0) -> {
                                        // Primary pointer down
                                        if (change.previousPressed == false) {
                                            downTime = System.currentTimeMillis()
                                            downX = change.position.x
                                            downY = change.position.y
                                            dragActive = false
                                            verticalSwipeActive = false
                                            // Start long press detection
                                            longPressJob?.cancel()
                                            longPressJob = this.launch {
                                                delay(longPressTimeout)
                                                if (!dragActive && !verticalSwipeActive) {
                                                    isLongPressing = true
                                                }
                                            }
                                        }

                                        // Move handling
                                        val deltaX = change.position.x - downX
                                        val deltaY = change.position.y - downY
                                        if (!dragActive && !verticalSwipeActive) {
                                            // Determine gesture type
                                            if (abs(deltaX) > abs(deltaY) && abs(deltaX) > 20) {
                                                // Horizontal drag started
                                                dragActive = true
                                                longPressJob?.cancel()
                                                dragStartX = downX
                                                dragStartPositionMs = player.getCurrentPosition()
                                                wasPlayingBeforeDrag = player.isPlaying
                                                player.startFfmpegSeekMode()
                                                isDragging = true
                                                showSeekbar = true
                                            } else if (abs(deltaY) > abs(deltaX) && abs(deltaY) > swipeThreshold) {
                                                // Vertical swipe
                                                verticalSwipeActive = true
                                                longPressJob?.cancel()
                                                if (deltaY < 0) {
                                                    val newPos = (player.getCurrentPosition() + 5000).coerceAtMost(player.getDuration())
                                                    player.seekTo(newPos)
                                                    feedbackText = "+5s"
                                                } else {
                                                    val newPos = (player.getCurrentPosition() - 5000).coerceAtLeast(0)
                                                    player.seekTo(newPos)
                                                    feedbackText = "-5s"
                                                }
                                                showFeedback = true
                                                scope.launch {
                                                    delay(1000)
                                                    showFeedback = false
                                                }
                                            }
                                        }

                                        // Ongoing drag
                                        if (dragActive) {
                                            val deltaMs = ((change.position.x - dragStartX) / screenWidthPx * player.getDuration()).toLong()
                                            val newPos = (dragStartPositionMs + deltaMs).coerceIn(0, player.getDuration())
                                            if (newPos != currentDragPositionMs) {
                                                currentDragPositionMs = newPos
                                                player.updateFfmpegFrame(newPos)
                                                seekTargetTime = formatTime(newPos)
                                                showTimeOverlay = true
                                                seekDirection = if (deltaX > 0) "+" else "-"
                                            }
                                        }
                                    }

                                    !change.pressed && change.id == PointerId(0) -> {
                                        // Primary pointer up
                                        longPressJob?.cancel()
                                        if (isLongPressing) {
                                            isLongPressing = false
                                        } else if (dragActive) {
                                            player.endFfmpegSeekMode(currentDragPositionMs)
                                            isDragging = false
                                            showTimeOverlay = false
                                        } else if (!verticalSwipeActive) {
                                            // Check for tap (short press)
                                            val deltaX = change.position.x - downX
                                            val deltaY = change.position.y - downY
                                            val duration = System.currentTimeMillis() - downTime
                                            if (duration < 500 && abs(deltaX) < 20 && abs(deltaY) < 20) {
                                                player.togglePlayPause()
                                                feedbackText = if (player.isPlaying) "Play" else "Pause"
                                                showFeedback = true
                                                scope.launch {
                                                    delay(1000)
                                                    showFeedback = false
                                                }
                                            }
                                        }
                                        dragActive = false
                                        verticalSwipeActive = false
                                    }
                                }
                                change.consume()
                            }
                        }
                    }
                }
        )

        // Top right log button
        Button(
            onClick = onToggleLog,
            modifier = Modifier
                .align(Alignment.TopEnd)
                .padding(16.dp)
                .size(48.dp)
                .clip(RoundedCornerShape(24.dp)),
            shape = RoundedCornerShape(24.dp)
        ) {
            Text("📄", fontSize = 20.sp)
        }

        // Log panel
        if (logVisible) {
            Box(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .height(300.dp)
                    .background(Color.Black.copy(alpha = 0.8f))
                    .padding(8.dp)
            ) {
                Column {
                    Text(
                        text = logText,
                        color = Color.White,
                        fontSize = 12.sp,
                        modifier = Modifier.weight(1f)
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceEvenly
                    ) {
                        Button(onClick = { /* copy log */ }) { Text("Copy") }
                        Button(onClick = { /* clear log */ }) { Text("Clear") }
                        Button(onClick = onToggleLog) { Text("Close") }
                    }
                }
            }
        }

        // Seek bar and time
        if (showSeekbar) {
            Box(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .padding(16.dp)
            ) {
                Column {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(
                            text = if (showTimeOverlay) seekTargetTime else currentTime,
                            color = Color.White,
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Medium,
                            modifier = Modifier
                                .background(Color.DarkGray.copy(alpha = 0.8f))
                                .padding(horizontal = 8.dp, vertical = 4.dp)
                        )
                        if (showTimeOverlay) {
                            Text(
                                text = seekDirection,
                                color = Color.White,
                                fontSize = 14.sp,
                                fontWeight = FontWeight.Bold,
                                modifier = Modifier
                                    .background(Color.DarkGray.copy(alpha = 0.8f))
                                    .padding(horizontal = 8.dp, vertical = 4.dp)
                            )
                        }
                        Text(
                            text = totalTime,
                            color = Color.White,
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Medium,
                            modifier = Modifier
                                .background(Color.DarkGray.copy(alpha = 0.8f))
                                .padding(horizontal = 8.dp, vertical = 4.dp)
                        )
                    }

                    Spacer(modifier = Modifier.height(8.dp))

                    // Seek bar
                    SimpleSeekBar(
                        position = if (isDragging) currentDragPositionMs.toFloat() else player.getCurrentPosition().toFloat(),
                        duration = player.getDuration().toFloat(),
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(48.dp)
                    )
                }
            }
        }

        // Feedback text (center)
        if (showFeedback) {
            Text(
                text = feedbackText,
                color = Color.White,
                fontSize = 20.sp,
                fontWeight = FontWeight.Bold,
                modifier = Modifier
                    .align(Alignment.Center)
                    .background(Color.DarkGray.copy(alpha = 0.8f))
                    .padding(horizontal = 24.dp, vertical = 12.dp)
                    .clip(RoundedCornerShape(8.dp))
            )
        }

        // Speed indicator
        if (isLongPressing) {
            Text(
                text = "2X",
                color = Color.White,
                fontSize = 18.sp,
                fontWeight = FontWeight.Bold,
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .offset(y = 80.dp)
                    .background(Color.DarkGray.copy(alpha = 0.8f))
                    .padding(horizontal = 16.dp, vertical = 8.dp)
                    .clip(RoundedCornerShape(8.dp))
            )
        }
    }
}

// ------------------------------------------------------------------------
// Simple Seek Bar (draggable)
// ------------------------------------------------------------------------
@Composable
fun SimpleSeekBar(
    position: Float,
    duration: Float,
    modifier: Modifier = Modifier
) {
    val progress = if (duration > 0) (position / duration).coerceIn(0f, 1f) else 0f
    Box(modifier = modifier) {
        // Background
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(4.dp)
                .align(Alignment.CenterStart)
                .background(Color.Gray.copy(alpha = 0.6f))
        )
        // Progress
        Box(
            modifier = Modifier
                .fillMaxWidth(progress)
                .height(4.dp)
                .align(Alignment.CenterStart)
                .background(Color.White)
        )
        // Thumb (optional, can add touch handling)
    }
}

// ------------------------------------------------------------------------
// Time formatting helper
// ------------------------------------------------------------------------
fun formatTime(ms: Long): String {
    val totalSeconds = ms / 1000
    val hours = totalSeconds / 3600
    val minutes = (totalSeconds % 3600) / 60
    val seconds = totalSeconds % 60
    return if (hours > 0) String.format("%02d:%02d:%02d", hours, minutes, seconds)
    else String.format("%02d:%02d", minutes, seconds)
}
