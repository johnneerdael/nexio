package com.nexio.tv.core.metadata.router

import com.nexio.tv.core.integration.RecordingTraceSink
import com.nexio.tv.core.trace.TraceMetadataEvents
import com.nexio.tv.data.repository.TitleRatingOverrideRepository
import com.nexio.tv.domain.model.ContentType
import com.nexio.tv.domain.model.MetaPreview
import com.nexio.tv.domain.model.ProviderIds
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Verifies that [MetadataRouterFacade.applyRatingResolverSelection] does not
 * call title-rating override repositories from the metadata hydration path.
 * Detail ratings are resolved by DetailRatingDisplayRepository.
 */
class MetadataRouterFacadeRatingFallbackTest {

    @Test
    fun `applyRatingResolverSelection does not call custom imdb from remote ids during metadata hydration`() = runTest {
        val imdbId = "tt0137523"

        val ratingRepo = mockk<TitleRatingOverrideRepository>()

        val adapter = RemoteIdsMetadataProviderAdapter(
            provider = MetadataPrimaryProvider.TVDB,
            remoteIds = mapOf("imdb" to setOf(imdbId))
        )

        val facade = facade(
            adapter,
            titleRatingOverrideRepository = ratingRepo
        )

        // Request with previewStableIds.imdb = null — simulates a TMDB-rail item
        // that did NOT carry an imdb stable ID through the preview.
        val result = facade.resolveRequest(
            MetadataRequest(
                contentId = "tvdb:121361",
                contentType = ContentType.SERIES,
                sourceContext = MetadataSourceContext(
                    previewStableIds = ProviderIds(imdb = null)
                ),
                depth = MetadataDepth.DETAIL_CORE
            )
        )

        assertNull(result.resolvedDocument.rating)
        coVerify(exactly = 0) { ratingRepo.titleRatingCandidates(any<MetaPreview>(), any(), any()) }
    }

    @Test
    fun `applyRatingResolverSelection does not call custom imdb when previewStableIds imdb is non-blank`() = runTest {
        val previewImdbId = "tt9999999"
        val remoteIdsImdbId = "tt0137523"

        val ratingRepo = mockk<TitleRatingOverrideRepository>()

        val adapter = RemoteIdsMetadataProviderAdapter(
            provider = MetadataPrimaryProvider.TVDB,
            remoteIds = mapOf("imdb" to setOf(remoteIdsImdbId))
        )

        val facade = facade(
            adapter,
            titleRatingOverrideRepository = ratingRepo
        )

        val result = facade.resolveRequest(
            MetadataRequest(
                contentId = "tvdb:121361",
                contentType = ContentType.SERIES,
                sourceContext = MetadataSourceContext(
                    previewStableIds = ProviderIds(imdb = previewImdbId)
                ),
                depth = MetadataDepth.DETAIL_CORE
            )
        )

        assertNull(result.resolvedDocument.rating)
        coVerify(exactly = 0) { ratingRepo.titleRatingCandidates(any<MetaPreview>(), any(), any()) }
    }

    @Test
    fun `applyRatingResolverSelection skips REMOTE_IDS lookup when ratingRepo is null`() = runTest {
        val adapter = RemoteIdsMetadataProviderAdapter(
            provider = MetadataPrimaryProvider.TVDB,
            remoteIds = mapOf("imdb" to setOf("tt0137523"))
        )

        // No titleRatingOverrideRepository provided
        val facade = facade(adapter, titleRatingOverrideRepository = null)

        val result = facade.resolveRequest(
            MetadataRequest(
                contentId = "tvdb:121361",
                contentType = ContentType.SERIES,
                sourceContext = MetadataSourceContext(
                    previewStableIds = ProviderIds(imdb = null)
                ),
                depth = MetadataDepth.DETAIL_CORE
            )
        )

        // No rating — no repo to query.
        assertNull(result.resolvedDocument.rating)
    }

    // --- helpers ---

    private fun facade(
        vararg adapters: MetadataProviderAdapter,
        titleRatingOverrideRepository: TitleRatingOverrideRepository? = null
    ): MetadataRouterFacade =
        MetadataRouterFacade(
            router = MetadataRouter(
                normalizer = MetadataRequestNormalizer(
                    traceEvents = TraceMetadataEvents(RecordingTraceSink()) { null }
                ),
                animeIdentityIndex = InMemoryAnimeIdentityIndex(),
                idMappingStore = InMemoryIdMappingStore()
            ),
            providerPlanExecutor = ProviderPlanExecutor(),
            resolverOrchestrator = ResolverOrchestrator(),
            identityResolver = MetadataIdentityResolver(object : MetadataIdentityResolver.Lookup {
                override suspend fun tmdbToTvdb(tmdbId: String): String? = null
                override suspend fun tvdbToTmdb(tvdbId: String): String? = null
            }),
            providerPlanRunner = ProviderPlanRunner(adapters.toSet()),
            fieldResolver = FieldResolver(),
            titleRatingOverrideRepository = titleRatingOverrideRepository
        )

    /**
     * A minimal [MetadataProviderAdapter] that returns a primary candidate carrying
     * [ResolvedField.REMOTE_IDS] in addition to a title, so that the fallback path
     * can read the imdb id from the primary candidate's fields.
     */
    private class RemoteIdsMetadataProviderAdapter(
        override val provider: MetadataPrimaryProvider,
        private val remoteIds: Map<String, Set<String>>
    ) : MetadataProviderAdapter {

        override fun supports(step: ProviderPlanStep): Boolean = true

        override suspend fun execute(route: MetadataRoute, step: ProviderPlanStep): ProviderStepResult =
            ProviderStepResult(
                step = step,
                candidate = MetadataCandidate(
                    provider = provider,
                    fields = buildMap {
                        put(ResolvedField.TITLE, FieldValue("Test title", FieldOwner.PRIMARY))
                        if (remoteIds.isNotEmpty()) {
                            put(ResolvedField.REMOTE_IDS, FieldValue(remoteIds, FieldOwner.PRIMARY))
                        }
                    }
                ),
                episodeMetadata = emptyMap()
            )
    }
}
