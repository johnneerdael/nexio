package com.nexio.tv.ui.screens.home

import com.nexio.tv.core.artwork.ArtworkBundle
import com.nexio.tv.core.artwork.ArtworkDecisionKey
import com.nexio.tv.core.artwork.ArtworkDisplayRef
import com.nexio.tv.core.artwork.ArtworkDisplayHints
import com.nexio.tv.core.artwork.ArtworkSourceRole
import com.nexio.tv.core.artwork.ArtworkTrace
import com.nexio.tv.core.artwork.ArtworkType
import com.nexio.tv.core.integration.IntegrationProvider
import com.nexio.tv.domain.model.CatalogRow
import com.nexio.tv.domain.model.ContentType
import com.nexio.tv.domain.model.DisplaySourceRank
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
import org.junit.Assert.assertNotNull
import org.junit.Test

class HomeResolvedDisplayMapperReducerTest {
    @Test
    fun `mapper output populates slots and resolved poster wins over rail addon URL`() {
        val rawAddonItem = MetaPreview(
            id = "tmdb:550",
            type = ContentType.MOVIE,
            rawType = ContentType.MOVIE.toApiString(),
            name = "Fight Club (rail)",
            poster = "https://image.tmdb.org/raw.jpg",
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
        val itemKey = homeDisplayItemKey(rawAddonItem.apiType, rawAddonItem.id)
        val resolvedPoster = ArtworkDisplayRef.RuntimeAsset(
            decisionKey = ArtworkDecisionKey("artwork-decision-poster-imdb-tt0137523"),
            assetKey = null,
            imageType = ArtworkType.POSTER,
            selectedProvider = null,
            sourceRole = ArtworkSourceRole.PREMIUM,
            trace = ArtworkTrace.empty(),
            displayHints = ArtworkDisplayHints()
        )
        val overlayFields = HomeDisplayMetadata(
            title = "Fight Club (resolved)",
            artwork = ArtworkBundle(poster = resolvedPoster)
        )
        val overlay = HydratedHomeOverlay(
            overlayKey = hydratedHomeOverlayKey(
                canonicalProvider = ProviderId.TMDB,
                canonicalId = "550",
                contentType = ContentType.MOVIE,
                languageTag = "en-US",
                policyVersion = 1
            ),
            itemKey = itemKey,
            canonicalProvider = ProviderId.TMDB,
            canonicalId = "550",
            imdbId = "tt0137523",
            contentType = ContentType.MOVIE,
            languageTag = "en-US",
            policyVersion = 1,
            fields = overlayFields,
            fieldTrace = emptyList(),
            displayHash = overlayFields.hydratedHomeDisplayHash(),
            updatedAtMs = 1_000L,
            staleAtMs = 2_000L,
            expiresAtMs = 3_000L,
            state = HomeItemHydrationState.CANONICAL_READY
        )

        val items = HomeResolvedDisplayMapper.toResolvedDisplayItems(
            rows = listOf(
                CatalogRow(
                    addonId = "home",
                    addonName = "Home",
                    addonBaseUrl = "https://home.example",
                    catalogId = "tmdb:popular",
                    catalogName = "Popular",
                    type = ContentType.MOVIE,
                    items = listOf(rawAddonItem)
                )
            ),
            overlaysByItemKey = mapOf(itemKey to overlay),
            nowMs = 1_500L // between updatedAtMs (1_000) and staleAtMs (2_000) => CANONICAL_READY / RESOLVED rank
        )

        val resolved = items.single()
        assertNotNull(resolved.slots)
        assertEquals(DisplaySourceRank.RESOLVED, resolved.slots!!.poster.rank)
        // Title: overlay wins
        assertEquals("Fight Club (resolved)", resolved.slots.title.value)
        // Poster: durable runtime asset survives
        val posterRef = resolved.slots.poster.value
        assertNotNull(posterRef)
        assertEquals(true, posterRef is ArtworkDisplayRef.RuntimeAsset)
    }
}
