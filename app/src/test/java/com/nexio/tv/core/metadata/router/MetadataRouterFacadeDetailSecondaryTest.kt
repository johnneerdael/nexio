package com.nexio.tv.core.metadata.router

import com.nexio.tv.core.integration.RecordingTraceSink
import com.nexio.tv.core.metadata.router.resolver.OrganizationPersonResolver
import com.nexio.tv.core.metadata.router.resolver.RecommendationResolver
import com.nexio.tv.core.metadata.router.resolver.ReviewResolver
import com.nexio.tv.core.metadata.router.resolver.TrailerResolver
import com.nexio.tv.core.trace.TraceMetadataEvents
import com.nexio.tv.domain.model.ContentType
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins the contract that `MetadataRouterFacade.resolveRequest(depth = DETAIL_SECONDARY)`
 * dispatches every entry of [ResolverSchedule.networkResolvers] (REVIEWS, RECOMMENDATIONS,
 * ORGANIZATION_PERSON) through the registered resolver instances, which in turn emit
 * `metadata.field_selected` events for the field they own.
 *
 * Closes F-B-04 ("schedule output is event-only"). Before Task 19 the resolverSchedule
 * was emitted by [ResolverOrchestrator] but never consumed — the facade did not call any
 * of the per-field resolvers. This test fails fast if a future refactor regresses the
 * dispatch loop.
 */
class MetadataRouterFacadeDetailSecondaryTest {

    @Test
    fun `DETAIL_SECONDARY dispatches REVIEWS, RECOMMENDATIONS, ORGANIZATION_PERSON resolvers`() = runTest {
        val sink = RecordingTraceSink()
        val events = TraceMetadataEvents(sink, sessionId = { "s1" })

        // Per-step candidates: PRIMARY_CORE carries TITLE; MEDIA carries TRAILERS;
        // first SECONDARY carries REVIEWS; second SECONDARY carries RECOMMENDATIONS + CAST.
        val primaryCandidate = MetadataCandidate(
            provider = MetadataPrimaryProvider.TMDB,
            fields = mapOf(
                ResolvedField.TITLE to FieldValue("The Matrix", FieldOwner.PRIMARY)
            )
        )
        val mediaCandidate = MetadataCandidate(
            provider = MetadataPrimaryProvider.TMDB,
            fields = mapOf(
                ResolvedField.TRAILERS to FieldValue(
                    listOf(mapOf("youtubeId" to "abc")),
                    FieldOwner.TRAILERS
                )
            )
        )
        val reviewsCandidate = MetadataCandidate(
            provider = MetadataPrimaryProvider.TMDB,
            fields = mapOf(
                ResolvedField.REVIEWS to FieldValue(
                    listOf(mapOf("author" to "ebert", "content" to "great")),
                    FieldOwner.REVIEWS
                )
            )
        )
        val recsAndOrgCandidate = MetadataCandidate(
            provider = MetadataPrimaryProvider.TMDB,
            fields = mapOf(
                ResolvedField.RECOMMENDATIONS to FieldValue(
                    listOf(mapOf("id" to "tmdb:9999")),
                    FieldOwner.RECOMMENDATIONS
                ),
                ResolvedField.CAST to FieldValue(
                    listOf(mapOf("name" to "Keanu Reeves")),
                    FieldOwner.ORGANIZATION_PERSON
                )
            )
        )

        val adapter = PerRoleCandidateAdapter(
            provider = MetadataPrimaryProvider.TMDB,
            byRole = mapOf(
                ProviderPlanRole.PRIMARY_CORE to primaryCandidate,
                ProviderPlanRole.MEDIA to mediaCandidate
            ),
            secondaryQueue = listOf(reviewsCandidate, recsAndOrgCandidate)
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
            providerPlanRunner = ProviderPlanRunner(setOf(adapter)),
            fieldResolver = FieldResolver(events),
            trailerResolver = TrailerResolver(events),
            reviewResolver = ReviewResolver(events),
            recommendationResolver = RecommendationResolver(events),
            organizationPersonResolver = OrganizationPersonResolver(events)
        )

        facade.resolveRequest(
            MetadataRequest(
                contentId = "tmdb:603",
                contentType = ContentType.MOVIE,
                sourceContext = MetadataSourceContext(),
                language = "eng",
                depth = MetadataDepth.DETAIL_SECONDARY
            )
        )

        val fieldSelectedFields = sink.events
            .filter { it.eventType == "metadata.field_selected" }
            .mapNotNull { (it.payload as? Map<*, *>)?.get("field") as? String }
            .toSet()

        // The dispatch loop must drive at least these three field_selected events for
        // DETAIL_SECONDARY: REVIEWS (ReviewResolver), RECOMMENDATIONS (RecommendationResolver),
        // and CAST (OrganizationPersonResolver).
        assertTrue(
            "expected REVIEWS in $fieldSelectedFields",
            "REVIEWS" in fieldSelectedFields
        )
        assertTrue(
            "expected RECOMMENDATIONS in $fieldSelectedFields",
            "RECOMMENDATIONS" in fieldSelectedFields
        )
        assertTrue(
            "expected CAST in $fieldSelectedFields",
            "CAST" in fieldSelectedFields
        )

        val routeEvents = sink.events.filter { it.eventType == "metadata.route_decision" }
        assertEquals(
            "expected exactly one route_decision, got ${sink.events.map { it.eventType }}",
            1,
            routeEvents.size
        )
    }

    /**
     * Returns a fixed candidate for fixed-role plan steps (PRIMARY_CORE, MEDIA), and pulls
     * candidates from a queue for SECONDARY steps so two same-role steps can produce
     * different candidates (TMDB's DETAIL_SECONDARY plan emits two SECONDARY steps:
     * REVIEWS then RECOMMENDATIONS).
     */
    private class PerRoleCandidateAdapter(
        override val provider: MetadataPrimaryProvider,
        private val byRole: Map<ProviderPlanRole, MetadataCandidate>,
        secondaryQueue: List<MetadataCandidate>
    ) : MetadataProviderAdapter {
        private val secondaryIterator = secondaryQueue.iterator()

        override fun supports(step: ProviderPlanStep): Boolean = true

        override suspend fun execute(route: MetadataRoute, step: ProviderPlanStep): ProviderStepResult {
            val candidate = if (step.role == ProviderPlanRole.SECONDARY) {
                if (secondaryIterator.hasNext()) secondaryIterator.next() else null
            } else {
                byRole[step.role]
            }
            return ProviderStepResult(
                step = step,
                candidate = candidate,
                episodeMetadata = emptyMap()
            )
        }
    }
}
