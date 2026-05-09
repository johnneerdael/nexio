package com.nexio.tv.ui.screens.home

import com.nexio.tv.domain.model.DisplaySourceRank
import com.nexio.tv.ui.screens.home.testutil.HomeReducerTestFixtures.backdropSlot
import com.nexio.tv.ui.screens.home.testutil.HomeReducerTestFixtures.logoSlot
import com.nexio.tv.ui.screens.home.testutil.HomeReducerTestFixtures.posterSlot
import com.nexio.tv.ui.screens.home.testutil.HomeReducerTestFixtures.ratingSlot
import com.nexio.tv.ui.screens.home.testutil.HomeReducerTestFixtures.slotsWith
import com.nexio.tv.ui.screens.home.testutil.HomeReducerTestFixtures.stringSlot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test

class HomeFirstPaintInvariantTest {
    @Test
    fun `late_first_paint_does_not_replace_hydrated_poster`() {
        val resolved = slotsWith(poster = posterSlot(DisplaySourceRank.RESOLVED, "nexio-artwork://decision/abc", "TVDB"))
        val lateFirstPaint = slotsWith(poster = posterSlot(DisplaySourceRank.FIRST_PAINT, "https://addon/raw.jpg", "TRAKT"))
        val merged = HomeRailProjectionReducer.reduce(
            firstPaint = lateFirstPaint,
            overlay = null,
            existing = resolved,
            profile = null
        )
        assertEquals(DisplaySourceRank.RESOLVED, merged.poster.rank)
        assertEquals("TVDB", merged.poster.provider)
    }

    @Test
    fun `late_first_paint_does_not_replace_hydrated_backdrop`() {
        val resolved = slotsWith(backdrop = backdropSlot(DisplaySourceRank.RESOLVED, "nexio-artwork://decision/bg", "TVDB"))
        val lateFirstPaint = slotsWith(backdrop = backdropSlot(DisplaySourceRank.FIRST_PAINT, "https://addon/bg.jpg", "TRAKT"))
        val merged = HomeRailProjectionReducer.reduce(lateFirstPaint, null, resolved, null)
        assertEquals(DisplaySourceRank.RESOLVED, merged.backdrop.rank)
    }

    @Test
    fun `late_first_paint_does_not_replace_hydrated_logo`() {
        val resolved = slotsWith(logo = logoSlot(DisplaySourceRank.RESOLVED, "nexio-artwork://decision/logo", "TVDB"))
        val lateFirstPaint = slotsWith(logo = logoSlot(DisplaySourceRank.FIRST_PAINT, "https://addon/logo.png", "TRAKT"))
        val merged = HomeRailProjectionReducer.reduce(lateFirstPaint, null, resolved, null)
        assertEquals(DisplaySourceRank.RESOLVED, merged.logo.rank)
    }

    @Test
    fun `late_first_paint_does_not_clear_resolved_rating`() {
        val resolved = slotsWith(rating = ratingSlot(DisplaySourceRank.RESOLVED, 8.0, "TVDB"))
        val lateFirstPaint = slotsWith(rating = ratingSlot(DisplaySourceRank.FIRST_PAINT, null, "TRAKT"))
        val merged = HomeRailProjectionReducer.reduce(lateFirstPaint, null, resolved, null)
        assertEquals(DisplaySourceRank.RESOLVED, merged.rating.rank)
        assertNotNull(merged.rating.value)
        assertEquals(8.0, merged.rating.value!!.value, 0.001)
    }

    @Test
    fun `late_first_paint_null_fields_do_not_remove_hydrated_fields`() {
        val resolved = slotsWith(title = stringSlot(DisplaySourceRank.RESOLVED, "Real Title", "TVDB"))
        val lateFirstPaint = slotsWith(title = stringSlot(DisplaySourceRank.FIRST_PAINT, null, "TRAKT"))
        val merged = HomeRailProjectionReducer.reduce(lateFirstPaint, null, resolved, null)
        assertEquals(DisplaySourceRank.RESOLVED, merged.title.rank)
        assertEquals("Real Title", merged.title.value)
    }
}
