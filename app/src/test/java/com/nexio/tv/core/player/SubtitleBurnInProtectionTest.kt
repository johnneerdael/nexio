package com.nexio.tv.core.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class SubtitleBurnInProtectionTest {
    @Test
    fun disabled_state_has_zero_offsets_and_disabled_flag() {
        val state = BurnInProtectionState.DISABLED
        assertFalse(state.enabled)
        assertEquals(0f, state.verticalDeltaPercent, 0.0001f)
        assertEquals(0f, state.horizontalOffsetPx, 0.0001f)
    }
}
