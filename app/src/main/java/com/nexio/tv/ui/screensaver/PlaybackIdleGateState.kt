package com.nexio.tv.ui.screensaver

import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

data class PlaybackIdleGateSnapshot(
    val hasActiveSession: Boolean = false,
    val isPausedByUser: Boolean = false
)

@Singleton
class PlaybackIdleGateState @Inject constructor() {
    private val _snapshot = MutableStateFlow(PlaybackIdleGateSnapshot())
    val snapshot: StateFlow<PlaybackIdleGateSnapshot> = _snapshot.asStateFlow()

    fun onPlayerSessionStarted() {
        _snapshot.value = PlaybackIdleGateSnapshot(
            hasActiveSession = true,
            isPausedByUser = false
        )
    }

    fun onUserPauseStateChanged(isPausedByUser: Boolean) {
        _snapshot.update { current ->
            current.copy(
                hasActiveSession = current.hasActiveSession,
                isPausedByUser = current.hasActiveSession && isPausedByUser
            )
        }
    }

    fun onPlayerSessionEnded() {
        _snapshot.value = PlaybackIdleGateSnapshot()
    }
}
