package com.nexio.tv.domain.model

import org.junit.Assert.assertTrue
import org.junit.Test

class DisplaySourceRankTest {
    @Test
    fun `rank ordering matches spec`() {
        assertTrue(DisplaySourceRank.EMPTY < DisplaySourceRank.PLACEHOLDER)
        assertTrue(DisplaySourceRank.PLACEHOLDER < DisplaySourceRank.FIRST_PAINT)
        assertTrue(DisplaySourceRank.FIRST_PAINT < DisplaySourceRank.STALE_RESOLVED)
        assertTrue(DisplaySourceRank.STALE_RESOLVED < DisplaySourceRank.RESOLVED)
        assertTrue(DisplaySourceRank.RESOLVED < DisplaySourceRank.USER_PROFILE_OVERLAY)
    }
}
