package com.nexio.tv.ui.screens.home

import com.nexio.tv.domain.model.CatalogRow
import com.nexio.tv.domain.model.ContentType
import com.nexio.tv.domain.model.FirstPaintSource
import com.nexio.tv.domain.model.HomeDisplayMetadata
import com.nexio.tv.domain.model.HomeItemHydrationState
import com.nexio.tv.domain.model.HydratedHomeOverlay
import com.nexio.tv.domain.model.MetaPreview
import com.nexio.tv.domain.model.PosterShape
import com.nexio.tv.domain.model.ProviderId
import com.nexio.tv.domain.model.ProviderIds
import com.nexio.tv.domain.model.homeDisplayItemKey
import com.nexio.tv.domain.model.hydratedHomeDisplayHash
import com.nexio.tv.domain.model.hydratedHomeOverlayKey
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

class HomeHydrationOverlayApplierShortCircuitTest {

    @Test
    fun `apply seam short-circuits when cache says overlay was already applied to itemKey`() {
        val item = baseItem()
        val overlay = baseOverlay(item)
        // Pre-populate the cache with the overlay's hash to simulate a prior successful apply.
        val cache = mutableMapOf(overlay.itemKey to overlay.displayHash)
        val row = baseRow(listOf(item))
        val applied = row.applyHydratedHomeOverlays(
            overlaysByItemKey = mapOf(overlay.itemKey to overlay),
            appliedOverlayHashes = cache
        )
        // Cache hit: item and row returned as the same instances — no slot conversion ran.
        assertSame(item, applied.items[0])
        assertSame(row, applied)
    }

    @Test
    fun `apply seam populates cache after successful projection`() {
        val item = baseItem()
        val overlay = baseOverlay(item)
        val cache = mutableMapOf<String, String>()
        val row = baseRow(listOf(item))
        row.applyHydratedHomeOverlays(
            overlaysByItemKey = mapOf(overlay.itemKey to overlay),
            appliedOverlayHashes = cache
        )
        // After a successful projection the cache must record the overlay's displayHash for this key.
        assertEquals(overlay.displayHash, cache[overlay.itemKey])
    }

    @Test
    fun `apply seam does not short-circuit when cache is null`() {
        // When no cache is provided (null), full slot conversion always runs — legacy behaviour.
        val item = baseItem()
        val overlay = baseOverlay(item)
        val row = baseRow(listOf(item))
        // Call without a cache — should not throw and must produce an updated row.
        val applied = row.applyHydratedHomeOverlays(
            overlaysByItemKey = mapOf(overlay.itemKey to overlay),
            appliedOverlayHashes = null
        )
        // The overlay carries a different title so the item must have changed.
        assertEquals(overlay.fields.title, applied.items[0].name)
    }

    @Test
    fun `apply seam re-runs projection when overlay displayHash changed even if cache had prior entry`() {
        val item = baseItem()
        val overlay = baseOverlay(item)
        // Cache carries a stale hash for the same key.
        val cache = mutableMapOf(overlay.itemKey to "stale-hash-from-previous-overlay")
        val row = baseRow(listOf(item))
        row.applyHydratedHomeOverlays(
            overlaysByItemKey = mapOf(overlay.itemKey to overlay),
            appliedOverlayHashes = cache
        )
        // Cache must now be updated to the current overlay's hash.
        assertEquals(overlay.displayHash, cache[overlay.itemKey])
    }

    // ── helpers ──────────────────────────────────────────────────────────────

    private fun baseItem(): MetaPreview = MetaPreview(
        id = "tmdb:550",
        type = ContentType.MOVIE,
        name = "Fight Club (rail)",
        poster = "https://image.tmdb.org/t/p/w500/raw.jpg",
        posterShape = PosterShape.POSTER,
        background = null,
        logo = null,
        description = null,
        genres = emptyList(),
        releaseInfo = null,
        runtime = null,
        imdbRating = null,
        ratingSource = null,
        tomatoesRating = null,
        trailerYtIds = emptyList(),
        language = null,
        posterProviderTag = null,
        firstPaintSource = FirstPaintSource.RAIL_PREVIEW,
        firstPaintSourceProvider = null,
        firstPaintStableIds = ProviderIds(tmdb = "550"),
        firstPaintRailSource = null,
        firstPaintSourceItemId = "tmdb:550",
        artwork = null
    )

    private fun baseOverlay(item: MetaPreview): HydratedHomeOverlay {
        val itemKey = homeDisplayItemKey(item.rawType, item.id)
        // Use a title distinct from the raw item so the projection actually changes the item.
        val fields = HomeDisplayMetadata(title = "Fight Club (resolved)")
        return HydratedHomeOverlay(
            overlayKey = hydratedHomeOverlayKey(ProviderId.TMDB, "550", ContentType.MOVIE, "en-US", 1),
            itemKey = itemKey,
            canonicalProvider = ProviderId.TMDB,
            canonicalId = "550",
            imdbId = "tt0137523",
            contentType = ContentType.MOVIE,
            languageTag = "en-US",
            policyVersion = 1,
            fields = fields,
            fieldTrace = emptyList(),
            displayHash = fields.hydratedHomeDisplayHash(),
            updatedAtMs = 1_000L,
            staleAtMs = 2_000L,
            expiresAtMs = 3_000L,
            state = HomeItemHydrationState.CANONICAL_READY
        )
    }

    private fun baseRow(items: List<MetaPreview>): CatalogRow = CatalogRow(
        addonId = "",
        addonName = "",
        addonBaseUrl = "",
        catalogId = "tmdb:popular",
        catalogName = "Popular",
        type = ContentType.MOVIE,
        items = items
    )
}
