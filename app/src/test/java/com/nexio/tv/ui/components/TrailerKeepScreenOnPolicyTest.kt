package com.nexio.tv.ui.components

import org.junit.Test
import org.junit.Assert.assertTrue
import org.junit.Assert.assertFalse

class TrailerKeepScreenOnPolicyTest {
    @Test fun `keepScreenOn is true while playing`() {
        assertTrue(shouldKeepScreenOnForTrailer(isPlaying = true, isBuffering = false))
    }

    @Test fun `keepScreenOn is true while buffering`() {
        assertTrue(shouldKeepScreenOnForTrailer(isPlaying = false, isBuffering = true))
    }

    @Test fun `keepScreenOn is false while paused`() {
        assertFalse(shouldKeepScreenOnForTrailer(isPlaying = false, isBuffering = false))
    }

    @Test fun `pause key is consumed`() {
        assertTrue(shouldConsumeTrailerKey(android.view.KeyEvent.KEYCODE_MEDIA_PAUSE))
        assertTrue(shouldConsumeTrailerKey(android.view.KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE))
    }

    @Test fun `non-pause keys are not consumed`() {
        assertFalse(shouldConsumeTrailerKey(android.view.KeyEvent.KEYCODE_DPAD_CENTER))
        assertFalse(shouldConsumeTrailerKey(android.view.KeyEvent.KEYCODE_BACK))
    }
}
