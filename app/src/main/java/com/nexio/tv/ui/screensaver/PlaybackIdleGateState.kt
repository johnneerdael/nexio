package com.nexio.tv.ui.screensaver

import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

data class PlaybackIdleGateSnapshot(
    val hasActiveSession: Boolean = false,
    val isPausedByUser: Boolean = false,
    val idleTrailerPlaybackActive: Boolean = false
)

@Singleton
class PlaybackIdleGateState @Inject constructor() {
    private val _snapshot = MutableStateFlow(PlaybackIdleGateSnapshot())
    val snapshot: StateFlow<PlaybackIdleGateSnapshot> = _snapshot.asStateFlow()

    private var playerSessionActive = false
    private var inAppTrailerPlaybackActive = false
    private var idleTrailerPlaybackActive = false
    private var pausedByUser = false

    fun onPlayerSessionStarted() {
        playerSessionActive = true
        pausedByUser = false
        publishSnapshot()
    }

    fun onUserPauseStateChanged(isPausedByUser: Boolean) {
        pausedByUser = playerSessionActive && isPausedByUser
        publishSnapshot()
    }

    fun onPlaybackResumed() {
        if (!playerSessionActive) return
        pausedByUser = false
        publishSnapshot()
    }

    fun onPlayerSessionEnded() {
        playerSessionActive = false
        pausedByUser = false
        publishSnapshot()
    }

    fun onInAppTrailerPlaybackActiveChanged(active: Boolean) {
        inAppTrailerPlaybackActive = active
        publishSnapshot()
    }

    fun onIdleTrailerPlaybackActiveChanged(active: Boolean) {
        idleTrailerPlaybackActive = active
        publishSnapshot()
    }

    private fun publishSnapshot() {
        _snapshot.value = PlaybackIdleGateSnapshot(
            hasActiveSession = playerSessionActive || inAppTrailerPlaybackActive,
            isPausedByUser = playerSessionActive && pausedByUser,
            idleTrailerPlaybackActive = idleTrailerPlaybackActive
        )
    }
}
