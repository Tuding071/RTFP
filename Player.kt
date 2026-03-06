// Player.kt
package com.rtfp

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.MotionEvent
import android.view.Surface
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import java.io.File

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
    // FFmpeg native methods (implemented in libffmpeg_wrapper.so)
    // ------------------------------------------------------------------------
    init {
        try {
            System.loadLibrary("ffmpeg_wrapper")
        } catch (e: UnsatisfiedLinkError) {
            e.printStackTrace()
            // Fallback: perhaps show an error toast
        }
    }

    /** Open video file and return a handle (0 on error). */
    private external fun nativeOpenFile(path: String): Long

    /** Seek to timestamp (microseconds) and decode a frame. Returns 0 on success. */
    private external fun nativeSeekTo(handle: Long, timestampUs: Long): Int

    /** Get the last decoded frame as a Bitmap (must be recycled by caller). */
    private external fun nativeGetFrameAsBitmap(handle: Long): Bitmap?

    /** Close the video and release resources. */
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
                }
            })
        }
    }

    fun openVideoFile(file: File) {
        videoFile = file
        val mediaItem = MediaItem.fromUri(file.toURI())
        exoPlayer?.setMediaItem(mediaItem)
        exoPlayer?.prepare()
        exoPlayer?.playWhenReady = false
        isPlaying = false

        if (ffmpegHandle != 0L) nativeClose(ffmpegHandle)
        ffmpegHandle = nativeOpenFile(file.absolutePath)
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
