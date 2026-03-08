package com.rtfp.player

/**
 * Represents the current state of the video player
 */
data class PlayerState(
    val isPlaying: Boolean = false,
    val currentPosition: Long = 0,
    val duration: Long = 0,
    val videoTitle: String = "RTFP Player",
    val videoPath: String = "",
    val volume: Int = 50,
    val isLoading: Boolean = false,
    val error: String? = null
)

/**
 * Sealed class for player events
 */
sealed class PlayerEvent {
    object Play : PlayerEvent()
    object Pause : PlayerEvent()
    data class Seek(val position: Long) : PlayerEvent()
    data class SetVolume(val volume: Int) : PlayerEvent()
    data class LoadVideo(val path: String) : PlayerEvent()
    object TogglePlayPause : PlayerEvent()
}
