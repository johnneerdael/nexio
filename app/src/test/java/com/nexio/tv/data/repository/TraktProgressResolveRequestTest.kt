package com.nexio.tv.data.repository

import com.nexio.tv.core.integration.passThroughTestRuntime
import com.nexio.tv.core.metadata.router.MetadataDecisionReason
import com.nexio.tv.core.metadata.router.MetadataDepth
import com.nexio.tv.core.metadata.router.MetadataMediaKind
import com.nexio.tv.core.metadata.router.MetadataPrimaryProvider
import com.nexio.tv.core.metadata.router.MetadataRequest
import com.nexio.tv.core.metadata.router.MetadataResolutionResult
import com.nexio.tv.core.metadata.router.MetadataRoute
import com.nexio.tv.core.metadata.router.MetadataRouterFacade
import com.nexio.tv.core.metadata.router.MetadataSourceContext
import com.nexio.tv.core.metadata.router.ResolvedMetadataDocument
import com.nexio.tv.core.metadata.router.ResolverSchedule
import com.nexio.tv.core.tvdb.TvEpisodeMetadata
import com.nexio.tv.core.tvdb.TvMetadataDecision
import com.nexio.tv.core.tvdb.TvMetadataDecisionReason
import com.nexio.tv.core.tvdb.TvMetadataRequest
import com.nexio.tv.core.tvdb.TvProvider
import com.nexio.tv.data.integration.trakt.TraktIntegrationProvider
import com.nexio.tv.data.remote.api.TraktApi
import com.nexio.tv.data.repository.trakt.TraktProgressMutationExecutor
import com.nexio.tv.domain.model.HomeDisplayMetadata
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test

/**
 * Verifies that both sites in TraktProgressService that previously called
 * MetaRepository.getMetaFromAllAddons now route through
 * MetadataRouterFacade.resolveRequest / fetchTvEpisodeEnrichment.
 *
 * Site 1: findEpisodeInfo  → fetchTvEpisodeEnrichment
 * Site 2: fetchContentMetadata → resolveRequest
 */
class TraktProgressResolveRequestTest {

    // -------------------------------------------------------------------------
    // Site 1: findEpisodeInfo  →  fetchTvEpisodeEnrichment
    // -------------------------------------------------------------------------

    @Test
    fun `findEpisodeInfo routes via fetchTvEpisodeEnrichment and returns composite stub videoId`() = runTest {
        val facade = mockk<MetadataRouterFacade>(relaxed = true)
        val capturedEpisodeRequest = slot<MetadataRequest>()
        coEvery {
            facade.fetchTvEpisodeEnrichment(
                metadataRequest = capture(capturedEpisodeRequest),
                tvRequest = any()
            )
        } returns TvMetadataDecision(
            provider = TvProvider.TVDB,
            reason = TvMetadataDecisionReason.TVDB_SUCCESS,
            value = mapOf(
                (1 to 3) to TvEpisodeMetadata(
                    episodeNumber = 3,
                    seasonNumber = 1,
                    title = "Pilot",
                    airDate = "2019-07-26"
                )
            )
        )

        val service = buildService(facade)
        val result = service.findEpisodeInfo("tmdb:355567", 1, 3)

        coVerify(atLeast = 1) {
            facade.fetchTvEpisodeEnrichment(metadataRequest = any(), tvRequest = any())
        }
        // depth must be SEASON for episode enrichment
        assertEquals(MetadataDepth.SEASON, capturedEpisodeRequest.captured.depth)
        assertEquals("tmdb:355567", capturedEpisodeRequest.captured.contentId)
        // result is non-null — no more UnresolvedEpisode from null
        assertNotNull(result)
        // videoId is the composite stub "$contentId:$season:$episode"
        assertEquals("tmdb:355567:1:3", result.videoId)
        // released is mapped from airDate
        assertEquals("2019-07-26", result.released)
    }

    @Test
    fun `findEpisodeInfo returns composite stub fallback when fetchTvEpisodeEnrichment fails`() = runTest {
        val facade = mockk<MetadataRouterFacade>(relaxed = true)
        coEvery {
            facade.fetchTvEpisodeEnrichment(metadataRequest = any(), tvRequest = any())
        } throws RuntimeException("TVDB unavailable")

        val service = buildService(facade)
        val result = service.findEpisodeInfo("tvdb:355567", 2, 5)

        // Must still return non-null (no more UnresolvedEpisode path for this failure)
        assertNotNull("findEpisodeInfo must return non-null even on enrichment failure", result)
        assertEquals("tvdb:355567:2:5", result.videoId)
        assertEquals(null, result.released)
    }

    @Test
    fun `findEpisodeInfo returns non-null when episode not found in enrichment map`() = runTest {
        val facade = mockk<MetadataRouterFacade>(relaxed = true)
        coEvery {
            facade.fetchTvEpisodeEnrichment(metadataRequest = any(), tvRequest = any())
        } returns TvMetadataDecision(
            provider = TvProvider.TVDB,
            reason = TvMetadataDecisionReason.TVDB_SUCCESS,
            value = emptyMap() // no episodes in map
        )

        val service = buildService(facade)
        val result = service.findEpisodeInfo("tt1234567", 3, 7)

        assertNotNull(result)
        assertEquals("tt1234567:3:7", result.videoId)
        assertEquals(null, result.released)
    }

    // -------------------------------------------------------------------------
    // Site 2: fetchContentMetadata  →  resolveRequest
    // -------------------------------------------------------------------------

    @Test
    fun `fetchContentMetadata routes via resolveRequest with DETAIL_CORE depth`() = runTest {
        val facade = mockk<MetadataRouterFacade>(relaxed = true)
        val capturedRequest = slot<MetadataRequest>()
        coEvery { facade.resolveRequest(capture(capturedRequest)) } returns successResult("tvdb:355567")

        val service = buildService(facade)
        val result = service.fetchContentMetadata("tvdb:355567", "series")

        coVerify(exactly = 1) { facade.resolveRequest(any()) }
        assertEquals("tvdb:355567", capturedRequest.captured.contentId)
        assertEquals(MetadataDepth.DETAIL_CORE, capturedRequest.captured.depth)
        assertNotNull(result)
        assertEquals("Sample Show", result!!.name)
    }

    @Test
    fun `fetchContentMetadata returns null when route is null`() = runTest {
        val facade = mockk<MetadataRouterFacade>(relaxed = true)
        coEvery { facade.resolveRequest(any()) } returns nullRouteResult()

        val service = buildService(facade)
        val result = service.fetchContentMetadata("tmdb:12345", "movie")

        assertEquals(null, result)
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private fun buildService(facade: MetadataRouterFacade): TraktProgressService {
        val traktAuthService = mockk<TraktAuthService>(relaxed = true) {
            every { currentTraktProfileId() } returns 1
        }
        return TraktProgressService(
            traktIntegrationProvider = TraktIntegrationProvider(
                runtime = passThroughTestRuntime(),
                traktApi = mockk<TraktApi>(relaxed = true),
                traktAuthService = traktAuthService
            ),
            traktProgressMutationExecutor = mockk<TraktProgressMutationExecutor>(relaxed = true),
            metadataRouterFacade = facade
        )
    }

    private fun successResult(contentId: String) = MetadataResolutionResult(
        route = MetadataRoute(
            provider = MetadataPrimaryProvider.TVDB,
            parentId = contentId,
            mediaKind = MetadataMediaKind.SERIES,
            reason = MetadataDecisionReason.ITEM_TYPE_SERIES,
            sourceContext = MetadataSourceContext(),
            targetIds = mapOf(MetadataPrimaryProvider.TVDB to contentId),
            trace = emptyList()
        ),
        plan = null,
        resolverSchedule = ResolverSchedule(MetadataDepth.DETAIL_CORE, emptyList(), emptyList()),
        resolvedDocument = ResolvedMetadataDocument(
            canonicalId = contentId,
            title = "Sample Show",
            overview = null,
            poster = "tvdb-p",
            backdrop = "tvdb-b",
            logo = null,
            rating = null,
            runtimeMinutes = null,
            fieldOwners = emptyMap(),
            ignoredOverwrites = emptyList()
        ),
        displayMetadata = HomeDisplayMetadata(
            title = "Sample Show",
            poster = "tvdb-p",
            backdrop = "tvdb-b"
        ),
        trace = emptyList()
    )

    private fun nullRouteResult() = MetadataResolutionResult(
        route = null,
        plan = null,
        resolverSchedule = ResolverSchedule(MetadataDepth.DETAIL_CORE, emptyList(), emptyList()),
        resolvedDocument = ResolvedMetadataDocument(
            canonicalId = null,
            title = null,
            overview = null,
            poster = null,
            backdrop = null,
            logo = null,
            rating = null,
            runtimeMinutes = null,
            fieldOwners = emptyMap(),
            ignoredOverwrites = emptyList()
        ),
        displayMetadata = HomeDisplayMetadata(),
        trace = emptyList()
    )
}
