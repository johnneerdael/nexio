package com.nexio.tv.core.metadata.router

import com.nexio.tv.core.integration.RecordingTraceSink
import com.nexio.tv.core.metadata.router.resolver.Confidence
import com.nexio.tv.core.metadata.router.resolver.RatingCandidate
import com.nexio.tv.core.metadata.router.resolver.SourceRole
import com.nexio.tv.core.trace.TraceMetadataEvents
import com.nexio.tv.data.repository.TitleRatingOverrideRepository
import com.nexio.tv.domain.model.ContentType
import com.nexio.tv.domain.model.ProviderIds
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Verifies that [MetadataRouterFacade.applyRatingResolverSelection] falls back to
 * the canonical REMOTE_IDS imdb entry from the primary candidate when
 * [MetadataSourceContext.previewStableIds].imdb is null/blank.
 */
class MetadataRouterFacadeRatingFallbackTest {

    @Test
    fun `applyRatingResolverSelection uses primary REMOTE_IDS imdb when previewStableIds imdb is null`() = runTest {
        val imdbId = "tt0137523"
        val expectedRating = 8.8

        val ratingRepo = mockk<TitleRatingOverrideRepository>()
        // Stub returns a CUSTOM_IMDB candidate when called with a providerIds that has the imdb from REMOTE_IDS
        coEvery {
            ratingRepo.titleRatingCandidates(
                preview = any(),
                stableIdBundle = null,
                providerIds = ProviderIds(imdb = imdbId)
            )
        } returns listOf(
            RatingCandidate(
                value = expectedRating,
                sourceRole = SourceRole.CUSTOM_IMDB,
                sourceProvider = "IMDB",
                confidence = Confidence.HIGH
            )
        )

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

        // The rating from CUSTOM_IMDB (via REMOTE_IDS fallback) should be selected.
        assertEquals(expectedRating, result.resolvedDocument.rating)

        // Verify the repository was called with the imdb id recovered from REMOTE_IDS.
        coVerify {
            ratingRepo.titleRatingCandidates(
                preview = any(),
                stableIdBundle = null,
                providerIds = ProviderIds(imdb = imdbId)
            )
        }
    }

    @Test
    fun `applyRatingResolverSelection does NOT use REMOTE_IDS fallback when previewStableIds imdb is non-blank`() = runTest {
        val previewImdbId = "tt9999999"
        val remoteIdsImdbId = "tt0137523"

        val ratingRepo = mockk<TitleRatingOverrideRepository>()
        // Only stub the preview imdb id path; REMOTE_IDS id should not be used.
        coEvery {
            ratingRepo.titleRatingCandidates(
                preview = any(),
                stableIdBundle = null,
                providerIds = ProviderIds(imdb = previewImdbId)
            )
        } returns listOf(
            RatingCandidate(
                value = 7.5,
                sourceRole = SourceRole.CUSTOM_IMDB,
                sourceProvider = "IMDB",
                confidence = Confidence.HIGH
            )
        )

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

        assertEquals(7.5, result.resolvedDocument.rating)

        // REMOTE_IDS imdb id must NOT have been used.
        coVerify(exactly = 0) {
            ratingRepo.titleRatingCandidates(
                preview = any(),
                stableIdBundle = null,
                providerIds = ProviderIds(imdb = remoteIdsImdbId)
            )
        }
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
