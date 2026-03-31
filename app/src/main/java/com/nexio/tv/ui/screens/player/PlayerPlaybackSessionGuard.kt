package com.nexio.tv.ui.screens.player

internal class PlayerPlaybackSessionGuard {
    private var isExitInProgress: Boolean = false
    private var activePlaybackSessionId: Long = 0L
    private var nextPlaybackSessionId: Long = 1L

    fun beginPlaybackSession(): Long {
        isExitInProgress = false
        val sessionId = nextPlaybackSessionId++
        activePlaybackSessionId = sessionId
        return sessionId
    }

    fun beginPlayerExit() {
        isExitInProgress = true
    }

    fun onPlayerReleased() {
        activePlaybackSessionId = 0L
    }

    fun activePlaybackSessionId(): Long = activePlaybackSessionId

    fun shouldHandleCallback(playbackSessionId: Long): Boolean {
        return !isExitInProgress &&
            playbackSessionId != 0L &&
            playbackSessionId == activePlaybackSessionId
    }
}
