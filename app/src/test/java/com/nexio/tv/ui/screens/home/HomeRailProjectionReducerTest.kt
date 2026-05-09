package com.nexio.tv.ui.screens.home

import com.nexio.tv.core.artwork.ArtworkDisplayRef
import com.nexio.tv.core.artwork.ArtworkTrace
import com.nexio.tv.core.artwork.ArtworkType
import com.nexio.tv.domain.model.DisplaySourceRank
import com.nexio.tv.domain.model.ResolvedDisplayFieldSlots
import com.nexio.tv.domain.model.ResolvedSlot
import org.junit.Assert.assertEquals
import org.junit.Test

class HomeRailProjectionReducerTest {
    @Test
    fun `RESOLVED poster beats FIRST_PAINT poster`() {
        val firstPaint = slotsWithPoster(rank = DisplaySourceRank.FIRST_PAINT, value = "https://addon/raw.jpg")
        val resolved = slotsWithPoster(rank = DisplaySourceRank.RESOLVED, value = "nexio-artwork://decision/abc")
        val merged = HomeRailProjectionReducer.reduce(firstPaint, resolved, existing = null, profile = null)
        assertEquals("nexio-artwork://decision/abc", (merged.poster.value as ArtworkDisplayRef.LegacyString).value)
        assertEquals(DisplaySourceRank.RESOLVED, merged.poster.rank)
    }

    @Test
    fun `STALE_RESOLVED beats FIRST_PAINT but loses to RESOLVED`() {
        val firstPaint = slotsWithPoster(DisplaySourceRank.FIRST_PAINT, "https://addon/raw.jpg")
        val stale = slotsWithPoster(DisplaySourceRank.STALE_RESOLVED, "nexio-artwork://decision/stale")
        val resolved = slotsWithPoster(DisplaySourceRank.RESOLVED, "nexio-artwork://decision/fresh")

        val mergedStale = HomeRailProjectionReducer.reduce(firstPaint, overlay = null, existing = stale, profile = null)
        assertEquals("nexio-artwork://decision/stale", (mergedStale.poster.value as ArtworkDisplayRef.LegacyString).value)

        val mergedFresh = HomeRailProjectionReducer.reduce(firstPaint, overlay = resolved, existing = stale, profile = null)
        assertEquals("nexio-artwork://decision/fresh", (mergedFresh.poster.value as ArtworkDisplayRef.LegacyString).value)
    }

    @Test
    fun `RESOLVED null logo is preserved against FIRST_PAINT logo`() {
        val firstPaint = slotsWithLogo(DisplaySourceRank.FIRST_PAINT, "first-paint-logo.png")
        val resolvedNull = slotsWithLogo(DisplaySourceRank.RESOLVED, value = null)
        val merged = HomeRailProjectionReducer.reduce(firstPaint, overlay = resolvedNull, existing = null, profile = null)
        assertEquals(null, merged.logo.value)
        assertEquals(DisplaySourceRank.RESOLVED, merged.logo.rank)
    }

    @Test
    fun `FIRST_PAINT only fills empty existing slots`() {
        val firstPaint = slotsWithPoster(DisplaySourceRank.FIRST_PAINT, "https://addon/raw.jpg")
        val emptySlots = ResolvedDisplayFieldSlots(
            title = ResolvedSlot.empty(0L),
            originalTitle = ResolvedSlot.empty(0L),
            overview = ResolvedSlot.empty(0L),
            genres = ResolvedSlot.empty(0L),
            releaseInfo = ResolvedSlot.empty(0L),
            runtime = ResolvedSlot.empty(0L),
            rating = ResolvedSlot.empty(0L),
            poster = ResolvedSlot.empty(0L),
            backdrop = ResolvedSlot.empty(0L),
            logo = ResolvedSlot.empty(0L),
            thumbnail = ResolvedSlot.empty(0L),
            posterProviderTag = ResolvedSlot.empty(0L)
        )
        val merged = HomeRailProjectionReducer.reduce(firstPaint, overlay = null, existing = emptySlots, profile = null)
        assertEquals(DisplaySourceRank.FIRST_PAINT, merged.poster.rank)
    }

    @Test
    fun `USER_PROFILE_OVERLAY beats RESOLVED`() {
        val resolved = slotsWithPoster(DisplaySourceRank.RESOLVED, "nexio-artwork://decision/abc")
        val profile = slotsWithPoster(DisplaySourceRank.USER_PROFILE_OVERLAY, "nexio-artwork://decision/user-pinned")
        val merged = HomeRailProjectionReducer.reduce(firstPaint = resolved, overlay = null, existing = null, profile = profile)
        assertEquals("nexio-artwork://decision/user-pinned", (merged.poster.value as ArtworkDisplayRef.LegacyString).value)
        assertEquals(DisplaySourceRank.USER_PROFILE_OVERLAY, merged.poster.rank)
    }

    private fun slotsWithPoster(rank: DisplaySourceRank, value: String?): ResolvedDisplayFieldSlots {
        val ref: ArtworkDisplayRef? = value?.let {
            ArtworkDisplayRef.LegacyString(it, ArtworkType.POSTER, ArtworkTrace.empty())
        }
        val r = if (value == null && rank != DisplaySourceRank.RESOLVED && rank != DisplaySourceRank.STALE_RESOLVED) DisplaySourceRank.EMPTY else rank
        return ResolvedDisplayFieldSlots(
            title = ResolvedSlot.empty(0L),
            originalTitle = ResolvedSlot.empty(0L),
            overview = ResolvedSlot.empty(0L),
            genres = ResolvedSlot.empty(0L),
            releaseInfo = ResolvedSlot.empty(0L),
            runtime = ResolvedSlot.empty(0L),
            rating = ResolvedSlot.empty(0L),
            poster = ResolvedSlot(ref, r, "TEST", "TEST", 0L, null, emptyList()),
            backdrop = ResolvedSlot.empty(0L),
            logo = ResolvedSlot.empty(0L),
            thumbnail = ResolvedSlot.empty(0L),
            posterProviderTag = ResolvedSlot.empty(0L)
        )
    }

    private fun slotsWithLogo(rank: DisplaySourceRank, value: String?): ResolvedDisplayFieldSlots {
        val ref: ArtworkDisplayRef? = value?.let {
            ArtworkDisplayRef.LegacyString(it, ArtworkType.LOGO, ArtworkTrace.empty())
        }
        return ResolvedDisplayFieldSlots(
            title = ResolvedSlot.empty(0L),
            originalTitle = ResolvedSlot.empty(0L),
            overview = ResolvedSlot.empty(0L),
            genres = ResolvedSlot.empty(0L),
            releaseInfo = ResolvedSlot.empty(0L),
            runtime = ResolvedSlot.empty(0L),
            rating = ResolvedSlot.empty(0L),
            poster = ResolvedSlot.empty(0L),
            backdrop = ResolvedSlot.empty(0L),
            logo = ResolvedSlot(ref, rank, "TEST", "TEST", 0L, null, emptyList()),
            thumbnail = ResolvedSlot.empty(0L),
            posterProviderTag = ResolvedSlot.empty(0L)
        )
    }
}
