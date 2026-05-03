package com.nexio.tv.core.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SubtitleBurnInProtectionTest {
    @Test
    fun disabled_state_has_zero_offsets_and_disabled_flag() {
        val state = BurnInProtectionState.DISABLED
        assertFalse(state.enabled)
        assertEquals(0f, state.verticalDeltaPercent, 0.0001f)
        assertEquals(0f, state.horizontalOffsetPx, 0.0001f)
    }

    @Test
    fun compute_returns_disabled_when_enabled_flag_false() {
        val state = computeBurnInProtectionState(
            enabled = false,
            mediaSeedKey = "tt1234567:s1e1",
            userSalt = "salt-abc",
            nowMs = 1_700_000_000_000L,
        )
        assertEquals(BurnInProtectionState.DISABLED, state)
    }

    @Test
    fun compute_is_deterministic_for_same_inputs() {
        val a = computeBurnInProtectionState(true, "tt1234567:s1e1", "salt-abc", 1_700_000_000_000L)
        val b = computeBurnInProtectionState(true, "tt1234567:s1e1", "salt-abc", 1_700_000_000_000L)
        assertEquals(a, b)
    }

    @Test
    fun compute_horizontal_offset_within_jitter_bounds() {
        val state = computeBurnInProtectionState(true, "anything", "salt", 1_700_000_000_000L)
        assertTrue(kotlin.math.abs(state.horizontalOffsetPx) <= SUBTITLE_BURN_IN_HORIZONTAL_JITTER_PX)
    }

    @Test
    fun seed_uses_content_id_with_season_and_episode_when_all_present() {
        assertEquals("tt9999:s2e5", buildMediaSeedKey("tt9999", 2, 5, "https://example/x"))
    }

    @Test
    fun seed_uses_content_id_alone_for_movies() {
        assertEquals("tt9999", buildMediaSeedKey("tt9999", null, null, "https://example/x"))
    }
}
