package com.nexio.tv.core.metadata.router

import com.nexio.tv.core.integration.RecordingTraceSink
import com.nexio.tv.core.trace.TraceMetadataEvents
import com.nexio.tv.data.integration.metadata.MetadataSecondaryRepository
import com.nexio.tv.domain.model.ContentType
import com.nexio.tv.domain.model.MetaReview
import com.nexio.tv.domain.model.MetaReviewSource
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins the contract that `MetadataRouterFacade.fetchReviews(...)`:
 *  1. Fires the canonical `metadata.route_decision` (and at least one `metadata.field_selected`)
 *     trace events via the resolve pipeline at depth `DETAIL_SECONDARY`.
 *  2. Returns the [MetaReview] list from [MetadataSecondaryRepository] unchanged.
 *
 * Facade-level pin for Task 13 of the cluster-A facade-bypass migration:
 * `MetaDetailsViewModel.fetchTmdbReviews(...)` no longer calls the secondary repository
 * directly for TMDB reviews, so trace observability (audit's primary goal) is restored.
 *
 * The Trakt review path (`ReviewsRepository.fetchTraktReviewPage(...)`) intentionally
 * stays direct — see Task 12 scope decision (deferred until `MetadataPrimaryProvider.TRAKT`
 * is added to the provider enum).
 */
class MetadataRouterFacadeFetchReviewsTest {

    @Test
    fun `fetchReviews delegates to secondary repo and emits canonical trace events`() = runTest {
        val sink = RecordingTraceSink()
        val events = TraceMetadataEvents(sink, sessionId = { "s1" })

        val canned = listOf(
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

        val secondaryRepo = mockk<MetadataSecondaryRepository>()
        coEvery { secondaryRepo.fetchReviews("603", ContentType.MOVIE) } returns canned

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

        assertEquals(canned, result)
        coVerify(exactly = 1) { secondaryRepo.fetchReviews("603", ContentType.MOVIE) }

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

        // The secondary repo must NOT be hit when resolver candidates produced reviews.
        val secondaryRepo = mockk<MetadataSecondaryRepository>()

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
                setOf(
                    MultiCandidateAdapter(
                        provider = MetadataPrimaryProvider.TMDB,
                        candidates = listOf(tmdbCandidateWithReviews, traktCandidateWithReviews)
                    )
                )
            ),
            fieldResolver = FieldResolver(events),
            metadataSecondaryRepository = secondaryRepo
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
        // Backwards-compat fallback must NOT trigger when resolver produced reviews.
        coVerify(exactly = 0) { secondaryRepo.fetchReviews(any(), any()) }
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
