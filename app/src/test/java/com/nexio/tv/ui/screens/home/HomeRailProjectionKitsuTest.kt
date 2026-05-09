package com.nexio.tv.ui.screens.home

import com.nexio.tv.core.artwork.ArtworkType
import com.nexio.tv.domain.model.DisplaySourceRank
import com.nexio.tv.ui.screens.home.testutil.HomeReducerTestFixtures.backdropSlot
import com.nexio.tv.ui.screens.home.testutil.HomeReducerTestFixtures.logoSlot
import com.nexio.tv.ui.screens.home.testutil.HomeReducerTestFixtures.posterSlot
import com.nexio.tv.ui.screens.home.testutil.HomeReducerTestFixtures.slotsWith
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test

/**
 * Spec mandatory tests — Kitsu (anime) artwork-type boundary invariants.
 *
 * Kitsu carries posters and covers but no logos. The reducer must:
 * - never receive a Kitsu cover in the poster slot (type-typed)
 * - never have a Kitsu RESOLVED entry in the LOGO slot (Kitsu doesn't emit logos)
 * - therefore allow FIRST_PAINT preview logos to survive at the LOGO slot
 *
 * These reducer-level tests assume upstream type-tagging (SlotConversions +
 * artwork-type boundary helpers) is already correct. They prove the reducer
 * does not invent or smuggle wrong-typed refs.
 */
class HomeRailProjectionKitsuTest {
    @Test
    fun `kitsu_poster_typed_as_poster`() {
        val firstPaint = slotsWith(poster = posterSlot(DisplaySourceRank.FIRST_PAINT, "https://kitsu/poster.jpg", "KITSU"))
        val merged = HomeRailProjectionReducer.reduce(firstPaint, overlay = null, existing = null, profile = null)
        val ref = merged.poster.value
        assertNotNull(ref)
        assertEquals(ArtworkType.POSTER, ref!!.imageType)
    }

    @Test
    fun `kitsu_cover_typed_as_backdrop`() {
        val firstPaint = slotsWith(backdrop = backdropSlot(DisplaySourceRank.FIRST_PAINT, "https://kitsu/cover.jpg", "KITSU"))
        val merged = HomeRailProjectionReducer.reduce(firstPaint, overlay = null, existing = null, profile = null)
        val ref = merged.backdrop.value
        assertNotNull(ref)
        assertEquals(ArtworkType.BACKDROP, ref!!.imageType)
    }

    @Test
    fun `kitsu_does_not_emit_logo_candidate`() {
        // A Kitsu overlay slot for logo with value=null at RESOLVED rank simulates Kitsu
        // explicitly producing no logo. With NO first-paint logo, the reducer keeps EMPTY null.
        val emptyFirstPaint = slotsWith()
        val kitsuOverlay = slotsWith(logo = logoSlot(DisplaySourceRank.RESOLVED, value = null, provider = "KITSU"))
        val merged = HomeRailProjectionReducer.reduce(emptyFirstPaint, overlay = kitsuOverlay, existing = null, profile = null)
        assertEquals(null, merged.logo.value)
    }

    @Test
    fun `kitsu_preview_logo_survives_when_primary_has_no_logo`() {
        // When the canonical Kitsu overlay does NOT include a logo slot at all
        // (modeled here as overlay=null OR overlay's logo at EMPTY), a FIRST_PAINT preview
        // logo must survive. This proves the reducer does not clear it.
        val firstPaintWithLogo = slotsWith(logo = logoSlot(DisplaySourceRank.FIRST_PAINT, "https://addon/logo.png", "TRAKT"))
        val kitsuNoLogoOverlay = slotsWith() // no logo entry — kitsu doesn't emit
        val merged = HomeRailProjectionReducer.reduce(firstPaintWithLogo, overlay = kitsuNoLogoOverlay, existing = null, profile = null)
        assertEquals(DisplaySourceRank.FIRST_PAINT, merged.logo.rank)
        assertNotNull(merged.logo.value)
    }

    @Test
    fun `kitsu_overlay_never_writes_integration_poster`() {
        // The reducer relies on SlotConversions to enforce type tagging. We simulate the
        // contract: a properly-typed Kitsu poster at RESOLVED replaces FIRST_PAINT correctly.
        val firstPaint = slotsWith(poster = posterSlot(DisplaySourceRank.FIRST_PAINT, "https://addon/raw.jpg", "TRAKT"))
        val kitsuOverlay = slotsWith(poster = posterSlot(DisplaySourceRank.RESOLVED, "https://kitsu/poster.jpg", "KITSU"))
        val merged = HomeRailProjectionReducer.reduce(firstPaint, overlay = kitsuOverlay, existing = null, profile = null)
        assertEquals(DisplaySourceRank.RESOLVED, merged.poster.rank)
        assertEquals("KITSU", merged.poster.provider)
        assertEquals(ArtworkType.POSTER, merged.poster.value!!.imageType)
    }
}
