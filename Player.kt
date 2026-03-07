// Player.kt
package com.rtfp

import android.Manifest
import android.content.ContentResolver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
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
import android.view.Gravity
import android.view.MotionEvent
import android.view.Surface
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.Button
import android.widget.FrameLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.VideoSize
import androidx.media3.exoplayer.ExoPlayer
import java.io.BufferedReader
import java.io.File
import java.io.FileOutputStream
import java.io.FileReader
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean

/**
 * A SurfaceView that maintains aspect ratio of video and centers itself.
 */
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
            // Width is limiting factor
            newWidth = viewWidth
            newHeight = (viewWidth / aspectRatio).toInt()
        } else {
            // Height is limiting factor
            newHeight = viewHeight
            newWidth = (viewHeight * aspectRatio).toInt()
        }

        setMeasuredDimension(newWidth, newHeight)
    }
}

class PlayerActivity : AppCompatActivity() {

    // UI
    private lateinit var surfaceView: AspectRatioSurfaceView
    private lateinit var debugOverlay: TextView
    private lateinit var ffmpegIndicator: TextView
    private lateinit var errorLogView: ScrollView
    private lateinit var errorLogText: TextView
    private lateinit var copyErrorButton: Button
    private lateinit var clearLogButton: Button
    private var errorLogVisible = false

    // File logging
    private lateinit var logFile: File
    private val logLock = Any()
    private val isLoggingEnabled = AtomicBoolean(true)

    // ExoPlayer
    private var exoPlayer: ExoPlayer? = null
    private var isPlaying = false
    private var videoUri: Uri? = null
    private var nativeLibraryLoaded = false
    private var savedPosition: Long = 0

    // FFmpeg native methods (byte array version)
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
    private val mainHandler = Handler(Looper.getMainLooper())
    private var isDragging = false
    private var dragStartX = 0f
    private var dragStartPositionMs = 0L
    private var currentDragPositionMs = 0L

    companion object {
        private const val PERMISSION_REQUEST_READ_STORAGE = 100
        private const val TAG = "RTFP"
        private const val LOG_FILE_NAME = "rtfp_crash_log.txt"
        private const val KEY_POSITION = "player_position"
        private const val DRAG_THRESHOLD = 10
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
    // Activity lifecycle
    // ------------------------------------------------------------------------
    override fun onCreate(savedInstanceState: Bundle?) {
        logFile = File(cacheDir, LOG_FILE_NAME)

        val defaultHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            logToFile("*** CRASH *** Thread: ${thread.name}")
            logToFile(throwable)
            runOnUiThread {
                try {
                    if (::errorLogText.isInitialized) {
                        appendToErrorLogView("*** CRASH *** ${throwable.message}")
                        errorLogVisible = true
                        errorLogView.visibility = View.VISIBLE
                    }
                } catch (e: Exception) { /* ignore */ }
            }
            defaultHandler?.uncaughtException(thread, throwable)
        }

        try {
            super.onCreate(savedInstanceState)
            nativeLibraryLoaded = loadNativeLibrary()
            hideSystemUI()

            if (savedInstanceState != null) {
                savedPosition = savedInstanceState.getLong(KEY_POSITION, 0)
                logToFile("Restored position: $savedPosition")
            }

            val root = FrameLayout(this).apply {
                layoutParams = ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT
                )
                setBackgroundColor(Color.BLACK)
            }

            surfaceView = AspectRatioSurfaceView(this).apply {
                layoutParams = FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply { gravity = Gravity.CENTER }
            }
            root.addView(surfaceView)

            debugOverlay = TextView(this).apply {
                layoutParams = FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply {
                    gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
                    topMargin = 50
                }
                setTextColor(Color.WHITE)
                text = "00:00"
                visibility = View.GONE
            }
            root.addView(debugOverlay)

            ffmpegIndicator = TextView(this).apply {
                layoutParams = FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply {
                    gravity = Gravity.TOP or Gravity.END
                    topMargin = 50
                    rightMargin = 50
                }
                setTextColor(Color.GREEN)
                text = "FFmpeg"
                visibility = View.GONE
            }
            root.addView(ffmpegIndicator)

            // Error log UI
            errorLogText = TextView(this).apply {
                setTextColor(Color.WHITE)
                textSize = 12f
            }
            copyErrorButton = Button(this).apply {
                text = "Copy Log"
                setOnClickListener {
                    val clipboard = getSystemService(CLIPBOARD_SERVICE) as android.content.ClipboardManager
                    val clip = android.content.ClipData.newPlainText("RTFP Error Log", errorLogText.text)
                    clipboard.setPrimaryClip(clip)
                    Toast.makeText(this@PlayerActivity, "Log copied", Toast.LENGTH_SHORT).show()
                }
            }
            clearLogButton = Button(this).apply {
                text = "Clear Log"
                setOnClickListener {
                    clearLogFile()
                    errorLogText.text = ""
                    Toast.makeText(this@PlayerActivity, "Log cleared", Toast.LENGTH_SHORT).show()
                }
            }
            val buttonBar = FrameLayout(this).apply {
                layoutParams = FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                )
                copyErrorButton.layoutParams = FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply { gravity = Gravity.START }
                clearLogButton.layoutParams = FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply { gravity = Gravity.END }
                addView(copyErrorButton)
                addView(clearLogButton)
            }
            val errorContent = FrameLayout(this).apply {
                layoutParams = FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                )
                addView(errorLogText)
                addView(buttonBar)
            }
            errorLogView = ScrollView(this).apply {
                layoutParams = FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    500
                ).apply { gravity = Gravity.BOTTOM }
                setBackgroundColor(Color.argb(200, 0, 0, 0))
                addView(errorContent)
                visibility = View.GONE
            }
            root.addView(errorLogView)

            setContentView(root)

            loadPreviousLogs()
            setupExoPlayer()
            setupTouchListeners()
            requestStoragePermissionIfNeeded()
            handleIntent(intent)
        } catch (e: Exception) {
            logToFile("Exception in onCreate: ${e.message}")
            logToFile(e)
            if (::errorLogText.isInitialized) {
                appendToErrorLogView("FATAL: ${e.message}")
                errorLogVisible = true
                errorLogView.visibility = View.VISIBLE
            } else {
                val tv = TextView(this)
                tv.setTextColor(Color.RED)
                tv.text = "FATAL ERROR: ${e.message}\n${e.stackTraceToString()}"
                setContentView(tv)
            }
        }
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) hideSystemUI()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putLong(KEY_POSITION, exoPlayer?.currentPosition ?: savedPosition)
    }

    override fun onRestoreInstanceState(savedInstanceState: Bundle) {
        super.onRestoreInstanceState(savedInstanceState)
        savedPosition = savedInstanceState.getLong(KEY_POSITION, 0)
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
                logToFile("Paused onPause")
            }
            savedPosition = it.currentPosition
        }
        if (ffmpegHandle != 0L) {
            nativeClose(ffmpegHandle)
            ffmpegHandle = 0
            logToFile("Closed FFmpeg handle onPause")
        }
    }

    override fun onResume() {
        super.onResume()
        logToFile("onResume, position: $savedPosition")
        if (savedPosition > 0 && exoPlayer != null) {
            exoPlayer?.seekTo(savedPosition)
        }
        if (videoUri != null && nativeLibraryLoaded) {
            val file = uriToFile(videoUri!!)
            if (file != null && file.exists()) {
                ffmpegHandle = nativeOpenFile(file.absolutePath)
                if (ffmpegHandle != 0L) {
                    videoWidth = nativeGetWidth(ffmpegHandle)
                    videoHeight = nativeGetHeight(ffmpegHandle)
                    logToFile("Re-opened FFmpeg: ${videoWidth}x${videoHeight}")
                }
            }
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

    override fun onBackPressed() {
        releasePlayer()
        super.onBackPressed()
    }

    private fun releasePlayer() {
        exoPlayer?.let {
            savedPosition = it.currentPosition
            it.release()
            exoPlayer = null
            logToFile("ExoPlayer released")
        }
    }

    // ------------------------------------------------------------------------
    // Logging
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
        if (::errorLogText.isInitialized) {
            mainHandler.post { appendToErrorLogView(message) }
        }
    }

    private fun logToFile(throwable: Throwable) {
        val sw = StringWriter()
        val pw = PrintWriter(sw)
        throwable.printStackTrace(pw)
        logToFile(sw.toString())
    }

    private fun appendToErrorLogView(message: String) {
        val time = SimpleDateFormat("HH:mm:ss", Locale.US).format(Date())
        errorLogText.append("$time: $message\n")
        errorLogView.post { errorLogView.fullScroll(ScrollView.FOCUS_DOWN) }
    }

    private fun loadPreviousLogs() {
        try {
            if (logFile.exists()) {
                BufferedReader(FileReader(logFile)).use { reader ->
                    reader.forEachLine { line -> appendToErrorLogView(line) }
                }
                appendToErrorLogView("--- End of previous log ---")
            } else {
                appendToErrorLogView("No previous crash log.")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load logs", e)
        }
    }

    private fun clearLogFile() {
        synchronized(logLock) {
            try {
                if (logFile.exists()) logFile.delete()
                logFile.createNewFile()
            } catch (e: Exception) { /* ignore */ }
        }
    }

    // ------------------------------------------------------------------------
    // Permissions
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
                    setVideoSurface(holder.surface)
                }
                override fun surfaceChanged(h: SurfaceHolder, f: Int, w: Int, hh: Int) {
                    logToFile("Surface changed: ${w}x$hh")
                }
                override fun surfaceDestroyed(holder: SurfaceHolder) {
                    logToFile("Surface destroyed")
                    clearVideoSurface()
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
    }

    // ------------------------------------------------------------------------
    // Touch handling (tap + drag)
    // ------------------------------------------------------------------------
    private fun setupTouchListeners() {
        surfaceView.setOnTouchListener { _, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    logToFile("Touch DOWN at (${event.x}, ${event.y})")
                    if (event.x < 200 && event.y < 200) {
                        toggleErrorLog()
                        return@setOnTouchListener true
                    }
                    dragStartX = event.x
                    dragStartPositionMs = exoPlayer?.currentPosition ?: 0
                    currentDragPositionMs = dragStartPositionMs
                    false
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = event.x - dragStartX
                    if (!isDragging && kotlin.math.abs(dx) > DRAG_THRESHOLD) {
                        isDragging = true
                        startFfmpegSeekMode()
                    }
                    if (isDragging) {
                        val deltaMs = (dx / 100f * 1000).toLong()
                        val newPos = (dragStartPositionMs + deltaMs).coerceIn(0, exoPlayer?.duration ?: 0)
                        if (newPos != currentDragPositionMs) {
                            currentDragPositionMs = newPos
                            val success = updateFfmpegFrame(currentDragPositionMs * 1000)
                            if (!success) logToFile("FFmpeg frame failed")
                            updateOverlayTime(currentDragPositionMs)
                        }
                        return@setOnTouchListener true
                    }
                    false
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    if (isDragging) {
                        isDragging = false
                        debugOverlay.visibility = View.GONE
                        ffmpegIndicator.visibility = View.GONE
                        exitFfmpegSeekMode()
                        exoPlayer?.seekTo(currentDragPositionMs)
                        if (isPlaying) exoPlayer?.play()
                        logToFile("Seek ended at ${currentDragPositionMs}ms")
                    } else {
                        togglePlayPause()
                    }
                    true
                }
                else -> false
            }
        }
    }

    private fun togglePlayPause() {
        exoPlayer?.let {
            if (it.isPlaying) {
                it.pause()
                isPlaying = false
                logToFile("Paused (tap)")
            } else {
                it.play()
                isPlaying = true
                logToFile("Played (tap)")
            }
        }
    }

    // ------------------------------------------------------------------------
    // FFmpeg seek mode (byte array version)
    // ------------------------------------------------------------------------
    private fun startFfmpegSeekMode() {
        if (!nativeLibraryLoaded) {
            logToFile("FFmpeg not loaded")
            return
        }
        if (ffmpegHandle == 0L) {
            logToFile("FFmpeg handle 0")
            return
        }
        exoPlayer?.pause()
        isFfmpegMode = true
        debugOverlay.visibility = View.VISIBLE
        ffmpegIndicator.visibility = View.VISIBLE
        updateFfmpegFrame(exoPlayer?.currentPosition?.times(1000) ?: 0)
        logToFile("FFmpeg seek started")
        Toast.makeText(this, "FFmpeg seek", Toast.LENGTH_SHORT).show()
    }

    private fun updateFfmpegFrame(timestampUs: Long): Boolean {
        if (!isFfmpegMode || ffmpegHandle == 0L) return false
        return try {
            val result = nativeSeekTo(ffmpegHandle, timestampUs)
            if (result == 0) {
                val rgba = nativeGetFrameRGBA(ffmpegHandle)
                if (rgba != null && videoWidth > 0 && videoHeight > 0) {
                    val bitmap = Bitmap.createBitmap(videoWidth, videoHeight, Bitmap.Config.ARGB_8888)
                    bitmap.copyPixelsFromBuffer(java.nio.ByteBuffer.wrap(rgba))
                    drawBitmapOnSurface(bitmap)
                    bitmap.recycle()
                    true
                } else {
                    false
                }
            } else {
                false
            }
        } catch (e: Exception) {
            logToFile("FFmpeg frame exception: ${e.message}")
            false
        }
    }

    private fun drawBitmapOnSurface(bitmap: Bitmap) {
        val surface = surfaceView.holder.surface ?: return
        try {
            val canvas = surface.lockCanvas(null) ?: return
            val rect = Rect(0, 0, canvas.width, canvas.height)
            val paint = Paint().apply { isFilterBitmap = true }
            canvas.drawBitmap(bitmap, null, rect, paint)
            surface.unlockCanvasAndPost(canvas)
        } catch (e: Exception) {
            logToFile("Draw failed: ${e.message}")
        }
    }

    private fun exitFfmpegSeekMode() {
        isFfmpegMode = false
    }

    private fun updateOverlayTime(ms: Long) {
        val sec = ms / 1000
        val minutes = sec / 60
        val seconds = sec % 60
        debugOverlay.text = String.format("%02d:%02d", minutes, seconds)
    }

    private fun toggleErrorLog() {
        errorLogVisible = !errorLogVisible
        errorLogView.visibility = if (errorLogVisible) View.VISIBLE else View.GONE
        if (errorLogVisible) {
            errorLogText.text = ""
            loadPreviousLogs()
        }
    }
}
