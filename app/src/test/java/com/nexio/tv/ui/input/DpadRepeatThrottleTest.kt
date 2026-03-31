package com.nexio.tv.ui.input

import androidx.compose.ui.input.key.Key
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DpadRepeatThrottleTest {

    @Test
    fun `same axis repeat within throttle window is consumed`() {
        val state = DpadRepeatThrottleState(verticalLastDirectionalInputUptimeMs = 1_000L)

        assertTrue(
            shouldConsumeDirectionalRepeat(
                axis = DpadRepeatThrottleAxis.VERTICAL,
                repeatCount = 1,
                nowUptimeMs = 1_050L,
                state = state,
                throttleMs = 112L
            )
        )
    }

    @Test
    fun `cross axis repeat does not consume the other axis timestamp`() {
        val state = DpadRepeatThrottleState(horizontalLastDirectionalInputUptimeMs = 1_000L)

        assertFalse(
            shouldConsumeDirectionalRepeat(
                axis = DpadRepeatThrottleAxis.VERTICAL,
                repeatCount = 1,
                nowUptimeMs = 1_050L,
                state = state,
                throttleMs = 112L
            )
        )
    }

    @Test
    fun `directionalAxisForKey maps dpad keys by axis`() {
        assertEquals(DpadRepeatThrottleAxis.HORIZONTAL, directionalAxisForKey(Key.DirectionLeft))
        assertEquals(DpadRepeatThrottleAxis.HORIZONTAL, directionalAxisForKey(Key.DirectionRight))
        assertEquals(DpadRepeatThrottleAxis.VERTICAL, directionalAxisForKey(Key.DirectionUp))
        assertEquals(DpadRepeatThrottleAxis.VERTICAL, directionalAxisForKey(Key.DirectionDown))
        assertEquals(null, directionalAxisForKey(Key.Enter))
    }
}
