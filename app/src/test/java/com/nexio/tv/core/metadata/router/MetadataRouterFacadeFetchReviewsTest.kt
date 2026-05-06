package com.nexio.tv.core.metadata.router

import com.nexio.tv.core.integration.RecordingTraceSink
import com.nexio.tv.core.integration.TmdbApiShapes
import com.nexio.tv.core.trace.TraceMetadataEvents
import com.nexio.tv.domain.model.ContentType
import com.nexio.tv.domain.model.MetaReview
import com.nexio.tv.domain.model.MetaReviewSource
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins the contract that `MetadataRouterFacade.fetchReviews(...)`:
 *  1. Fires the canonical `metadata.route_decision` (and at least one `metadata.field_selected`)
 *     trace events via the resolve pipeline at depth `DETAIL_SECONDARY`.
 *  2. Returns the provider-plan REVIEWS candidate projection.
 */
class MetadataRouterFacadeFetchReviewsTest {

    @Test
    fun `fetchReviews returns provider-plan reviews and emits canonical trace events`() = runTest {
        val sink = RecordingTraceSink()
        val events = TraceMetadataEvents(sink, sessionId = { "s1" })

        val providerReviews = listOf(
            MetaReview(
                id = "rev-1",
                author = "siskel",
                content = "thumbs up",
                rating = 9.0,
                createdAt = "2024-01-01T00:00:00Z",
                updatedAt = null,
                url = null,
                source = MetaReviewSource.TMDB
            )
        )

        val tmdbCandidate = MetadataCandidate(
            provider = MetadataPrimaryProvider.TMDB,
            resolverType = ResolverType.REVIEWS,
            fields = mapOf(
                ResolvedField.TITLE to FieldValue("The Matrix", FieldOwner.PRIMARY),
                ResolvedField.REVIEWS to FieldValue(providerReviews, FieldOwner.REVIEWS)
            )
        )

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
                        candidateShape = TmdbApiShapes.MOVIE_REVIEWS
                    )
                )
            ),
            fieldResolver = FieldResolver(events)
        )
        val result = facade.fetchReviews(
            metadataRequest = MetadataRequest(
                contentId = "tmdb:603",
                contentType = ContentType.MOVIE,
                sourceContext = MetadataSourceContext(),
                language = "eng",
                depth = MetadataDepth.DETAIL_CORE
            ),
            tmdbId = "603",
            contentType = ContentType.MOVIE
        )

        assertEquals(providerReviews, result)

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

    @Test
    fun `fetchReviews returns aggregated reviews from every REVIEWS-bearing step candidate`() = runTest {
        val sink = RecordingTraceSink()
        val events = TraceMetadataEvents(sink, sessionId = { "s1" })

        val tmdbReview = MetaReview(
            id = "tmdb-1",
            author = "siskel",
            content = "tmdb-r1",
            source = MetaReviewSource.TMDB
        )
        val traktReview = MetaReview(
            id = "trakt-1",
            author = "ebert",
            content = "trakt-r1",
            source = MetaReviewSource.TRAKT
        )

        // The PRIMARY_CORE TMDB step contributes its baseline candidate (TITLE),
        // while a REVIEWS-bearing candidate gets attached via a step result that
        // mirrors what a TmdbReviewMetadataAdapter / TraktReviewMetadataAdapter
        // would emit: fields[REVIEWS] = FieldValue(List<MetaReview>, REVIEWS).
        val tmdbCandidateWithReviews = MetadataCandidate(
            provider = MetadataPrimaryProvider.TMDB,
            resolverType = ResolverType.REVIEWS,
            fields = mapOf(
                ResolvedField.TITLE to FieldValue("The Matrix", FieldOwner.PRIMARY),
                ResolvedField.REVIEWS to FieldValue(listOf(tmdbReview), FieldOwner.REVIEWS)
            )
        )
        val traktCandidateWithReviews = MetadataCandidate(
            provider = MetadataPrimaryProvider.TMDB, // adapters report provider=TMDB; source field carries TRAKT
            resolverType = ResolverType.REVIEWS,
            fields = mapOf(
                ResolvedField.REVIEWS to FieldValue(listOf(traktReview), FieldOwner.REVIEWS)
            )
        )

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
                    MultiCandidateAdapter(
                        provider = MetadataPrimaryProvider.TMDB,
                        candidates = listOf(tmdbCandidateWithReviews, traktCandidateWithReviews)
                    )
                )
            ),
            fieldResolver = FieldResolver(events)
        )

        val result = facade.fetchReviews(
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

        // Aggregated result must include reviews from BOTH adapters.
        assertTrue(
            "expected TMDB review in aggregated result, got $result",
            result.any { it.source == MetaReviewSource.TMDB && it.content == "tmdb-r1" }
        )
        assertTrue(
            "expected Trakt review in aggregated result, got $result",
            result.any { it.source == MetaReviewSource.TRAKT && it.content == "trakt-r1" }
        )
    }

    @Test
    fun `fetchReviewsPage threads page and limit and reports hasMore when full page returned`() = runTest {
        val sink = RecordingTraceSink()
        val events = TraceMetadataEvents(sink, sessionId = { "s1" })

        // Adapter returns exactly `limit` reviews, simulating a "full page" so the facade
        // should infer hasMore=true and nextPage=page+1.
        val limit = 5
        val pageReviews = (1..limit).map { idx ->
            MetaReview(
                id = "r-$idx",
                author = "a-$idx",
                content = "c-$idx",
                source = MetaReviewSource.TRAKT
            )
        }
        val candidateWithReviews = MetadataCandidate(
            provider = MetadataPrimaryProvider.TMDB,
            resolverType = ResolverType.REVIEWS,
            fields = mapOf(
                ResolvedField.REVIEWS to FieldValue(pageReviews, FieldOwner.REVIEWS)
            )
        )

        // Capture what the adapter saw on `route.pagination` so we can assert page/limit
        // were threaded through. Restrict to the MOVIE_REVIEWS shape so it doesn't also
        // attach REVIEWS to every other plan step (PRIMARY_CORE, VIDEOS, RECOMMENDATIONS).
        var observedPagination: PaginationCursor? = null
        // The planner requires every plan step to have a supporting adapter, so we
        // claim support on all steps but only emit the REVIEWS candidate for the
        // MOVIE_REVIEWS shape. Other steps return an empty candidate.
        val capturingAdapter = object : MetadataProviderAdapter {
            override val provider: MetadataPrimaryProvider = MetadataPrimaryProvider.TMDB
            override fun supports(step: ProviderPlanStep): Boolean = true
            override suspend fun execute(route: MetadataRoute, step: ProviderPlanStep): ProviderStepResult {
                if (step.apiShapeId != TmdbApiShapes.MOVIE_REVIEWS) {
                    return ProviderStepResult(
                        step = step,
                        candidate = MetadataCandidate(
                            provider = MetadataPrimaryProvider.TMDB,
                            fields = emptyMap()
                        ),
                        episodeMetadata = emptyMap()
                    )
                }
                observedPagination = route.pagination
                return ProviderStepResult(
                    step = step,
                    candidate = candidateWithReviews,
                    episodeMetadata = emptyMap()
                )
            }
        }

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
            providerPlanRunner = ProviderPlanRunner(setOf(capturingAdapter)),
            fieldResolver = FieldResolver(events)
        )

        val result = facade.fetchReviewsPage(
            metadataRequest = MetadataRequest(
                contentId = "tmdb:603",
                contentType = ContentType.MOVIE,
                sourceContext = MetadataSourceContext(),
                language = "eng",
                depth = MetadataDepth.DETAIL_CORE
            ),
            tmdbId = "603",
            contentType = ContentType.MOVIE,
            page = 2,
            limit = limit
        )

        assertEquals(pageReviews, result.reviews)
        assertEquals(true, result.hasMore)
        assertEquals(3, result.nextPage)
        // Pagination cursor was threaded MetadataRequest -> MetadataRoute -> adapter.
        assertEquals(PaginationCursor(page = 2, limit = limit), observedPagination)
    }

    @Test
    fun `fetchReviewsPage reports hasMore=false and nextPage=null when partial page`() = runTest {
        val sink = RecordingTraceSink()
        val events = TraceMetadataEvents(sink, sessionId = { "s1" })

        // Only 2 reviews returned for a limit of 5 — adapter has exhausted its results.
        val limit = 5
        val partialPage = listOf(
            MetaReview(id = "r-1", author = "a", content = "c", source = MetaReviewSource.TRAKT),
            MetaReview(id = "r-2", author = "b", content = "d", source = MetaReviewSource.TRAKT)
        )
        val candidate = MetadataCandidate(
            provider = MetadataPrimaryProvider.TMDB,
            resolverType = ResolverType.REVIEWS,
            fields = mapOf(
                ResolvedField.REVIEWS to FieldValue(partialPage, FieldOwner.REVIEWS)
            )
        )

        // Restrict to the MOVIE_REVIEWS step so it doesn't also attach REVIEWS to every
        // other DETAIL_SECONDARY plan step (PRIMARY_CORE, MOVIE_VIDEOS, MOVIE_RECOMMENDATIONS),
        // which would inflate the aggregated count beyond the simulated partial page.
        val reviewOnlyAdapter = object : MetadataProviderAdapter {
            override val provider: MetadataPrimaryProvider = MetadataPrimaryProvider.TMDB
            override fun supports(step: ProviderPlanStep): Boolean = true
            override suspend fun execute(route: MetadataRoute, step: ProviderPlanStep): ProviderStepResult =
                ProviderStepResult(
                    step = step,
                    candidate = if (step.apiShapeId == TmdbApiShapes.MOVIE_REVIEWS) candidate
                    else MetadataCandidate(provider = MetadataPrimaryProvider.TMDB, fields = emptyMap()),
                    episodeMetadata = emptyMap()
                )
        }

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
            providerPlanRunner = ProviderPlanRunner(setOf(reviewOnlyAdapter)),
            fieldResolver = FieldResolver(events)
        )

        val result = facade.fetchReviewsPage(
            metadataRequest = MetadataRequest(
                contentId = "tmdb:603",
                contentType = ContentType.MOVIE,
                sourceContext = MetadataSourceContext(),
                language = "eng",
                depth = MetadataDepth.DETAIL_SECONDARY
            ),
            tmdbId = "603",
            contentType = ContentType.MOVIE,
            page = 1,
            limit = limit
        )

        assertEquals(partialPage, result.reviews)
        assertEquals(false, result.hasMore)
        assertEquals(null, result.nextPage)
    }

    private class CannedCandidateAdapter(
        override val provider: MetadataPrimaryProvider,
        private val candidate: MetadataCandidate,
        private val candidateShape: String
    ) : MetadataProviderAdapter {
        override fun supports(step: ProviderPlanStep): Boolean = true

        override suspend fun execute(route: MetadataRoute, step: ProviderPlanStep): ProviderStepResult =
            ProviderStepResult(
                step = step,
                candidate = if (step.apiShapeId == candidateShape) {
                    candidate
                } else {
                    MetadataCandidate(provider = provider, fields = emptyMap())
                },
                episodeMetadata = emptyMap()
            )
    }

    /**
     * Returns one of the [candidates] per step in plan order, cycling through the list.
     * Lets a single adapter mock simulate multiple REVIEWS-bearing step results
     * (TmdbReviewMetadataAdapter + TraktReviewMetadataAdapter) without wiring two
     * adapters that both report `provider = TMDB`.
     */
    private class MultiCandidateAdapter(
        override val provider: MetadataPrimaryProvider,
        private val candidates: List<MetadataCandidate>
    ) : MetadataProviderAdapter {
        private var index = 0
        override fun supports(step: ProviderPlanStep): Boolean = true

        override suspend fun execute(route: MetadataRoute, step: ProviderPlanStep): ProviderStepResult {
            val candidate = candidates[index % candidates.size]
            index += 1
            return ProviderStepResult(
                step = step,
                candidate = candidate,
                episodeMetadata = emptyMap()
            )
        }
    }
}
