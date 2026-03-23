package com.nexio.tv.ui.screensaver

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PlaybackIdleGateStateTest {

    @Test
    fun `onPlaybackResumed clears paused-by-user while preserving active session`() {
        val state = PlaybackIdleGateState()

        state.onPlayerSessionStarted()
        state.onUserPauseStateChanged(isPausedByUser = true)

        state.onPlaybackResumed()

        val snapshot = state.snapshot.value
        assertTrue(snapshot.hasActiveSession)
        assertFalse(snapshot.isPausedByUser)
    }
}
