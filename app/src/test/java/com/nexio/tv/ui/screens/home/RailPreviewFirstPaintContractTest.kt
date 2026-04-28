package com.nexio.tv.ui.screens.home

import com.nexio.tv.domain.model.ContentType
import com.nexio.tv.domain.model.ProviderId
import com.nexio.tv.domain.model.ProviderIds
import com.nexio.tv.domain.model.RailDisplaySeed
import com.nexio.tv.domain.model.RailItemPreview
import com.nexio.tv.domain.model.RailSource
import com.nexio.tv.domain.model.SourcePayloadQuality
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
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
        assertFirstPaintHelperOnlyAcceptsDisplayInputs()
        assertFirstPaintBoundaryDoesNotReference(
            "metadata router",
            "MetadataRouter",
            "MetadataRouterFacade",
            "ProviderMetadataRouter",
            "TvMetadataRouter",
            "metadataRouter"
        )
        assertFirstPaintConversionUsesDelegatedPreviewDataOnly()
    }

    @Test
    fun api_rail_first_paint_does_not_call_provider_plan_runner() {
        assertFirstPaintHelperOnlyAcceptsDisplayInputs()
        assertFirstPaintBoundaryDoesNotReference(
            "provider plan runner",
            "ProviderPlanRunner",
            "providerPlanRunner"
        )
        assertFirstPaintConversionUsesDelegatedPreviewDataOnly()
    }

    @Test
    fun api_rail_first_paint_does_not_execute_runtime_metadata_calls() {
        assertFirstPaintHelperOnlyAcceptsDisplayInputs()
        assertFirstPaintBoundaryDoesNotReference(
            "runtime metadata calls",
            "runtimeMetadata",
            "resolveHomeRequest",
            "resolveRequest",
            "fetchEnrichment",
            "fetchEpisodeEnrichment",
            "fetchSeasonEpisodes",
            ".fetch"
        )
        assertFirstPaintConversionUsesDelegatedPreviewDataOnly()
    }

    private fun assertFirstPaintHelperOnlyAcceptsDisplayInputs() {
        val helperSource = railPreviewsToCatalogRowSource()

        assertTrue(helperSource.contains("addonId: String"))
        assertTrue(helperSource.contains("addonName: String"))
        assertTrue(helperSource.contains("addonBaseUrl: String"))
        assertTrue(helperSource.contains("catalogId: String"))
        assertTrue(helperSource.contains("catalogName: String"))
        assertTrue(helperSource.contains("type: ContentType"))
        assertTrue(helperSource.contains("previews: List<RailItemPreview>"))
    }

    private fun assertFirstPaintBoundaryDoesNotReference(systemName: String, vararg prohibitedTerms: String) {
        val sourceBoundary = firstPaintConversionBoundarySource()

        prohibitedTerms.forEach { term ->
            assertFalse("First-paint conversion must not reference $systemName term '$term'", sourceBoundary.contains(term))
        }
    }

    private fun assertFirstPaintConversionUsesDelegatedPreviewDataOnly() {
        val helperSource = railPreviewsToCatalogRowSource()

        assertTrue(
            "First-paint helper must delegate only through RailItemPreview.toMetaPreview()",
            helperSource.contains("items = previews.map { it.toMetaPreview() }")
        )

        val row = railPreviewsToCatalogRow(
            addonId = "trakt",
            addonName = "Trakt",
            addonBaseUrl = "https://api.trakt.tv",
            catalogId = "trakt_trending_shows",
            catalogName = "Trending Shows",
            type = ContentType.SERIES,
            previews = listOf(preview())
        )

        assertEquals("Breaking Bad", row.items.single().name)
        assertEquals("2008", row.items.single().releaseInfo)
        assertEquals(null, row.items.single().poster)
    }

    private fun firstPaintConversionBoundarySource(): String {
        return railPreviewsToCatalogRowSource() + "\n" + railItemPreviewToMetaPreviewSource()
    }

    private fun railPreviewsToCatalogRowSource(): String {
        val source = File("app/src/main/java/com/nexio/tv/ui/screens/home/HomeViewModelCatalogUtils.kt").readText()
        val start = source.indexOf("internal fun railPreviewsToCatalogRow(")
        val end = source.indexOf("\n\ninternal fun HomeViewModel.catalogKey", start)

        assertTrue("railPreviewsToCatalogRow source should exist", start >= 0)
        assertTrue("railPreviewsToCatalogRow source boundary should exist", end > start)

        return source.substring(start, end)
    }

    private fun railItemPreviewToMetaPreviewSource(): String {
        val source = File("app/src/main/java/com/nexio/tv/domain/model/RailItemPreview.kt").readText()
        val start = source.indexOf("fun RailItemPreview.toMetaPreview(): MetaPreview")
        val ratingSourceHelperStart = source.indexOf("\n\nprivate fun ProviderId?.toTitleRatingSource()", start)
        val end = source.indexOf("\n}", ratingSourceHelperStart)

        assertTrue("RailItemPreview.toMetaPreview source should exist", start >= 0)
        assertTrue("ProviderId?.toTitleRatingSource source should exist", ratingSourceHelperStart > start)
        assertTrue("ProviderId?.toTitleRatingSource source boundary should exist", end > ratingSourceHelperStart)

        return source.substring(start, end + "\n}".length)
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
