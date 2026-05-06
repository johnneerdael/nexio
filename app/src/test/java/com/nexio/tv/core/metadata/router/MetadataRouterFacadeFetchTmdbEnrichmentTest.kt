package com.nexio.tv.core.metadata.router

import com.nexio.tv.core.integration.RecordingTraceSink
import com.nexio.tv.core.integration.TmdbApiShapes
import com.nexio.tv.core.tmdb.TmdbEnrichment
import com.nexio.tv.core.trace.TraceMetadataEvents
import com.nexio.tv.domain.model.ContentType
import com.nexio.tv.domain.model.MetaCastMember
import com.nexio.tv.domain.model.MetaCompany
import com.nexio.tv.domain.model.MetaCompanyKind
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins the contract that `MetadataRouterFacade.fetchTmdbEnrichment(...)`:
 *  1. Fires the canonical `metadata.route_decision` (and at least one `metadata.field_selected`)
 *     trace events via the resolve pipeline.
 *  2. Returns a legacy [TmdbEnrichment] projection from resolved provider-plan output.
 */
class MetadataRouterFacadeFetchTmdbEnrichmentTest {

    @Test
    fun `fetchTmdbEnrichment projects resolved provider-plan output and emits canonical trace events`() = runTest {
        val sink = RecordingTraceSink()
        val events = TraceMetadataEvents(sink, sessionId = { "s1" })
        val cast = listOf(MetaCastMember(name = "Keanu Reeves", character = "Neo", tmdbId = 6384))
        val productionCompany = MetaCompany(tmdbId = 79, name = "Village Roadshow", kind = MetaCompanyKind.COMPANY)
        val network = MetaCompany(tmdbId = 49, name = "HBO", kind = MetaCompanyKind.NETWORK)
        val expected = TmdbEnrichment(
            localizedTitle = "The Matrix",
            description = "Neo discovers the truth.",
            genres = listOf("Action", "Science Fiction"),
            backdrop = "/backdrop.jpg",
            logo = "/logo.png",
            poster = "/poster.jpg",
            directorMembers = emptyList(),
            writerMembers = emptyList(),
            castMembers = cast,
            releaseInfo = "1999-03-31",
            rating = 8.7,
            runtimeMinutes = 136,
            director = emptyList(),
            writer = emptyList(),
            productionCompanies = listOf(productionCompany),
            networks = listOf(network),
            ageRating = "R",
            countries = listOf("US"),
            language = "en",
            collectionId = null,
            collectionName = null
        )

        val tmdbCandidate = MetadataCandidate(
            provider = MetadataPrimaryProvider.TMDB,
            fields = mapOf(
                ResolvedField.TITLE to FieldValue("The Matrix", FieldOwner.PRIMARY),
                ResolvedField.OVERVIEW to FieldValue("Neo discovers the truth.", FieldOwner.PRIMARY),
                ResolvedField.GENRES to FieldValue(listOf("Action", "Science Fiction"), FieldOwner.PRIMARY),
                ResolvedField.BACKDROP to FieldValue("/backdrop.jpg", FieldOwner.PRIMARY),
                ResolvedField.LOGO to FieldValue("/logo.png", FieldOwner.PRIMARY),
                ResolvedField.POSTER to FieldValue("/poster.jpg", FieldOwner.PRIMARY),
                ResolvedField.CAST to FieldValue(cast, FieldOwner.PRIMARY),
                ResolvedField.RELEASE_DATE to FieldValue("1999-03-31", FieldOwner.PRIMARY),
                ResolvedField.RATING to FieldValue(8.7, FieldOwner.RATING),
                ResolvedField.RUNTIME to FieldValue(136, FieldOwner.PRIMARY),
                ResolvedField.ORGANIZATION_LIST to FieldValue(listOf(productionCompany, network), FieldOwner.PRIMARY),
                ResolvedField.AGE_RATING to FieldValue("R", FieldOwner.PRIMARY),
                ResolvedField.COUNTRIES to FieldValue(listOf("US"), FieldOwner.PRIMARY),
                ResolvedField.LANGUAGE to FieldValue("en", FieldOwner.PRIMARY)
            )
        )

        val observedShapes = mutableListOf<String>()
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
                        observedShapes = observedShapes
                    )
                )
            ),
            fieldResolver = FieldResolver(events)
        )

        val result = facade.fetchTmdbEnrichment(
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

        assertEquals(expected, result)
        assertTrue(
            "expected DETAIL_SECONDARY plan to include TMDB reviews, got $observedShapes",
            TmdbApiShapes.MOVIE_REVIEWS in observedShapes
        )
        assertTrue(
            "expected DETAIL_SECONDARY plan to include TMDB recommendations, got $observedShapes",
            TmdbApiShapes.MOVIE_RECOMMENDATIONS in observedShapes
        )

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
        private val observedShapes: MutableList<String>
    ) : MetadataProviderAdapter {
        override fun supports(step: ProviderPlanStep): Boolean = true

        override suspend fun execute(route: MetadataRoute, step: ProviderPlanStep): ProviderStepResult {
            observedShapes += step.apiShapeId
            return ProviderStepResult(
                step = step,
                candidate = candidate,
                episodeMetadata = emptyMap()
            )
        }
    }
}
