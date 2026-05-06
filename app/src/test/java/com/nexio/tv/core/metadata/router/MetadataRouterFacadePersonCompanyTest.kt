package com.nexio.tv.core.metadata.router

import com.nexio.tv.core.integration.RecordingTraceSink
import com.nexio.tv.core.integration.TmdbApiShapes
import com.nexio.tv.core.trace.TraceMetadataEvents
import com.nexio.tv.domain.model.ContentType
import com.nexio.tv.domain.model.PersonDetail
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.assertSame
import org.junit.Test

/**
 * Pins the contract that the person/company facade methods on [MetadataRouterFacade]:
 *  1. Fire the canonical `metadata.route_decision` (and at least one
 *     `metadata.field_selected`) trace events via the resolve pipeline at depth
 *     `DETAIL_SECONDARY`.
 *  2. Return compatibility projections from provider-plan organization/person candidates.
 */
class MetadataRouterFacadePersonCompanyTest {

    @Test
    fun `findPersonIdByExactName returns provider-plan canonical id and emits canonical trace events`() = runTest {
        val sink = RecordingTraceSink()
        val facade = newFacade(
            sink = sink,
            tmdbCandidate = MetadataCandidate(
                provider = MetadataPrimaryProvider.TMDB,
                resolverType = ResolverType.ORGANIZATION_PERSON,
                fields = mapOf(
                    ResolvedField.CANONICAL_ID to FieldValue("tmdb:person:608", FieldOwner.ORGANIZATION_PERSON)
                )
            )
        )

        val result = facade.findPersonIdByExactName(
            metadataRequest = personLookupRequest(name = "Hayao Miyazaki"),
            name = "Hayao Miyazaki"
        )

        assertEquals(608, result)
        assertCanonicalTraceEvents(sink)
    }

    @Test
    fun `findCompanyIdByExactName returns provider-plan organization id and emits canonical trace events`() = runTest {
        val sink = RecordingTraceSink()
        val facade = newFacade(
            sink = sink,
            tmdbCandidate = MetadataCandidate(
                provider = MetadataPrimaryProvider.TMDB,
                resolverType = ResolverType.ORGANIZATION_PERSON,
                fields = mapOf(
                    ResolvedField.ORGANIZATION_LIST to FieldValue(listOf(10342), FieldOwner.ORGANIZATION_PERSON)
                )
            )
        )

        val result = facade.findCompanyIdByExactName(
            metadataRequest = companyLookupRequest(name = "Studio Ghibli"),
            name = "Studio Ghibli"
        )

        assertEquals(10342, result)
        assertCanonicalTraceEvents(sink)
    }

    @Test
    fun `findPersonIdByExactName returns null when provider-plan output has no id`() = runTest {
        val sink = RecordingTraceSink()
        val facade = newFacade(
            sink = sink,
            tmdbCandidate = MetadataCandidate(
                provider = MetadataPrimaryProvider.TMDB,
                resolverType = ResolverType.ORGANIZATION_PERSON,
                fields = mapOf(
                    ResolvedField.TITLE to FieldValue("Nobody Knows", FieldOwner.PRIMARY)
                )
            )
        )

        val result = facade.findPersonIdByExactName(
            metadataRequest = personLookupRequest(name = "Nobody Knows"),
            name = "Nobody Knows"
        )

        assertNull(result)
        assertCanonicalTraceEvents(sink)
    }

    @Test
    fun `fetchPersonDetail returns provider-plan person detail and emits canonical trace events`() = runTest {
        val sink = RecordingTraceSink()
        val canned = PersonDetail(
            tmdbId = 608,
            name = "Hayao Miyazaki",
            biography = "An animator and filmmaker.",
            birthday = "1941-01-05",
            deathday = null,
            placeOfBirth = "Tokyo, Japan",
            profilePhoto = "/path.jpg",
            knownFor = "Directing",
            movieCredits = emptyList(),
            tvCredits = emptyList()
        )

        val facade = newFacade(
            sink = sink,
            tmdbCandidate = MetadataCandidate(
                provider = MetadataPrimaryProvider.TMDB,
                resolverType = ResolverType.ORGANIZATION_PERSON,
                fields = mapOf(
                    ResolvedField.CAST to FieldValue(canned, FieldOwner.ORGANIZATION_PERSON)
                )
            )
        )

        val result = facade.fetchPersonDetail(
            metadataRequest = personDetailRequest(personId = 608),
            personId = 608
        )

        assertEquals(canned, result)
        assertCanonicalTraceEvents(sink)
    }

    @Test
    fun `fetchPersonDetail routes preferCrewCredits through combined credits provider step`() = runTest {
        val sink = RecordingTraceSink()
        var observedShape: String? = null
        val crewDetail = PersonDetail(
            tmdbId = 608,
            name = "Hayao Miyazaki",
            biography = null,
            birthday = null,
            deathday = null,
            placeOfBirth = null,
            profilePhoto = null,
            knownFor = "Writing",
            movieCredits = emptyList(),
            tvCredits = emptyList()
        )
        val adapter = object : MetadataProviderAdapter {
            override val provider: MetadataPrimaryProvider = MetadataPrimaryProvider.TMDB
            override fun supports(step: ProviderPlanStep): Boolean = true
            override suspend fun execute(route: MetadataRoute, step: ProviderPlanStep): ProviderStepResult {
                observedShape = step.apiShapeId
                return ProviderStepResult(
                    step = step,
                    candidate = MetadataCandidate(
                        provider = MetadataPrimaryProvider.TMDB,
                        resolverType = ResolverType.ORGANIZATION_PERSON,
                        fields = mapOf(
                            ResolvedField.CREW to FieldValue(crewDetail, FieldOwner.ORGANIZATION_PERSON)
                        )
                    )
                )
            }
        }

        val facade = newFacadeWithExtraAdapter(
            sink = sink,
            tmdbCandidate = MetadataCandidate(MetadataPrimaryProvider.TMDB, fields = emptyMap()),
            extraAdapter = adapter
        )

        val result = facade.fetchPersonDetail(
            metadataRequest = personDetailRequest(personId = 608),
            personId = 608,
            preferCrewCredits = true
        )

        assertEquals(crewDetail, result)
        assertEquals(TmdbApiShapes.PERSON_COMBINED_CREDITS, observedShape)
    }

    @Test
    fun `fetchPersonDetail with tvdb prefix returns TVDB adapter's PersonDetail and skips TMDB repo`() = runTest {
        val sink = RecordingTraceSink()
        val tvdbPerson = PersonDetail(
            tmdbId = 0,
            name = "Mark Hamill",
            biography = "Voice actor and Jedi.",
            birthday = "1951-09-25",
            deathday = null,
            placeOfBirth = "Oakland, California, USA",
            profilePhoto = "/tvdb/mark.jpg",
            knownFor = "Acting",
            movieCredits = emptyList(),
            tvCredits = emptyList()
        )
        val tvdbCandidate = MetadataCandidate(
            provider = MetadataPrimaryProvider.TVDB,
            fields = mapOf(
                ResolvedField.CAST to FieldValue(tvdbPerson, FieldOwner.PRIMARY)
            )
        )

        val facade = newFacadeWithExtraAdapter(
            sink = sink,
            tmdbCandidate = MetadataCandidate(MetadataPrimaryProvider.TMDB, fields = emptyMap()),
            extraAdapter = CannedCandidateAdapter(MetadataPrimaryProvider.TVDB, tvdbCandidate)
        )

        val result = facade.fetchPersonDetail(
            metadataRequest = MetadataRequest(
                contentId = "tvdb:person:287",
                // Use SERIES so the router takes the TVDB-native path (tvdb scheme, native type
                // is SERIES) and ProviderPlanExecutor.tvdbSteps validates without identity
                // resolution; the CannedCandidateAdapter then returns our TVDB CAST candidate
                // regardless of which step is dispatched.
                contentType = ContentType.SERIES,
                sourceContext = MetadataSourceContext(),
                language = "eng",
                depth = MetadataDepth.DETAIL_SECONDARY
            ),
            personId = 287
        )

        assertNotNull(result)
        assertSame(tvdbPerson, result)
    }

    private fun assertCanonicalTraceEvents(sink: RecordingTraceSink) {
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

    private fun personLookupRequest(name: String) = MetadataRequest(
        contentId = "tmdb:person:$name",
        contentType = ContentType.MOVIE,
        sourceContext = MetadataSourceContext(),
        language = "eng",
        depth = MetadataDepth.DETAIL_SECONDARY
    )

    private fun companyLookupRequest(name: String) = MetadataRequest(
        contentId = "tmdb:company:$name",
        contentType = ContentType.MOVIE,
        sourceContext = MetadataSourceContext(),
        language = "eng",
        depth = MetadataDepth.DETAIL_SECONDARY
    )

    private fun personDetailRequest(personId: Int) = MetadataRequest(
        contentId = "tmdb:person:$personId",
        contentType = ContentType.MOVIE,
        sourceContext = MetadataSourceContext(),
        language = "eng",
        depth = MetadataDepth.DETAIL_SECONDARY
    )

    private fun newFacade(
        sink: RecordingTraceSink,
        tmdbCandidate: MetadataCandidate
    ): MetadataRouterFacade = newFacadeWithExtraAdapter(sink, tmdbCandidate, extraAdapter = null)

    private fun newFacadeWithExtraAdapter(
        sink: RecordingTraceSink,
        tmdbCandidate: MetadataCandidate,
        extraAdapter: MetadataProviderAdapter?
    ): MetadataRouterFacade {
        val events = TraceMetadataEvents(sink, sessionId = { "s1" })
        val adapters: Set<MetadataProviderAdapter> = buildSet {
            if (extraAdapter != null) {
                add(extraAdapter)
            } else {
                add(CannedCandidateAdapter(MetadataPrimaryProvider.TMDB, tmdbCandidate))
            }
        }
        return MetadataRouterFacade(
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
            providerPlanRunner = ProviderPlanRunner(adapters),
            fieldResolver = FieldResolver(events)
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
