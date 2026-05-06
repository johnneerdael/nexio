package com.nexio.tv.core.metadata.router

import com.nexio.tv.core.integration.RecordingTraceSink
import com.nexio.tv.core.integration.TmdbApiShapes
import com.nexio.tv.core.trace.TraceMetadataEvents
import com.nexio.tv.domain.model.ContentType
import com.nexio.tv.domain.model.MetaPreview
import com.nexio.tv.domain.model.PosterShape
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins the contract that `MetadataRouterFacade.fetchRecommendations(...)`:
 *  1. Fires the canonical `metadata.route_decision` (and at least one `metadata.field_selected`)
 *     trace events via the resolve pipeline at depth `DETAIL_SECONDARY`.
 *  2. Returns the provider-plan RECOMMENDATIONS candidate projection.
 */
class MetadataRouterFacadeFetchRecommendationsTest {

    @Test
    fun `fetchRecommendations returns provider-plan recommendations and emits canonical trace events`() = runTest {
        val sink = RecordingTraceSink()
        val events = TraceMetadataEvents(sink, sessionId = { "s1" })

        val recommendations = listOf(
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

        val tmdbCandidate = MetadataCandidate(
            provider = MetadataPrimaryProvider.TMDB,
            resolverType = ResolverType.RECOMMENDATIONS,
            fields = mapOf(
                ResolvedField.TITLE to FieldValue("The Matrix", FieldOwner.PRIMARY),
                ResolvedField.RECOMMENDATIONS to FieldValue(recommendations, FieldOwner.RECOMMENDATIONS)
            )
        )

        var observedPagination: PaginationCursor? = null
        val facade = MetadataRouterFacade(
            router = MetadataRouter(
                normalizer = MetadataRequestNormalizer(traceEvents = events),
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
                setOf(
                    CannedCandidateAdapter(
                        provider = MetadataPrimaryProvider.TMDB,
                        candidate = tmdbCandidate,
                        candidateShape = TmdbApiShapes.MOVIE_RECOMMENDATIONS,
                        observePagination = { observedPagination = it }
                    )
                )
            ),
            fieldResolver = FieldResolver(events)
        )
        val requestPagination = PaginationCursor(page = 7, limit = 70)

        val result = facade.fetchRecommendations(
            metadataRequest = MetadataRequest(
                contentId = "tmdb:603",
                contentType = ContentType.MOVIE,
                sourceContext = MetadataSourceContext(),
                language = "eng",
                depth = MetadataDepth.DETAIL_CORE,
                pagination = requestPagination
            ),
            tmdbId = "603",
            contentType = ContentType.MOVIE
        )

        assertEquals(recommendations, result)
        assertEquals(requestPagination, observedPagination)

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
        private val candidate: MetadataCandidate,
        private val candidateShape: String,
        private val observePagination: (PaginationCursor?) -> Unit = {}
    ) : MetadataProviderAdapter {
        override fun supports(step: ProviderPlanStep): Boolean = true

        override suspend fun execute(route: MetadataRoute, step: ProviderPlanStep): ProviderStepResult {
            if (step.apiShapeId == candidateShape) {
                observePagination(route.pagination)
            }
            return ProviderStepResult(
                step = step,
                candidate = if (step.apiShapeId == candidateShape) {
                    candidate
                } else {
                    MetadataCandidate(provider = provider, fields = emptyMap())
                },
                episodeMetadata = emptyMap()
            )
        }
    }
}
