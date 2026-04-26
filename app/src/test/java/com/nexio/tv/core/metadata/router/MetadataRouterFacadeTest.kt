package com.nexio.tv.core.metadata.router

import com.nexio.tv.core.tvdb.ProviderMetadataRouter
import com.nexio.tv.core.tvdb.TvEpisodeMetadata
import com.nexio.tv.core.tvdb.TvMetadataDecision
import com.nexio.tv.core.tvdb.TvMetadataDecisionReason
import com.nexio.tv.core.tvdb.TvMetadataEnrichment
import com.nexio.tv.core.tvdb.TvMetadataRequest
import com.nexio.tv.core.tvdb.TvProvider
import com.nexio.tv.core.tvdb.TvSeasonEpisode
import com.nexio.tv.domain.model.ContentType
import com.nexio.tv.domain.model.HomeDisplayMetadata
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class MetadataRouterFacadeTest {
    @Test
    fun `preview requests bypass route and provider plan`() = runTest {
        val addonMetadata = HomeDisplayMetadata(title = "Addon title")
        val result = facade().resolveRequest(
            MetadataRequest(
                contentId = "tvdb:123",
                contentType = ContentType.SERIES,
                sourceContext = MetadataSourceContext(addonMetadata = addonMetadata),
                depth = MetadataDepth.PREVIEW
            )
        )

        assertNull(result.route)
        assertNull(result.plan)
        assertEquals(MetadataDepth.PREVIEW, result.resolverSchedule.depth)
        assertSame(addonMetadata, result.displayMetadata)
    }

    @Test
    fun `detail requests route build provider plan and keep addon display as initial metadata`() = runTest {
        val addonMetadata = HomeDisplayMetadata(title = "Addon title")
        val result = facade().resolveRequest(
            MetadataRequest(
                contentId = "tvdb:123",
                contentType = ContentType.SERIES,
                sourceContext = MetadataSourceContext(addonMetadata = addonMetadata),
                depth = MetadataDepth.DETAIL_CORE
            )
        )

        assertEquals(MetadataPrimaryProvider.TVDB, result.route?.provider)
        assertEquals(MetadataDepth.DETAIL_CORE, result.plan?.depth)
        assertEquals(MetadataDepth.DETAIL_CORE, result.resolverSchedule.depth)
        assertSame(addonMetadata, result.displayMetadata)
    }

    @Test
    fun `player requests route and return route only provider plan`() = runTest {
        val result = facade().resolveRequest(
            MetadataRequest(
                contentId = "tmdb:550",
                contentType = ContentType.MOVIE,
                sourceContext = MetadataSourceContext(),
                depth = MetadataDepth.PLAYER
            )
        )

        assertEquals(MetadataPrimaryProvider.TMDB, result.route?.provider)
        assertEquals(MetadataDepth.PLAYER, result.plan?.depth)
        assertEquals(emptyList<ProviderPlanStep>(), result.plan?.steps)
        assertEquals(MetadataDepth.PLAYER, result.resolverSchedule.depth)
    }

    @Test
    fun `provider-native conflict returns unresolved route without executable plan`() = runTest {
        val result = facade().resolveRequest(
            MetadataRequest(
                contentId = "tmdb:1399",
                contentType = ContentType.SERIES,
                sourceContext = MetadataSourceContext(),
                depth = MetadataDepth.DETAIL_CORE
            )
        )

        assertEquals(MetadataPrimaryProvider.TVDB, result.route?.provider)
        assertTrue(result.route?.targetIdRequiresIdentityResolution == true)
        assertNull(result.plan)
        assertEquals(MetadataDepth.DETAIL_CORE, result.resolverSchedule.depth)
    }

    @Test
    fun `facade routes then delegates tv enrichment through provider metadata router`() = runTest {
        val providerRouter = RecordingProviderMetadataRouter()
        val result = facade(providerRouter).fetchTvEnrichment(
            metadataRequest = MetadataRequest(
                contentId = "tvdb:81189",
                contentType = ContentType.SERIES,
                sourceContext = MetadataSourceContext(),
                depth = MetadataDepth.DETAIL_CORE
            ),
            tvRequest = TvMetadataRequest(
                contentId = "tvdb:81189",
                contentType = ContentType.SERIES
            )
        )

        assertEquals(TvProvider.TVDB, result.provider)
        assertEquals(1, providerRouter.enrichmentCalls)
        assertEquals("tvdb:81189", providerRouter.lastEnrichmentRequest?.contentId)
    }

    @Test
    fun `facade refuses tv enrichment when route still needs identity resolution`() = runTest {
        val providerRouter = RecordingProviderMetadataRouter()

        val error = try {
            facade(providerRouter).fetchTvEnrichment(
                metadataRequest = MetadataRequest(
                    contentId = "tmdb:1399",
                    contentType = ContentType.SERIES,
                    sourceContext = MetadataSourceContext(),
                    depth = MetadataDepth.DETAIL_CORE
                ),
                tvRequest = TvMetadataRequest(
                    contentId = "tmdb:1399",
                    contentType = ContentType.SERIES
                )
            )
            null
        } catch (exception: IllegalStateException) {
            exception
        }

        assertTrue("Expected unresolved identity route to be rejected", error != null)
        assertTrue(error!!.message!!.contains("requires identity resolution"))
        assertEquals(0, providerRouter.enrichmentCalls)
    }

    private fun facade(
        providerMetadataRouter: ProviderMetadataRouter = RecordingProviderMetadataRouter()
    ): MetadataRouterFacade =
        MetadataRouterFacade(
            router = MetadataRouter(
                normalizer = MetadataRequestNormalizer(),
                animeIdentityIndex = InMemoryAnimeIdentityIndex(),
                idMappingStore = InMemoryIdMappingStore()
            ),
            providerPlanExecutor = ProviderPlanExecutor(),
            resolverOrchestrator = ResolverOrchestrator(),
            providerMetadataRouter = providerMetadataRouter
        )

    private class RecordingProviderMetadataRouter : ProviderMetadataRouter {
        var enrichmentCalls = 0
        var episodeCalls = 0
        var seasonCalls = 0
        var lastEnrichmentRequest: TvMetadataRequest? = null

        override suspend fun fetchEnrichment(
            request: TvMetadataRequest
        ): TvMetadataDecision<TvMetadataEnrichment> {
            enrichmentCalls += 1
            lastEnrichmentRequest = request
            return TvMetadataDecision(
                provider = TvProvider.TVDB,
                reason = TvMetadataDecisionReason.TVDB_SUCCESS,
                value = TvMetadataEnrichment(seriesTvdbId = request.contentId.substringAfter(":").toIntOrNull()),
                diagnostics = emptyList()
            )
        }

        override suspend fun fetchEpisodeEnrichment(
            request: TvMetadataRequest
        ): TvMetadataDecision<Map<Pair<Int, Int>, TvEpisodeMetadata>> {
            episodeCalls += 1
            return TvMetadataDecision(
                provider = TvProvider.TVDB,
                reason = TvMetadataDecisionReason.TVDB_SUCCESS,
                value = emptyMap(),
                diagnostics = emptyList()
            )
        }

        override suspend fun fetchSeasonEpisodes(
            contentId: String,
            fallbackContentId: String?,
            seasonNumber: Int,
            language: String?
        ): TvMetadataDecision<List<TvSeasonEpisode>> {
            seasonCalls += 1
            return TvMetadataDecision(
                provider = TvProvider.TVDB,
                reason = TvMetadataDecisionReason.TVDB_SUCCESS,
                value = emptyList(),
                diagnostics = emptyList()
            )
        }
    }
}
