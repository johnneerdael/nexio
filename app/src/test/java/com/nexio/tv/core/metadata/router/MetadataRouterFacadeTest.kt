package com.nexio.tv.core.metadata.router

import com.nexio.tv.core.tvdb.TvMetadataRequest
import com.nexio.tv.core.tvdb.TvProvider
import com.nexio.tv.core.tvdb.TvEpisodeMetadata
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
        val adapter = RecordingMetadataProviderAdapter(MetadataPrimaryProvider.TVDB)

        val result = facade(adapter).resolveRequest(
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
        assertEquals(0, adapter.calls)
    }

    @Test
    fun `metadata facade executes provider plan and never calls provider metadata router`() = runTest {
        val adapter = RecordingMetadataProviderAdapter(MetadataPrimaryProvider.TVDB)

        val result = facade(adapter).resolveRequest(
            MetadataRequest(
                contentId = "tvdb:123",
                contentType = ContentType.SERIES,
                sourceContext = MetadataSourceContext(
                    addonMetadata = HomeDisplayMetadata(title = "Addon title")
                ),
                depth = MetadataDepth.DETAIL_CORE
            )
        )

        assertEquals(MetadataPrimaryProvider.TVDB, result.route?.provider)
        assertEquals(MetadataDepth.DETAIL_CORE, result.plan?.depth)
        assertTrue(adapter.calls > 0)
        assertEquals("Runtime title", result.resolvedDocument.title)
        assertEquals("Runtime title", result.displayMetadata.title)
    }

    @Test
    fun `route request records authority without executing provider plan`() = runTest {
        val adapter = RecordingMetadataProviderAdapter(MetadataPrimaryProvider.TVDB)

        val route = facade(adapter).routeRequest(
            MetadataRequest(
                contentId = "tvdb:123",
                contentType = ContentType.SERIES,
                sourceContext = MetadataSourceContext(),
                depth = MetadataDepth.DETAIL_CORE
            )
        )

        assertEquals(MetadataPrimaryProvider.TVDB, route.provider)
        assertEquals("tvdb:123", route.parentId)
        assertEquals(0, adapter.calls)
    }

    @Test
    fun `provider plan steps are executed via integration runtime adapters`() = runTest {
        val adapter = RecordingMetadataProviderAdapter(MetadataPrimaryProvider.TVDB)

        facade(adapter).resolveRequest(
            MetadataRequest(
                contentId = "tvdb:123",
                contentType = ContentType.SERIES,
                sourceContext = MetadataSourceContext(),
                depth = MetadataDepth.DETAIL_CORE
            )
        )

        assertEquals(listOf("tvdb.series.extended"), adapter.apiShapeIds)
    }

    @Test
    fun `missing plan step adapter mapping fails test`() = runTest {
        val error = try {
            facade().resolveRequest(
                MetadataRequest(
                    contentId = "tvdb:123",
                    contentType = ContentType.SERIES,
                    sourceContext = MetadataSourceContext(),
                    depth = MetadataDepth.DETAIL_CORE
                )
            )
            null
        } catch (exception: MetadataRouteFailure.MissingPlanStepAdapter) {
            exception
        }

        assertTrue(error?.message?.contains("tvdb.series.extended") == true)
    }

    @Test
    fun `provider-native conflict fails before provider plan execution when identity is unresolved`() = runTest {
        val adapter = RecordingMetadataProviderAdapter(MetadataPrimaryProvider.TVDB)

        val error = try {
            facade(adapter).resolveRequest(
                MetadataRequest(
                    contentId = "tmdb:1399",
                    contentType = ContentType.SERIES,
                    sourceContext = MetadataSourceContext(),
                    depth = MetadataDepth.DETAIL_CORE
                )
            )
            null
        } catch (exception: MetadataRouteFailure.IdentityResolutionFailed) {
            exception
        }

        assertTrue("Expected unresolved identity route to be rejected", error != null)
        assertEquals(0, adapter.calls)
    }

    @Test
    fun `facade tv enrichment bridge uses resolved document output`() = runTest {
        val adapter = RecordingMetadataProviderAdapter(MetadataPrimaryProvider.TVDB)

        val result = facade(adapter).fetchTvEnrichment(
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
        assertEquals("Runtime title", result.value?.localizedTitle)
        assertTrue(adapter.calls > 0)
    }

    @Test
    fun `facade season episode bridge returns provider plan episode metadata`() = runTest {
        val adapter = RecordingMetadataProviderAdapter(MetadataPrimaryProvider.TVDB)

        val result = facade(adapter).fetchTvSeasonEpisodes(
            metadataRequest = MetadataRequest(
                contentId = "tvdb:1399",
                contentType = ContentType.SERIES,
                sourceContext = MetadataSourceContext(),
                seasonNumber = 1,
                depth = MetadataDepth.SEASON
            ),
            contentId = "tvdb:1399",
            fallbackContentId = null,
            seasonNumber = 1
        )

        assertEquals(TvProvider.TVDB, result.provider)
        assertEquals(listOf(1), result.value?.map { it.episodeNumber })
        assertEquals("2024-01-01", result.value?.single()?.airDate)
        assertTrue(adapter.apiShapeIds.contains("tvdb.series.episodes.language"))
    }

    private fun facade(
        vararg adapters: MetadataProviderAdapter,
        identityLookup: MetadataIdentityResolver.Lookup = object : MetadataIdentityResolver.Lookup {
            override suspend fun tmdbToTvdb(tmdbId: String): String? = null
            override suspend fun tvdbToTmdb(tvdbId: String): String? = null
        }
    ): MetadataRouterFacade =
        MetadataRouterFacade(
            router = MetadataRouter(
                normalizer = MetadataRequestNormalizer(),
                animeIdentityIndex = InMemoryAnimeIdentityIndex(),
                idMappingStore = InMemoryIdMappingStore()
            ),
            providerPlanExecutor = ProviderPlanExecutor(),
            resolverOrchestrator = ResolverOrchestrator(),
            identityResolver = MetadataIdentityResolver(identityLookup),
            providerPlanRunner = ProviderPlanRunner(adapters.toSet()),
            fieldResolver = FieldResolver()
        )

    private class RecordingMetadataProviderAdapter(
        override val provider: MetadataPrimaryProvider
    ) : MetadataProviderAdapter {
        var calls = 0
        val apiShapeIds = mutableListOf<String>()

        override fun supports(step: ProviderPlanStep): Boolean = true

        override suspend fun execute(route: MetadataRoute, step: ProviderPlanStep): ProviderStepResult {
            calls += 1
            apiShapeIds += step.apiShapeId
            val episodeMetadata = if (step.role == ProviderPlanRole.SEASON) {
                val seasonNumber = route.seasonNumber ?: 1
                mapOf(
                    (seasonNumber to 1) to TvEpisodeMetadata(
                        seasonNumber = seasonNumber,
                        episodeNumber = 1,
                        title = "Runtime episode",
                        airDate = "2024-01-01"
                    )
                )
            } else {
                emptyMap()
            }
            return ProviderStepResult(
                step = step,
                candidate = MetadataCandidate(
                    provider = route.provider,
                    fields = mapOf(
                        ResolvedField.TITLE to FieldValue("Runtime title", FieldOwner.PRIMARY)
                    )
                ),
                episodeMetadata = episodeMetadata
            )
        }
    }
}
