package com.nexio.tv.ui.screens.home

import android.content.Context
import android.content.SharedPreferences
import com.nexio.tv.core.metadata.router.testMetadataRouterFacade
import com.nexio.tv.core.player.PlaybackActivityTracker
import com.nexio.tv.core.poster.PosterRatingsUrlResolver
import com.nexio.tv.core.profile.ProfileBoundary
import com.nexio.tv.core.tvdb.ProviderLocalizedMetadataResolver
import com.nexio.tv.core.tvdb.TvMetadataDecision
import com.nexio.tv.core.tvdb.TvMetadataDecisionReason
import com.nexio.tv.core.tvdb.TvMetadataRouter
import com.nexio.tv.core.tvdb.TvProvider
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
import com.nexio.tv.domain.model.Addon
import com.nexio.tv.domain.model.AddonResource
import com.nexio.tv.domain.model.CatalogDescriptor
import com.nexio.tv.domain.model.CatalogRow
import com.nexio.tv.domain.model.ContentType
import com.nexio.tv.domain.model.MetaPreview
import com.nexio.tv.domain.model.PosterShape
import com.nexio.tv.domain.repository.CatalogRepository
import com.nexio.tv.domain.repository.MetaRepository
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
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
            provider = TvProvider.TVDB,
            reason = TvMetadataDecisionReason.TVDB_INACTIVE,
            value = null
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
            onCatalogReady = { _, row, _ -> publishedRows += row },
            onLog = { _, _ -> }
        )

        assertEquals(1, refreshed)
        assertEquals(1, publishedRows.size)
        assertEquals("tt-first-paint", publishedRows.single().items.single().id)
        assertEquals("Catalog payload description", publishedRows.single().items.single().description)
        assertEquals("2026", publishedRows.single().items.single().releaseInfo)
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
    }

    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    @Test
    fun `home serialized refresh publishes coordinator first paint without meta repository detail fetch`() = runTest {
        val metaRepository = mockk<MetaRepository>(relaxed = true)
        val coordinator = mockk<HomeCatalogRefreshCoordinator>()
        val viewModel = mockk<HomeViewModel>(relaxed = true)
        val catalogsMap = linkedMapOf<String, CatalogRow>()
        val catalogPreview = preview(id = "tt-viewmodel-first-paint", poster = null).copy(
            description = "ViewModel catalog payload",
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
        every { viewModel.isCurrentHomeProfileGeneration(1L) } returns true
        every { viewModel.shouldBlockProfileSwitchDiskSnapshotRefresh(any()) } returns false
        every { viewModel.playbackIdleGateState } returns com.nexio.tv.ui.screensaver.PlaybackIdleGateState()
        every { viewModel.metaRepository } returns metaRepository
        every { viewModel.homeCatalogRefreshCoordinator } returns coordinator
        every { viewModel.addonsCache } returns listOf(addon())
        every { viewModel.startupPerfTelemetryEnabled } returns false
        every { viewModel.catalogsMap } returns catalogsMap
        every { viewModel._uiState } returns MutableStateFlow(HomeUiState())
        every { viewModel._fullCatalogRows } returns MutableStateFlow(emptyList())
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
                onLog = any()
            )
        } coAnswers {
            val onCatalogReady = arg<suspend (String, CatalogRow, CatalogItemDiff) -> Unit>(5)
            onCatalogReady(
                "addon_movie_popular",
                catalogRow,
                CatalogItemDiff(addedOrChanged = listOf(catalogPreview), removed = emptyList())
            )
            1
        }

        try {
            Dispatchers.setMain(UnconfinedTestDispatcher(testScheduler))
            viewModel.runSerializedPostStartupRefreshPipeline(expectedGeneration = 1L, reason = "account_sync")
        } finally {
            Dispatchers.resetMain()
        }

        assertEquals(catalogRow, catalogsMap["addon_movie_popular"])
        verify(exactly = 0) {
            metaRepository.getMetaFromAllAddons(any(), any(), any(), any(), any())
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
                onLog = any()
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

    private fun addon(): Addon {
        return Addon(
            id = "addon",
            name = "Addon",
            version = "1.0.0",
            description = null,
            logo = null,
            baseUrl = "https://addon.example",
            catalogs = listOf(
                CatalogDescriptor(
                    type = ContentType.MOVIE,
                    id = "popular",
                    name = "Popular"
                )
            ),
            types = listOf(ContentType.MOVIE),
            resources = listOf(AddonResource(name = "catalog", types = listOf("movie"), idPrefixes = null))
        )
    }

    private fun coordinator(
        catalogRepository: CatalogRepository,
        tvMetadataRouter: TvMetadataRouter
    ): HomeCatalogRefreshCoordinator {
        val titleRatingOverrideRepository = mockk<TitleRatingOverrideRepository>()
        val metadataDiskCacheStore = mockk<MetadataDiskCacheStore>(relaxed = true)
        val posterRatingsUrlResolver = mockk<PosterRatingsUrlResolver>(relaxed = true)
        val profileBoundary = mockk<ProfileBoundary>()
        val playbackActivityTracker = mockk<PlaybackActivityTracker>()
        val context = mockLocaleContext()
        coEvery { titleRatingOverrideRepository.enrichPreview(any()) } answers { firstArg() }
        every { posterRatingsUrlResolver.apply(any<MetaPreview>(), any()) } answers { firstArg() }
        every { profileBoundary.currentLanguageTag() } returns "en"
        every { playbackActivityTracker.isActive } returns MutableStateFlow(true)

        return HomeCatalogRefreshCoordinator(
            catalogRepository = catalogRepository,
            titleRatingOverrideRepository = titleRatingOverrideRepository,
            metadataDiskCacheStore = metadataDiskCacheStore,
            metadataRouterFacade = testMetadataRouterFacade(tvMetadataRouter),
            providerLocalizedMetadataResolver = ProviderLocalizedMetadataResolver(
                metadataRouterFacade = testMetadataRouterFacade(tvMetadataRouter)
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

}
