package com.nexio.tv.ui.screens.home

import com.nexio.tv.domain.model.CatalogRow
import com.nexio.tv.domain.model.ContentType
import com.nexio.tv.domain.model.FirstPaintSource
import com.nexio.tv.domain.model.HomeItemHydrationState
import com.nexio.tv.domain.model.HydratedHomeOverlay
import com.nexio.tv.domain.model.MetaPreview
import com.nexio.tv.domain.model.PosterShape
import com.nexio.tv.domain.model.ProviderId
import com.nexio.tv.domain.model.ProviderIds
import com.nexio.tv.domain.model.homeDisplayItemKey
import com.nexio.tv.domain.model.hydratedHomeOverlayKey
import com.nexio.tv.domain.model.toHomeDisplayMetadata
import org.junit.Assert.assertSame
import org.junit.Test

class HomeHydrationOverlayApplierShortCircuitTest {
    @Test
    fun `apply seam returns same item when overlay displayHash matches current projection`() {
        // Build a MetaPreview whose toHomeDisplayMetadata().hydratedHomeDisplayHash()
        // matches the overlay's displayHash exactly. The seam should short-circuit and
        // return the same item instance (===), proving no slot conversion ran.
        val item = MetaPreview(
            id = "tmdb:550",
            type = ContentType.MOVIE,
            name = "Fight Club",
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
        // Compute the hash the overlay must match.
        val itemHash = item.displayHashForHomeOverlay()
        val itemKey = homeDisplayItemKey(item.rawType, item.id)
        // Build an overlay whose displayHash equals itemHash.
        val overlayFields = item.toHomeDisplayMetadata()
        val overlay = HydratedHomeOverlay(
            overlayKey = hydratedHomeOverlayKey(ProviderId.TMDB, "550", ContentType.MOVIE, "en-US", 1),
            itemKey = itemKey,
            canonicalProvider = ProviderId.TMDB,
            canonicalId = "550",
            imdbId = null,
            contentType = ContentType.MOVIE,
            languageTag = "en-US",
            policyVersion = 1,
            fields = overlayFields,
            fieldTrace = emptyList(),
            displayHash = itemHash,
            updatedAtMs = 0L,
            staleAtMs = 0L,
            expiresAtMs = 0L,
            state = HomeItemHydrationState.CANONICAL_READY
        )
        val row = CatalogRow(
            catalogId = "tmdb:popular",
            type = ContentType.MOVIE,
            catalogName = "Popular",
            addonId = "stub",
            addonName = "stub",
            addonBaseUrl = "https://stub",
            items = listOf(item)
        )
        val applied = row.applyHydratedHomeOverlays(mapOf(overlay.itemKey to overlay))
        // Short-circuit: same item instance; row also returned as-is (no copy).
        assertSame(item, applied.items[0])
        assertSame(row, applied)
    }
}
