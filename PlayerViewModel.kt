package com.rtfp.player

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * ViewModel for managing player state
 * Follows MVVM pattern with StateFlow for reactive UI updates
 */
class PlayerViewModel : ViewModel() {
    
    private val _playerState = MutableStateFlow(PlayerState())
    val playerState: StateFlow<PlayerState> = _playerState.asStateFlow()
    
    private val _currentVolume = MutableStateFlow(50)
    val currentVolume: StateFlow<Int> = _currentVolume.asStateFlow()
    
    val maxVolume = 100
    
    /**
     * Handle player events
     */
    fun onEvent(event: PlayerEvent) {
        when (event) {
            is PlayerEvent.Play -> {
                _playerState.value = _playerState.value.copy(isPlaying = true)
            }
            is PlayerEvent.Pause -> {
                _playerState.value = _playerState.value.copy(isPlaying = false)
            }
            is PlayerEvent.Seek -> {
                _playerState.value = _playerState.value.copy(currentPosition = event.position)
            }
            is PlayerEvent.SetVolume -> {
                _currentVolume.value = event.volume.coerceIn(0, maxVolume)
            }
            is PlayerEvent.LoadVideo -> {
                _playerState.value = _playerState.value.copy(
                    videoPath = event.path,
                    isLoading = true,
                    error = null
                )
            }
            is PlayerEvent.TogglePlayPause -> {
                _playerState.value = _playerState.value.copy(
                    isPlaying = !_playerState.value.isPlaying
                )
            }
        }
    }
    
    /**
     * Update player state from MPV
     */
    fun updatePlayerState(
        isPlaying: Boolean? = null,
        currentPosition: Long? = null,
        duration: Long? = null,
        videoTitle: String? = null
    ) {
        viewModelScope.launch {
            _playerState.value = _playerState.value.copy(
                isPlaying = isPlaying ?: _playerState.value.isPlaying,
                currentPosition = currentPosition ?: _playerState.value.currentPosition,
                duration = duration ?: _playerState.value.duration,
                videoTitle = videoTitle ?: _playerState.value.videoTitle,
                isLoading = false
            )
        }
    }
    
    /**
     * Set error state
     */
    fun setError(error: String) {
        _playerState.value = _playerState.value.copy(
            error = error,
            isLoading = false
        )
    }
    
    /**
     * Clear error state
     */
    fun clearError() {
        _playerState.value = _playerState.value.copy(error = null)
    }
}
