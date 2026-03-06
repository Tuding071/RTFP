// Player.kt
package com.rtfp

import android.Manifest
import android.content.ContentResolver
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.OpenableColumns
import android.util.Log
import android.view.MotionEvent
import android.view.Surface
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.View
import android.view.ViewGroup
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
import androidx.media3.exoplayer.ExoPlayer
import java.io.File
import java.io.FileOutputStream
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class PlayerActivity : AppCompatActivity() {

    // ------------------------------------------------------------------------
    // UI Components
    // ------------------------------------------------------------------------
    private lateinit var surfaceView: SurfaceView
    private lateinit var debugOverlay: TextView
    private lateinit var errorLogView: ScrollView
    private lateinit var errorLogText: TextView
    private lateinit var copyErrorButton: Button
    private var errorLogVisible = false

    // ------------------------------------------------------------------------
    // ExoPlayer
    // ------------------------------------------------------------------------
    private var exoPlayer: ExoPlayer? = null
    private var isPlaying = false
    private var videoUri: Uri? = null

    // ------------------------------------------------------------------------
    // FFmpeg native methods
    // ------------------------------------------------------------------------
    init {
        try {
            System.loadLibrary("ffmpeg_wrapper")
        } catch (e: UnsatisfiedLinkError) {
            e.printStackTrace()
            logError("Failed to load native library: ${e.message}")
        }
    }

    private external fun nativeOpenFile(path: String): Long
    private external fun nativeSeekTo(handle: Long, timestampUs: Long): Int
    private external fun nativeGetFrameAsBitmap(handle: Long): Bitmap?
    private external fun nativeClose(handle: Long)

    private var ffmpegHandle: Long = 0
    private var isFfmpegMode = false

    // ------------------------------------------------------------------------
    // Touch handling state
    // ------------------------------------------------------------------------
    private val mainHandler = Handler(Looper.getMainLooper())
    private var longPressRunnable: Runnable? = null
    private var isLongPressing = false
    private var isDragging = false

    private var dragStartX = 0f
    private var dragStartPositionMs = 0L
    private var currentDragPositionMs = 0L

    // Permission request code
    private companion object {
        private const val PERMISSION_REQUEST_READ_STORAGE = 100
        private const val TAG = "RTFP"
    }

    // ------------------------------------------------------------------------
    // Activity lifecycle
    // ------------------------------------------------------------------------
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val root = FrameLayout(this).apply {
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
            setBackgroundColor(Color.BLACK)
        }

        // SurfaceView for video
        surfaceView = SurfaceView(this).apply {
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        }
        root.addView(surfaceView)

        // Debug overlay (current time during seek)
        debugOverlay = TextView(this).apply {
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                gravity = android.view.Gravity.TOP or android.view.Gravity.CENTER_HORIZONTAL
                topMargin = 50
            }
            setTextColor(Color.WHITE)
            text = "00:00"
            visibility = View.GONE
        }
        root.addView(debugOverlay)

        // Error log view (initially hidden)
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
        val errorContent = FrameLayout(this).apply {
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
            addView(errorLogText)
            addView(copyErrorButton)
        }
        errorLogView = ScrollView(this).apply {
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                400
            ).apply {
                gravity = android.view.Gravity.BOTTOM
                bottomMargin = 0
            }
            setBackgroundColor(Color.argb(200, 0, 0, 0))
            addView(errorContent)
            visibility = View.GONE
        }
        root.addView(errorLogView)

        setContentView(root)

        setupExoPlayer()
        setupTouchListeners()

        // Request storage permission on first launch (so it appears in settings)
        requestStoragePermissionIfNeeded()

        // Handle incoming intent
        handleIntent(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    override fun onDestroy() {
        super.onDestroy()
        exoPlayer?.release()
        if (ffmpegHandle != 0L) {
            nativeClose(ffmpegHandle)
            ffmpegHandle = 0
        }
    }

    // ------------------------------------------------------------------------
    // Permission handling
    // ------------------------------------------------------------------------
    private fun requestStoragePermissionIfNeeded() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            // Only below Android 10 we need to request permission
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
        when (requestCode) {
            PERMISSION_REQUEST_READ_STORAGE -> {
                if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                    logError("Storage permission granted")
                } else {
                    logError("Storage permission denied")
                    Toast.makeText(this, "Storage permission denied", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    // ------------------------------------------------------------------------
    // Intent handling
    // ------------------------------------------------------------------------
    private fun handleIntent(intent: Intent) {
        when (intent.action) {
            Intent.ACTION_VIEW -> {
                val uri = intent.data ?: return
                logError("Opening URI: $uri")
                openVideo(uri)
            }
        }
    }

    private fun openVideo(uri: Uri) {
        videoUri = uri
        // For content:// URIs, ExoPlayer can handle them directly
        // For file:// URIs, convert to Uri.fromFile if needed, but MediaItem.fromUri works with file:// too
        val mediaItem = MediaItem.fromUri(uri)
        exoPlayer?.setMediaItem(mediaItem)
        exoPlayer?.prepare()
        exoPlayer?.playWhenReady = true
        isPlaying = true

        // For FFmpeg seeking, we need a file path. If it's a content URI, we may need to copy to cache.
        // But let's try to get a file path from content URI via content resolver (may work if it's a media store file)
        val file = uriToFile(uri)
        if (file != null && file.exists()) {
            if (ffmpegHandle != 0L) nativeClose(ffmpegHandle)
            ffmpegHandle = nativeOpenFile(file.absolutePath)
        } else {
            logError("Cannot get file for FFmpeg seeking; seeking will be disabled")
        }
    }

    // Convert Uri to File if possible (for content URIs, try to get real path; fallback to copy to cache)
    private fun uriToFile(uri: Uri): File? {
        return when (uri.scheme) {
            ContentResolver.SCHEME_FILE -> File(uri.path!!)
            ContentResolver.SCHEME_CONTENT -> {
                // Try to get a file path via content resolver (may work for media store)
                val projection = arrayOf(OpenableColumns.DISPLAY_NAME)
                val cursor = contentResolver.query(uri, projection, null, null, null)
                cursor?.use {
                    if (it.moveToFirst()) {
                        val nameIndex = it.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                        val name = it.getString(nameIndex)
                        // Attempt to get path (not reliable)
                        // Fallback: copy to cache
                        val inputStream = contentResolver.openInputStream(uri) ?: return null
                        val outFile = File(cacheDir, name)
                        FileOutputStream(outFile).use { output ->
                            inputStream.copyTo(output)
                        }
                        return outFile
                    }
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
                    setVideoSurface(holder.surface)
                }
                override fun surfaceChanged(
                    holder: SurfaceHolder,
                    format: Int,
                    width: Int,
                    height: Int
                ) = Unit
                override fun surfaceDestroyed(holder: SurfaceHolder) {
                    clearVideoSurface()
                }
            })

            addListener(object : Player.Listener {
                override fun onPlaybackStateChanged(playbackState: Int) {
                    logError("Playback state changed: $playbackState")
                }
                override fun onPlayerError(error: PlaybackException) {
                    error.printStackTrace()
                    logError("ExoPlayer error: ${error.message}")
                    Toast.makeText(this@PlayerActivity, "Playback error", Toast.LENGTH_SHORT).show()
                }
            })
        }
    }

    // ------------------------------------------------------------------------
    // Touch handling
    // ------------------------------------------------------------------------
    private fun setupTouchListeners() {
        surfaceView.setOnTouchListener { v, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    // Long press on top-left corner to show error log
                    if (event.x < 200 && event.y < 200) {
                        toggleErrorLog()
                        return@setOnTouchListener true
                    }
                    longPressRunnable?.let { mainHandler.removeCallbacks(it) }
                    longPressRunnable = Runnable {
                        if (!isDragging) {
                            isLongPressing = true
                            exoPlayer?.setPlaybackSpeed(2.0f)
                        }
                    }
                    mainHandler.postDelayed(longPressRunnable!!, 500)

                    dragStartX = event.x
                    dragStartPositionMs = exoPlayer?.currentPosition ?: 0
                    currentDragPositionMs = dragStartPositionMs
                    false
                }

                MotionEvent.ACTION_MOVE -> {
                    val dx = event.x - dragStartX
                    if (!isDragging && kotlin.math.abs(dx) > 20) {
                        isDragging = true
                        mainHandler.removeCallbacks(longPressRunnable!!)
                        startFfmpegSeekMode()
                    }

                    if (isDragging) {
                        val deltaMs = (dx / 100f * 1000).toLong()
                        val newPos = (dragStartPositionMs + deltaMs).coerceIn(0, exoPlayer?.duration ?: 0)
                        if (newPos != currentDragPositionMs) {
                            currentDragPositionMs = newPos
                            updateFfmpegFrame(currentDragPositionMs * 1000)
                            updateOverlayTime(currentDragPositionMs)
                        }
                        return@setOnTouchListener true
                    }
                    false
                }

                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    mainHandler.removeCallbacks(longPressRunnable!!)

                    if (isLongPressing) {
                        isLongPressing = false
                        exoPlayer?.setPlaybackSpeed(1.0f)
                    }

                    if (isDragging) {
                        isDragging = false
                        debugOverlay.visibility = View.GONE
                        exitFfmpegSeekMode()
                        exoPlayer?.seekTo(currentDragPositionMs)
                        if (isPlaying) {
                            exoPlayer?.play()
                        }
                    } else {
                        if (!isDragging && !isLongPressing) {
                            togglePlayPause()
                        }
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
            } else {
                it.play()
                isPlaying = true
            }
        }
    }

    // ------------------------------------------------------------------------
    // FFmpeg seek mode
    // ------------------------------------------------------------------------
    private fun startFfmpegSeekMode() {
        if (ffmpegHandle == 0L || videoUri == null) return
        exoPlayer?.pause()
        isFfmpegMode = true
        debugOverlay.visibility = View.VISIBLE
        updateFfmpegFrame(exoPlayer?.currentPosition?.times(1000) ?: 0)
    }

    private fun updateFfmpegFrame(timestampUs: Long) {
        if (!isFfmpegMode || ffmpegHandle == 0L) return
        val result = nativeSeekTo(ffmpegHandle, timestampUs)
        if (result == 0) {
            val bitmap = nativeGetFrameAsBitmap(ffmpegHandle)
            bitmap?.let {
                drawBitmapOnSurface(it)
                it.recycle()
            }
        } else {
            logError("FFmpeg seek failed at $timestampUs")
        }
    }

    private fun drawBitmapOnSurface(bitmap: Bitmap) {
        val surface = surfaceView.holder.surface ?: return
        val canvas: Canvas? = surface.lockCanvas(null)
        canvas?.apply {
            val rect = android.graphics.Rect(0, 0, width, height)
            val paint = Paint().apply { isFilterBitmap = true }
            drawBitmap(bitmap, null, rect, paint)
            surface.unlockCanvasAndPost(this)
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

    // ------------------------------------------------------------------------
    // Error log view
    // ------------------------------------------------------------------------
    private fun toggleErrorLog() {
        errorLogVisible = !errorLogVisible
        errorLogView.visibility = if (errorLogVisible) View.VISIBLE else View.GONE
    }

    private fun logError(message: String) {
        Log.e(TAG, message)
        mainHandler.post {
            val time = SimpleDateFormat("HH:mm:ss", Locale.US).format(Date())
            errorLogText.append("$time: $message\n")
            // Auto-scroll to bottom
            errorLogView.post { errorLogView.fullScroll(ScrollView.FOCUS_DOWN) }
        }
    }

    private fun logError(throwable: Throwable) {
        val sw = StringWriter()
        val pw = PrintWriter(sw)
        throwable.printStackTrace(pw)
        logError(sw.toString())
    }
}
