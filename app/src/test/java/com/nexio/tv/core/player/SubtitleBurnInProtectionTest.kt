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
    fun compute_changes_across_day_boundary_at_least_70_percent_of_the_time() {
        val day1Ms = 1_700_000_000_000L
        val day2Ms = day1Ms + 24L * 60 * 60 * 1000
        var different = 0
        val total = 100
        repeat(total) { i ->
            val seed = "tt$i:s1e1"
            val a = computeBurnInProtectionState(true, seed, "salt-abc", day1Ms)
            val b = computeBurnInProtectionState(true, seed, "salt-abc", day2Ms)
            if (a.verticalDeltaPercent != b.verticalDeltaPercent) different++
        }
        assertTrue("expected day rollover to change zone >=70% of media; got $different/$total", different >= 70)
    }

    @Test
    fun compute_zone_distribution_is_roughly_uniform_across_distinct_media() {
        val nowMs = 1_700_000_000_000L
        val buckets = IntArray(SUBTITLE_BURN_IN_ZONE_COUNT)
        val total = 500
        repeat(total) { i ->
            val state = computeBurnInProtectionState(true, "tt$i:s1e1", "salt-abc", nowMs)
            val deltaSlots = listOf(-3f, -1.5f, 0f, 1.5f, 3f)
            val idx = deltaSlots.indexOfFirst { kotlin.math.abs(it - state.verticalDeltaPercent) < 0.01f }
            assertTrue("delta ${state.verticalDeltaPercent} not in expected slots", idx >= 0)
            buckets[idx]++
        }
        buckets.forEach { count ->
            val pct = count.toFloat() / total
            assertTrue("bucket $count outside [0.10, 0.40] (pct=$pct)", pct in 0.10f..0.40f)
        }
    }

    @Test
    fun compute_horizontal_offset_within_jitter_bounds() {
        val state = computeBurnInProtectionState(true, "anything", "salt", 1_700_000_000_000L)
        assertTrue(kotlin.math.abs(state.horizontalOffsetPx) <= SUBTITLE_BURN_IN_HORIZONTAL_JITTER_PX)
    }

    @Test
    fun off_white_constant_is_F0F0F0() {
        assertEquals(0xFFF0F0F0.toInt(), SUBTITLE_OFF_WHITE_ARGB)
    }

    @Test
    fun max_alpha_constant_is_zero_point_nine() {
        assertEquals(0.90f, SUBTITLE_MAX_ALPHA, 0.0001f)
    }

    @Test
    fun seed_uses_content_id_with_season_and_episode_when_all_present() {
        assertEquals("tt9999:s2e5", buildMediaSeedKey("tt9999", 2, 5, "https://example/x"))
    }

    @Test
    fun seed_uses_content_id_alone_for_movies() {
        assertEquals("tt9999", buildMediaSeedKey("tt9999", null, null, "https://example/x"))
    }

    @Test
    fun seed_falls_back_to_stream_url_hash_when_content_id_missing() {
        val seed = buildMediaSeedKey(null, null, null, "https://example/movie.mkv")
        assertEquals("url:${"https://example/movie.mkv".hashCode()}", seed)
    }

    @Test
    fun seed_treats_blank_content_id_as_missing() {
        val seed = buildMediaSeedKey("   ", null, null, "https://example/y")
        assertEquals("url:${"https://example/y".hashCode()}", seed)
    }
}
