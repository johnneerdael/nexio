package com.nexio.tv.ui.screens.home

import com.nexio.tv.domain.model.DisplaySourceRank
import com.nexio.tv.ui.screens.home.testutil.HomeReducerTestFixtures.posterSlot
import com.nexio.tv.ui.screens.home.testutil.HomeReducerTestFixtures.slotsWith
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Spec mandatory tests — Premium poster + stale-if-error invariants.
 *
 * These tests verify the reducer correctly chooses among premium-resolved (RPDB),
 * stale-RPDB-on-soft-failure, and TMDB fallback artwork. The reducer never
 * persists TMDB as authoritative when an RPDB resolution is pending; nor does
 * it allow TMDB to overwrite a stale RPDB on a transient refresh failure.
 */
class HomeRailProjectionPremiumTest {
    @Test
    fun `rpdb_poster_wins_for_tv_when_configured`() {
        val firstPaint = slotsWith(poster = posterSlot(DisplaySourceRank.FIRST_PAINT, "https://addon/raw.jpg", "TRAKT"))
        val rpdb = slotsWith(poster = posterSlot(DisplaySourceRank.RESOLVED, "nexio-artwork://decision/rpdb-tv", "RPDB"))
        val merged = HomeRailProjectionReducer.reduce(firstPaint, overlay = rpdb, existing = null, profile = null)
        assertEquals("RPDB", merged.poster.provider)
        assertEquals(DisplaySourceRank.RESOLVED, merged.poster.rank)
    }

    @Test
    fun `rpdb_poster_wins_for_movie_when_configured`() {
        val firstPaint = slotsWith(poster = posterSlot(DisplaySourceRank.FIRST_PAINT, "https://addon/raw.jpg", "TMDB"))
        val rpdb = slotsWith(poster = posterSlot(DisplaySourceRank.RESOLVED, "nexio-artwork://decision/rpdb-movie", "RPDB"))
        val merged = HomeRailProjectionReducer.reduce(firstPaint, overlay = rpdb, existing = null, profile = null)
        assertEquals("RPDB", merged.poster.provider)
        assertEquals(DisplaySourceRank.RESOLVED, merged.poster.rank)
    }

    @Test
    fun `soft_refresh_failure_keeps_stale_rpdb_poster`() {
        // Scenario: a previous RPDB resolution is now stale; this refresh's hydration
        // failed (overlay==null). FIRST_PAINT TMDB rail row must NOT replace the
        // stale RPDB ref because STALE_RESOLVED beats FIRST_PAINT.
        val firstPaint = slotsWith(poster = posterSlot(DisplaySourceRank.FIRST_PAINT, "https://image.tmdb.org/raw.jpg", "TMDB"))
        val staleRpdb = slotsWith(poster = posterSlot(DisplaySourceRank.STALE_RESOLVED, "nexio-artwork://decision/rpdb-stale", "RPDB"))
        val merged = HomeRailProjectionReducer.reduce(firstPaint, overlay = null, existing = staleRpdb, profile = null)
        assertEquals("RPDB", merged.poster.provider)
        assertEquals(DisplaySourceRank.STALE_RESOLVED, merged.poster.rank)
    }

    @Test
    fun `fresh_premium_pending_does_not_persist_tmdb_as_authoritative_replacement`() {
        // Scenario: rail emits TMDB FIRST_PAINT; RPDB resolution still pending
        // (no overlay yet, no existing slot). The reducer must mark this slot
        // as FIRST_PAINT — never RESOLVED — so that when RPDB lands, the
        // non-downgrade rule lets it replace TMDB.
        val firstPaint = slotsWith(poster = posterSlot(DisplaySourceRank.FIRST_PAINT, "https://image.tmdb.org/raw.jpg", "TMDB"))
        val merged = HomeRailProjectionReducer.reduce(firstPaint, overlay = null, existing = null, profile = null)
        assertEquals(DisplaySourceRank.FIRST_PAINT, merged.poster.rank)
        assertEquals("TMDB", merged.poster.provider)
    }

    @Test
    fun `premium_provider_disabled_allows_fallback`() {
        // Scenario: RPDB is disabled, so the overlay carries TMDB at RESOLVED rank
        // (the fallback hydration provider). RESOLVED TMDB must beat FIRST_PAINT TMDB.
        val firstPaint = slotsWith(poster = posterSlot(DisplaySourceRank.FIRST_PAINT, "https://image.tmdb.org/rail-raw.jpg", "TMDB"))
        val resolvedTmdb = slotsWith(poster = posterSlot(DisplaySourceRank.RESOLVED, "nexio-artwork://decision/tmdb-canonical", "TMDB"))
        val merged = HomeRailProjectionReducer.reduce(firstPaint, overlay = resolvedTmdb, existing = null, profile = null)
        assertEquals(DisplaySourceRank.RESOLVED, merged.poster.rank)
        assertEquals("TMDB", merged.poster.provider)
    }
}
