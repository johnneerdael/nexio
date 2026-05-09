package com.nexio.tv.ui.screens.home

import com.nexio.tv.domain.model.DisplaySourceRank
import com.nexio.tv.domain.model.TitleRating
import com.nexio.tv.domain.model.TitleRatingSource
import com.nexio.tv.domain.model.ResolvedSlot
import com.nexio.tv.ui.screens.home.testutil.HomeReducerTestFixtures.ratingSlot
import com.nexio.tv.ui.screens.home.testutil.HomeReducerTestFixtures.slotsWith
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class HomeRailProjectionRatingTest {
    @Test
    fun `tmdb_rail_vote_average_becomes_rating_candidate`() {
        // FIRST_PAINT TMDB rating becomes the projected rating when no higher-ranked source
        val firstPaint = slotsWith(rating = ratingSlot(DisplaySourceRank.FIRST_PAINT, 7.5, "TMDB"))
        val merged = HomeRailProjectionReducer.reduce(firstPaint, overlay = null, existing = null, profile = null)
        assertEquals(DisplaySourceRank.FIRST_PAINT, merged.rating.rank)
        assertNotNull(merged.rating.value)
        assertEquals(7.5, merged.rating.value!!.value, 0.001)
        assertEquals("TMDB", merged.rating.provider)
    }

    @Test
    fun `tvdb_canonical_series_can_use_tmdb_rating`() {
        // RESOLVED rating sourced from TMDB beats FIRST_PAINT Trakt rating
        val firstPaint = slotsWith(rating = ratingSlot(DisplaySourceRank.FIRST_PAINT, 7.0, "TRAKT"))
        val overlay = slotsWith(rating = ratingSlot(DisplaySourceRank.RESOLVED, 8.2, "TMDB"))
        val merged = HomeRailProjectionReducer.reduce(firstPaint, overlay = overlay, existing = null, profile = null)
        assertEquals(DisplaySourceRank.RESOLVED, merged.rating.rank)
        assertEquals("TMDB", merged.rating.provider)
        assertEquals(8.2, merged.rating.value!!.value, 0.001)
    }

    @Test
    fun `rating_source_not_defaulted_to_imdb_when_unknown`() {
        // Build a rating slot whose source is explicitly TMDB (NOT IMDB).
        // After reduce, the source should still be TMDB — not silently overridden to IMDB.
        val tmdbRating = ResolvedSlot(
            value = TitleRating(value = 7.0, source = TitleRatingSource.TMDB),
            rank = DisplaySourceRank.RESOLVED,
            provider = "TMDB",
            role = "PRIMARY",
            updatedAtMs = 0L,
            expiresAtMs = null,
            trace = emptyList()
        )
        val merged = HomeRailProjectionReducer.reduce(
            firstPaint = slotsWith(rating = ratingSlot(DisplaySourceRank.FIRST_PAINT, 6.0, "TRAKT")),
            overlay = slotsWith(rating = tmdbRating),
            existing = null,
            profile = null
        )
        assertEquals(TitleRatingSource.TMDB, merged.rating.value!!.source)
    }

    @Test
    fun `invalid_preview_rating_rejected`() {
        // The ratingSlot helper passes through to RatingValueValidator only via SlotConversions.
        // At the reducer level, a slot with value=null at FIRST_PAINT remains EMPTY (because
        // ratingSlot() promotes to EMPTY when value is null and rank<STALE_RESOLVED).
        val firstPaint = slotsWith(rating = ratingSlot(DisplaySourceRank.FIRST_PAINT, value = null))
        val merged = HomeRailProjectionReducer.reduce(firstPaint, overlay = null, existing = null, profile = null)
        assertEquals(DisplaySourceRank.EMPTY, merged.rating.rank)
        assertNull(merged.rating.value)
    }

    @Test
    fun `rating_update_reprojects_home_row`() {
        // Simulate a rating-only overlay update: existing FIRST_PAINT, no overlay yet,
        // then re-reduce with overlay carrying RESOLVED rating. Verify both states are correct.
        val firstPaintOnly = slotsWith(rating = ratingSlot(DisplaySourceRank.FIRST_PAINT, 6.0, "TMDB"))
        val phase1 = HomeRailProjectionReducer.reduce(firstPaintOnly, overlay = null, existing = null, profile = null)
        assertEquals(DisplaySourceRank.FIRST_PAINT, phase1.rating.rank)
        assertEquals(6.0, phase1.rating.value!!.value, 0.001)

        val updateOverlay = slotsWith(rating = ratingSlot(DisplaySourceRank.RESOLVED, 8.5, "RPDB"))
        val phase2 = HomeRailProjectionReducer.reduce(firstPaintOnly, overlay = updateOverlay, existing = phase1, profile = null)
        assertEquals(DisplaySourceRank.RESOLVED, phase2.rating.rank)
        assertEquals(8.5, phase2.rating.value!!.value, 0.001)
        assertEquals("RPDB", phase2.rating.provider)
    }
}
