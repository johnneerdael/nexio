package com.nexio.tv.data.repository

import com.nexio.tv.core.metadata.router.MetadataDepth
import com.nexio.tv.core.metadata.router.MetadataDecisionReason
import com.nexio.tv.core.metadata.router.MetadataMediaKind
import com.nexio.tv.core.metadata.router.MetadataPrimaryProvider
import com.nexio.tv.core.metadata.router.MetadataRequest
import com.nexio.tv.core.metadata.router.MetadataRoute
import com.nexio.tv.core.metadata.router.MetadataRouterFacade
import com.nexio.tv.core.metadata.router.MetadataRouteTrace
import com.nexio.tv.core.metadata.router.MetadataSourceContext
import com.nexio.tv.data.local.ContinueWatchingSnapshotStore
import com.nexio.tv.data.local.MetadataDiskCacheStore
import com.nexio.tv.data.local.TraktSettingsDataStore
import com.nexio.tv.domain.model.ContentType
import com.nexio.tv.domain.model.HomeDisplayMetadata
import com.nexio.tv.domain.model.WatchProgress
import com.nexio.tv.domain.repository.MetaRepository
import com.nexio.tv.domain.repository.WatchProgressRepository
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ContinueWatchingMetadataRouterTest {

    @Test
    fun `playback snapshot stores route context and click-time display metadata`() {
        val clickTime = HomeDisplayMetadata(
            title = "Addon Title",
            poster = "addon-poster",
            backdrop = "addon-backdrop"
        )
        val route = metadataRoute(
            parentId = "tvdb:121361",
            provider = MetadataPrimaryProvider.TVDB,
            reason = MetadataDecisionReason.ITEM_TYPE_SERIES
        )

        val metadataSnapshot = ContinueWatchingMetadataSnapshot.fromRoute(
            route = route,
            clickTimeDisplayMetadata = clickTime
        )
        val snapshot = ContinueWatchingSnapshot(
            metadataSnapshotsByItemKey = mapOf("series:121361" to metadataSnapshot)
        )

        val stored = snapshot.metadataSnapshotsByItemKey.getValue("series:121361")
        assertEquals(ContinueWatchingMetadataSnapshot.CURRENT_ROUTING_VERSION, stored.routingVersion)
        assertEquals("tvdb:121361", stored.parentId)
        assertEquals(MetadataPrimaryProvider.TVDB, stored.primaryProvider)
        assertEquals(MetadataDecisionReason.ITEM_TYPE_SERIES, stored.decisionReason)
        assertEquals(clickTime, stored.clickTimeDisplayMetadata)
    }

    @Test
    fun `routing version mismatch requires one reroute`() {
        assertTrue(ContinueWatchingMetadataSnapshot.shouldReroute(storedRoutingVersion = 0))
        assertFalse(
            ContinueWatchingMetadataSnapshot.shouldReroute(
                storedRoutingVersion = ContinueWatchingMetadataSnapshot.CURRENT_ROUTING_VERSION
            )
        )
    }

    @Test
    fun `offline render merges canonical then click-time then persisted fallback`() {
        val canonical = HomeDisplayMetadata(
            title = "Canonical Title",
            description = "Canonical Description"
        )
        val clickTime = HomeDisplayMetadata(
            title = "Click Title",
            poster = "click-poster",
            backdrop = "click-backdrop"
        )
        val persistedFallback = HomeDisplayMetadata(
            title = "Persisted Title",
            description = "Persisted Description",
            runtime = "42m",
            poster = "persisted-poster"
        )

        val rendered = ContinueWatchingMetadataSnapshot.renderDisplayMetadata(
            canonical = canonical,
            clickTime = clickTime,
            persistedFallback = persistedFallback
        )

        assertEquals("Canonical Title", rendered.title)
        assertEquals("Canonical Description", rendered.description)
        assertEquals("click-poster", rendered.poster)
        assertEquals("click-backdrop", rendered.backdrop)
        assertEquals("42m", rendered.runtime)
    }

    @Test
    fun `stale continue watching route reroutes once from stored parent id`() = runTest {
        val requestSlot = slot<MetadataRequest>()
        val facade = mockk<MetadataRouterFacade>()
        coEvery { facade.routeRequest(capture(requestSlot)) } returns metadataRoute(
            parentId = "kitsu:7442",
            provider = MetadataPrimaryProvider.KITSU,
            reason = MetadataDecisionReason.ID_MAPPING_TO_KITSU
        )
        val service = continueWatchingSnapshotService(metadataRouterFacade = facade)
        val clickTime = HomeDisplayMetadata(title = "Click Title")
        val staleSnapshot = ContinueWatchingMetadataSnapshot(
            routingVersion = 0,
            parentId = "tt12343534",
            primaryProvider = MetadataPrimaryProvider.TVDB,
            decisionReason = MetadataDecisionReason.ITEM_TYPE_SERIES,
            clickTimeDisplayMetadata = clickTime
        )

        val upgraded = service.upgradeStaleRouteSnapshots(
            ContinueWatchingSnapshot(
                resumeItems = listOf(
                    WatchProgress(
                        videoId = "tt12343534:1:1",
                        contentId = "tt12343534",
                        contentType = "series",
                        name = "Crunchyroll row",
                        poster = null,
                        backdrop = null,
                        logo = null,
                        season = 1,
                        episode = 1,
                        episodeTitle = null,
                        position = 1_000L,
                        duration = 2_000L,
                        lastWatched = 10L
                    )
                ),
                metadataSnapshotsByItemKey = mapOf("series:tt12343534" to staleSnapshot)
            )
        )

        val upgradedSnapshot = upgraded.metadataSnapshotsByItemKey.getValue("series:tt12343534")
        assertEquals(ContinueWatchingMetadataSnapshot.CURRENT_ROUTING_VERSION, upgradedSnapshot.routingVersion)
        assertEquals("kitsu:7442", upgradedSnapshot.parentId)
        assertEquals(MetadataPrimaryProvider.KITSU, upgradedSnapshot.primaryProvider)
        assertEquals(clickTime, upgradedSnapshot.clickTimeDisplayMetadata)
        assertEquals("tt12343534", requestSlot.captured.contentId)
        assertEquals(ContentType.SERIES, requestSlot.captured.contentType)
        coVerify(exactly = 1) { facade.routeRequest(any()) }
    }

    private fun metadataRoute(
        parentId: String,
        provider: MetadataPrimaryProvider,
        reason: MetadataDecisionReason
    ): MetadataRoute {
        return MetadataRoute(
            provider = provider,
            parentId = parentId,
            mediaKind = MetadataMediaKind.SERIES,
            reason = reason,
            sourceContext = MetadataSourceContext(),
            targetIds = mapOf(provider to parentId),
            trace = listOf(MetadataRouteTrace(reason, "test route"))
        )
    }

    private fun continueWatchingSnapshotService(
        metadataRouterFacade: MetadataRouterFacade
    ): ContinueWatchingSnapshotService =
        ContinueWatchingSnapshotService(
            watchProgressRepository = mockk<WatchProgressRepository>(relaxed = true) {
                every { allProgress } returns flowOf(emptyList())
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
            metaRepository = mockk<MetaRepository>(relaxed = true),
            metadataDiskCacheStore = mockk<MetadataDiskCacheStore>(relaxed = true),
            snapshotStore = mockk<ContinueWatchingSnapshotStore>(relaxed = true) {
                every { read(any()) } returns null
            },
            metadataRouterFacade = metadataRouterFacade
        )
}
