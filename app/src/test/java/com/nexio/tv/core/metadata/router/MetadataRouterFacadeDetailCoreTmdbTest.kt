package com.nexio.tv.core.metadata.router

import com.nexio.tv.core.integration.RecordingTraceSink
import com.nexio.tv.core.trace.TraceMetadataEvents
import com.nexio.tv.domain.model.ContentType
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins the contract that `MetadataRouterFacade.resolveRequest(depth = DETAIL_CORE)` for a
 * TMDB-native movie identity returns a populated [ResolvedMetadataDocument] sourced from the
 * TMDB provider candidate and emits the canonical trace event taxonomy:
 *  * exactly one `metadata.route_decision`
 *  * at least one `metadata.field_selected`
 *
 * Task 5 will migrate `MetaDetailsViewModel` onto this facade entry point; this test exists to
 * lock the behavioral contract before the migration.
 */
class MetadataRouterFacadeDetailCoreTmdbTest {

    @Test
    fun `movie DETAIL_CORE returns ResolvedMetadataDocument populated from TMDB candidate`() = runTest {
        val sink = RecordingTraceSink()
        val events = TraceMetadataEvents(sink, sessionId = { "s1" })

        val tmdbCandidate = MetadataCandidate(
            provider = MetadataPrimaryProvider.TMDB,
            fields = mapOf(
                ResolvedField.TITLE to FieldValue("The Matrix", FieldOwner.PRIMARY),
                ResolvedField.OVERVIEW to FieldValue("Neo discovers the truth.", FieldOwner.PRIMARY),
                ResolvedField.RUNTIME to FieldValue(136, FieldOwner.PRIMARY)
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
                setOf(CannedCandidateAdapter(MetadataPrimaryProvider.TMDB, tmdbCandidate))
            ),
            fieldResolver = FieldResolver(events)
        )

        val result = facade.resolveRequest(
            MetadataRequest(
                contentId = "tmdb:603",
                contentType = ContentType.MOVIE,
                sourceContext = MetadataSourceContext(),
                language = "eng",
                depth = MetadataDepth.DETAIL_CORE
            )
        )

        assertNotNull(result.resolvedDocument)
        assertEquals("The Matrix", result.resolvedDocument.title)
        assertEquals("Neo discovers the truth.", result.resolvedDocument.overview)
        assertEquals(136, result.resolvedDocument.runtimeMinutes)
        assertEquals(MetadataPrimaryProvider.TMDB, result.route?.provider)
        assertEquals(MetadataDepth.DETAIL_CORE, result.plan?.depth)

        val routeEvents = sink.events.filter { it.eventType == "metadata.route_decision" }
        assertEquals(
            "expected exactly one route_decision, got ${sink.events.map { it.eventType }}",
            1,
            routeEvents.size
        )

        val fieldSelectedEvents = sink.events.filter { it.eventType == "metadata.field_selected" }
        assertTrue(
            "expected >=1 field_selected, got 0 (events=${sink.events.map { it.eventType }})",
            fieldSelectedEvents.isNotEmpty()
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
