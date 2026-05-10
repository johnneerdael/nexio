package com.nexio.tv.ui.screens.home

import android.content.Context
import android.content.SharedPreferences
import com.nexio.tv.core.integration.ActiveProfileSession
import com.nexio.tv.core.metadata.router.MetadataRouterFacade
import com.nexio.tv.core.metadata.router.StableIdResolutionTrigger
import com.nexio.tv.core.metadata.router.testMetadataRouterFacade
import com.nexio.tv.core.player.PlaybackActivityTracker
import com.nexio.tv.core.poster.PosterRatingsUrlResolver
import com.nexio.tv.core.profile.ProfileBoundary
import com.nexio.tv.core.profile.ProfileManager
import com.nexio.tv.core.tvdb.ProviderLocalizedMetadataResolver
import com.nexio.tv.core.tvdb.TvMetadataDecision
import com.nexio.tv.core.tvdb.TvMetadataDecisionReason
import com.nexio.tv.core.tvdb.TvMetadataRouter
import com.nexio.tv.data.local.MDBListCatalogPreferences
import com.nexio.tv.data.local.MetadataDiskCacheStore
import com.nexio.tv.data.local.SimklCatalogPreferences
import com.nexio.tv.data.local.SyntheticHomeCatalogStore
import com.nexio.tv.data.local.TmdbCatalogIds
import com.nexio.tv.data.local.TmdbCatalogPreferences
import com.nexio.tv.data.local.TraktCatalogPreferences
import com.nexio.tv.data.repository.CatalogInventoryRepository
import com.nexio.tv.data.repository.MDBListDiscoverySnapshot
import com.nexio.tv.data.repository.SimklDiscoverySnapshot
import com.nexio.tv.data.repository.TmdbDiscoverySnapshot
import com.nexio.tv.data.repository.TraktDiscoverySnapshot
import com.nexio.tv.core.tvdb.TvMetadataEnrichment
import com.nexio.tv.core.tvdb.TvProvider
import com.nexio.tv.domain.model.Addon
import com.nexio.tv.domain.model.AddonResource
import com.nexio.tv.domain.model.ArtworkProviderChoiceKey
import com.nexio.tv.domain.model.ArtworkProviderSelectionSettings
import com.nexio.tv.domain.model.CatalogDescriptor
import com.nexio.tv.domain.model.CatalogRow
import com.nexio.tv.domain.model.ContentType
import com.nexio.tv.domain.model.HydratedHomeOverlay
import com.nexio.tv.domain.model.MetaPreview
import com.nexio.tv.domain.model.PosterShape
import com.nexio.tv.domain.model.ArtworkProviderSettings
import com.nexio.tv.domain.repository.CatalogRepository
import com.nexio.tv.domain.repository.MetaRepository
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class HomeCatalogRefreshCoordinatorTest {

    @Test
    fun `diffCatalogItems marks new and changed entries as addedOrChanged`() {
        val oldItems = listOf(
            preview(id = "a", poster = "posterA"),
            preview(id = "b", poster = "posterB")
        )
        val newItems = listOf(
            preview(id = "a", poster = "posterA_v2"),
            preview(id = "b", poster = "posterB"),
            preview(id = "c", poster = "posterC")
        )

        val diff = diffCatalogItems(oldItems, newItems)

        val changedIds = diff.addedOrChanged.map { it.id }.toSet()
        assertEquals(setOf("a", "c"), changedIds)
        assertTrue(diff.removed.isEmpty())
    }

    @Test
    fun `diffCatalogItems marks removed entries`() {
        val oldItems = listOf(
            preview(id = "a", poster = "posterA"),
            preview(id = "b", poster = "posterB")
        )
        val newItems = listOf(
            preview(id = "a", poster = "posterA")
        )

        val diff = diffCatalogItems(oldItems, newItems)

        assertEquals(0, diff.addedOrChanged.size)
        assertEquals(1, diff.removed.size)
        assertEquals("b", diff.removed.first().id)
    }

    @Test
    fun `diffCatalogItems marks metadata only changes as addedOrChanged`() {
        val oldItems = listOf(
            preview(id = "a", poster = "posterA").copy(description = "old", runtime = "90m")
        )
        val newItems = listOf(
            preview(id = "a", poster = "posterA").copy(description = "new", runtime = "95m")
        )

        val diff = diffCatalogItems(oldItems, newItems)

        assertEquals(1, diff.addedOrChanged.size)
        assertEquals("a", diff.addedOrChanged.first().id)
    }

    @Test
    fun `provider home enrichment no longer mutates preview runtime`() = runTest {
        val item = preview(id = "a", poster = "posterA")
        val resolver = mockk<ProviderLocalizedMetadataResolver>()
        val profileBoundary = mockk<ProfileBoundary>()
        coEvery { resolver.fetchDecision(any(), any()) } returns TvMetadataDecision(
            provider = TvProvider.TVDB,
            reason = TvMetadataDecisionReason.TVDB_SUCCESS,
            value = TvMetadataEnrichment(
                seriesTvdbId = 121361,
                runtimeMinutes = 52
            )
        )
        every { profileBoundary.currentLanguageTag() } returns "en"

        val enriched = overlayProviderLocalizedMetadataForHome(
            item = item,
            providerLocalizedMetadataResolver = resolver,
            profileBoundary = profileBoundary
        )

        assertEquals(item, enriched)
    }

    @Test
    fun `shouldReusePersistedHomeItem only reuses unchanged rows with tomatoes persisted`() {
        assertTrue(
            shouldReusePersistedHomeItem(
                itemChanged = false,
                persistedFallback = preview(id = "a", poster = "posterA").copy(tomatoesRating = 71.0)
            )
        )
        assertEquals(
            false,
            shouldReusePersistedHomeItem(
                itemChanged = false,
                persistedFallback = preview(id = "a", poster = "posterA")
            )
        )
        assertEquals(
            false,
            shouldReusePersistedHomeItem(
                itemChanged = true,
                persistedFallback = preview(id = "a", poster = "posterA").copy(tomatoesRating = 71.0)
            )
        )
    }

    @Test
    fun `refresh first paint publishes catalog row without addon detail metadata fetch`() = runTest {
        val catalogRepository = mockk<CatalogRepository>()
        val tvMetadataRouter = mockk<TvMetadataRouter>()
        val publishedRows = mutableListOf<CatalogRow>()
        val catalogPreview = preview(id = "tt-first-paint", poster = null).copy(
            name = "Catalog payload title",
            description = "Catalog payload description",
            releaseInfo = "2026"
        )
        val catalogRow = CatalogRow(
            addonId = "addon",
            addonName = "Addon",
            addonBaseUrl = "https://addon.example",
            catalogId = "popular",
            catalogName = "Popular",
            type = ContentType.MOVIE,
            items = listOf(catalogPreview),
            hasMore = false
        )
        coEvery {
            catalogRepository.refreshCatalogToDisk(
                addonBaseUrl = "https://addon.example",
                addonId = "addon",
                addonName = "Addon",
                catalogId = "popular",
                catalogName = "Popular",
                type = "movie",
                skip = 0,
                skipStep = 100,
                supportsSkip = false
            )
        } returns Result.success(catalogRow)
        coEvery { tvMetadataRouter.fetchEnrichment(any()) } returns TvMetadataDecision(
            provider = TvProvider.TMDB,
            reason = TvMetadataDecisionReason.TVDB_FALLBACK_TMDB,
            value = TvMetadataEnrichment(
                seriesTvdbId = null,
                localizedTitle = "Provider title",
                description = "Provider description",
                releaseInfo = "2027"
            )
        )

        val refreshed = coordinator(
            catalogRepository = catalogRepository,
            tvMetadataRouter = tvMetadataRouter
        ).refreshSerially(
            addons = listOf(addon()),
            telemetryEnabled = true,
            isCatalogDisabled = { _, _ -> false },
            getCurrentRow = { null },
            isItemReferencedElsewhere = { _, _ -> false },
            onCatalogReady = { _, row, _ ->
                if (publishedRows.isEmpty()) {
                    coVerify(exactly = 0) { tvMetadataRouter.fetchEnrichment(any()) }
                }
                publishedRows += row
            },
            onLog = { _, _ -> }
        )

        assertEquals(1, refreshed)
        assertEquals(2, publishedRows.size)
        assertEquals("tt-first-paint", publishedRows[0].items.single().id)
        assertEquals("Catalog payload description", publishedRows[0].items.single().description)
        assertEquals("2026", publishedRows[0].items.single().releaseInfo)
        assertEquals("Provider title", publishedRows[1].items.single().name)
        assertEquals("Provider description", publishedRows[1].items.single().description)
        assertEquals("2026", publishedRows[1].items.single().releaseInfo)
        coVerify(exactly = 1) {
            catalogRepository.refreshCatalogToDisk(
                addonBaseUrl = "https://addon.example",
                addonId = "addon",
                addonName = "Addon",
                catalogId = "popular",
                catalogName = "Popular",
                type = "movie",
                skip = 0,
                skipStep = 100,
                supportsSkip = false
            )
        }
        coVerify(exactly = 1) { tvMetadataRouter.fetchEnrichment(any()) }
    }

    @Test
    fun `refresh first paint publishes all serial rows before provider hydration`() = runTest {
        val catalogRepository = mockk<CatalogRepository>()
        val tvMetadataRouter = mockk<TvMetadataRouter>()
        val events = mutableListOf<String>()
        val firstCatalogRow = CatalogRow(
            addonId = "addon",
            addonName = "Addon",
            addonBaseUrl = "https://addon.example",
            catalogId = "popular",
            catalogName = "Popular",
            type = ContentType.MOVIE,
            items = listOf(preview(id = "tt-first-serial", poster = null).copy(name = "Raw first")),
            hasMore = false
        )
        val secondCatalogRow = CatalogRow(
            addonId = "addon",
            addonName = "Addon",
            addonBaseUrl = "https://addon.example",
            catalogId = "featured",
            catalogName = "Featured",
            type = ContentType.MOVIE,
            items = listOf(preview(id = "tt-second-serial", poster = null).copy(name = "Raw second")),
            hasMore = false
        )
        coEvery {
            catalogRepository.refreshCatalogToDisk(
                addonBaseUrl = "https://addon.example",
                addonId = "addon",
                addonName = "Addon",
                catalogId = "popular",
                catalogName = "Popular",
                type = "movie",
                skip = 0,
                skipStep = 100,
                supportsSkip = false
            )
        } returns Result.success(firstCatalogRow)
        coEvery {
            catalogRepository.refreshCatalogToDisk(
                addonBaseUrl = "https://addon.example",
                addonId = "addon",
                addonName = "Addon",
                catalogId = "featured",
                catalogName = "Featured",
                type = "movie",
                skip = 0,
                skipStep = 100,
                supportsSkip = false
            )
        } returns Result.success(secondCatalogRow)
        coEvery { tvMetadataRouter.fetchEnrichment(any()) } coAnswers {
            events += "provider"
            TvMetadataDecision(
                provider = TvProvider.TMDB,
                reason = TvMetadataDecisionReason.TVDB_FALLBACK_TMDB,
                value = TvMetadataEnrichment(
                    seriesTvdbId = null,
                    localizedTitle = "Provider title"
                )
            )
        }

        val refreshed = coordinator(
            catalogRepository = catalogRepository,
            tvMetadataRouter = tvMetadataRouter
        ).refreshSerially(
            addons = listOf(
                addon(
                    catalogs = listOf(
                        CatalogDescriptor(type = ContentType.MOVIE, id = "popular", name = "Popular"),
                        CatalogDescriptor(type = ContentType.MOVIE, id = "featured", name = "Featured")
                    )
                )
            ),
            telemetryEnabled = true,
            isCatalogDisabled = { _, _ -> false },
            getCurrentRow = { null },
            isItemReferencedElsewhere = { _, _ -> false },
            onCatalogReady = { catalogKey, row, _ ->
                events += "publish:$catalogKey:${row.items.single().name}"
                if (events.count { it.startsWith("publish:") && it.contains(":Raw ") } < 2) {
                    coVerify(exactly = 0) { tvMetadataRouter.fetchEnrichment(any()) }
                }
            },
            onLog = { _, _ -> }
        )

        assertEquals(2, refreshed)
        assertTrue(
            events.indexOf("provider") >
                events.indexOf("publish:addon_movie_popular:Raw first")
        )
        assertTrue(
            events.indexOf("provider") >
                events.indexOf("publish:addon_movie_featured:Raw second")
        )
        assertTrue(
            events.indexOf("publish:addon_movie_popular:Provider title") >
                events.indexOf("provider")
        )
        assertTrue(
            events.indexOf("publish:addon_movie_featured:Provider title") >
                events.indexOf("provider")
        )
        coVerify(exactly = 2) { tvMetadataRouter.fetchEnrichment(any()) }
    }

    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    @Test
    fun `home serialized refresh immediately publishes every raw first paint row before hydrated republish`() = runTest {
        val metaRepository = mockk<MetaRepository>(relaxed = true)
        val coordinator = mockk<HomeCatalogRefreshCoordinator>()
        val viewModel = mockk<HomeViewModel>(relaxed = true)
        val catalogsMap = linkedMapOf<String, CatalogRow>()
        val renderedRows = mutableListOf<List<CatalogRow>>()
        val catalogPreview = preview(id = "tt-viewmodel-first-paint", poster = null).copy(
            description = "ViewModel catalog payload",
            releaseInfo = "2026"
        )
        val secondCatalogPreview = preview(id = "tt-viewmodel-second-raw", poster = null).copy(
            description = "Second raw payload",
            releaseInfo = "2026"
        )
        val hydratedPreview = catalogPreview.copy(
            name = "Provider title",
            description = "Provider description"
        )
        val catalogRow = CatalogRow(
            addonId = "addon",
            addonName = "Addon",
            addonBaseUrl = "https://addon.example",
            catalogId = "popular",
            catalogName = "Popular",
            type = ContentType.MOVIE,
            items = listOf(catalogPreview),
            hasMore = false
        )
        val secondCatalogRow = catalogRow.copy(
            catalogId = "featured",
            catalogName = "Featured",
            items = listOf(secondCatalogPreview)
        )
        val hydratedCatalogRow = catalogRow.copy(items = listOf(hydratedPreview))
        every { viewModel.isCurrentHomeProfileGeneration(1L) } returns true
        every { viewModel.shouldBlockProfileSwitchDiskSnapshotRefresh(any()) } returns false
        every { viewModel.playbackIdleGateState } returns com.nexio.tv.ui.screensaver.PlaybackIdleGateState()
        every { viewModel.metaRepository } returns metaRepository
        every { viewModel.homeCatalogRefreshCoordinator } returns coordinator
        every { viewModel.addonsCache } returns listOf(addon())
        every { viewModel.startupPerfTelemetryEnabled } returns false
        every { viewModel.catalogsMap } returns catalogsMap
        every { viewModel._uiState } returns MutableStateFlow(HomeUiState())
        every { viewModel.catalogInventoryRepository.isEmpty() } returns true
        every { viewModel._displayCatalogRows } returns MutableStateFlow(emptyList())
        every { viewModel._displayContinueWatchingItems } returns MutableStateFlow(emptyList())
        every { viewModel.activeProfileTraktAuthenticated } returns false
        every { viewModel.traktCatalogPreferences } returns TraktCatalogPreferences(enabledCatalogs = emptySet())
        every { viewModel.simklCatalogPreferences } returns SimklCatalogPreferences(enabledCatalogs = emptySet())
        every { viewModel.mdbListCatalogPreferences } returns MDBListCatalogPreferences()
        every { viewModel.tmdbCatalogPreferences } returns TmdbCatalogPreferences(enabledCatalogs = emptySet())
        every { viewModel.syntheticTomatoesOverridesByItemId } returns linkedMapOf()
        every { viewModel.persistedTraktSyntheticGroups } returns emptyList()
        every { viewModel.persistedSimklSyntheticGroups } returns emptyList()
        every { viewModel.persistedMDBListSyntheticGroups } returns emptyList()
        every { viewModel.persistedTmdbSyntheticGroups } returns emptyList()
        every { viewModel.traktDiscoveryService.observeSnapshot(autoRefreshOnStart = false) } returns flowOf(
            TraktDiscoverySnapshot(updatedAtMs = 1L)
        )
        every { viewModel.simklDiscoveryService.observeSnapshot(autoRefreshOnStart = false) } returns flowOf(
            SimklDiscoverySnapshot(updatedAtMs = 1L)
        )
        every { viewModel.mdbListDiscoveryService.observeSnapshot(autoRefreshOnStart = false) } returns flowOf(
            MDBListDiscoverySnapshot(updatedAtMs = 1L)
        )
        every { viewModel.tmdbDiscoveryService.observeSnapshot() } returns flowOf(
            TmdbDiscoverySnapshot(updatedAtMs = 1L)
        )
        coEvery { viewModel.flushCatalogRowsForFirstPaint(any()) } coAnswers {
            renderedRows += catalogsMap.values.toList()
        }
        coEvery {
            coordinator.refreshSerially(
                addons = any(),
                telemetryEnabled = any(),
                isCatalogDisabled = any(),
                getCurrentRow = any(),
                isItemReferencedElsewhere = any(),
                onCatalogReady = any(),
                onRawCatalogBatchComplete = any(),
                onLog = any()
            )
        } coAnswers {
            val onCatalogReady = arg<suspend (String, CatalogRow, CatalogItemDiff) -> Unit>(5)
            val onRawCatalogBatchComplete = arg<suspend () -> Unit>(6)
            onCatalogReady(
                "addon_movie_popular",
                catalogRow,
                CatalogItemDiff(addedOrChanged = listOf(catalogPreview), removed = emptyList())
            )
            onCatalogReady(
                "addon_movie_featured",
                secondCatalogRow,
                CatalogItemDiff(addedOrChanged = listOf(secondCatalogPreview), removed = emptyList())
            )
            onRawCatalogBatchComplete()
            onCatalogReady(
                "addon_movie_popular",
                hydratedCatalogRow,
                CatalogItemDiff(addedOrChanged = listOf(hydratedPreview), removed = emptyList())
            )
            2
        }

        val profileSession = activeProfileSession()
        every { viewModel.profileManager.activeProfileSession } returns MutableStateFlow(profileSession)

        try {
            Dispatchers.setMain(UnconfinedTestDispatcher(testScheduler))
            viewModel.runSerializedPostStartupRefreshPipeline(
                expectedGeneration = 1L,
                expectedProfileSession = profileSession,
                reason = "account_sync"
            )
        } finally {
            Dispatchers.resetMain()
        }

        assertEquals(hydratedCatalogRow, catalogsMap["addon_movie_popular"])
        assertEquals(secondCatalogRow, catalogsMap["addon_movie_featured"])
        assertEquals(
            listOf(
                listOf(catalogRow),
                listOf(catalogRow, secondCatalogRow)
            ),
            renderedRows
        )
        verify(exactly = 0) {
            metaRepository.hydrateAddonOriginItem(any(), any(), any(), any(), any(), any())
        }
        verify(exactly = 0) {
            metaRepository.getMeta(any(), any(), any(), any(), any(), any())
        }
        coVerify(exactly = 1) {
            coordinator.refreshSerially(
                addons = listOf(addon()),
                telemetryEnabled = false,
                isCatalogDisabled = any(),
                getCurrentRow = any(),
                isItemReferencedElsewhere = any(),
                onCatalogReady = any(),
                onRawCatalogBatchComplete = any(),
                onLog = any()
            )
        }
        coVerify(exactly = 2) { viewModel.flushCatalogRowsForFirstPaint(profileSession) }
    }

    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    @Test
    fun `serialized refresh rejects catalog callback after profile session changes`() = runTest {
        val coordinator = mockk<HomeCatalogRefreshCoordinator>()
        val viewModel = mockk<HomeViewModel>(relaxed = true)
        val catalogsMap = linkedMapOf<String, CatalogRow>()
        val oldPreview = preview(id = "tt-old-profile-row", poster = null).copy(name = "Old Profile Row")
        val oldRow = CatalogRow(
            addonId = "addon",
            addonName = "Addon",
            addonBaseUrl = "https://addon.example",
            catalogId = "popular",
            catalogName = "Popular",
            type = ContentType.MOVIE,
            items = listOf(oldPreview),
            hasMore = false
        )
        val expectedProfileSession = activeProfileSession()
        val activeProfileSession = MutableStateFlow(expectedProfileSession)

        every { viewModel.isCurrentHomeProfileGeneration(1L) } returns true
        every { viewModel.shouldBlockProfileSwitchDiskSnapshotRefresh(any()) } returns false
        every { viewModel.playbackIdleGateState } returns com.nexio.tv.ui.screensaver.PlaybackIdleGateState()
        every { viewModel.homeCatalogRefreshCoordinator } returns coordinator
        every { viewModel.addonsCache } returns listOf(addon())
        every { viewModel.startupPerfTelemetryEnabled } returns false
        every { viewModel.catalogsMap } returns catalogsMap
        every { viewModel._uiState } returns MutableStateFlow(HomeUiState())
        every { viewModel._displayCatalogRows } returns MutableStateFlow(emptyList())
        every { viewModel._displayContinueWatchingItems } returns MutableStateFlow(emptyList())
        every { viewModel.activeProfileTraktAuthenticated } returns false
        every { viewModel.traktCatalogPreferences } returns TraktCatalogPreferences(enabledCatalogs = emptySet())
        every { viewModel.simklCatalogPreferences } returns SimklCatalogPreferences(enabledCatalogs = emptySet())
        every { viewModel.mdbListCatalogPreferences } returns MDBListCatalogPreferences()
        every { viewModel.tmdbCatalogPreferences } returns TmdbCatalogPreferences(enabledCatalogs = emptySet())
        every { viewModel.syntheticTomatoesOverridesByItemId } returns linkedMapOf()
        every { viewModel.persistedTraktSyntheticGroups } returns emptyList()
        every { viewModel.persistedSimklSyntheticGroups } returns emptyList()
        every { viewModel.persistedMDBListSyntheticGroups } returns emptyList()
        every { viewModel.persistedTmdbSyntheticGroups } returns emptyList()
        every { viewModel.traktDiscoveryService.observeSnapshot(autoRefreshOnStart = false) } returns flowOf(
            TraktDiscoverySnapshot(updatedAtMs = 1L)
        )
        every { viewModel.simklDiscoveryService.observeSnapshot(autoRefreshOnStart = false) } returns flowOf(
            SimklDiscoverySnapshot(updatedAtMs = 1L)
        )
        every { viewModel.mdbListDiscoveryService.observeSnapshot(autoRefreshOnStart = false) } returns flowOf(
            MDBListDiscoverySnapshot(updatedAtMs = 1L)
        )
        every { viewModel.tmdbDiscoveryService.observeSnapshot() } returns flowOf(
            TmdbDiscoverySnapshot(updatedAtMs = 1L)
        )
        coEvery { viewModel.flushCatalogRowsForFirstPaint(any()) } coAnswers {}
        coEvery {
            coordinator.refreshSerially(
                addons = any(),
                telemetryEnabled = any(),
                isCatalogDisabled = any(),
                getCurrentRow = any(),
                isItemReferencedElsewhere = any(),
                onCatalogReady = any(),
                onRawCatalogBatchComplete = any(),
                onLog = any()
            )
        } coAnswers {
            val onCatalogReady = arg<suspend (String, CatalogRow, CatalogItemDiff) -> Unit>(5)
            activeProfileSession.value = expectedProfileSession.copy(
                profileId = 2,
                sessionId = "new-session",
                sessionOrdinal = 2L
            )
            onCatalogReady(
                "addon_movie_popular",
                oldRow,
                CatalogItemDiff(addedOrChanged = listOf(oldPreview), removed = emptyList())
            )
            1
        }
        every { viewModel.profileManager.activeProfileSession } returns activeProfileSession

        try {
            Dispatchers.setMain(UnconfinedTestDispatcher(testScheduler))
            viewModel.runSerializedPostStartupRefreshPipeline(
                expectedGeneration = 1L,
                expectedProfileSession = expectedProfileSession,
                reason = "account_sync"
            )
        } finally {
            Dispatchers.resetMain()
        }

        assertTrue(catalogsMap.isEmpty())
        coVerify(exactly = 0) { viewModel.flushCatalogRowsForFirstPaint(any()) }
        verify(exactly = 0) { viewModel.scheduleUpdateCatalogRows(any()) }
    }

    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    @Test
    fun `serialized refresh rejects final settlement after profile session changes during snapshot read`() = runTest {
        val coordinator = mockk<HomeCatalogRefreshCoordinator>()
        val viewModel = mockk<HomeViewModel>(relaxed = true)
        val expectedProfileSession = activeProfileSession()
        val activeProfileSession = MutableStateFlow(expectedProfileSession)
        var tmdbObserveCount = 0

        every { viewModel.isCurrentHomeProfileGeneration(1L) } returns true
        every { viewModel.shouldBlockProfileSwitchDiskSnapshotRefresh(any()) } returns false
        every { viewModel.playbackIdleGateState } returns com.nexio.tv.ui.screensaver.PlaybackIdleGateState()
        every { viewModel.homeCatalogRefreshCoordinator } returns coordinator
        every { viewModel.addonsCache } returns listOf(addon())
        every { viewModel.startupPerfTelemetryEnabled } returns false
        every { viewModel.catalogsMap } returns linkedMapOf()
        every { viewModel._uiState } returns MutableStateFlow(HomeUiState())
        every { viewModel._displayCatalogRows } returns MutableStateFlow(emptyList())
        every { viewModel._displayContinueWatchingItems } returns MutableStateFlow(emptyList())
        every { viewModel.activeProfileTraktAuthenticated } returns false
        every { viewModel.traktCatalogPreferences } returns TraktCatalogPreferences(enabledCatalogs = emptySet())
        every { viewModel.simklCatalogPreferences } returns SimklCatalogPreferences(enabledCatalogs = emptySet())
        every { viewModel.mdbListCatalogPreferences } returns MDBListCatalogPreferences()
        every { viewModel.tmdbCatalogPreferences } returns TmdbCatalogPreferences(enabledCatalogs = emptySet())
        every { viewModel.syntheticTomatoesOverridesByItemId } returns linkedMapOf()
        every { viewModel.persistedTraktSyntheticGroups } returns emptyList()
        every { viewModel.persistedSimklSyntheticGroups } returns emptyList()
        every { viewModel.persistedMDBListSyntheticGroups } returns emptyList()
        every { viewModel.persistedTmdbSyntheticGroups } returns emptyList()
        every { viewModel.traktDiscoveryService.observeSnapshot(autoRefreshOnStart = false) } returns flowOf(
            TraktDiscoverySnapshot(updatedAtMs = 1L)
        )
        every { viewModel.simklDiscoveryService.observeSnapshot(autoRefreshOnStart = false) } returns flowOf(
            SimklDiscoverySnapshot(updatedAtMs = 1L)
        )
        every { viewModel.mdbListDiscoveryService.observeSnapshot(autoRefreshOnStart = false) } returns flowOf(
            MDBListDiscoverySnapshot(updatedAtMs = 1L)
        )
        every { viewModel.tmdbDiscoveryService.observeSnapshot() } returns flow {
            tmdbObserveCount += 1
            if (tmdbObserveCount == 2) {
                activeProfileSession.value = expectedProfileSession.copy(
                    profileId = 2,
                    sessionId = "new-session",
                    sessionOrdinal = 2L
                )
            }
            emit(TmdbDiscoverySnapshot(updatedAtMs = tmdbObserveCount.toLong()))
        }
        every { viewModel.tmdbDiscoverySnapshot = any() } answers { Unit }
        coEvery {
            coordinator.refreshSerially(
                addons = any(),
                telemetryEnabled = any(),
                isCatalogDisabled = any(),
                getCurrentRow = any(),
                isItemReferencedElsewhere = any(),
                onCatalogReady = any(),
                onRawCatalogBatchComplete = any(),
                onLog = any()
            )
        } returns 0
        every { viewModel.profileManager.activeProfileSession } returns activeProfileSession

        try {
            Dispatchers.setMain(UnconfinedTestDispatcher(testScheduler))
            viewModel.runSerializedPostStartupRefreshPipeline(
                expectedGeneration = 1L,
                expectedProfileSession = expectedProfileSession,
                reason = "account_sync"
            )
        } finally {
            Dispatchers.resetMain()
        }

        assertEquals(2, tmdbObserveCount)
        verify(exactly = 0) { viewModel.tmdbDiscoverySnapshot = any() }
    }

    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    @Test
    fun `serialized synthetic renewal uses captured profile session and skips stale in-memory apply`() = runTest {
        val viewModel = mockk<HomeViewModel>(relaxed = true)
        val profileManager = mockk<ProfileManager>()
        val syntheticHomeCatalogStore = mockk<SyntheticHomeCatalogStore>()
        val expectedProfileSession = activeProfileSession()
        val newerProfileSession = expectedProfileSession.copy(
            profileId = 2,
            sessionId = "new-session",
            sessionOrdinal = 2L
        )
        val activeProfileSession = MutableStateFlow(newerProfileSession)
        val activeProfileId = MutableStateFlow(newerProfileSession.profileId)
        val tmdbPrefs = TmdbCatalogPreferences(
            enabledCatalogs = setOf(TmdbCatalogIds.TRENDING_MOVIES),
            catalogOrder = listOf(TmdbCatalogIds.TRENDING_MOVIES)
        )
        val tmdbRow = CatalogRow(
            addonId = TMDB_HOME_ADDON_ID,
            addonName = "TMDB",
            addonBaseUrl = "https://api.themoviedb.org/3",
            catalogId = TmdbCatalogIds.TRENDING_MOVIES,
            catalogName = "TMDB Trending Movies",
            type = ContentType.MOVIE,
            items = listOf(preview(id = "tt-old-profile-tmdb", poster = "poster")),
            hasMore = false
        )
        val tmdbSnapshot = TmdbDiscoverySnapshot(
            rowsByCatalog = mapOf(TmdbCatalogIds.TRENDING_MOVIES to tmdbRow),
            updatedAtMs = 10L,
            includeAdult = tmdbPrefs.includeAdult,
            hideUnreleasedDigital = tmdbPrefs.hideUnreleasedDigital,
            catalogIdsWithCurrentPreferences = setOf(TmdbCatalogIds.TRENDING_MOVIES)
        )

        every { profileManager.activeProfileSession } returns activeProfileSession
        every { profileManager.activeProfileId } returns activeProfileId
        every { viewModel.profileManager } returns profileManager
        every { viewModel.isCurrentHomeProfileGeneration(1L) } returns true
        every { viewModel.syntheticHomeCatalogStore } returns syntheticHomeCatalogStore
        every { viewModel.syntheticCatalogStoreMutex } returns Mutex()
        every { viewModel.tmdbCatalogPreferences } returns tmdbPrefs
        every { viewModel.persistedTraktSyntheticGroups } returns emptyList()
        every { viewModel.persistedSimklSyntheticGroups } returns emptyList()
        every { viewModel.persistedMDBListSyntheticGroups } returns emptyList()
        every { viewModel.persistedKitsuSyntheticGroups } returns emptyList()
        every { viewModel.persistedTmdbSyntheticGroups } returns emptyList()
        every { viewModel.persistedTmdbSyntheticIncludeAdult } returns null
        every { viewModel.persistedTmdbSyntheticHideUnreleasedDigital } returns null
        every { syntheticHomeCatalogStore.read(profileId = expectedProfileSession.profileId) } returns
            SyntheticHomeCatalogStore.Snapshot()
        every { syntheticHomeCatalogStore.write(any(), profileId = expectedProfileSession.profileId) } answers { Unit }
        every { syntheticHomeCatalogStore.write(any(), profileId = newerProfileSession.profileId) } answers { Unit }

        try {
            Dispatchers.setMain(UnconfinedTestDispatcher(testScheduler))
            viewModel.renewTmdbSyntheticSnapshotPipeline(
                snapshot = tmdbSnapshot,
                expectedGeneration = 1L,
                expectedProfileSession = expectedProfileSession
            )
        } finally {
            Dispatchers.resetMain()
        }

        verify(exactly = 1) {
            syntheticHomeCatalogStore.write(any(), profileId = expectedProfileSession.profileId)
        }
        verify(exactly = 0) {
            syntheticHomeCatalogStore.write(any(), profileId = newerProfileSession.profileId)
        }
        verify(exactly = 0) { viewModel.persistedTmdbSyntheticGroups = any() }
    }

    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    @Test
    fun `serialized refresh delegates visible overlay hydration and image prefetch after successful catalog refresh`() = runTest {
        val coordinator = mockk<HomeCatalogRefreshCoordinator>()
        val homeHydrationCoordinator = mockk<HomeHydrationCoordinator>()
        val viewModel = mockk<HomeViewModel>(relaxed = true)
        val catalogsMap = linkedMapOf<String, CatalogRow>()
        val displayCatalogRows = MutableStateFlow<List<CatalogRow>>(emptyList())
        val uiState = MutableStateFlow(HomeUiState())
        val hydratedOverlays = MutableStateFlow<Map<String, HydratedHomeOverlay>>(emptyMap())
        val visiblePreview = preview(id = "tt-visible-refresh", poster = "poster").copy(
            description = "Visible catalog payload",
            releaseInfo = "2026"
        )
        val hiddenPreview = preview(id = "tt-hidden-refresh", poster = "poster").copy(
            description = "Hidden full-row payload",
            releaseInfo = "2026"
        )
        val displayRow = CatalogRow(
            addonId = "addon",
            addonName = "Addon",
            addonBaseUrl = "https://addon.example",
            catalogId = "popular",
            catalogName = "Popular",
            type = ContentType.MOVIE,
            items = listOf(visiblePreview),
            hasMore = false
        )
        val fullRow = displayRow.copy(items = listOf(visiblePreview, hiddenPreview))

        every { viewModel.isCurrentHomeProfileGeneration(1L) } returns true
        every { viewModel.shouldBlockProfileSwitchDiskSnapshotRefresh(any()) } returns false
        every { viewModel.playbackIdleGateState } returns com.nexio.tv.ui.screensaver.PlaybackIdleGateState()
        every { viewModel.homeCatalogRefreshCoordinator } returns coordinator
        every { viewModel.homeHydrationCoordinator } returns homeHydrationCoordinator
        every { viewModel.addonsCache } returns listOf(addon())
        every { viewModel.startupPerfTelemetryEnabled } returns false
        every { viewModel.catalogsMap } returns catalogsMap
        every { viewModel._uiState } returns uiState
        every { viewModel.catalogInventoryRepository.isEmpty() } returns true
        every { viewModel._displayCatalogRows } returns displayCatalogRows
        every { viewModel._displayContinueWatchingItems } returns MutableStateFlow(emptyList())
        every { viewModel.hydratedHomeOverlaysByItemKey } returns hydratedOverlays
        every { viewModel.visibleHomeHydrationInFlightItemKeys } returns mutableSetOf()
        every { viewModel.homeProfileGeneration } returns 1L
        every { viewModel.profileBoundary.currentLanguageTag() } returns "en"
        every { viewModel.activeProfileTraktAuthenticated } returns false
        every { viewModel.traktCatalogPreferences } returns TraktCatalogPreferences(enabledCatalogs = emptySet())
        every { viewModel.simklCatalogPreferences } returns SimklCatalogPreferences(enabledCatalogs = emptySet())
        every { viewModel.mdbListCatalogPreferences } returns MDBListCatalogPreferences()
        every { viewModel.tmdbCatalogPreferences } returns TmdbCatalogPreferences(enabledCatalogs = emptySet())
        every { viewModel.syntheticTomatoesOverridesByItemId } returns linkedMapOf()
        every { viewModel.persistedTraktSyntheticGroups } returns emptyList()
        every { viewModel.persistedSimklSyntheticGroups } returns emptyList()
        every { viewModel.persistedMDBListSyntheticGroups } returns emptyList()
        every { viewModel.persistedTmdbSyntheticGroups } returns emptyList()
        every { viewModel.traktDiscoveryService.observeSnapshot(autoRefreshOnStart = false) } returns flowOf(
            TraktDiscoverySnapshot(updatedAtMs = 1L)
        )
        every { viewModel.simklDiscoveryService.observeSnapshot(autoRefreshOnStart = false) } returns flowOf(
            SimklDiscoverySnapshot(updatedAtMs = 1L)
        )
        every { viewModel.mdbListDiscoveryService.observeSnapshot(autoRefreshOnStart = false) } returns flowOf(
            MDBListDiscoverySnapshot(updatedAtMs = 1L)
        )
        every { viewModel.tmdbDiscoveryService.observeSnapshot() } returns flowOf(
            TmdbDiscoverySnapshot(updatedAtMs = 1L)
        )
        coEvery { viewModel.flushCatalogRowsForFirstPaint(any()) } coAnswers {
            displayCatalogRows.value = listOf(displayRow)
        }
        coEvery {
            coordinator.refreshSerially(
                addons = any(),
                telemetryEnabled = any(),
                isCatalogDisabled = any(),
                getCurrentRow = any(),
                isItemReferencedElsewhere = any(),
                onCatalogReady = any(),
                onRawCatalogBatchComplete = any(),
                onLog = any()
            )
        } coAnswers {
            val onCatalogReady = arg<suspend (String, CatalogRow, CatalogItemDiff) -> Unit>(5)
            val onRawCatalogBatchComplete = arg<suspend () -> Unit>(6)
            onCatalogReady(
                "addon_movie_popular",
                fullRow,
                CatalogItemDiff(addedOrChanged = fullRow.items, removed = emptyList())
            )
            onRawCatalogBatchComplete()
            1
        }
        coEvery {
            homeHydrationCoordinator.hydrate(any(), any(), any(), any(), any(), any(), any())
        } returns null
        coEvery {
            coordinator.prefetchVisibleImagesOnly(any(), any(), any())
        } returns Unit

        val profileSession = activeProfileSession()
        every { viewModel.profileManager.activeProfileSession } returns MutableStateFlow(profileSession)

        try {
            Dispatchers.setMain(UnconfinedTestDispatcher(testScheduler))
            viewModel.runSerializedPostStartupRefreshPipeline(
                expectedGeneration = 1L,
                expectedProfileSession = profileSession,
                reason = "account_sync"
            )
        } finally {
            Dispatchers.resetMain()
        }

        coVerify(exactly = 1) {
            homeHydrationCoordinator.hydrate(
                item = visiblePreview,
                trigger = StableIdResolutionTrigger.VISIBLE_HOME_HYDRATION,
                priority = HomeHydrationPriority.VISIBLE,
                languageTag = "en",
                expectedGeneration = 1L,
                currentGeneration = any(),
                onOverlayApplied = any()
            )
        }
        coVerify(exactly = 1) {
            coordinator.prefetchVisibleImagesOnly(
                items = listOf(visiblePreview),
                telemetryEnabled = false,
                onLog = any()
            )
        }
    }

    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    @Test
    fun `serialized settlement skips visible prefetch when session changes during visible hydration`() = runTest {
        val coordinator = mockk<HomeCatalogRefreshCoordinator>()
        val homeHydrationCoordinator = mockk<HomeHydrationCoordinator>()
        val viewModel = mockk<HomeViewModel>(relaxed = true)
        val catalogsMap = linkedMapOf<String, CatalogRow>()
        val displayCatalogRows = MutableStateFlow<List<CatalogRow>>(emptyList())
        val uiState = MutableStateFlow(HomeUiState())
        val hydratedOverlays = MutableStateFlow<Map<String, HydratedHomeOverlay>>(emptyMap())
        val visiblePreview = preview(id = "tt-visible-session-race", poster = "poster").copy(
            description = "Visible catalog payload",
            releaseInfo = "2026"
        )
        val displayRow = CatalogRow(
            addonId = "addon",
            addonName = "Addon",
            addonBaseUrl = "https://addon.example",
            catalogId = "popular",
            catalogName = "Popular",
            type = ContentType.MOVIE,
            items = listOf(visiblePreview),
            hasMore = false
        )
        val expectedProfileSession = activeProfileSession()
        val activeProfileSession = MutableStateFlow(expectedProfileSession)

        every { viewModel.isCurrentHomeProfileGeneration(1L) } returns true
        every { viewModel.shouldBlockProfileSwitchDiskSnapshotRefresh(any()) } returns false
        every { viewModel.playbackIdleGateState } returns com.nexio.tv.ui.screensaver.PlaybackIdleGateState()
        every { viewModel.homeCatalogRefreshCoordinator } returns coordinator
        every { viewModel.homeHydrationCoordinator } returns homeHydrationCoordinator
        every { viewModel.addonsCache } returns listOf(addon())
        every { viewModel.startupPerfTelemetryEnabled } returns false
        every { viewModel.catalogsMap } returns catalogsMap
        every { viewModel._uiState } returns uiState
        every { viewModel.catalogInventoryRepository.isEmpty() } returns true
        every { viewModel._displayCatalogRows } returns displayCatalogRows
        every { viewModel._displayContinueWatchingItems } returns MutableStateFlow(emptyList())
        every { viewModel.hydratedHomeOverlaysByItemKey } returns hydratedOverlays
        every { viewModel.visibleHomeHydrationInFlightItemKeys } returns mutableSetOf()
        every { viewModel.homeProfileGeneration } returns 1L
        every { viewModel.profileBoundary.currentLanguageTag() } returns "en"
        every { viewModel.activeProfileTraktAuthenticated } returns false
        every { viewModel.traktCatalogPreferences } returns TraktCatalogPreferences(enabledCatalogs = emptySet())
        every { viewModel.simklCatalogPreferences } returns SimklCatalogPreferences(enabledCatalogs = emptySet())
        every { viewModel.mdbListCatalogPreferences } returns MDBListCatalogPreferences()
        every { viewModel.tmdbCatalogPreferences } returns TmdbCatalogPreferences(enabledCatalogs = emptySet())
        every { viewModel.syntheticTomatoesOverridesByItemId } returns linkedMapOf()
        every { viewModel.persistedTraktSyntheticGroups } returns emptyList()
        every { viewModel.persistedSimklSyntheticGroups } returns emptyList()
        every { viewModel.persistedMDBListSyntheticGroups } returns emptyList()
        every { viewModel.persistedTmdbSyntheticGroups } returns emptyList()
        every { viewModel.traktDiscoveryService.observeSnapshot(autoRefreshOnStart = false) } returns flowOf(
            TraktDiscoverySnapshot(updatedAtMs = 1L)
        )
        every { viewModel.simklDiscoveryService.observeSnapshot(autoRefreshOnStart = false) } returns flowOf(
            SimklDiscoverySnapshot(updatedAtMs = 1L)
        )
        every { viewModel.mdbListDiscoveryService.observeSnapshot(autoRefreshOnStart = false) } returns flowOf(
            MDBListDiscoverySnapshot(updatedAtMs = 1L)
        )
        every { viewModel.tmdbDiscoveryService.observeSnapshot() } returns flowOf(
            TmdbDiscoverySnapshot(updatedAtMs = 1L)
        )
        coEvery { viewModel.flushCatalogRowsForFirstPaint(any()) } coAnswers {
            displayCatalogRows.value = listOf(displayRow)
        }
        coEvery {
            coordinator.refreshSerially(
                addons = any(),
                telemetryEnabled = any(),
                isCatalogDisabled = any(),
                getCurrentRow = any(),
                isItemReferencedElsewhere = any(),
                onCatalogReady = any(),
                onRawCatalogBatchComplete = any(),
                onLog = any()
            )
        } coAnswers {
            val onCatalogReady = arg<suspend (String, CatalogRow, CatalogItemDiff) -> Unit>(5)
            val onRawCatalogBatchComplete = arg<suspend () -> Unit>(6)
            onCatalogReady(
                "addon_movie_popular",
                displayRow,
                CatalogItemDiff(addedOrChanged = displayRow.items, removed = emptyList())
            )
            onRawCatalogBatchComplete()
            1
        }
        coEvery {
            homeHydrationCoordinator.hydrate(any(), any(), any(), any(), any(), any(), any())
        } coAnswers {
            activeProfileSession.value = expectedProfileSession.copy(
                profileId = 2,
                sessionId = "new-session",
                sessionOrdinal = 2L
            )
            null
        }
        coEvery {
            coordinator.prefetchVisibleImagesOnly(any(), any(), any())
        } returns Unit
        every { viewModel.profileManager.activeProfileSession } returns activeProfileSession

        try {
            Dispatchers.setMain(UnconfinedTestDispatcher(testScheduler))
            viewModel.runSerializedPostStartupRefreshPipeline(
                expectedGeneration = 1L,
                expectedProfileSession = expectedProfileSession,
                reason = "account_sync"
            )
        } finally {
            Dispatchers.resetMain()
        }

        coVerify(exactly = 1) {
            homeHydrationCoordinator.hydrate(
                item = visiblePreview,
                trigger = StableIdResolutionTrigger.VISIBLE_HOME_HYDRATION,
                priority = HomeHydrationPriority.VISIBLE,
                languageTag = "en",
                expectedGeneration = 1L,
                currentGeneration = any(),
                onOverlayApplied = any()
            )
        }
        coVerify(exactly = 0) {
            coordinator.prefetchVisibleImagesOnly(any(), any(), any())
        }
    }

    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    @Test
    fun `serialized refresh skips visible bundle hydration when only full rows are populated`() = runTest {
        val coordinator = mockk<HomeCatalogRefreshCoordinator>()
        val homeHydrationCoordinator = mockk<HomeHydrationCoordinator>()
        val viewModel = mockk<HomeViewModel>(relaxed = true)
        val catalogsMap = linkedMapOf<String, CatalogRow>()
        val hiddenPreview = preview(id = "tt-hidden-full-row", poster = "poster").copy(
            description = "Hidden full-row payload",
            releaseInfo = "2026"
        )
        val fullRow = CatalogRow(
            addonId = "addon",
            addonName = "Addon",
            addonBaseUrl = "https://addon.example",
            catalogId = "popular",
            catalogName = "Popular",
            type = ContentType.MOVIE,
            items = listOf(hiddenPreview),
            hasMore = false
        )
        catalogsMap["addon_movie_popular"] = fullRow
        val catalogInventoryRepository = CatalogInventoryRepository().apply {
            publish(listOf(fullRow))
        }
        val uiState = MutableStateFlow(HomeUiState())
        val displayCatalogRows = MutableStateFlow<List<CatalogRow>>(emptyList())

        every { viewModel.isCurrentHomeProfileGeneration(1L) } returns true
        every { viewModel.shouldBlockProfileSwitchDiskSnapshotRefresh(any()) } returns false
        every { viewModel.playbackIdleGateState } returns com.nexio.tv.ui.screensaver.PlaybackIdleGateState()
        every { viewModel.homeCatalogRefreshCoordinator } returns coordinator
        every { viewModel.homeHydrationCoordinator } returns homeHydrationCoordinator
        every { viewModel.addonsCache } returns listOf(addon())
        every { viewModel.startupPerfTelemetryEnabled } returns false
        every { viewModel.catalogsMap } returns catalogsMap
        every { viewModel._uiState } returns uiState
        every { viewModel.catalogInventoryRepository } returns catalogInventoryRepository
        every { viewModel._displayCatalogRows } returns displayCatalogRows
        every { viewModel._displayContinueWatchingItems } returns MutableStateFlow(emptyList())
        every { viewModel.activeProfileTraktAuthenticated } returns false
        every { viewModel.traktCatalogPreferences } returns TraktCatalogPreferences(enabledCatalogs = emptySet())
        every { viewModel.simklCatalogPreferences } returns SimklCatalogPreferences(enabledCatalogs = emptySet())
        every { viewModel.mdbListCatalogPreferences } returns MDBListCatalogPreferences()
        every { viewModel.tmdbCatalogPreferences } returns TmdbCatalogPreferences(enabledCatalogs = emptySet())
        every { viewModel.syntheticTomatoesOverridesByItemId } returns linkedMapOf()
        every { viewModel.persistedTraktSyntheticGroups } returns emptyList()
        every { viewModel.persistedSimklSyntheticGroups } returns emptyList()
        every { viewModel.persistedMDBListSyntheticGroups } returns emptyList()
        every { viewModel.persistedTmdbSyntheticGroups } returns emptyList()
        every { viewModel.traktDiscoveryService.observeSnapshot(autoRefreshOnStart = false) } returns flowOf(
            TraktDiscoverySnapshot(updatedAtMs = 1L)
        )
        every { viewModel.simklDiscoveryService.observeSnapshot(autoRefreshOnStart = false) } returns flowOf(
            SimklDiscoverySnapshot(updatedAtMs = 1L)
        )
        every { viewModel.mdbListDiscoveryService.observeSnapshot(autoRefreshOnStart = false) } returns flowOf(
            MDBListDiscoverySnapshot(updatedAtMs = 1L)
        )
        every { viewModel.tmdbDiscoveryService.observeSnapshot() } returns flowOf(
            TmdbDiscoverySnapshot(updatedAtMs = 1L)
        )
        coEvery {
            coordinator.refreshSerially(
                addons = any(),
                telemetryEnabled = any(),
                isCatalogDisabled = any(),
                getCurrentRow = any(),
                isItemReferencedElsewhere = any(),
                onCatalogReady = any(),
                onRawCatalogBatchComplete = any(),
                onLog = any()
            )
        } returns 0
        coEvery {
            coordinator.prefetchVisibleImagesOnly(any(), any(), any())
        } returns Unit

        val profileSession = activeProfileSession()
        every { viewModel.profileManager.activeProfileSession } returns MutableStateFlow(profileSession)

        try {
            Dispatchers.setMain(UnconfinedTestDispatcher(testScheduler))
            viewModel.runSerializedPostStartupRefreshPipeline(
                expectedGeneration = 1L,
                expectedProfileSession = profileSession,
                reason = "account_sync"
            )
        } finally {
            Dispatchers.resetMain()
        }

        coVerify(exactly = 0) {
            homeHydrationCoordinator.hydrate(any(), any(), any(), any(), any(), any(), any())
        }
        coVerify(exactly = 0) {
            coordinator.prefetchVisibleImagesOnly(any(), any(), any())
        }
    }

    @Test
    fun `hydrated catalog republish preserves appended row items and loading state`() = runTest {
        val catalogRepository = mockk<CatalogRepository>()
        val tvMetadataRouter = mockk<TvMetadataRouter>()
        val rawPreview = preview(id = "tt-first-page", poster = null).copy(name = "Raw first")
        val appendedPreview = preview(id = "tt-appended", poster = null).copy(name = "Appended")
        val rawRow = CatalogRow(
            addonId = "addon",
            addonName = "Addon",
            addonBaseUrl = "https://addon.example",
            catalogId = "popular",
            catalogName = "Popular",
            type = ContentType.MOVIE,
            items = listOf(rawPreview),
            hasMore = true,
            currentPage = 0,
            supportsSkip = true,
            skipStep = 100
        )
        val currentRowAfterLoadMore = rawRow.copy(
            items = listOf(rawPreview, appendedPreview),
            isLoading = true,
            currentPage = 1,
            hasMore = false
        )
        val publishedRows = mutableListOf<CatalogRow>()
        var currentRow: CatalogRow? = null

        coEvery {
            catalogRepository.refreshCatalogToDisk(
                addonBaseUrl = "https://addon.example",
                addonId = "addon",
                addonName = "Addon",
                catalogId = "popular",
                catalogName = "Popular",
                type = "movie",
                skip = 0,
                skipStep = 100,
                supportsSkip = false
            )
        } returns Result.success(rawRow)
        coEvery { tvMetadataRouter.fetchEnrichment(any()) } returns TvMetadataDecision(
            provider = TvProvider.TMDB,
            reason = TvMetadataDecisionReason.TVDB_FALLBACK_TMDB,
            value = TvMetadataEnrichment(
                seriesTvdbId = null,
                localizedTitle = "Hydrated first"
            )
        )

        coordinator(
            catalogRepository = catalogRepository,
            tvMetadataRouter = tvMetadataRouter
        ).refreshSerially(
            addons = listOf(addon()),
            telemetryEnabled = true,
            isCatalogDisabled = { _, _ -> false },
            getCurrentRow = { currentRow },
            isItemReferencedElsewhere = { _, _ -> false },
            onCatalogReady = { _, row, _ ->
                publishedRows += row
                currentRow = if (publishedRows.size == 1) {
                    currentRowAfterLoadMore
                } else {
                    row
                }
            },
            onLog = { _, _ -> }
        )

        assertEquals(2, publishedRows.size)
        val hydratedRepublish = publishedRows.last()
        assertEquals(listOf("Hydrated first", "Appended"), hydratedRepublish.items.map { it.name })
        assertEquals(true, hydratedRepublish.isLoading)
        assertEquals(1, hydratedRepublish.currentPage)
        assertEquals(false, hydratedRepublish.hasMore)
    }

    @Test
    fun `visible prefetch only does not write metadata cache or resolve metadata`() = runTest {
        val catalogRepository = mockk<CatalogRepository>(relaxed = true)
        val metadataRouterFacade = mockk<MetadataRouterFacade>()
        val metadataDiskCacheStore = mockk<MetadataDiskCacheStore>(relaxed = true)
        val posterRatingsUrlResolver = mockk<PosterRatingsUrlResolver>(relaxed = true)
        val logs = mutableListOf<Pair<String, String?>>()
        val firstPaintItem = preview(id = "1007757", poster = null).copy(
            type = ContentType.MOVIE,
            rawType = "movie"
        )

        coordinator(
            catalogRepository = catalogRepository,
            metadataRouterFacade = metadataRouterFacade,
            metadataDiskCacheStore = metadataDiskCacheStore,
            posterRatingsUrlResolver = posterRatingsUrlResolver
        ).prefetchVisibleImagesOnly(
            items = listOf(firstPaintItem),
            telemetryEnabled = true,
            onLog = { event, details -> logs += event to details }
        )

        assertTrue(logs.any { it.first == "image_prefetch_start" })
        assertTrue(logs.any { it.first == "image_prefetch_end" })
        coVerify(exactly = 0) { metadataRouterFacade.resolveRequest(any()) }
        coVerify(exactly = 0) {
            metadataRouterFacade.resolveStableIdBundle(
                request = any(),
                trigger = any(),
                itemKey = any()
            )
        }
        verify(exactly = 0) {
            metadataDiskCacheStore.writeHomeDisplayMetadata(any(), any(), any())
        }
    }

    @Test
    fun `visible prefetch skips internal artwork refs`() = runTest {
        val catalogRepository = mockk<CatalogRepository>(relaxed = true)
        val metadataRouterFacade = mockk<MetadataRouterFacade>()
        val metadataDiskCacheStore = mockk<MetadataDiskCacheStore>(relaxed = true)
        val posterRatingsUrlResolver = mockk<PosterRatingsUrlResolver>(relaxed = true)
        val logs = mutableListOf<Pair<String, String?>>()
        val item = preview(id = "1007757", poster = "nexio-artwork://asset/posterAsset").copy(
            background = "nexio-placeholder://backdrop",
            logo = "nexio-artwork://asset/logoAsset"
        )

        coordinator(
            catalogRepository = catalogRepository,
            metadataRouterFacade = metadataRouterFacade,
            metadataDiskCacheStore = metadataDiskCacheStore,
            posterRatingsUrlResolver = posterRatingsUrlResolver
        ).prefetchVisibleImagesOnly(
            items = listOf(item),
            telemetryEnabled = true,
            onLog = { event, details -> logs += event to details }
        )

        assertTrue(logs.any { it.first == "item_image_skipped_no_urls" })
        assertTrue(logs.any { it.first == "image_prefetch_start" && it.second?.contains("urls_total=0") == true })
        assertTrue(logs.any { it.first == "image_prefetch_end" && it.second?.contains("fetched_urls=0") == true })
    }

    @Test
    fun `home refresh uses shared artwork projection and not legacy provider apply`() = runTest {
        val catalogRepository = mockk<CatalogRepository>()
        val tvMetadataRouter = mockk<TvMetadataRouter>()
        val posterRatingsUrlResolver = mockk<PosterRatingsUrlResolver>()
        val rawPreview = preview(id = "tt-refresh-artwork", poster = "https://image.tmdb.org/t/p/w500/raw.jpg")
        val artworkPreview = rawPreview.copy(poster = "nexio-artwork://decision/home")
        val row = CatalogRow(
            addonId = "addon",
            addonName = "Addon",
            addonBaseUrl = "https://addon.example",
            catalogId = "popular",
            catalogName = "Popular",
            type = ContentType.MOVIE,
            items = listOf(rawPreview),
            hasMore = false
        )
        coEvery { tvMetadataRouter.fetchEnrichment(any()) } returns TvMetadataDecision(
            provider = TvProvider.TMDB,
            reason = TvMetadataDecisionReason.TVDB_FALLBACK_TMDB,
            value = TvMetadataEnrichment(seriesTvdbId = null)
        )
        coEvery { posterRatingsUrlResolver.currentSettings() } returns ArtworkProviderSettings()
        every { posterRatingsUrlResolver.applyArtworkRef(any(), any()) } returns artworkPreview

        val hydratedRows = coordinator(
            catalogRepository = catalogRepository,
            tvMetadataRouter = tvMetadataRouter,
            posterRatingsUrlResolver = posterRatingsUrlResolver
        ).hydrateAndPrefetchRows(
            rows = listOf(row),
            telemetryEnabled = false,
            onLog = { _, _ -> }
        )

        assertEquals("nexio-artwork://decision/home", hydratedRows.single().items.single().poster)
        coVerify(exactly = 1) { posterRatingsUrlResolver.currentSettings() }
        verify(exactly = 1) {
            posterRatingsUrlResolver.applyArtworkRef(
                match { it.id == rawPreview.id && it.poster == rawPreview.poster },
                any()
            )
        }
        verify(exactly = 0) { posterRatingsUrlResolver.apply(any<MetaPreview>(), any()) }
    }

    @Test
    fun `home refresh preserves compatible persisted internal poster before artwork projection`() = runTest {
        val catalogRepository = mockk<CatalogRepository>()
        val tvMetadataRouter = mockk<TvMetadataRouter>()
        val posterRatingsUrlResolver = mockk<PosterRatingsUrlResolver>()
        val rawPreview = preview(id = "tt-refresh-artwork", poster = "https://image.tmdb.org/t/p/w500/raw.jpg")
        val persistedPreview = rawPreview.copy(
            poster = "nexio-artwork://decision/existing",
            posterProviderTag = "rpdb"
        )
        val row = CatalogRow(
            addonId = "addon",
            addonName = "Addon",
            addonBaseUrl = "https://addon.example",
            catalogId = "popular",
            catalogName = "Popular",
            type = ContentType.MOVIE,
            items = listOf(rawPreview),
            hasMore = false
        )
        val existingRow = row.copy(items = listOf(persistedPreview))
        coEvery { tvMetadataRouter.fetchEnrichment(any()) } returns TvMetadataDecision(
            provider = TvProvider.TMDB,
            reason = TvMetadataDecisionReason.TVDB_FALLBACK_TMDB,
            value = TvMetadataEnrichment(seriesTvdbId = null)
        )
        coEvery { posterRatingsUrlResolver.currentSettings() } returns ArtworkProviderSettings(
            rpdbApiKey = "rpdb-key",
            selection = ArtworkProviderSelectionSettings(
                posterProvider = ArtworkProviderChoiceKey.RPDB
            )
        )
        every { posterRatingsUrlResolver.applyArtworkRef(any(), any()) } answers { firstArg() }

        val hydratedRows = coordinator(
            catalogRepository = catalogRepository,
            tvMetadataRouter = tvMetadataRouter,
            posterRatingsUrlResolver = posterRatingsUrlResolver
        ).hydrateAndPrefetchRows(
            rows = listOf(row),
            existingRowsByKey = mapOf(homeCatalogGlobalKey(row) to existingRow),
            telemetryEnabled = false,
            onLog = { _, _ -> }
        )

        assertEquals("nexio-artwork://decision/existing", hydratedRows.single().items.single().poster)
        assertEquals("rpdb", hydratedRows.single().items.single().posterProviderTag)
        verify(exactly = 1) {
            posterRatingsUrlResolver.applyArtworkRef(
                match { it.poster == "nexio-artwork://decision/existing" },
                any()
            )
        }
    }

    @Test
    fun `home refresh does not preserve primary fallback when premium provider becomes active`() = runTest {
        val catalogRepository = mockk<CatalogRepository>()
        val tvMetadataRouter = mockk<TvMetadataRouter>()
        val posterRatingsUrlResolver = mockk<PosterRatingsUrlResolver>()
        val rawPreview = preview(id = "tt-refresh-artwork", poster = "https://image.tmdb.org/t/p/w500/raw.jpg")
        val persistedPreview = rawPreview.copy(
            poster = "nexio-artwork://decision/primary-fallback",
            posterProviderTag = null
        )
        val premiumPreview = rawPreview.copy(
            poster = "nexio-artwork://decision/rpdb",
            posterProviderTag = "rpdb"
        )
        val row = CatalogRow(
            addonId = "addon",
            addonName = "Addon",
            addonBaseUrl = "https://addon.example",
            catalogId = "popular",
            catalogName = "Popular",
            type = ContentType.MOVIE,
            items = listOf(rawPreview),
            hasMore = false
        )
        val existingRow = row.copy(items = listOf(persistedPreview))
        coEvery { tvMetadataRouter.fetchEnrichment(any()) } returns TvMetadataDecision(
            provider = TvProvider.TMDB,
            reason = TvMetadataDecisionReason.TVDB_FALLBACK_TMDB,
            value = TvMetadataEnrichment(seriesTvdbId = null)
        )
        coEvery { posterRatingsUrlResolver.currentSettings() } returns ArtworkProviderSettings(
            rpdbApiKey = "rpdb-key",
            selection = ArtworkProviderSelectionSettings(
                posterProvider = ArtworkProviderChoiceKey.RPDB
            )
        )
        every { posterRatingsUrlResolver.applyArtworkRef(any(), any()) } returns premiumPreview

        val hydratedRows = coordinator(
            catalogRepository = catalogRepository,
            tvMetadataRouter = tvMetadataRouter,
            posterRatingsUrlResolver = posterRatingsUrlResolver
        ).hydrateAndPrefetchRows(
            rows = listOf(row),
            existingRowsByKey = mapOf(homeCatalogGlobalKey(row) to existingRow),
            telemetryEnabled = false,
            onLog = { _, _ -> }
        )

        assertEquals("nexio-artwork://decision/rpdb", hydratedRows.single().items.single().poster)
        assertEquals("rpdb", hydratedRows.single().items.single().posterProviderTag)
        // Reducer preserves the persisted primary-fallback ref (STALE_RESOLVED beats FIRST_PAINT raw URL).
        // applyArtworkRef receives the persisted ref and the RPDB provider upgrades it to the premium ref.
        verify(exactly = 1) {
            posterRatingsUrlResolver.applyArtworkRef(
                match { it.poster == "nexio-artwork://decision/primary-fallback" },
                any()
            )
        }
    }

    @Test
    fun `home refresh does not preserve premium poster after provider switches to default`() = runTest {
        val catalogRepository = mockk<CatalogRepository>()
        val tvMetadataRouter = mockk<TvMetadataRouter>()
        val posterRatingsUrlResolver = mockk<PosterRatingsUrlResolver>()
        val rawPreview = preview(id = "tt-refresh-artwork", poster = "https://image.tmdb.org/t/p/w500/raw.jpg")
        val persistedPreview = rawPreview.copy(
            poster = "nexio-artwork://decision/rpdb",
            posterProviderTag = "rpdb"
        )
        val primaryPreview = rawPreview.copy(
            poster = "nexio-artwork://decision/primary-fallback",
            posterProviderTag = null
        )
        val row = CatalogRow(
            addonId = "addon",
            addonName = "Addon",
            addonBaseUrl = "https://addon.example",
            catalogId = "popular",
            catalogName = "Popular",
            type = ContentType.MOVIE,
            items = listOf(rawPreview),
            hasMore = false
        )
        val existingRow = row.copy(items = listOf(persistedPreview))
        coEvery { tvMetadataRouter.fetchEnrichment(any()) } returns TvMetadataDecision(
            provider = TvProvider.TMDB,
            reason = TvMetadataDecisionReason.TVDB_FALLBACK_TMDB,
            value = TvMetadataEnrichment(seriesTvdbId = null)
        )
        coEvery { posterRatingsUrlResolver.currentSettings() } returns ArtworkProviderSettings()
        every { posterRatingsUrlResolver.applyArtworkRef(any(), any()) } returns primaryPreview

        val hydratedRows = coordinator(
            catalogRepository = catalogRepository,
            tvMetadataRouter = tvMetadataRouter,
            posterRatingsUrlResolver = posterRatingsUrlResolver
        ).hydrateAndPrefetchRows(
            rows = listOf(row),
            existingRowsByKey = mapOf(homeCatalogGlobalKey(row) to existingRow),
            telemetryEnabled = false,
            onLog = { _, _ -> }
        )

        assertEquals("nexio-artwork://decision/primary-fallback", hydratedRows.single().items.single().poster)
        assertEquals(null, hydratedRows.single().items.single().posterProviderTag)
        // Reducer preserves the persisted RPDB ref (STALE_RESOLVED beats FIRST_PAINT raw URL).
        // applyArtworkRef receives the persisted RPDB ref; since the provider is now DEFAULT, it
        // demotes the artwork to the primary fallback. The final poster is primary-fallback, as asserted above.
        verify(exactly = 1) {
            posterRatingsUrlResolver.applyArtworkRef(
                match { it.poster == "nexio-artwork://decision/rpdb" },
                any()
            )
        }
    }

    private fun preview(id: String, poster: String?): MetaPreview {
        return MetaPreview(
            id = id,
            type = ContentType.MOVIE,
            rawType = "movie",
            name = "Item $id",
            poster = poster,
            posterShape = PosterShape.POSTER,
            background = null,
            logo = null,
            description = null,
            releaseInfo = null,
            imdbRating = null,
            genres = emptyList()
        )
    }

    private fun addon(
        catalogs: List<CatalogDescriptor> = listOf(
            CatalogDescriptor(
                type = ContentType.MOVIE,
                id = "popular",
                name = "Popular"
            )
        )
    ): Addon {
        return Addon(
            id = "addon",
            name = "Addon",
            version = "1.0.0",
            description = null,
            logo = null,
            baseUrl = "https://addon.example",
            catalogs = catalogs,
            types = listOf(ContentType.MOVIE),
            resources = listOf(AddonResource(name = "catalog", types = listOf("movie"), idPrefixes = null))
        )
    }

    private fun coordinator(
        catalogRepository: CatalogRepository,
        tvMetadataRouter: TvMetadataRouter,
        metadataDiskCacheStore: MetadataDiskCacheStore = mockk(relaxed = true),
        posterRatingsUrlResolver: PosterRatingsUrlResolver? = null
    ): HomeCatalogRefreshCoordinator {
        val posterResolver = posterRatingsUrlResolver ?: mockk<PosterRatingsUrlResolver>(relaxed = true).also {
            coEvery { it.currentSettings() } returns ArtworkProviderSettings()
            every { it.applyArtworkRef(any(), any()) } answers { firstArg() }
        }
        val profileBoundary = mockk<ProfileBoundary>()
        val playbackActivityTracker = mockk<PlaybackActivityTracker>()
        val context = mockLocaleContext()
        every { profileBoundary.currentLanguageTag() } returns "en"
        every { playbackActivityTracker.isActive } returns MutableStateFlow(true)

        return HomeCatalogRefreshCoordinator(
            catalogRepository = catalogRepository,
            metadataDiskCacheStore = metadataDiskCacheStore,
            metadataRouterFacade = testMetadataRouterFacade(tvMetadataRouter),
            providerLocalizedMetadataResolver = ProviderLocalizedMetadataResolver(
                metadataRouterFacade = testMetadataRouterFacade(tvMetadataRouter)
            ),
            posterRatingsUrlResolver = posterResolver,
            profileBoundary = profileBoundary,
            playbackActivityTracker = playbackActivityTracker,
            appContext = context
        )
    }

    private fun coordinator(
        catalogRepository: CatalogRepository,
        metadataRouterFacade: MetadataRouterFacade,
        metadataDiskCacheStore: MetadataDiskCacheStore,
        posterRatingsUrlResolver: PosterRatingsUrlResolver
    ): HomeCatalogRefreshCoordinator {
        val profileBoundary = mockk<ProfileBoundary>()
        val playbackActivityTracker = mockk<PlaybackActivityTracker>()
        val context = mockLocaleContext()
        every { profileBoundary.currentLanguageTag() } returns "en"
        every { playbackActivityTracker.isActive } returns MutableStateFlow(true)

        return HomeCatalogRefreshCoordinator(
            catalogRepository = catalogRepository,
            metadataDiskCacheStore = metadataDiskCacheStore,
            metadataRouterFacade = metadataRouterFacade,
            providerLocalizedMetadataResolver = ProviderLocalizedMetadataResolver(
                metadataRouterFacade = metadataRouterFacade
            ),
            posterRatingsUrlResolver = posterRatingsUrlResolver,
            profileBoundary = profileBoundary,
            playbackActivityTracker = playbackActivityTracker,
            appContext = context
        )
    }

    private fun mockLocaleContext(): Context {
        val context = mockk<Context>(relaxed = true)
        val prefs = mockk<SharedPreferences>(relaxed = true)
        every { context.getSharedPreferences("app_locale", Context.MODE_PRIVATE) } returns prefs
        every { prefs.getString(any(), any()) } returns "en"
        every { prefs.getInt(any(), any()) } returns 1
        return context
    }

    private fun activeProfileSession() = ActiveProfileSession(
        profileId = 1,
        sessionId = "test-session",
        sessionOrdinal = 1L,
        startedAtMs = 1L
    )

}
