package com.nexio.tv.ui.screensaver

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PlaybackIdleGateStateTest {

    @Test
    fun `in app trailer playback marks playback session active for work gating`() {
        val state = PlaybackIdleGateState()

        state.onInAppTrailerPlaybackActiveChanged(active = true)

        val snapshot = state.snapshot.value
        assertTrue(snapshot.hasActiveSession)
        assertFalse(snapshot.isPausedByUser)
    }

    @Test
    fun `ending player session preserves active in app trailer gate`() {
        val state = PlaybackIdleGateState()

        state.onInAppTrailerPlaybackActiveChanged(active = true)
        state.onPlayerSessionStarted()
        state.onPlayerSessionEnded()

        assertTrue(state.snapshot.value.hasActiveSession)
    }

    @Test
    fun `idle trailer playback marks idle trailer gate without making route playback active`() {
        val state = PlaybackIdleGateState()

        state.onIdleTrailerPlaybackActiveChanged(active = true)

        val snapshot = state.snapshot.value
        assertFalse(snapshot.hasActiveSession)
        assertTrue(snapshot.idleTrailerPlaybackActive)
        assertFalse(snapshot.isPausedByUser)
    }

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
