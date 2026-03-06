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
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.OpenableColumns
import android.view.MotionEvent
import android.view.Surface
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
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

class PlayerActivity : AppCompatActivity() {

    // ------------------------------------------------------------------------
    // UI Components
    // ------------------------------------------------------------------------
    private lateinit var surfaceView: SurfaceView
    private lateinit var debugOverlay: TextView

    // ------------------------------------------------------------------------
    // ExoPlayer
    // ------------------------------------------------------------------------
    private var exoPlayer: ExoPlayer? = null
    private var isPlaying = false
    private var videoFile: File? = null

    // ------------------------------------------------------------------------
    // FFmpeg native methods
    // ------------------------------------------------------------------------
    init {
        try {
            System.loadLibrary("ffmpeg_wrapper")
        } catch (e: UnsatisfiedLinkError) {
            e.printStackTrace()
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

        surfaceView = SurfaceView(this).apply {
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        }
        root.addView(surfaceView)

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

        setContentView(root)

        setupExoPlayer()
        setupTouchListeners()

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
    // Intent handling
    // ------------------------------------------------------------------------
    private fun handleIntent(intent: Intent) {
        when (intent.action) {
            Intent.ACTION_VIEW -> {
                val uri = intent.data ?: return
                // Check permission
                if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_EXTERNAL_STORAGE)
                    != PackageManager.PERMISSION_GRANTED
                ) {
                    // Request permission
                    ActivityCompat.requestPermissions(
                        this,
                        arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE),
                        PERMISSION_REQUEST_READ_STORAGE
                    )
                    // Save URI for later
                    pendingUri = uri
                } else {
                    openVideoFromUri(uri)
                }
            }
        }
    }

    private var pendingUri: Uri? = null

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        when (requestCode) {
            PERMISSION_REQUEST_READ_STORAGE -> {
                if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                    pendingUri?.let { openVideoFromUri(it) }
                    pendingUri = null
                } else {
                    Toast.makeText(this, "Storage permission required", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    private fun openVideoFromUri(uri: Uri) {
        val file = uriToFile(uri)
        if (file != null && file.exists()) {
            openVideoFile(file)
        } else {
            Toast.makeText(this, "Cannot open video file", Toast.LENGTH_SHORT).show()
        }
    }

    // Convert Uri to File (handles file:// and content://)
    private fun uriToFile(uri: Uri): File? {
        return when (uri.scheme) {
            ContentResolver.SCHEME_FILE -> File(uri.path!!)
            ContentResolver.SCHEME_CONTENT -> {
                // Copy to cache directory
                val cursor = contentResolver.query(uri, null, null, null, null)
                cursor?.use {
                    if (it.moveToFirst()) {
                        val nameIndex = it.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                        val name = it.getString(nameIndex)
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
                override fun onPlaybackStateChanged(playbackState: Int) { }
                override fun onPlayerError(error: PlaybackException) {
                    error.printStackTrace()
                    Toast.makeText(this@PlayerActivity, "Playback error", Toast.LENGTH_SHORT).show()
                }
            })
        }
    }

    fun openVideoFile(file: File) {
        videoFile = file
        val mediaItem = MediaItem.fromUri(Uri.fromFile(file))
        exoPlayer?.setMediaItem(mediaItem)
        exoPlayer?.prepare()
        exoPlayer?.playWhenReady = false
        isPlaying = false

        if (ffmpegHandle != 0L) nativeClose(ffmpegHandle)
        ffmpegHandle = nativeOpenFile(file.absolutePath)

        Toast.makeText(this, "Opened: ${file.name}", Toast.LENGTH_SHORT).show()
    }

    // ------------------------------------------------------------------------
    // Touch handling
    // ------------------------------------------------------------------------
    private fun setupTouchListeners() {
        surfaceView.setOnTouchListener { v, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
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
        if (ffmpegHandle == 0L || videoFile == null) return
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
}
