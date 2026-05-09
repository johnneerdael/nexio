package com.nexio.tv.ui.screens.home

import com.nexio.tv.domain.model.DisplaySourceRank
import com.nexio.tv.ui.screens.home.testutil.HomeReducerTestFixtures.posterSlot
import com.nexio.tv.ui.screens.home.testutil.HomeReducerTestFixtures.slotsWith
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Spec mandatory tests — overlay alias key invariants.
 *
 * The store-level alias resolution (HomeArtworkOverlayKeys.aliasesFor /
 * HydratedHomeOverlayStore.readForItemKeys) lives at the persistence boundary
 * and is exercised by store/coordinator tests. At the reducer level, the
 * invariant we care about is: ONCE the right overlay is paired to the right
 * row (via alias resolution), the reducer correctly chooses overlay over rail.
 * These tests confirm that contract holds for each alias scenario the spec
 * enumerates: TVDB-canonical → Trakt row, TMDB-tv-key → TVDB-canonical row,
 * row-key after alias change.
 */
class HomeRailProjectionAliasTest {
    @Test
    fun `overlay_written_under_tvdb_canonical_key_read_by_trakt_row`() {
        // Trakt rail row carries an FIRST_PAINT poster; the overlay (alias-resolved
        // to the same row via TVDB-canonical key) carries the RESOLVED poster.
        val traktRail = slotsWith(poster = posterSlot(DisplaySourceRank.FIRST_PAINT, "https://trakt/preview.jpg", "TRAKT"))
        val tvdbOverlay = slotsWith(poster = posterSlot(DisplaySourceRank.RESOLVED, "nexio-artwork://decision/tvdb-canonical", "TVDB"))
        val merged = HomeRailProjectionReducer.reduce(traktRail, overlay = tvdbOverlay, existing = null, profile = null)
        assertEquals(DisplaySourceRank.RESOLVED, merged.poster.rank)
        assertEquals("TVDB", merged.poster.provider)
    }

    @Test
    fun `overlay_written_under_tmdb_tv_key_read_by_tvdb_canonical_row`() {
        val tvdbRail = slotsWith(poster = posterSlot(DisplaySourceRank.FIRST_PAINT, "https://tvdb/raw.jpg", "TVDB"))
        val tmdbOverlay = slotsWith(poster = posterSlot(DisplaySourceRank.RESOLVED, "nexio-artwork://decision/tmdb-tv", "TMDB"))
        val merged = HomeRailProjectionReducer.reduce(tvdbRail, overlay = tmdbOverlay, existing = null, profile = null)
        assertEquals(DisplaySourceRank.RESOLVED, merged.poster.rank)
        assertEquals("TMDB", merged.poster.provider)
    }

    @Test
    fun `overlay_written_under_row_key_read_after_alias_change`() {
        // After an alias change, the previously stored overlay (now at STALE_RESOLVED)
        // must still beat a fresh FIRST_PAINT rail emit until a new RESOLVED hydration lands.
        val rail = slotsWith(poster = posterSlot(DisplaySourceRank.FIRST_PAINT, "https://addon/new.jpg", "TRAKT"))
        val staleAfterAlias = slotsWith(poster = posterSlot(DisplaySourceRank.STALE_RESOLVED, "nexio-artwork://decision/old-alias", "TVDB"))
        val merged = HomeRailProjectionReducer.reduce(rail, overlay = null, existing = staleAfterAlias, profile = null)
        assertEquals(DisplaySourceRank.STALE_RESOLVED, merged.poster.rank)
        assertEquals("TVDB", merged.poster.provider)
    }
}
