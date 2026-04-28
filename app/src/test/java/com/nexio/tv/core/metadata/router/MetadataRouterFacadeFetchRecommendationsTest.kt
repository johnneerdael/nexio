package com.nexio.tv.core.metadata.router

import com.nexio.tv.core.integration.RecordingTraceSink
import com.nexio.tv.core.trace.TraceMetadataEvents
import com.nexio.tv.data.integration.metadata.MetadataSecondaryRepository
import com.nexio.tv.domain.model.ContentType
import com.nexio.tv.domain.model.MetaPreview
import com.nexio.tv.domain.model.PosterShape
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins the contract that `MetadataRouterFacade.fetchRecommendations(...)`:
 *  1. Fires the canonical `metadata.route_decision` (and at least one `metadata.field_selected`)
 *     trace events via the resolve pipeline at depth `DETAIL_SECONDARY`.
 *  2. Returns the [MetaPreview] list from [MetadataSecondaryRepository] unchanged.
 *
 * Facade-level pin for Task 15 of the cluster-A facade-bypass migration:
 * `MetaDetailsViewModel.loadMoreLikeThis(...)` no longer calls the secondary repository
 * directly for TMDB recommendations, so trace observability (audit's primary goal) is restored.
 */
class MetadataRouterFacadeFetchRecommendationsTest {

    @Test
    fun `fetchRecommendations delegates to secondary repo and emits canonical trace events`() = runTest {
        val sink = RecordingTraceSink()
        val events = TraceMetadataEvents(sink, sessionId = { "s1" })

        val canned = listOf(
            MetaPreview(
                id = "tmdb:456",
                type = ContentType.MOVIE,
                rawType = "movie",
                name = "Inception",
                poster = null,
                posterShape = PosterShape.POSTER,
                background = null,
                logo = null,
                description = null,
                releaseInfo = null,
                imdbRating = null,
                genres = emptyList()
            )
        )

        val secondaryRepo = mockk<MetadataSecondaryRepository>()
        coEvery { secondaryRepo.fetchMoreLikeThis("603", ContentType.MOVIE) } returns canned

        val tmdbCandidate = MetadataCandidate(
            provider = MetadataPrimaryProvider.TMDB,
            fields = mapOf(
                ResolvedField.TITLE to FieldValue("The Matrix", FieldOwner.PRIMARY)
            )
        )

        val facade = MetadataRouterFacade(
            router = MetadataRouter(
                normalizer = MetadataRequestNormalizer(),
                animeIdentityIndex = InMemoryAnimeIdentityIndex(),
                idMappingStore = InMemoryIdMappingStore(),
                traceEvents = events
            ),
            providerPlanExecutor = ProviderPlanExecutor(),
            resolverOrchestrator = ResolverOrchestrator(events),
            identityResolver = MetadataIdentityResolver(
                object : MetadataIdentityResolver.Lookup {
                    override suspend fun tmdbToTvdb(tmdbId: String): String? = null
                    override suspend fun tvdbToTmdb(tvdbId: String): String? = null
                }
            ),
            providerPlanRunner = ProviderPlanRunner(
                setOf(CannedCandidateAdapter(MetadataPrimaryProvider.TMDB, tmdbCandidate))
            ),
            fieldResolver = FieldResolver(events),
            metadataSecondaryRepository = secondaryRepo
        )

        val result = facade.fetchRecommendations(
            metadataRequest = MetadataRequest(
                contentId = "tmdb:603",
                contentType = ContentType.MOVIE,
                sourceContext = MetadataSourceContext(),
                language = "eng",
                depth = MetadataDepth.DETAIL_SECONDARY
            ),
            tmdbId = "603",
            contentType = ContentType.MOVIE
        )

        assertEquals(canned, result)
        coVerify(exactly = 1) { secondaryRepo.fetchMoreLikeThis("603", ContentType.MOVIE) }

        val routeEvents = sink.events.filter { it.eventType == "metadata.route_decision" }
        assertEquals(
            "expected exactly one route_decision, got ${sink.events.map { it.eventType }}",
            1,
            routeEvents.size
        )
        assertTrue(
            "expected >=1 field_selected, got 0 (events=${sink.events.map { it.eventType }})",
            sink.events.any { it.eventType == "metadata.field_selected" }
        )
    }

    private class CannedCandidateAdapter(
        override val provider: MetadataPrimaryProvider,
        private val candidate: MetadataCandidate
    ) : MetadataProviderAdapter {
        override fun supports(step: ProviderPlanStep): Boolean = true

        override suspend fun execute(route: MetadataRoute, step: ProviderPlanStep): ProviderStepResult =
            ProviderStepResult(
                step = step,
                candidate = candidate,
                episodeMetadata = emptyMap()
            )
    }
}
