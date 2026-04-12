package com.nexio.tv.ui.screens.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VodWarmAheadPolicyTest {
    @Test
    fun enabledSettingAllowsWarmAheadWhenVodCacheIsEnabled() {
        assertTrue(
            VodWarmAheadPolicy.shouldStartWarmAhead(
                useVodCache = true,
                warmAheadEnabled = true
            )
        )
    }

    @Test
    fun disabledSettingDisablesWarmAheadWhenVodCacheIsEnabled() {
        assertFalse(
            VodWarmAheadPolicy.shouldStartWarmAhead(
                useVodCache = true,
                warmAheadEnabled = false
            )
        )
    }

    @Test
    fun disabledVodCacheDisablesWarmAheadEvenWhenSettingIsEnabled() {
        assertFalse(
            VodWarmAheadPolicy.shouldStartWarmAhead(
                useVodCache = false,
                warmAheadEnabled = true
            )
        )
    }

    @Test
    fun warmAheadUsesPlaybackCacheKey() {
        assertEquals(
            "https://real-debrid.com/d/ABC/movie.mkv",
            VodWarmAheadPolicy.warmAheadCacheKey(
                playbackStreamUrl = "https://real-debrid.com/d/ABC/movie.mkv",
                resolvedRequestUrl = "https://cdn.example.net/file"
            )
        )
    }
}
