package com.nexio.tv.ui.screens.home

import android.content.Context
import android.content.SharedPreferences
import com.nexio.tv.core.metadata.router.CanonicalStableIds
import com.nexio.tv.core.metadata.router.MetadataDecisionReason
import com.nexio.tv.core.metadata.router.MetadataDepth
import com.nexio.tv.core.metadata.router.MetadataMediaKind
import com.nexio.tv.core.metadata.router.MetadataPrimaryProvider
import com.nexio.tv.core.metadata.router.MetadataRequest
import com.nexio.tv.core.metadata.router.MetadataResolutionResult
import com.nexio.tv.core.metadata.router.MetadataRoute
import com.nexio.tv.core.metadata.router.MetadataRouterFacade
import com.nexio.tv.core.metadata.router.MetadataSourceContext
import com.nexio.tv.core.metadata.router.ResolverSchedule
import com.nexio.tv.core.metadata.router.ResolvedMetadataDocument
import com.nexio.tv.core.metadata.router.SidecarStableIds
import com.nexio.tv.core.metadata.router.SourceRole
import com.nexio.tv.core.metadata.router.SourceStableIds
import com.nexio.tv.core.metadata.router.StableIdBundle
import com.nexio.tv.core.metadata.router.StableIdResolutionTrigger
import com.nexio.tv.core.metadata.router.testMetadataRouterFacade
import com.nexio.tv.core.player.PlaybackActivityTracker
import com.nexio.tv.core.poster.PosterRatingsUrlResolver
import com.nexio.tv.core.profile.ProfileBoundary
import com.nexio.tv.core.tvdb.ProviderLocalizedMetadataResolver
import com.nexio.tv.core.tvdb.TvMetadataDecision
import com.nexio.tv.core.tvdb.TvMetadataDecisionReason
import com.nexio.tv.core.tvdb.TvMetadataRouter
import com.nexio.tv.data.local.MDBListCatalogPreferences
import com.nexio.tv.data.local.MetadataDiskCacheStore
import com.nexio.tv.data.local.SimklCatalogPreferences
import com.nexio.tv.data.local.TmdbCatalogPreferences
import com.nexio.tv.data.local.TraktCatalogPreferences
import com.nexio.tv.data.repository.MDBListDiscoverySnapshot
import com.nexio.tv.data.repository.SimklDiscoverySnapshot
import com.nexio.tv.data.repository.TmdbDiscoverySnapshot
import com.nexio.tv.data.repository.TitleRatingOverrideRepository
import com.nexio.tv.data.repository.TraktDiscoverySnapshot
import com.nexio.tv.core.tvdb.TvMetadataEnrichment
import com.nexio.tv.core.tvdb.TvProvider
import com.nexio.tv.domain.model.Addon
import com.nexio.tv.domain.model.AddonResource
import com.nexio.tv.domain.model.CatalogDescriptor
import com.nexio.tv.domain.model.CatalogRow
import com.nexio.tv.domain.model.ContentType
import com.nexio.tv.domain.model.HomeDisplayMetadata
import com.nexio.tv.domain.model.MetaPreview
import com.nexio.tv.domain.model.PosterShape
import com.nexio.tv.domain.model.ProviderIds
import com.nexio.tv.domain.model.TitleRatingSource
import com.nexio.tv.domain.repository.CatalogRepository
import com.nexio.tv.domain.repository.MetaRepository
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.coVerifyOrder
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.Runs
import io.mockk.slot
import io.mockk.verify
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
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
    fun `tvdb home enrichment carries runtime into preview`() {
        val enriched = preview(id = "a", poster = "posterA")
            .applyTvMetadataEnrichmentForHome(
                TvMetadataEnrichment(
                    seriesTvdbId = 121361,
                    runtimeMinutes = 52
                )
            )

        assertEquals("52 min", enriched.runtime)
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
        val fullCatalogRows = MutableStateFlow<List<CatalogRow>>(emptyList())
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
        every { viewModel._fullCatalogRows } returns fullCatalogRows
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
        coEvery { viewModel.flushCatalogRowsForFirstPaint() } coAnswers {
            fullCatalogRows.value = catalogsMap.values.toList()
            renderedRows += fullCatalogRows.value
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

        try {
            Dispatchers.setMain(UnconfinedTestDispatcher(testScheduler))
            viewModel.runSerializedPostStartupRefreshPipeline(expectedGeneration = 1L, reason = "account_sync")
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
        coVerify(exactly = 2) { viewModel.flushCatalogRowsForFirstPaint() }
    }

    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    @Test
    fun `serialized refresh schedules visible bundle hydration after successful catalog refresh`() = runTest {
        val coordinator = mockk<HomeCatalogRefreshCoordinator>()
        val viewModel = mockk<HomeViewModel>(relaxed = true)
        val catalogsMap = linkedMapOf<String, CatalogRow>()
        val fullCatalogRows = MutableStateFlow<List<CatalogRow>>(emptyList())
        val uiState = MutableStateFlow(HomeUiState())
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
        every { viewModel.addonsCache } returns listOf(addon())
        every { viewModel.startupPerfTelemetryEnabled } returns false
        every { viewModel.catalogsMap } returns catalogsMap
        every { viewModel._uiState } returns uiState
        every { viewModel._fullCatalogRows } returns fullCatalogRows
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
        coEvery { viewModel.flushCatalogRowsForFirstPaint() } coAnswers {
            fullCatalogRows.value = listOf(fullRow)
            uiState.value = uiState.value.copy(catalogRows = listOf(displayRow))
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
            coordinator.hydrateAndPrefetchVisibleItems(any(), any(), any())
        } returns Unit

        try {
            Dispatchers.setMain(UnconfinedTestDispatcher(testScheduler))
            viewModel.runSerializedPostStartupRefreshPipeline(expectedGeneration = 1L, reason = "account_sync")
        } finally {
            Dispatchers.resetMain()
        }

        coVerify(exactly = 1) {
            coordinator.hydrateAndPrefetchVisibleItems(
                items = listOf(visiblePreview),
                telemetryEnabled = false,
                onLog = any()
            )
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
    fun `visible hydration resolves stable bundle after provider overlay and passes it to rating enrichment`() = runTest {
        val catalogRepository = mockk<CatalogRepository>(relaxed = true)
        val metadataRouterFacade = mockk<MetadataRouterFacade>()
        val titleRatingOverrideRepository = mockk<TitleRatingOverrideRepository>()
        val metadataDiskCacheStore = mockk<MetadataDiskCacheStore>(relaxed = true)
        val posterRatingsUrlResolver = mockk<PosterRatingsUrlResolver>(relaxed = true)
        val providerRequests = mutableListOf<MetadataRequest>()
        val bundleRequests = mutableListOf<MetadataRequest>()
        val cachedMetadata = slot<HomeDisplayMetadata>()
        val stableIdBundle = stableIdBundle(
            itemKey = "movie:1007757",
            tmdbMovieId = "1007757",
            imdbId = "tt1007757"
        )
        val firstPaintItem = preview(id = "1007757", poster = null).copy(
            type = ContentType.MOVIE,
            rawType = "movie",
            name = "Swapped",
            imdbRating = 0.0f,
            ratingSource = TitleRatingSource.TMDB
        )

        every { metadataDiskCacheStore.hasCurrentMetaForItem(any(), any()) } returns false
        every { metadataDiskCacheStore.hasCurrentHomeDisplayMetadataForItem(any(), any()) } returns false
        every {
            metadataDiskCacheStore.writeHomeDisplayMetadata(
                itemKey = "movie:1007757",
                languageTag = "en",
                metadata = capture(cachedMetadata)
            )
        } just Runs
        coEvery { metadataRouterFacade.resolveRequest(capture(providerRequests)) } returns successResult()
        coEvery {
            metadataRouterFacade.resolveStableIdBundle(
                request = capture(bundleRequests),
                trigger = StableIdResolutionTrigger.VISIBLE_HOME_HYDRATION,
                itemKey = "movie:1007757"
            )
        } returns stableIdBundle
        coEvery { titleRatingOverrideRepository.enrichPreview(any(), stableIdBundle) } answers {
            firstArg<MetaPreview>().copy(
                imdbRating = 9.7f,
                ratingSource = TitleRatingSource.IMDB
            )
        }
        every { posterRatingsUrlResolver.apply(any<MetaPreview>(), any()) } answers { firstArg() }

        coordinator(
            catalogRepository = catalogRepository,
            metadataRouterFacade = metadataRouterFacade,
            titleRatingOverrideRepository = titleRatingOverrideRepository,
            metadataDiskCacheStore = metadataDiskCacheStore,
            posterRatingsUrlResolver = posterRatingsUrlResolver
        ).hydrateAndPrefetchVisibleItems(
            items = listOf(firstPaintItem),
            telemetryEnabled = false,
            onLog = { _, _ -> }
        )

        assertEquals(SourceRole.ADDON_PREVIEW, providerRequests.single().sourceContext.previewSourceRole)
        assertEquals(MetadataDepth.DETAIL_CORE, bundleRequests.single().depth)
        assertEquals("1007757", bundleRequests.single().contentId)
        assertEquals(ContentType.MOVIE, bundleRequests.single().contentType)
        assertEquals(9.7f, cachedMetadata.captured.imdbRating)
        assertEquals(TitleRatingSource.IMDB, cachedMetadata.captured.ratingSource)
        coVerifyOrder {
            metadataRouterFacade.resolveRequest(any())
            metadataRouterFacade.resolveStableIdBundle(
                request = any(),
                trigger = StableIdResolutionTrigger.VISIBLE_HOME_HYDRATION,
                itemKey = "movie:1007757"
            )
            titleRatingOverrideRepository.enrichPreview(any(), stableIdBundle)
        }
    }

    @Test
    fun `visible hydration falls back to legacy rating enrichment and logs when stable bundle resolution fails`() = runTest {
        val catalogRepository = mockk<CatalogRepository>(relaxed = true)
        val metadataRouterFacade = mockk<MetadataRouterFacade>()
        val titleRatingOverrideRepository = mockk<TitleRatingOverrideRepository>()
        val metadataDiskCacheStore = mockk<MetadataDiskCacheStore>(relaxed = true)
        val posterRatingsUrlResolver = mockk<PosterRatingsUrlResolver>(relaxed = true)
        val logs = mutableListOf<Pair<String, String?>>()
        val cachedMetadata = slot<HomeDisplayMetadata>()
        val firstPaintItem = preview(id = "1007757", poster = null).copy(
            type = ContentType.MOVIE,
            rawType = "movie",
            name = "Swapped",
            imdbRating = 0.0f,
            ratingSource = TitleRatingSource.TMDB
        )

        every { metadataDiskCacheStore.hasCurrentMetaForItem(any(), any()) } returns false
        every { metadataDiskCacheStore.hasCurrentHomeDisplayMetadataForItem(any(), any()) } returns false
        every {
            metadataDiskCacheStore.writeHomeDisplayMetadata(
                itemKey = "movie:1007757",
                languageTag = "en",
                metadata = capture(cachedMetadata)
            )
        } just Runs
        coEvery { metadataRouterFacade.resolveRequest(any()) } returns successResult()
        coEvery {
            metadataRouterFacade.resolveStableIdBundle(
                request = any(),
                trigger = StableIdResolutionTrigger.VISIBLE_HOME_HYDRATION,
                itemKey = "movie:1007757"
            )
        } throws IllegalStateException("identity backend unavailable")
        coEvery { titleRatingOverrideRepository.enrichPreview(any(), null) } answers {
            firstArg<MetaPreview>().copy(
                imdbRating = 8.8f,
                ratingSource = TitleRatingSource.IMDB
            )
        }
        every { posterRatingsUrlResolver.apply(any<MetaPreview>(), any()) } answers { firstArg() }

        coordinator(
            catalogRepository = catalogRepository,
            metadataRouterFacade = metadataRouterFacade,
            titleRatingOverrideRepository = titleRatingOverrideRepository,
            metadataDiskCacheStore = metadataDiskCacheStore,
            posterRatingsUrlResolver = posterRatingsUrlResolver
        ).hydrateAndPrefetchVisibleItems(
            items = listOf(firstPaintItem),
            telemetryEnabled = false,
            onLog = { event, payload -> logs += event to payload }
        )

        assertEquals(8.8f, cachedMetadata.captured.imdbRating)
        assertEquals(TitleRatingSource.IMDB, cachedMetadata.captured.ratingSource)
        assertTrue(
            logs.any { (event, payload) ->
                event == "stable_id_bundle_failed" &&
                    payload?.contains("trigger=VISIBLE_HOME_HYDRATION") == true &&
                    payload.contains("itemKey=movie:1007757")
            }
        )
        coVerify(exactly = 1) { titleRatingOverrideRepository.enrichPreview(any(), null) }
    }

    @Test
    fun `visible hydration rethrows cancellation from stable bundle resolution`() = runTest {
        val catalogRepository = mockk<CatalogRepository>(relaxed = true)
        val metadataRouterFacade = mockk<MetadataRouterFacade>()
        val titleRatingOverrideRepository = mockk<TitleRatingOverrideRepository>(relaxed = true)
        val metadataDiskCacheStore = mockk<MetadataDiskCacheStore>(relaxed = true)
        val posterRatingsUrlResolver = mockk<PosterRatingsUrlResolver>(relaxed = true)
        val firstPaintItem = preview(id = "1007757", poster = null).copy(
            type = ContentType.MOVIE,
            rawType = "movie"
        )
        val cancellation = CancellationException("cancelled")

        every { metadataDiskCacheStore.hasCurrentMetaForItem(any(), any()) } returns false
        every { metadataDiskCacheStore.hasCurrentHomeDisplayMetadataForItem(any(), any()) } returns false
        coEvery { metadataRouterFacade.resolveRequest(any()) } returns successResult()
        coEvery {
            metadataRouterFacade.resolveStableIdBundle(
                request = any(),
                trigger = StableIdResolutionTrigger.VISIBLE_HOME_HYDRATION,
                itemKey = "movie:1007757"
            )
        } throws cancellation

        try {
            coordinator(
                catalogRepository = catalogRepository,
                metadataRouterFacade = metadataRouterFacade,
                titleRatingOverrideRepository = titleRatingOverrideRepository,
                metadataDiskCacheStore = metadataDiskCacheStore,
                posterRatingsUrlResolver = posterRatingsUrlResolver
            ).hydrateAndPrefetchVisibleItems(
                items = listOf(firstPaintItem),
                telemetryEnabled = false,
                onLog = { _, _ -> }
            )
            fail("Expected CancellationException")
        } catch (actual: CancellationException) {
            assertEquals(cancellation, actual)
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
        titleRatingOverrideRepository: TitleRatingOverrideRepository? = null,
        metadataDiskCacheStore: MetadataDiskCacheStore = mockk(relaxed = true),
        posterRatingsUrlResolver: PosterRatingsUrlResolver? = null
    ): HomeCatalogRefreshCoordinator {
        val ratingOverrides = titleRatingOverrideRepository ?: mockk<TitleRatingOverrideRepository>().also {
            coEvery { it.enrichPreview(any()) } answers { firstArg() }
        }
        val posterResolver = posterRatingsUrlResolver ?: mockk<PosterRatingsUrlResolver>(relaxed = true).also {
            every { it.apply(any<MetaPreview>(), any()) } answers { firstArg() }
        }
        val profileBoundary = mockk<ProfileBoundary>()
        val playbackActivityTracker = mockk<PlaybackActivityTracker>()
        val context = mockLocaleContext()
        every { profileBoundary.currentLanguageTag() } returns "en"
        every { playbackActivityTracker.isActive } returns MutableStateFlow(true)

        return HomeCatalogRefreshCoordinator(
            catalogRepository = catalogRepository,
            titleRatingOverrideRepository = ratingOverrides,
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
        titleRatingOverrideRepository: TitleRatingOverrideRepository,
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
            titleRatingOverrideRepository = titleRatingOverrideRepository,
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

    private fun successResult() = MetadataResolutionResult(
        route = MetadataRoute(
            provider = MetadataPrimaryProvider.TMDB,
            parentId = "tmdb:1007757",
            mediaKind = MetadataMediaKind.MOVIE,
            reason = MetadataDecisionReason.PROVIDER_NATIVE_DIRECT,
            sourceContext = MetadataSourceContext(),
            targetIds = mapOf(MetadataPrimaryProvider.TMDB to "tmdb:1007757"),
            trace = emptyList()
        ),
        plan = null,
        resolverSchedule = ResolverSchedule(MetadataDepth.DETAIL_CORE, emptyList(), emptyList()),
        resolvedDocument = ResolvedMetadataDocument(
            canonicalId = "tmdb:1007757",
            title = "Provider title",
            overview = "Provider description",
            poster = null,
            backdrop = null,
            logo = null,
            rating = 7.1,
            runtimeMinutes = null,
            fieldOwners = emptyMap(),
            ignoredOverwrites = emptyList()
        ),
        displayMetadata = HomeDisplayMetadata(title = "Provider title"),
        trace = emptyList()
    )

    private fun stableIdBundle(
        itemKey: String,
        tmdbMovieId: String,
        imdbId: String
    ): StableIdBundle =
        StableIdBundle(
            itemKey = itemKey,
            itemType = ContentType.MOVIE,
            canonical = CanonicalStableIds(tmdbMovieId = tmdbMovieId),
            sidecars = SidecarStableIds(imdbId = imdbId),
            source = SourceStableIds(
                sourceProvider = null,
                sourceItemId = null,
                railId = null,
                observedIds = ProviderIds()
            ),
            evidence = emptyList(),
            resolvedAtMs = 1L
        )

    private fun mockLocaleContext(): Context {
        val context = mockk<Context>(relaxed = true)
        val prefs = mockk<SharedPreferences>(relaxed = true)
        every { context.getSharedPreferences("app_locale", Context.MODE_PRIVATE) } returns prefs
        every { prefs.getString(any(), any()) } returns "en"
        every { prefs.getInt(any(), any()) } returns 1
        return context
    }

}
