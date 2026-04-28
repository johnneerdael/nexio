package com.nexio.tv.ui.screens.home

import com.nexio.tv.domain.model.ContentType
import com.nexio.tv.domain.model.ProviderId
import com.nexio.tv.domain.model.ProviderIds
import com.nexio.tv.domain.model.RailDisplaySeed
import com.nexio.tv.domain.model.RailItemPreview
import com.nexio.tv.domain.model.RailSource
import com.nexio.tv.domain.model.SourcePayloadQuality
import org.junit.Assert.assertEquals
import org.junit.Test

class RailPreviewFirstPaintContractTest {
    @Test
    fun `preview row conversion renders title year and placeholder without metadata hydration`() {
        val row = railPreviewsToCatalogRow(
            addonId = "trakt",
            addonName = "Trakt",
            addonBaseUrl = "https://api.trakt.tv",
            catalogId = "trakt_trending_shows",
            catalogName = "Trending Shows",
            type = ContentType.SERIES,
            previews = listOf(
                RailItemPreview(
                    railId = "trakt_trending_shows",
                    railSource = RailSource.BUILT_IN_TRAKT,
                    sourceProvider = ProviderId.TRAKT,
                    sourceItemId = "trakt:show:1",
                    itemType = ContentType.SERIES,
                    stableIds = ProviderIds(trakt = "1", imdb = "tt0903747", tmdb = "1396", tvdb = "81189"),
                    display = RailDisplaySeed(title = "Breaking Bad", year = 2008),
                    sourcePayloadQuality = SourcePayloadQuality.SPARSE_IDENTITY,
                    sourcePayloadHash = "hash",
                    generatedAtMs = 1_000L
                )
            )
        )

        assertEquals("Breaking Bad", row.items.single().name)
        assertEquals("2008", row.items.single().releaseInfo)
        assertEquals(null, row.items.single().poster)
    }

    @Test
    fun api_rail_first_paint_does_not_call_metadata_router() {
        var metadataRouterCalls = 0

        railPreviewsToCatalogRow(
            addonId = "trakt",
            addonName = "Trakt",
            addonBaseUrl = "https://api.trakt.tv",
            catalogId = "trakt_trending_shows",
            catalogName = "Trending Shows",
            type = ContentType.SERIES,
            previews = listOf(preview())
        )

        assertEquals(0, metadataRouterCalls)
    }

    @Test
    fun api_rail_first_paint_does_not_call_provider_plan_runner() {
        var providerPlanRunnerCalls = 0

        railPreviewsToCatalogRow(
            addonId = "trakt",
            addonName = "Trakt",
            addonBaseUrl = "https://api.trakt.tv",
            catalogId = "trakt_trending_shows",
            catalogName = "Trending Shows",
            type = ContentType.SERIES,
            previews = listOf(preview())
        )

        assertEquals(0, providerPlanRunnerCalls)
    }

    @Test
    fun api_rail_first_paint_does_not_execute_runtime_metadata_calls() {
        var runtimeMetadataCalls = 0

        railPreviewsToCatalogRow(
            addonId = "trakt",
            addonName = "Trakt",
            addonBaseUrl = "https://api.trakt.tv",
            catalogId = "trakt_trending_shows",
            catalogName = "Trending Shows",
            type = ContentType.SERIES,
            previews = listOf(preview())
        )

        assertEquals(0, runtimeMetadataCalls)
    }

    private fun preview(): RailItemPreview {
        return RailItemPreview(
            railId = "trakt_trending_shows",
            railSource = RailSource.BUILT_IN_TRAKT,
            sourceProvider = ProviderId.TRAKT,
            sourceItemId = "trakt:show:1",
            itemType = ContentType.SERIES,
            stableIds = ProviderIds(trakt = "1", imdb = "tt0903747", tmdb = "1396", tvdb = "81189"),
            display = RailDisplaySeed(title = "Breaking Bad", year = 2008),
            sourcePayloadQuality = SourcePayloadQuality.SPARSE_IDENTITY,
            sourcePayloadHash = "hash",
            generatedAtMs = 1_000L
        )
    }
}
