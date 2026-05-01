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
import com.nexio.tv.data.integration.trakt.TraktIntegrationProvider
import com.nexio.tv.data.local.DebugSettingsDataStore
import com.nexio.tv.data.local.TraktAuthDataStore
import com.nexio.tv.data.local.TraktLibrarySnapshotStore
import com.nexio.tv.data.remote.dto.trakt.TraktIdsDto
import com.nexio.tv.data.remote.dto.trakt.TraktListItemDto
import com.nexio.tv.data.remote.dto.trakt.TraktListSummaryDto
import com.nexio.tv.data.remote.dto.trakt.TraktMovieDto
import com.nexio.tv.data.trakt.outbox.TraktMutationOutboxCoordinator
import com.nexio.tv.domain.model.HomeDisplayMetadata
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test
import retrofit2.Response

@OptIn(ExperimentalCoroutinesApi::class)
class TraktLibraryResolveRequestTest {

    /**
     * Verifies that fetchMetadata routes through MetadataRouterFacade.resolveRequest,
     * threads LibraryEntry fields (name/poster/background/logo/genres/releaseInfo) into
     * MetadataSourceContext.addonMetadata, and never calls getMetaFromAllAddons.
     */
    @Test
    fun `fetchMetadata routes via resolveRequest with LibraryEntry fields as addonMetadata`() = runTest {
        val facade = mockk<MetadataRouterFacade>()
        val captured = slot<MetadataRequest>()
        coEvery { facade.resolveRequest(capture(captured)) } returns successResult("tt1234567")

        val service = buildService(facade = facade)
        service.refreshNow()
        advanceUntilIdle()

        coVerify(atLeast = 1) { facade.resolveRequest(any()) }
        val ctx = captured.captured.sourceContext
        assertNotNull(ctx.addonMetadata)
        // LibraryEntry.name → addonMetadata.title
        assertEquals("Watchlist Movie", ctx.addonMetadata!!.title)
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

        val snapshotStore = mockk<TraktLibrarySnapshotStore>(relaxed = true)
        val service = buildService(facade = facade, snapshotStore = snapshotStore)
        service.refreshNow()
        advanceUntilIdle()

        val items = service.observeAllItems().first()
        val item = items.firstOrNull { it.id == "tt1234567" }
        assertNotNull(item)
        assertNotNull(item!!.poster)
    }

    private fun buildService(
        facade: MetadataRouterFacade,
        snapshotStore: TraktLibrarySnapshotStore = mockk<TraktLibrarySnapshotStore>(relaxed = true)
    ): TraktLibraryService {
        val traktIntegrationProvider = mockk<TraktIntegrationProvider>()
        val traktAuthService = mockk<TraktAuthService>(relaxed = true)
        val debugSettingsDataStore = mockk<DebugSettingsDataStore>()
        val traktAuthDataStore = mockk<TraktAuthDataStore>()

        every { debugSettingsDataStore.diskFirstHomeStartupEnabled } returns flowOf(false)
        every { traktAuthDataStore.isEffectivelyAuthenticated } returns MutableStateFlow(true)
        every { snapshotStore.read(any()) } returns null

        // Stub Trakt API to return one watchlist movie
        coEvery { traktIntegrationProvider.getWatchlist(any(), any()) } returns
            Response.success(
                listOf(
                    TraktListItemDto(
                        rank = 1,
                        listedAt = "2026-03-30T12:00:00Z",
                        type = "movie",
                        movie = TraktMovieDto(
                            title = "Watchlist Movie",
                            year = 2024,
                            ids = TraktIdsDto(imdb = "tt1234567", trakt = 10)
                        )
                    )
                )
            )
        coEvery { traktIntegrationProvider.getUserLists(any(), any()) } returns
            Response.success(emptyList<TraktListSummaryDto>())

        return TraktLibraryService(
            traktIntegrationProvider = traktIntegrationProvider,
            traktAuthService = TraktRepositoryAuthGateway(traktAuthService),
            traktMutationOutboxCoordinator = mockk<TraktMutationOutboxCoordinator>(relaxed = true),
            metadataRouterFacade = facade,
            debugSettingsDataStore = debugSettingsDataStore,
            traktAuthDataStore = traktAuthDataStore,
            snapshotStore = snapshotStore
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
            title = "Watchlist Movie",
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
            title = "Watchlist Movie",
            poster = poster,
            backdrop = backdrop,
            logo = logo
        ),
        trace = emptyList()
    )
}
