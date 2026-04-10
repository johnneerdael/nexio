package com.nexio.tv.ui.screens.player

data class PlayerPlaybackProgressUiState(
    val currentPosition: Long = 0L,
    val duration: Long = 0L,
    val pendingPreviewSeekPosition: Long? = null
) {
    val displayPosition: Long
        get() = pendingPreviewSeekPosition ?: currentPosition
}
