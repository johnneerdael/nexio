package com.nexio.tv.core.metadata.router

import com.nexio.tv.core.integration.RecordingTraceSink
import com.nexio.tv.core.trace.TraceMetadataEvents
import com.nexio.tv.data.integration.metadata.MetadataSecondaryRepository
import com.nexio.tv.domain.model.ContentType
import com.nexio.tv.domain.model.PersonDetail
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins the contract that the person/company facade methods on [MetadataRouterFacade]:
 *  1. Fire the canonical `metadata.route_decision` (and at least one
 *     `metadata.field_selected`) trace events via the resolve pipeline at depth
 *     `DETAIL_SECONDARY`.
 *  2. Return the underlying [MetadataSecondaryRepository] result unchanged.
 *
 * Facade-level pin for Task 17 of the cluster-A facade-bypass migration:
 * `MetaDetailsViewModel.hydrateKitsuNavigationTargetsAsync` no longer calls the
 * secondary repository directly for actor / company id resolution, so trace
 * observability (audit's primary goal) is restored. `fetchPersonDetail` is also
 * exposed on the facade for the cast-detail path.
 */
class MetadataRouterFacadePersonCompanyTest {

    @Test
    fun `findPersonIdByExactName delegates to secondary repo and emits canonical trace events`() = runTest {
        val sink = RecordingTraceSink()
        val secondaryRepo = mockk<MetadataSecondaryRepository>()
        coEvery { secondaryRepo.findPersonIdByExactName("Hayao Miyazaki") } returns 608

        val facade = newFacade(sink, secondaryRepo)

        val result = facade.findPersonIdByExactName(
            metadataRequest = personLookupRequest(name = "Hayao Miyazaki"),
            name = "Hayao Miyazaki"
        )

        assertEquals(608, result)
        coVerify(exactly = 1) { secondaryRepo.findPersonIdByExactName("Hayao Miyazaki") }
        assertCanonicalTraceEvents(sink)
    }

    @Test
    fun `findCompanyIdByExactName delegates to secondary repo and emits canonical trace events`() = runTest {
        val sink = RecordingTraceSink()
        val secondaryRepo = mockk<MetadataSecondaryRepository>()
        coEvery { secondaryRepo.findCompanyIdByExactName("Studio Ghibli") } returns 10342

        val facade = newFacade(sink, secondaryRepo)

        val result = facade.findCompanyIdByExactName(
            metadataRequest = companyLookupRequest(name = "Studio Ghibli"),
            name = "Studio Ghibli"
        )

        assertEquals(10342, result)
        coVerify(exactly = 1) { secondaryRepo.findCompanyIdByExactName("Studio Ghibli") }
        assertCanonicalTraceEvents(sink)
    }

    @Test
    fun `findPersonIdByExactName propagates null misses unchanged`() = runTest {
        val sink = RecordingTraceSink()
        val secondaryRepo = mockk<MetadataSecondaryRepository>()
        coEvery { secondaryRepo.findPersonIdByExactName("Nobody Knows") } returns null

        val facade = newFacade(sink, secondaryRepo)

        val result = facade.findPersonIdByExactName(
            metadataRequest = personLookupRequest(name = "Nobody Knows"),
            name = "Nobody Knows"
        )

        assertNull(result)
        coVerify(exactly = 1) { secondaryRepo.findPersonIdByExactName("Nobody Knows") }
        assertCanonicalTraceEvents(sink)
    }

    @Test
    fun `fetchPersonDetail delegates to secondary repo and emits canonical trace events`() = runTest {
        val sink = RecordingTraceSink()
        val secondaryRepo = mockk<MetadataSecondaryRepository>()
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
        coEvery { secondaryRepo.fetchPersonDetail(608, false) } returns canned

        val facade = newFacade(sink, secondaryRepo)

        val result = facade.fetchPersonDetail(
            metadataRequest = personLookupRequest(name = "Hayao Miyazaki"),
            personId = 608
        )

        assertEquals(canned, result)
        coVerify(exactly = 1) { secondaryRepo.fetchPersonDetail(608, false) }
        assertCanonicalTraceEvents(sink)
    }

    @Test
    fun `fetchPersonDetail forwards preferCrewCredits flag`() = runTest {
        val sink = RecordingTraceSink()
        val secondaryRepo = mockk<MetadataSecondaryRepository>()
        coEvery { secondaryRepo.fetchPersonDetail(608, true) } returns null

        val facade = newFacade(sink, secondaryRepo)

        facade.fetchPersonDetail(
            metadataRequest = personLookupRequest(name = "Hayao Miyazaki"),
            personId = 608,
            preferCrewCredits = true
        )

        coVerify(exactly = 1) { secondaryRepo.fetchPersonDetail(608, true) }
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

    private fun newFacade(
        sink: RecordingTraceSink,
        secondaryRepo: MetadataSecondaryRepository
    ): MetadataRouterFacade {
        val events = TraceMetadataEvents(sink, sessionId = { "s1" })
        val tmdbCandidate = MetadataCandidate(
            provider = MetadataPrimaryProvider.TMDB,
            fields = mapOf(
                ResolvedField.TITLE to FieldValue("Spirited Away", FieldOwner.PRIMARY)
            )
        )
        return MetadataRouterFacade(
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
