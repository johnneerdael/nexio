package com.nexio.tv.ui.screens.home

import android.content.Context
import androidx.lifecycle.viewModelScope
import com.nexio.tv.core.artwork.PremiumArtworkInvalidationNotifier
import com.nexio.tv.core.integration.ActiveProfileSession
import com.nexio.tv.core.integration.IntegrationOwnershipService
import com.nexio.tv.core.sync.AccountSyncRefreshNotifier
import com.nexio.tv.core.metadata.router.MetadataDepth
import com.nexio.tv.core.metadata.router.MetadataRouterFacade
import com.nexio.tv.core.metadata.router.StableIdResolutionTrigger
import com.nexio.tv.core.profile.ProfileModeRouter
import com.nexio.tv.core.trace.NoopRuntimeTraceSink
import com.nexio.tv.core.trace.RuntimeTraceSink
import com.nexio.tv.core.trace.TraceEventEnvelope
import com.nexio.tv.core.trace.TraceMetadataEvents
import com.nexio.tv.core.tvdb.ProviderLocalizedMetadataResolver
import com.nexio.tv.core.tvdb.TvMetadataDecision
import com.nexio.tv.core.tvdb.TvMetadataDecisionReason
import com.nexio.tv.core.tvdb.TvProvider
import com.nexio.tv.data.local.DebugSettingsDataStore
import com.nexio.tv.data.local.HydratedHomeOverlayStore
import com.nexio.tv.data.local.HomeCatalogSnapshotStore
import com.nexio.tv.data.local.KitsuCatalogIds
import com.nexio.tv.data.local.KitsuCatalogPreferences
import com.nexio.tv.data.local.MetadataDiskCacheStore
import com.nexio.tv.data.local.SyntheticHomeCatalogStore
import com.nexio.tv.data.repository.ContinueWatchingSnapshotService
import com.nexio.tv.data.repository.TrackingProviderStateService
import com.nexio.tv.data.repository.TrackingScrobbleService
import com.nexio.tv.domain.model.Addon
import com.nexio.tv.domain.model.AddonResource
import com.nexio.tv.domain.model.CatalogDescriptor
import com.nexio.tv.domain.model.CatalogRow
import com.nexio.tv.domain.model.ContentType
import com.nexio.tv.domain.model.FirstPaintSource
import com.nexio.tv.domain.model.HomeDisplayMetadata
import com.nexio.tv.domain.model.HomeItemHydrationState
import com.nexio.tv.domain.model.HydratedHomeOverlay
import com.nexio.tv.domain.model.HydratedHomeFieldTrace
import com.nexio.tv.domain.model.MetaPreview
import com.nexio.tv.domain.model.PosterShape
import com.nexio.tv.domain.model.ProviderId
import com.nexio.tv.domain.model.ProviderIds
import com.nexio.tv.domain.model.RailHydrationState
import com.nexio.tv.domain.model.RailSource
import com.nexio.tv.domain.model.TmdbSettings
import com.nexio.tv.domain.model.TitleRatingSource
import com.nexio.tv.domain.model.hydratedHomeDisplayHash
import com.nexio.tv.domain.repository.AddonRepository
import com.nexio.tv.domain.repository.CatalogRepository
import com.nexio.tv.domain.repository.LibraryRepository
import com.nexio.tv.domain.repository.MetaRepository
import com.nexio.tv.domain.repository.WatchProgressRepository
import com.nexio.tv.ui.screensaver.PlaybackIdleGateState
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Regression guard for Task 2 of the rail-preview-first hydration plan.
 *
 * Verifies that [HomeViewModel.onItemFocusPipeline] sends RAIL_PREVIEW items into the
 * canonical detail-core hydration path, skips the no-route PREVIEW boundary, and is
 * idempotent for repeated focus of the same item (via [HomeViewModel.focusedItemHydrationStates]).
 */
@OptIn(ExperimentalCoroutinesApi::class)
class HomeViewModelFocusHydrationTest {

    private val testDispatcher = StandardTestDispatcher()
    private val createdViewModels = mutableListOf<HomeViewModel>()

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
    }

    @After
    fun tearDown() {
        createdViewModels.forEach { it.viewModelScope.cancel() }
        testDispatcher.scheduler.advanceUntilIdle()
        createdViewModels.clear()
        Dispatchers.resetMain()
    }

    @Test
    fun `onItemFocus for rail-preview item delegates focused hydration to coordinator`() = runTest(testDispatcher) {
        val homeHydrationCoordinator = mockk<HomeHydrationCoordinator>()
        val overlayCallback = slot<(HydratedHomeOverlay) -> Boolean>()
        val item = railPreviewMetaPreview().copy(type = ContentType.MOVIE, rawType = "movie")
        val overlay = overlay(
            itemKey = "movie:${item.id}",
            fields = HomeDisplayMetadata(title = "Canonical Focused")
        )
        coEvery {
            homeHydrationCoordinator.hydrate(
                item = item,
                trigger = StableIdResolutionTrigger.FOCUSED_HOME_ITEM,
                priority = HomeHydrationPriority.FOCUSED,
                languageTag = "en",
                expectedGeneration = any(),
                currentGeneration = any(),
                onOverlayApplied = capture(overlayCallback)
            )
        } coAnswers {
            overlayCallback.captured(overlay)
            overlay
        }

        val viewModel = buildTestHomeViewModel(
            metadataRouterFacade = mockk(relaxed = true),
            homeHydrationCoordinator = homeHydrationCoordinator,
            nonPlaybackHomeWorkAllowed = true
        )

        viewModel.onItemFocus(item)
        advanceUntilIdle()

        assertEquals(RailHydrationState.CANONICAL_READY, viewModel.focusedItemHydrationStates[item.homeOverlayItemKey()])
        assertEquals(overlay, viewModel.hydratedHomeOverlaysByItemKey.value.getValue("movie:${item.id}"))
        assertNotNull(viewModel.catalogUpdateJob)
        coVerify(exactly = 1) {
            homeHydrationCoordinator.hydrate(
                item = item,
                trigger = StableIdResolutionTrigger.FOCUSED_HOME_ITEM,
                priority = HomeHydrationPriority.FOCUSED,
                languageTag = "en",
                expectedGeneration = any(),
                currentGeneration = any(),
                onOverlayApplied = any()
            )
        }
    }

    @Test
    fun `onItemFocus for non-rail item delegates focused hydration to coordinator`() = runTest(testDispatcher) {
        val homeHydrationCoordinator = mockk<HomeHydrationCoordinator>()
        val overlayCallback = slot<(HydratedHomeOverlay) -> Boolean>()
        val item = railPreviewMetaPreview().copy(
            type = ContentType.MOVIE,
            rawType = "movie",
            firstPaintSource = FirstPaintSource.ADDON_META_PREVIEW
        )
        val overlay = overlay(
            itemKey = "movie:${item.id}",
            fields = HomeDisplayMetadata(title = "Canonical Non-Rail Focused")
        )
        coEvery {
            homeHydrationCoordinator.hydrate(
                item = item,
                trigger = StableIdResolutionTrigger.FOCUSED_HOME_ITEM,
                priority = HomeHydrationPriority.FOCUSED,
                languageTag = "en",
                expectedGeneration = any(),
                currentGeneration = any(),
                onOverlayApplied = capture(overlayCallback)
            )
        } coAnswers {
            overlayCallback.captured(overlay)
            overlay
        }

        val viewModel = buildTestHomeViewModel(
            metadataRouterFacade = mockk(relaxed = true),
            homeHydrationCoordinator = homeHydrationCoordinator,
            nonPlaybackHomeWorkAllowed = true
        )

        viewModel.onItemFocus(item)
        advanceUntilIdle()

        assertEquals(RailHydrationState.CANONICAL_READY, viewModel.focusedItemHydrationStates[item.homeOverlayItemKey()])
        assertEquals(overlay, viewModel.hydratedHomeOverlaysByItemKey.value.getValue("movie:${item.id}"))
        assertNotNull(viewModel.catalogUpdateJob)
        coVerify(exactly = 1) {
            homeHydrationCoordinator.hydrate(
                item = item,
                trigger = StableIdResolutionTrigger.FOCUSED_HOME_ITEM,
                priority = HomeHydrationPriority.FOCUSED,
                languageTag = "en",
                expectedGeneration = any(),
                currentGeneration = any(),
                onOverlayApplied = any()
            )
        }
    }

    @Test
    fun `visible home hydration ignores overlay applied after language changes`() = runTest(testDispatcher) {
        var currentLanguage = "en"
        val traceSink = RecordingTraceSink()
        val homeHydrationCoordinator = mockk<HomeHydrationCoordinator>()
        val overlayCallback = slot<(HydratedHomeOverlay) -> Boolean>()
        val visible = railPreviewMetaPreview().copy(type = ContentType.MOVIE, rawType = "movie")
        val overlay = overlay(
            itemKey = "movie:${visible.id}",
            fields = HomeDisplayMetadata(title = "Stale English")
        )
        coEvery {
            homeHydrationCoordinator.hydrate(
                item = visible,
                trigger = StableIdResolutionTrigger.VISIBLE_HOME_HYDRATION,
                priority = HomeHydrationPriority.VISIBLE,
                languageTag = "en",
                expectedGeneration = 7L,
                currentGeneration = any(),
                onOverlayApplied = capture(overlayCallback)
            )
        } coAnswers {
            currentLanguage = "nl"
            overlayCallback.captured.invoke(overlay)
            overlay
        }

        val viewModel = buildTestHomeViewModel(
            metadataRouterFacade = mockk(relaxed = true),
            homeHydrationCoordinator = homeHydrationCoordinator,
            nonPlaybackHomeWorkAllowed = true,
            currentLanguageTagProvider = { currentLanguage },
            traceEvents = TraceMetadataEvents(traceSink) { "home-trace" }
        )
        viewModel.homeProfileGeneration = 7L

        viewModel.hydrateVisibleHomeItemsWithCoordinator(
            items = listOf(visible),
            expectedGeneration = 7L
        )
        advanceUntilIdle()

        assertEquals(emptyMap<String, HydratedHomeOverlay>(), viewModel.hydratedHomeOverlaysByItemKey.value)
        assertNull(viewModel.catalogUpdateJob)
        assertEquals(1, traceSink.events.size)
        val traceEvent = traceSink.events.single()
        val tracePayload = traceEvent.payload as Map<*, *>
        assertEquals("home.hydration_ignored", traceEvent.eventType)
        assertEquals("movie:${visible.id}", tracePayload["itemKey"])
        assertEquals("language_changed", tracePayload["reason"])
        assertEquals(StableIdResolutionTrigger.VISIBLE_HOME_HYDRATION.name, tracePayload["trigger"])
    }

    @Test
    fun `visible home hydration delegates to coordinator and publishes overlay`() = runTest(testDispatcher) {
        val homeHydrationCoordinator = mockk<HomeHydrationCoordinator>()
        val callbacks = mutableListOf<(HydratedHomeOverlay) -> Boolean>()
        val visible = railPreviewMetaPreview().copy(type = ContentType.MOVIE, rawType = "movie")
        val duplicate = visible.copy(name = "Duplicate copy")
        val overlay = overlay(
            itemKey = "movie:${visible.id}",
            fields = HomeDisplayMetadata(title = "Canonical Visible")
        )
        coEvery {
            homeHydrationCoordinator.hydrate(
                item = any(),
                trigger = StableIdResolutionTrigger.VISIBLE_HOME_HYDRATION,
                priority = HomeHydrationPriority.VISIBLE,
                languageTag = "en",
                expectedGeneration = 7L,
                currentGeneration = any(),
                onOverlayApplied = capture(callbacks)
            )
        } coAnswers {
            callbacks.last().invoke(overlay)
            overlay
        }

        val viewModel = buildTestHomeViewModel(
            metadataRouterFacade = mockk(relaxed = true),
            homeHydrationCoordinator = homeHydrationCoordinator,
            nonPlaybackHomeWorkAllowed = true
        )
        viewModel.homeProfileGeneration = 7L

        viewModel.hydrateVisibleHomeItemsWithCoordinator(
            items = listOf(visible, duplicate),
            expectedGeneration = 7L
        )
        advanceUntilIdle()

        assertEquals(overlay, viewModel.hydratedHomeOverlaysByItemKey.value.getValue("movie:${visible.id}"))
        val surfaceItem = viewModel.resolvedDisplaySurfaceRepository.getSnapshot(profileId = 1).single()
        assertEquals("movie:${visible.id}", surfaceItem.itemKey)
        assertEquals("Canonical Visible", surfaceItem.display.title)
        assertNotNull(viewModel.catalogUpdateJob)
        coVerify(exactly = 1) {
            homeHydrationCoordinator.hydrate(
                item = visible,
                trigger = StableIdResolutionTrigger.VISIBLE_HOME_HYDRATION,
                priority = HomeHydrationPriority.VISIBLE,
                languageTag = "en",
                expectedGeneration = 7L,
                currentGeneration = any(),
                onOverlayApplied = any()
            )
        }
    }

    @Test
    fun `visible home hydration rejects overlay when active session changes before callback`() = runTest(testDispatcher) {
        val activeSession = MutableStateFlow(profileSession(profileId = 1, sessionId = "session-a"))
        val homeHydrationCoordinator = mockk<HomeHydrationCoordinator>()
        val callbacks = mutableListOf<(HydratedHomeOverlay) -> Boolean>()
        val visible = railPreviewMetaPreview().copy(type = ContentType.MOVIE, rawType = "movie")
        val overlay = overlay(
            itemKey = "movie:${visible.id}",
            fields = HomeDisplayMetadata(title = "Old Profile Canonical")
        )
        coEvery {
            homeHydrationCoordinator.hydrate(
                item = visible,
                trigger = StableIdResolutionTrigger.VISIBLE_HOME_HYDRATION,
                priority = HomeHydrationPriority.VISIBLE,
                languageTag = "en",
                expectedGeneration = 7L,
                currentGeneration = any(),
                onOverlayApplied = capture(callbacks)
            )
        } coAnswers {
            activeSession.value = profileSession(profileId = 2, sessionId = "session-b")
            callbacks.last().invoke(overlay)
            overlay
        }

        val viewModel = buildTestHomeViewModel(
            metadataRouterFacade = mockk(relaxed = true),
            homeHydrationCoordinator = homeHydrationCoordinator,
            nonPlaybackHomeWorkAllowed = true,
            profileSessionFlow = activeSession
        )
        viewModel.homeProfileGeneration = 7L

        viewModel.hydrateVisibleHomeItemsWithCoordinator(
            items = listOf(visible),
            expectedGeneration = 7L
        )
        advanceUntilIdle()

        assertEquals(emptyMap<String, HydratedHomeOverlay>(), viewModel.hydratedHomeOverlaysByItemKey.value)
        assertTrue(viewModel.resolvedDisplaySurfaceRepository.getSnapshot(profileId = 1).isEmpty())
        assertTrue(viewModel.resolvedDisplaySurfaceRepository.getSnapshot(profileId = 2).isEmpty())
    }

    @Test
    fun `scheduled catalog publish does not write old rows into new active session`() = runTest(testDispatcher) {
        val activeSession = MutableStateFlow(profileSession(profileId = 1, sessionId = "session-a"))
        val viewModel = buildTestHomeViewModel(
            metadataRouterFacade = mockk(relaxed = true),
            nonPlaybackHomeWorkAllowed = true,
            profileSessionFlow = activeSession
        )
        val row = CatalogRow(
            addonId = "addon",
            addonName = "Addon",
            addonBaseUrl = "https://addon.example",
            catalogId = "popular",
            catalogName = "Popular",
            type = ContentType.MOVIE,
            items = listOf(railPreviewMetaPreview().copy(id = "tt-old-profile", name = "Old Profile Row")),
            hasMore = false
        )
        val key = homeCatalogGlobalKey(row)
        viewModel.addonsCache = listOf(
            Addon(
                id = "addon",
                name = "Addon",
                version = "1.0.0",
                description = null,
                logo = null,
                baseUrl = "https://addon.example",
                catalogs = listOf(CatalogDescriptor(type = ContentType.MOVIE, id = "popular", name = "Popular")),
                types = listOf(ContentType.MOVIE),
                resources = listOf(AddonResource(name = "catalog", types = listOf("movie"), idPrefixes = null))
            )
        )
        viewModel.catalogsMap[key] = row
        viewModel.catalogOrder.clear()
        viewModel.catalogOrder.add(key)
        viewModel.installedAddonsObserved = true
        viewModel.traktDiscoveryObserved = true
        viewModel.simklDiscoveryObserved = true
        viewModel.mdbListDiscoveryObserved = true
        viewModel.tmdbDiscoveryObserved = true
        viewModel.kitsuDiscoveryObserved = true

        viewModel.scheduleUpdateCatalogRows()
        activeSession.value = profileSession(profileId = 2, sessionId = "session-b")
        advanceUntilIdle()

        assertTrue(viewModel.resolvedDisplaySurfaceRepository.getSnapshot(profileId = 1).isEmpty())
        assertTrue(viewModel.resolvedDisplaySurfaceRepository.getSnapshot(profileId = 2).isEmpty())
    }

    @Test
    fun `visible home hydration skips item with current same-language overlay`() = runTest(testDispatcher) {
        val homeHydrationCoordinator = mockk<HomeHydrationCoordinator>()
        val visible = railPreviewMetaPreview().copy(type = ContentType.MOVIE, rawType = "movie")
        val currentOverlay = overlay(
            itemKey = "movie:${visible.id}",
            fields = HomeDisplayMetadata(title = "Current Visible")
        )
        val viewModel = buildTestHomeViewModel(
            metadataRouterFacade = mockk(relaxed = true),
            homeHydrationCoordinator = homeHydrationCoordinator,
            nonPlaybackHomeWorkAllowed = true
        )
        viewModel.homeProfileGeneration = 7L
        viewModel.hydratedHomeOverlaysByItemKey.value = mapOf(visible.homeOverlayItemKey() to currentOverlay)

        viewModel.hydrateVisibleHomeItemsWithCoordinator(
            items = listOf(visible),
            expectedGeneration = 7L
        )
        advanceUntilIdle()

        assertEquals(currentOverlay, viewModel.hydratedHomeOverlaysByItemKey.value.getValue(visible.homeOverlayItemKey()))
        coVerify(exactly = 0) {
            homeHydrationCoordinator.hydrate(
                item = any(),
                trigger = StableIdResolutionTrigger.VISIBLE_HOME_HYDRATION,
                priority = HomeHydrationPriority.VISIBLE,
                languageTag = any(),
                expectedGeneration = any(),
                currentGeneration = any(),
                onOverlayApplied = any()
            )
        }
    }

    @Test
    fun `overlay scope invalidation clears observer state and schedules catalog recompute`() = runTest(testDispatcher) {
        val viewModel = buildTestHomeViewModel(metadataRouterFacade = mockk(relaxed = true))
        val observerJob = Job()
        viewModel.hydratedHomeOverlayObserverJob = observerJob
        viewModel.hydratedHomeOverlayObserverSignature = "en|movie:tmdb:550"
        viewModel.hydratedHomeOverlaysByItemKey.value = mapOf(
            "movie:tmdb:550" to mockk<HydratedHomeOverlay>(relaxed = true)
        )
        viewModel.lastCatalogComputationSignature = "previous"

        viewModel.invalidateHydratedHomeOverlayScope()

        assertTrue(observerJob.isCancelled)
        assertNull(viewModel.hydratedHomeOverlayObserverJob)
        assertNull(viewModel.hydratedHomeOverlayObserverSignature)
        assertEquals(emptyMap<String, HydratedHomeOverlay>(), viewModel.hydratedHomeOverlaysByItemKey.value)
        assertNull(viewModel.lastCatalogComputationSignature)
        assertNotNull(viewModel.catalogUpdateJob)
        viewModel.catalogUpdateJob?.cancel()
    }

    @Test
    fun `premium artwork invalidation clears live hydrated overlays and schedules catalog recompute`() = runTest(testDispatcher) {
        val notifier = PremiumArtworkInvalidationNotifier()
        val viewModel = buildTestHomeViewModel(
            metadataRouterFacade = mockk(relaxed = true),
            premiumArtworkInvalidationNotifier = notifier
        )
        advanceUntilIdle()
        val observerJob = Job()
        viewModel.hydratedHomeOverlayObserverJob = observerJob
        viewModel.hydratedHomeOverlayObserverSignature = "en|movie:tmdb:550"
        viewModel.hydratedHomeOverlaysByItemKey.value = mapOf(
            "movie:tmdb:550" to mockk<HydratedHomeOverlay>(relaxed = true)
        )
        viewModel.lastCatalogComputationSignature = "previous"

        notifier.notifyInvalidated()
        advanceUntilIdle()

        assertTrue(observerJob.isCancelled)
        assertNull(viewModel.hydratedHomeOverlayObserverJob)
        assertNull(viewModel.hydratedHomeOverlayObserverSignature)
        assertEquals(emptyMap<String, HydratedHomeOverlay>(), viewModel.hydratedHomeOverlaysByItemKey.value)
        assertNull(viewModel.lastCatalogComputationSignature)
        assertNotNull(viewModel.catalogUpdateJob)
        viewModel.catalogUpdateJob?.cancel()
    }

    @Test
    fun `catalog configuration invalidation clears stale row computation caches`() = runTest(testDispatcher) {
        val viewModel = buildTestHomeViewModel(metadataRouterFacade = mockk(relaxed = true))
        viewModel.lastCatalogComputationSignature = "previous"
        viewModel.lastCatalogOrderDiagnosticsSignature = "previous-order"
        viewModel.truncatedRowCache["row"] = HomeViewModel.TruncatedRowCacheEntry(
            sourceRow = mockk(relaxed = true),
            truncatedRow = mockk(relaxed = true)
        )

        viewModel.invalidateHomeCatalogConfigurationPipeline("test")

        assertNull(viewModel.lastCatalogComputationSignature)
        assertNull(viewModel.lastCatalogOrderDiagnosticsSignature)
        assertTrue(viewModel.truncatedRowCache.isEmpty())
    }

    @Test
    fun `onItemFocus for already-canonical item does not trigger MetadataRouter at PREVIEW depth`() = runTest(testDispatcher) {
        // Use a relaxed facade so the existing enrichment path (DETAIL_CORE) can call resolveRequest
        // without crashing — we only assert that our new RAIL_PREVIEW hydration does NOT fire.
        val facade = mockk<MetadataRouterFacade>(relaxed = true)
        val viewModel = buildTestHomeViewModel(metadataRouterFacade = facade)

        val addonMetaItem = MetaPreview(
            id = "tvdb:355567",
            type = ContentType.SERIES,
            name = "Sample",
            poster = "p",
            posterShape = PosterShape.POSTER,
            background = "b",
            logo = "l",
            description = "d",
            releaseInfo = "2019",
            imdbRating = 8.4f,
            genres = listOf("Drama"),
            // ADDON_META_PREVIEW: already has full preview data, no RAIL_PREVIEW router hydration needed
            firstPaintSource = FirstPaintSource.ADDON_META_PREVIEW,
            firstPaintSourceProvider = ProviderId.ADDON,
            firstPaintStableIds = ProviderIds(tvdb = "355567"),
            firstPaintRailSource = RailSource.ADDON_CATALOG,
            firstPaintSourceItemId = "tvdb:355567"
        )

        viewModel.onItemFocus(addonMetaItem)
        viewModel.onItemFocus(addonMetaItem)  // second focus — still no-op for our hydration
        advanceUntilIdle()

        coVerify(exactly = 0) { facade.resolveRequest(match { it.depth == MetadataDepth.PREVIEW }) }
    }

    @Test
    fun `focused rail hydration state is scoped by home overlay item key`() = runTest(testDispatcher) {
        val homeHydrationCoordinator = mockk<HomeHydrationCoordinator>()
        coEvery {
            homeHydrationCoordinator.hydrate(any(), any(), any(), any(), any(), any(), any())
        } returns null
        val movieItem = railPreviewMetaPreview().copy(
            id = "shared-id",
            type = ContentType.MOVIE,
            rawType = "movie"
        )
        val seriesItem = railPreviewMetaPreview().copy(
            id = "shared-id",
            type = ContentType.SERIES,
            rawType = "series"
        )
        val viewModel = buildTestHomeViewModel(
            metadataRouterFacade = mockk(relaxed = true),
            homeHydrationCoordinator = homeHydrationCoordinator,
            nonPlaybackHomeWorkAllowed = true
        )

        viewModel.onItemFocus(movieItem)
        viewModel.onItemFocus(seriesItem)
        advanceUntilIdle()

        assertEquals(RailHydrationState.HYDRATION_FAILED_USING_PREVIEW, viewModel.focusedItemHydrationStates["movie:shared-id"])
        assertEquals(RailHydrationState.HYDRATION_FAILED_USING_PREVIEW, viewModel.focusedItemHydrationStates["series:shared-id"])
        coVerify(exactly = 1) {
            homeHydrationCoordinator.hydrate(
                item = movieItem,
                trigger = StableIdResolutionTrigger.FOCUSED_HOME_ITEM,
                priority = HomeHydrationPriority.FOCUSED,
                languageTag = "en",
                expectedGeneration = any(),
                currentGeneration = any(),
                onOverlayApplied = any()
            )
        }
        coVerify(exactly = 1) {
            homeHydrationCoordinator.hydrate(
                item = seriesItem,
                trigger = StableIdResolutionTrigger.FOCUSED_HOME_ITEM,
                priority = HomeHydrationPriority.FOCUSED,
                languageTag = "en",
                expectedGeneration = any(),
                currentGeneration = any(),
                onOverlayApplied = any()
            )
        }
    }

    @Test
    fun `focused completion guard is scoped by home overlay item key`() = runTest(testDispatcher) {
        val homeHydrationCoordinator = mockk<HomeHydrationCoordinator>()
        val callbacks = mutableListOf<(HydratedHomeOverlay) -> Boolean>()
        coEvery {
            homeHydrationCoordinator.hydrate(
                item = any(),
                trigger = StableIdResolutionTrigger.FOCUSED_HOME_ITEM,
                priority = HomeHydrationPriority.FOCUSED,
                languageTag = "en",
                expectedGeneration = any(),
                currentGeneration = any(),
                onOverlayApplied = capture(callbacks)
            )
        } coAnswers {
            val item = firstArg<MetaPreview>()
            val itemOverlay = overlay(
                itemKey = item.homeOverlayItemKey(),
                fields = HomeDisplayMetadata(title = "Canonical ${item.apiType}")
            )
            callbacks.last().invoke(itemOverlay)
            itemOverlay
        }
        val movieItem = railPreviewMetaPreview().copy(
            id = "shared-id",
            type = ContentType.MOVIE,
            rawType = "movie",
            firstPaintSource = FirstPaintSource.ADDON_META_PREVIEW
        )
        val seriesItem = railPreviewMetaPreview().copy(
            id = "shared-id",
            type = ContentType.SERIES,
            rawType = "series",
            firstPaintSource = FirstPaintSource.ADDON_META_PREVIEW
        )
        val viewModel = buildTestHomeViewModel(
            metadataRouterFacade = mockk(relaxed = true),
            homeHydrationCoordinator = homeHydrationCoordinator,
            nonPlaybackHomeWorkAllowed = true
        )

        viewModel.onItemFocus(movieItem)
        advanceUntilIdle()
        viewModel.onItemFocus(seriesItem)
        advanceUntilIdle()

        assertEquals(RailHydrationState.CANONICAL_READY, viewModel.focusedItemHydrationStates["movie:shared-id"])
        assertEquals(RailHydrationState.CANONICAL_READY, viewModel.focusedItemHydrationStates["series:shared-id"])
        coVerify(exactly = 1) {
            homeHydrationCoordinator.hydrate(
                item = movieItem,
                trigger = StableIdResolutionTrigger.FOCUSED_HOME_ITEM,
                priority = HomeHydrationPriority.FOCUSED,
                languageTag = "en",
                expectedGeneration = any(),
                currentGeneration = any(),
                onOverlayApplied = any()
            )
        }
        coVerify(exactly = 1) {
            homeHydrationCoordinator.hydrate(
                item = seriesItem,
                trigger = StableIdResolutionTrigger.FOCUSED_HOME_ITEM,
                priority = HomeHydrationPriority.FOCUSED,
                languageTag = "en",
                expectedGeneration = any(),
                currentGeneration = any(),
                onOverlayApplied = any()
            )
        }
    }

    @Test
    fun `adjacent item preload delegates hydration to coordinator and publishes overlay`() = runTest(testDispatcher) {
        val homeHydrationCoordinator = mockk<HomeHydrationCoordinator>()
        val overlayCallback = slot<(HydratedHomeOverlay) -> Boolean>()
        val item = railPreviewMetaPreview().copy(type = ContentType.MOVIE, rawType = "movie")
        val overlay = overlay(
            itemKey = "movie:${item.id}",
            fields = HomeDisplayMetadata(title = "Canonical Adjacent")
        )
        coEvery {
            homeHydrationCoordinator.hydrate(
                item = item,
                trigger = StableIdResolutionTrigger.FOCUSED_HOME_ITEM,
                priority = HomeHydrationPriority.ADJACENT,
                languageTag = "en",
                expectedGeneration = any(),
                currentGeneration = any(),
                onOverlayApplied = capture(overlayCallback)
            )
        } coAnswers {
            overlayCallback.captured.invoke(overlay)
            overlay
        }
        val viewModel = buildTestHomeViewModel(
            metadataRouterFacade = mockk(relaxed = true),
            homeHydrationCoordinator = homeHydrationCoordinator,
            nonPlaybackHomeWorkAllowed = true
        )

        viewModel.preloadAdjacentItem(item)
        advanceUntilIdle()

        assertEquals(overlay, viewModel.hydratedHomeOverlaysByItemKey.value.getValue("movie:${item.id}"))
        assertNotNull(viewModel.catalogUpdateJob)
        coVerify(exactly = 1) {
            homeHydrationCoordinator.hydrate(
                item = item,
                trigger = StableIdResolutionTrigger.FOCUSED_HOME_ITEM,
                priority = HomeHydrationPriority.ADJACENT,
                languageTag = "en",
                expectedGeneration = any(),
                currentGeneration = any(),
                onOverlayApplied = any()
            )
        }
    }

    @Test
    fun `onItemFocus for the same rail-preview item twice only delegates focused hydration once`() = runTest(testDispatcher) {
        val homeHydrationCoordinator = mockk<HomeHydrationCoordinator>()
        coEvery {
            homeHydrationCoordinator.hydrate(any(), any(), any(), any(), any(), any(), any())
        } returns null
        val viewModel = buildTestHomeViewModel(
            metadataRouterFacade = mockk(relaxed = true),
            homeHydrationCoordinator = homeHydrationCoordinator,
            nonPlaybackHomeWorkAllowed = true
        )
        val railPreviewItem = railPreviewMetaPreview().copy(type = ContentType.MOVIE, rawType = "movie")

        viewModel.onItemFocus(railPreviewItem)
        viewModel.onItemFocus(railPreviewItem)  // second focus — idempotent
        advanceUntilIdle()

        coVerify(exactly = 1) {
            homeHydrationCoordinator.hydrate(
                item = railPreviewItem,
                trigger = StableIdResolutionTrigger.FOCUSED_HOME_ITEM,
                priority = HomeHydrationPriority.FOCUSED,
                languageTag = "en",
                expectedGeneration = any(),
                currentGeneration = any(),
                onOverlayApplied = any()
            )
        }
    }

    @Test
    fun `hero enrichment delegates to coordinator and applies overlay fields`() = runTest(testDispatcher) {
        val homeHydrationCoordinator = mockk<HomeHydrationCoordinator>()
        val overlayCallback = slot<(HydratedHomeOverlay) -> Boolean>()
        val item = railPreviewMetaPreview()
            .copy(type = ContentType.MOVIE, rawType = "movie", poster = "preview-poster")
        val overlay = overlay(
            itemKey = "movie:${item.id}",
            fields = HomeDisplayMetadata(
                title = "Canonical Hero",
                logo = "hero-logo",
                poster = "hero-poster",
                backdrop = "hero-backdrop"
            )
        )
        coEvery {
            homeHydrationCoordinator.hydrate(
                item = item,
                trigger = StableIdResolutionTrigger.FOCUSED_HOME_ITEM,
                priority = HomeHydrationPriority.HERO,
                languageTag = "en",
                expectedGeneration = any(),
                currentGeneration = any(),
                onOverlayApplied = capture(overlayCallback)
            )
        } coAnswers {
            overlayCallback.captured.invoke(overlay)
            overlay
        }
        val viewModel = buildTestHomeViewModel(
            metadataRouterFacade = mockk(relaxed = true),
            homeHydrationCoordinator = homeHydrationCoordinator
        )

        val enriched = viewModel.enrichHeroItemsPipeline(
            items = listOf(item, item.copy(name = "Duplicate rail copy")),
            settings = TmdbSettings()
        )

        assertEquals(2, enriched.size)
        assertEquals("Canonical Hero", enriched[0].name)
        assertEquals("Canonical Hero", enriched[1].name)
        assertEquals("preview-poster", enriched[0].poster)
        assertEquals("hero-logo", enriched[0].logo)
        assertEquals("hero-backdrop", enriched[0].background)
        assertEquals(overlay, viewModel.hydratedHomeOverlaysByItemKey.value.getValue("movie:${item.id}"))
        coVerify(exactly = 1) {
            homeHydrationCoordinator.hydrate(
                item = item,
                trigger = StableIdResolutionTrigger.FOCUSED_HOME_ITEM,
                priority = HomeHydrationPriority.HERO,
                languageTag = "en",
                expectedGeneration = any(),
                currentGeneration = any(),
                onOverlayApplied = any()
            )
        }
    }

    @Test
    fun `hero enrichment respects disabled overlay field groups`() = runTest(testDispatcher) {
        val homeHydrationCoordinator = mockk<HomeHydrationCoordinator>()
        val overlayCallback = slot<(HydratedHomeOverlay) -> Boolean>()
        val item = railPreviewMetaPreview().copy(
            type = ContentType.MOVIE,
            rawType = "movie",
            name = "Preview Title",
            logo = "preview-logo",
            background = "preview-backdrop",
            description = "Preview description",
            genres = listOf("Preview"),
            releaseInfo = "2024",
            runtime = "90m",
            imdbRating = 6.5f,
            ratingSource = TitleRatingSource.IMDB,
            language = "preview-language"
        )
        val overlay = overlay(
            itemKey = "movie:${item.id}",
            fields = HomeDisplayMetadata(
                title = "Canonical Hero",
                logo = "hero-logo",
                description = "Canonical description",
                genres = listOf("Canonical"),
                releaseInfo = "2025",
                runtime = "110m",
                imdbRating = 8.4f,
                ratingSource = TitleRatingSource.TMDB,
                backdrop = "hero-backdrop"
            )
        )
        coEvery {
            homeHydrationCoordinator.hydrate(
                item = item,
                trigger = StableIdResolutionTrigger.FOCUSED_HOME_ITEM,
                priority = HomeHydrationPriority.HERO,
                languageTag = "en",
                expectedGeneration = any(),
                currentGeneration = any(),
                onOverlayApplied = capture(overlayCallback)
            )
        } coAnswers {
            overlayCallback.captured.invoke(overlay)
            overlay
        }
        val viewModel = buildTestHomeViewModel(
            metadataRouterFacade = mockk(relaxed = true),
            homeHydrationCoordinator = homeHydrationCoordinator
        )

        val enriched = viewModel.enrichHeroItemsPipeline(
            items = listOf(item),
            settings = TmdbSettings(
                useArtwork = false,
                useBasicInfo = false,
                useDetails = false
            )
        )

        assertEquals("Preview Title", enriched.single().name)
        assertEquals("preview-logo", enriched.single().logo)
        assertEquals("preview-backdrop", enriched.single().background)
        assertEquals("Preview description", enriched.single().description)
        assertEquals(listOf("Preview"), enriched.single().genres)
        assertEquals("2024", enriched.single().releaseInfo)
        assertEquals("90m", enriched.single().runtime)
        assertEquals(6.5f, enriched.single().imdbRating ?: 0f, 0f)
        assertEquals(TitleRatingSource.IMDB, enriched.single().ratingSource)
        assertEquals("preview-language", enriched.single().language)
    }

    @Test
    fun `disabled kitsu catalog preference removes restored kitsu row from modern home`() = runTest(testDispatcher) {
        val viewModel = buildTestHomeViewModel(
            metadataRouterFacade = mockk(relaxed = true),
            nonPlaybackHomeWorkAllowed = true
        )
        viewModel.kitsuCatalogPreferences = KitsuCatalogPreferences(enabledCatalogs = emptySet())
        val kitsuRow = CatalogRow(
            addonId = KITSU_HOME_ADDON_ID,
            addonName = "Kitsu",
            addonBaseUrl = "https://kitsu.io/api/edge",
            catalogId = KitsuCatalogIds.TRENDING_ANIME,
            catalogName = "Kitsu Trending Anime",
            type = ContentType.SERIES,
            rawType = "series",
            items = listOf(
                railPreviewMetaPreview().copy(
                    id = "kitsu:12",
                    firstPaintStableIds = ProviderIds(kitsu = "12")
                )
            ),
            isLoading = false,
            hasMore = false,
            supportsSkip = false
        )

        viewModel.applyHomeSnapshotToUiPipeline(
            HomeCatalogSnapshotStore.Snapshot(
                catalogRows = listOf(kitsuRow),
                fullCatalogRows = listOf(kitsuRow),
                heroItems = emptyList(),
                orderedGroupKeys = listOf(KitsuCatalogIds.TRENDING_ANIME)
            )
        )

        assertTrue(viewModel.uiState.value.catalogRows.isEmpty())
        assertTrue(viewModel._fullCatalogRows.value.isEmpty())
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private fun railPreviewMetaPreview() = MetaPreview(
        id = "tvdb:355567",
        type = ContentType.SERIES,
        name = "Sample",
        poster = null,
        posterShape = PosterShape.POSTER,
        background = null,
        logo = null,
        description = null,
        releaseInfo = "2019",
        imdbRating = null,
        genres = emptyList(),
        firstPaintSource = FirstPaintSource.RAIL_PREVIEW,
        firstPaintSourceProvider = ProviderId.TRAKT,
        firstPaintStableIds = ProviderIds(tvdb = "355567", trakt = "1"),
        firstPaintRailSource = RailSource.BUILT_IN_TRAKT,
        firstPaintSourceItemId = "trakt:show:1"
    )

    private fun overlay(
        itemKey: String,
        fields: HomeDisplayMetadata
    ) = HydratedHomeOverlay(
        overlayKey = "canonical:TMDB:550:type:MOVIE:lang:en:policy:1",
        itemKey = itemKey,
        canonicalProvider = ProviderId.TMDB,
        canonicalId = "550",
        imdbId = "tt0137523",
        contentType = ContentType.MOVIE,
        languageTag = "en",
        fields = fields,
        fieldTrace = listOf(HydratedHomeFieldTrace("TITLE", "TMDB", "PRIMARY")),
        displayHash = fields.hydratedHomeDisplayHash(),
        updatedAtMs = 1L,
        staleAtMs = 2L,
        expiresAtMs = 3L,
        state = HomeItemHydrationState.CANONICAL_READY
    )

    /**
     * Constructs a real [HomeViewModel] wired for focus-hydration tests.
     *
     * All collaborators except [metadataRouterFacade] use [mockk(relaxed = true)] so the
     * [HomeViewModel.init] block can launch its observers without crashing.
     * [ProfileManager] is set to profile 1 (the default legacy profile) so
     * [HomeViewModel.startHomeProfileSession] resolves [ProfileModeRoute.DefaultLegacyRoute].
     *
     * We use a real [ProfileModeRouter] (not mocked) so the sealed-interface [when]
     * expression in [HomeViewModel.startHomeProfileSession] matches correctly.
     *
     * [Dispatchers.Main] must be set to a test dispatcher (done in [setUp]/[tearDown]) so
     * that [viewModelScope] uses the test scheduler and [advanceUntilIdle] drains coroutines.
     */
    private fun buildTestHomeViewModel(
        metadataRouterFacade: MetadataRouterFacade,
        nonPlaybackHomeWorkAllowed: Boolean = false,
        homeHydrationCoordinator: HomeHydrationCoordinator = mockk(relaxed = true),
        currentLanguageTagProvider: () -> String = { "en" },
        traceEvents: TraceMetadataEvents = TraceMetadataEvents(NoopRuntimeTraceSink) { null },
        premiumArtworkInvalidationNotifier: PremiumArtworkInvalidationNotifier = PremiumArtworkInvalidationNotifier(),
        profileSessionFlow: MutableStateFlow<ActiveProfileSession> = MutableStateFlow(
            profileSession(profileId = 1, sessionId = "test-session")
        )
    ): HomeViewModel {
        // ProviderLocalizedMetadataResolver wraps the facade under test.
        // Use a no-op TvMetadataRouter so the resolver doesn't make real network calls.
        val noOpTvRouter = mockk<com.nexio.tv.core.tvdb.TvMetadataRouter> {
            coEvery { fetchEnrichment(any()) } returns TvMetadataDecision(
                provider = TvProvider.TVDB,
                reason = TvMetadataDecisionReason.TVDB_INACTIVE,
                value = null
            )
            coEvery { fetchEpisodeEnrichment(any()) } returns TvMetadataDecision(
                provider = TvProvider.TVDB,
                reason = TvMetadataDecisionReason.TVDB_INACTIVE,
                value = emptyMap()
            )
            coEvery { fetchSeasonEpisodes(any(), any(), any(), any()) } returns TvMetadataDecision(
                provider = TvProvider.TVDB,
                reason = TvMetadataDecisionReason.TVDB_INACTIVE,
                value = emptyList()
            )
        }

        val profileModeRouter = ProfileModeRouter()

        // ProfileManager with profileSwitched SharedFlow so observeProfileSwitches()
        // in init doesn't NPE when it accesses profileManager.profileSwitched.
        val profileManagerWithSwitch = mockk<com.nexio.tv.core.profile.ProfileManager>(relaxed = true) {
            every { activeProfileId } returns MutableStateFlow(1)
            every { activeProfileSession } returns profileSessionFlow
            every { profileSwitched } returns MutableSharedFlow(extraBufferCapacity = 1)
        }

        // accountSyncRefreshNotifier and catalogPriorityHydrationNotifier have `.events`
        // SharedFlow<Long> which is collected in viewModelScope.launch {} during init.
        // Relaxed mocks for SharedFlow emit null which causes KotlinNothingValueException,
        // so we explicitly stub events to emptyFlow() (never-emitting).
        // Stub events as a never-emitting SharedFlow so the collect {} in init doesn't
        // throw KotlinNothingValueException from a relaxed-mock null emission.
        val neverEmittingEvents = MutableSharedFlow<Long>(extraBufferCapacity = 0)
        val accountSyncRefreshNotifier = mockk<AccountSyncRefreshNotifier>(relaxed = true) {
            every { events } returns neverEmittingEvents
        }
        val catalogPriorityHydrationNotifier = mockk<com.nexio.tv.core.sync.CatalogPriorityHydrationNotifier>(relaxed = true) {
            every { events } returns neverEmittingEvents
        }
        val playbackIdleGateState = PlaybackIdleGateState().also { gate ->
            if (!nonPlaybackHomeWorkAllowed) {
                gate.onPlayerSessionStarted()
            }
        }

        val profileBoundary = mockk<com.nexio.tv.core.profile.ProfileBoundary>(relaxed = true) {
            every { currentLanguageTag() } answers { currentLanguageTagProvider() }
        }

        return HomeViewModel(
            addonRepository = mockk(relaxed = true),
            catalogRepository = mockk(relaxed = true),
            watchProgressRepository = mockk(relaxed = true),
            libraryRepository = mockk(relaxed = true),
            metaRepository = mockk(relaxed = true),
            layoutPreferenceDataStore = mockk(relaxed = true),
            tmdbSettingsDataStore = mockk(relaxed = true),
            tmdbCatalogSettingsDataStore = mockk(relaxed = true),
            kitsuCatalogSettingsDataStore = mockk(relaxed = true),
            traktSettingsDataStore = mockk(relaxed = true),
            mdbListSettingsDataStore = mockk(relaxed = true),
            simklSettingsDataStore = mockk(relaxed = true),
            playerSettingsDataStore = mockk(relaxed = true),
            traktDiscoverySnapshotStore = mockk(relaxed = true),
            simklDiscoverySnapshotStore = mockk(relaxed = true),
            mdbListDiscoverySnapshotStore = mockk(relaxed = true),
            continueWatchingSnapshotService = mockk(relaxed = true),
            trackingScrobbleService = mockk(relaxed = true),
            traktDiscoveryService = mockk(relaxed = true),
            simklDiscoveryService = mockk(relaxed = true),
            mdbListDiscoveryService = mockk(relaxed = true),
            tmdbDiscoveryService = mockk(relaxed = true),
            kitsuDiscoveryService = mockk(relaxed = true),
            mdbListRepository = mockk(relaxed = true),
            metadataRouterFacade = metadataRouterFacade,
            providerLocalizedMetadataResolver = ProviderLocalizedMetadataResolver(metadataRouterFacade),
            trailerSettingsDataStore = mockk(relaxed = true),
            accountSyncRefreshNotifier = accountSyncRefreshNotifier,
            catalogPriorityHydrationNotifier = catalogPriorityHydrationNotifier,
            homeCatalogSnapshotStore = mockk(relaxed = true),
            homeCatalogRefreshCoordinator = mockk(relaxed = true),
            debugSettingsDataStore = mockk(relaxed = true),
            metadataDiskCacheStore = mockk(relaxed = true),
            syntheticHomeCatalogStore = mockk(relaxed = true),
            homeRailOrderStore = mockk(relaxed = true),
            profileManager = profileManagerWithSwitch,
            profileModeRouter = profileModeRouter,
            profileBoundary = profileBoundary,
            trackingProviderStateService = mockk(relaxed = true),
            playbackIdleGateState = playbackIdleGateState,
            integrationOwnershipService = mockk(relaxed = true),
            hydratedHomeOverlayStore = mockk<HydratedHomeOverlayStore>(relaxed = true),
            homeHydrationCoordinator = homeHydrationCoordinator,
            traceEvents = traceEvents,
            premiumArtworkInvalidationNotifier = premiumArtworkInvalidationNotifier,
            animeSeasonProjectionResolver = mockk(relaxed = true),
            appContext = mockk<Context>(relaxed = true)
        ).also(createdViewModels::add)
    }

    private class RecordingTraceSink : RuntimeTraceSink {
        val events = mutableListOf<TraceEventEnvelope<*>>()

        override fun emit(event: TraceEventEnvelope<*>) {
            events += event
        }

        override fun eventsWritten(): Long = events.size.toLong()

        override fun eventsDropped(): Long = 0L
    }

    private companion object {
        fun profileSession(
            profileId: Int,
            sessionId: String
        ) = ActiveProfileSession(
            profileId = profileId,
            sessionId = sessionId,
            sessionOrdinal = profileId.toLong(),
            startedAtMs = 1_000L + profileId
        )
    }
}
