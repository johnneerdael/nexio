package com.nexio.tv.data.repository

import com.nexio.tv.core.metadata.router.MetadataDecisionReason
import com.nexio.tv.core.metadata.router.MetadataDepth
import com.nexio.tv.core.metadata.router.MetadataMediaKind
import com.nexio.tv.core.metadata.router.MetadataPrimaryProvider
import com.nexio.tv.core.metadata.router.MetadataRequest
import com.nexio.tv.core.metadata.router.MetadataResolutionResult
import com.nexio.tv.core.metadata.router.MetadataRoute
import com.nexio.tv.core.metadata.router.MetadataRouteTrace
import com.nexio.tv.core.metadata.router.MetadataRouterFacade
import com.nexio.tv.core.metadata.router.MetadataSourceContext
import com.nexio.tv.core.metadata.router.ResolvedMetadataDocument
import com.nexio.tv.core.metadata.router.ResolverSchedule
import com.nexio.tv.data.local.ContinueWatchingSnapshotStore
import com.nexio.tv.data.local.MetadataDiskCacheStore
import com.nexio.tv.data.local.TraktSettingsDataStore
import com.nexio.tv.domain.model.HomeDisplayMetadata
import com.nexio.tv.domain.repository.WatchProgressRepository
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test

class ContinueWatchingResolveRequestTest {

    @Test
    fun `fetchHomeDisplayMetadata routes via resolveRequest with clickTimeDisplayMetadata as addonMetadata`() = runTest {
        val facade = mockk<MetadataRouterFacade>()
        val captured = slot<MetadataRequest>()

        val expectedClickTimeDisplay = HomeDisplayMetadata(
            title = "Sample Series",
            poster = "poster-url",
            backdrop = "backdrop-url",
            genres = listOf("Drama", "Thriller")
        )

        coEvery { facade.resolveRequest(capture(captured)) } returns successResult(
            contentId = "tvdb:355567",
            display = expectedClickTimeDisplay
        )

        val itemKey = "series:tvdb:355567"
        val metadataSnapshot = ContinueWatchingMetadataSnapshot(
            routingVersion = ContinueWatchingMetadataSnapshot.CURRENT_ROUTING_VERSION,
            parentId = "tvdb:355567",
            primaryProvider = MetadataPrimaryProvider.TVDB,
            decisionReason = MetadataDecisionReason.ITEM_TYPE_SERIES,
            clickTimeDisplayMetadata = expectedClickTimeDisplay
        )
        val snapshot = ContinueWatchingSnapshot(
            metadataSnapshotsByItemKey = mapOf(itemKey to metadataSnapshot)
        )

        val service = buildService(facade = facade)
        service.fetchHomeDisplayMetadata(
            contentType = "series",
            contentId = "tvdb:355567",
            snapshot = snapshot
        )

        coVerify(exactly = 1) { facade.resolveRequest(any<MetadataRequest>()) }

        val ctx = captured.captured.sourceContext
        assertNotNull(ctx.addonMetadata)
        assertEquals(expectedClickTimeDisplay.title, ctx.addonMetadata!!.title)
        assertEquals(expectedClickTimeDisplay.genres, ctx.addonMetadata!!.genres)
        assertEquals("tvdb:355567", captured.captured.contentId)
    }

    @Test
    fun `fetchHomeDisplayMetadata threads null addonMetadata when no routed snapshot exists`() = runTest {
        val facade = mockk<MetadataRouterFacade>()
        val captured = slot<MetadataRequest>()

        coEvery { facade.resolveRequest(capture(captured)) } returns successResult(
            contentId = "tmdb:12345",
            display = HomeDisplayMetadata(title = "Unknown Movie")
        )

        val snapshot = ContinueWatchingSnapshot(
            metadataSnapshotsByItemKey = emptyMap()
        )

        val service = buildService(facade = facade)
        service.fetchHomeDisplayMetadata(
            contentType = "movie",
            contentId = "tmdb:12345",
            snapshot = snapshot
        )

        coVerify(exactly = 1) { facade.resolveRequest(any<MetadataRequest>()) }
        // addonMetadata is null when there is no routed snapshot — acceptable per plan
        assertEquals(null, captured.captured.sourceContext.addonMetadata)
    }

    @Test
    fun `fetchHomeDisplayMetadata returns null when route is null`() = runTest {
        val facade = mockk<MetadataRouterFacade>()
        coEvery { facade.resolveRequest(any()) } returns nullRouteResult()

        val service = buildService(facade = facade)
        val result = service.fetchHomeDisplayMetadata(
            contentType = "series",
            contentId = "tmdb:99999",
            snapshot = ContinueWatchingSnapshot()
        )

        assertEquals(null, result)
    }

    // ---- helpers ----

    private fun successResult(
        contentId: String,
        display: HomeDisplayMetadata
    ) = MetadataResolutionResult(
        route = MetadataRoute(
            provider = MetadataPrimaryProvider.TVDB,
            parentId = contentId,
            mediaKind = MetadataMediaKind.SERIES,
            reason = MetadataDecisionReason.ITEM_TYPE_SERIES,
            sourceContext = MetadataSourceContext(),
            targetIds = mapOf(MetadataPrimaryProvider.TVDB to contentId),
            trace = listOf(MetadataRouteTrace(MetadataDecisionReason.ITEM_TYPE_SERIES, "test"))
        ),
        plan = null,
        resolverSchedule = ResolverSchedule(MetadataDepth.DETAIL_CORE, emptyList(), emptyList()),
        resolvedDocument = ResolvedMetadataDocument(
            canonicalId = contentId,
            title = display.title,
            overview = display.description,
            poster = display.poster,
            backdrop = display.backdrop,
            logo = display.logo,
            rating = null,
            runtimeMinutes = null,
            fieldOwners = emptyMap(),
            ignoredOverwrites = emptyList()
        ),
        displayMetadata = display,
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

    private fun buildService(
        facade: MetadataRouterFacade = mockk(relaxed = true)
    ): ContinueWatchingSnapshotService =
        ContinueWatchingSnapshotService(
            watchProgressRepository = mockk<WatchProgressRepository>(relaxed = true) {
                every { observeProgress(any()) } returns flowOf(emptyList())
            },
            trackingProgressService = mockk<TrackingProgressService>(relaxed = true) {
                every { observeRemoteSnapshotLoaded() } returns flowOf(false)
                every { observeContinueWatchingNextUp() } returns flowOf(emptyList())
                every { observeSyntheticContinueWatchingNextUp() } returns flowOf(emptyList())
            },
            trackingProviderStateService = mockk<TrackingProviderStateService>(relaxed = true) {
                every { state } returns flowOf(EffectiveTrackingProviderState())
            },
            traktSettingsDataStore = mockk<TraktSettingsDataStore>(relaxed = true) {
                every { dismissedNextUpKeys } returns flowOf(emptySet())
            },
            metadataDiskCacheStore = mockk<MetadataDiskCacheStore>(relaxed = true),
            snapshotStore = mockk<ContinueWatchingSnapshotStore>(relaxed = true) {
                every { read(any()) } returns null
            },
            metadataRouterFacade = facade
        )
}
