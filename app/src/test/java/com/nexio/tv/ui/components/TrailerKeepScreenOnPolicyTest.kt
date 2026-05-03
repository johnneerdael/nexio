package com.nexio.tv.ui.components

import android.view.KeyEvent
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TrailerKeepScreenOnPolicyTest {
    @Test
    fun `keepScreenOn is true while playing`() {
        assertTrue(shouldKeepScreenOnForTrailer(isPlaying = true, isBuffering = false))
    }

    @Test
    fun `keepScreenOn is true while buffering`() {
        assertTrue(shouldKeepScreenOnForTrailer(isPlaying = false, isBuffering = true))
    }

    @Test
    fun `keepScreenOn is false while paused`() {
        assertFalse(shouldKeepScreenOnForTrailer(isPlaying = false, isBuffering = false))
    }

    @Test
    fun `pause keys are consumed`() {
        assertTrue(shouldConsumeTrailerKey(KeyEvent.KEYCODE_MEDIA_PAUSE))
        assertTrue(shouldConsumeTrailerKey(KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE))
    }

    @Test
    fun `non pause keys are not consumed`() {
        assertFalse(shouldConsumeTrailerKey(KeyEvent.KEYCODE_DPAD_CENTER))
        assertFalse(shouldConsumeTrailerKey(KeyEvent.KEYCODE_BACK))
    }
}
