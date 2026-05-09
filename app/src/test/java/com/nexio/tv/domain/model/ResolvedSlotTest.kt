package com.nexio.tv.domain.model

import org.junit.Assert.assertEquals
import org.junit.Test

class ResolvedSlotTest {
    @Test
    fun `chooseHigherRank prefers RESOLVED over FIRST_PAINT`() {
        val resolved = ResolvedSlot(
            value = "resolved-poster",
            rank = DisplaySourceRank.RESOLVED,
            provider = "TVDB",
            role = "PRIMARY",
            updatedAtMs = 100L,
            expiresAtMs = null,
            trace = listOf("hydration:tvdb")
        )
        val firstPaint = ResolvedSlot(
            value = "first-paint-poster",
            rank = DisplaySourceRank.FIRST_PAINT,
            provider = "TRAKT",
            role = "RAIL_PREVIEW",
            updatedAtMs = 200L,
            expiresAtMs = null,
            trace = listOf("rail:trakt")
        )
        assertEquals(resolved, ResolvedSlot.chooseHigherRank(resolved, firstPaint))
        assertEquals(resolved, ResolvedSlot.chooseHigherRank(firstPaint, resolved))
    }

    @Test
    fun `chooseHigherRank prefers non-null when other side is null`() {
        val firstPaint = ResolvedSlot(
            value = "first-paint",
            rank = DisplaySourceRank.FIRST_PAINT,
            provider = "TMDB",
            role = "RAIL_PREVIEW",
            updatedAtMs = 100L,
            expiresAtMs = null,
            trace = emptyList()
        )
        val empty = ResolvedSlot<String>(
            value = null,
            rank = DisplaySourceRank.EMPTY,
            provider = null,
            role = null,
            updatedAtMs = 100L,
            expiresAtMs = null,
            trace = emptyList()
        )
        assertEquals(firstPaint, ResolvedSlot.chooseHigherRank(firstPaint, empty))
    }

    @Test
    fun `chooseHigherRank with null value at higher rank still beats lower rank with value`() {
        // Spec rule: a higher rank ALWAYS wins, even if its value is null.
        // Null at RESOLVED means "this provider explicitly resolved no value" — must not
        // be silently replaced by FIRST_PAINT.
        val resolvedNull = ResolvedSlot<String>(
            value = null,
            rank = DisplaySourceRank.RESOLVED,
            provider = "TVDB",
            role = "PRIMARY",
            updatedAtMs = 200L,
            expiresAtMs = null,
            trace = listOf("hydration:tvdb:no_logo")
        )
        val firstPaint = ResolvedSlot(
            value = "first-paint-logo",
            rank = DisplaySourceRank.FIRST_PAINT,
            provider = "TRAKT",
            role = "RAIL_PREVIEW",
            updatedAtMs = 100L,
            expiresAtMs = null,
            trace = emptyList()
        )
        assertEquals(resolvedNull, ResolvedSlot.chooseHigherRank(resolvedNull, firstPaint))
    }
}
