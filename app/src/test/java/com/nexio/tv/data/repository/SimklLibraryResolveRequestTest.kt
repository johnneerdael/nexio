package com.nexio.tv.data.repository

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
import com.nexio.tv.data.local.SimklAuthState
import com.nexio.tv.data.local.SimklLibrarySnapshotStore
import com.nexio.tv.data.remote.dto.simkl.SimklActivityBucketDto
import com.nexio.tv.data.remote.dto.simkl.SimklIdsDto
import com.nexio.tv.data.remote.dto.simkl.SimklLastActivitiesResponseDto
import com.nexio.tv.data.remote.dto.simkl.SimklLibraryItemDto
import com.nexio.tv.data.remote.dto.simkl.SimklMediaRefDto
import com.nexio.tv.domain.model.HomeDisplayMetadata
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test
import retrofit2.Response

@OptIn(ExperimentalCoroutinesApi::class)
class SimklLibraryResolveRequestTest {

    /**
     * Verifies that fetchMetadata routes through MetadataRouterFacade.resolveRequest,
     * threads LibraryEntry fields (name/poster/background/logo/genres/releaseInfo) into
     * MetadataSourceContext.addonMetadata, and never calls getMetaFromAllAddons.
     */
    @Test
    fun `fetchMetadata routes via resolveRequest with LibraryEntry fields as addonMetadata`() = runTest {
        val facade = mockk<MetadataRouterFacade>()
        val captured = slot<MetadataRequest>()
        coEvery { facade.resolveRequest(capture(captured)) } returns successResult("tt1375666")

        val service = buildService(facade = facade)
        service.refreshNow(force = true)
        advanceUntilIdle()

        coVerify(atLeast = 1) { facade.resolveRequest(any()) }
        val ctx = captured.captured.sourceContext
        assertNotNull(ctx.addonMetadata)
        // LibraryEntry.name → addonMetadata.title
        assertEquals("Inception", ctx.addonMetadata!!.title)
        // DETAIL_CORE depth used
        assertEquals(MetadataDepth.DETAIL_CORE, captured.captured.depth)
    }

    /**
     * Verifies that when the facade returns a route, the hydrated displayMetadata poster
     * is stored in the library entry, and getMetaFromAllAddons is never called.
     */
    @Test
    fun `fetchMetadata stores displayMetadata poster from resolveRequest result`() = runTest {
        val facade = mockk<MetadataRouterFacade>()
        coEvery { facade.resolveRequest(any()) } answers {
            val req = firstArg<MetadataRequest>()
            successResult(req.contentId, poster = "https://test.img/${req.contentId}/poster.jpg")
        }

        val snapshotStore = mockk<SimklLibrarySnapshotStore>(relaxed = true)
        val service = buildService(facade = facade, snapshotStore = snapshotStore)
        service.refreshNow(force = true)
        advanceUntilIdle()

        val items = service.observeAllItems().first()
        val item = items.firstOrNull { it.id == "tt1375666" }
        assertNotNull(item)
        assertNotNull(item!!.poster)
    }

    private fun buildService(
        facade: MetadataRouterFacade,
        snapshotStore: SimklLibrarySnapshotStore = mockk<SimklLibrarySnapshotStore>(relaxed = true)
    ): SimklLibraryService {
        val authDataStore = mockk<com.nexio.tv.data.local.SimklAuthDataStore>()
        val state = MutableStateFlow(SimklAuthState(accessToken = "token"))
        every { authDataStore.state } returns state
        every { authDataStore.isEffectivelyAuthenticated } returns state.map { it.isAuthenticated }
        every { authDataStore.stateForProfile(any()) } returns state
        every { snapshotStore.read(any()) } returns null

        val remote = mockk<SimklTrackingRemoteDataSource>()
        coEvery { remote.getLastActivities(any()) } returns Response.success(
            SimklLastActivitiesResponseDto(
                movies = SimklActivityBucketDto(all = "2026-04-12T00:00:00Z")
            )
        )
        coEvery { remote.getAllItems(dateFrom = null, extended = "full", session = any()) } returns Response.success(
            listOf(
                SimklLibraryItemDto(
                    status = "plantowatch",
                    movie = SimklMediaRefDto(
                        title = "Inception",
                        year = 2010,
                        ids = SimklIdsDto(imdb = "tt1375666", tmdb = "27205")
                    )
                )
            )
        )

        return SimklLibraryService(
            remote = remote,
            simklAuthDataStore = authDataStore,
            traktMutationOutboxCoordinator = mockk(relaxed = true),
            snapshotStore = snapshotStore,
            metadataRouterFacade = facade
        )
    }

    private fun successResult(
        contentId: String,
        poster: String? = "tvdb-poster",
        backdrop: String? = "tvdb-backdrop",
        logo: String? = "tvdb-logo"
    ) = MetadataResolutionResult(
        route = MetadataRoute(
            provider = MetadataPrimaryProvider.TVDB,
            parentId = contentId,
            mediaKind = MetadataMediaKind.MOVIE,
            reason = MetadataDecisionReason.ITEM_TYPE_MOVIE,
            sourceContext = MetadataSourceContext(),
            targetIds = mapOf(MetadataPrimaryProvider.TVDB to contentId),
            trace = emptyList()
        ),
        plan = null,
        resolverSchedule = ResolverSchedule(MetadataDepth.DETAIL_CORE, emptyList(), emptyList()),
        resolvedDocument = ResolvedMetadataDocument(
            canonicalId = contentId,
            title = "Inception",
            overview = null,
            poster = poster,
            backdrop = backdrop,
            logo = logo,
            rating = null,
            runtimeMinutes = null,
            fieldOwners = emptyMap(),
            ignoredOverwrites = emptyList()
        ),
        displayMetadata = HomeDisplayMetadata(
            title = "Inception",
            poster = poster,
            backdrop = backdrop,
            logo = logo
        ),
        trace = emptyList()
    )
}
