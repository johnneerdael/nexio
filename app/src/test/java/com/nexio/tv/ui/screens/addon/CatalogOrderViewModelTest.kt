package com.nexio.tv.ui.screens.addon

import com.nexio.tv.core.recommendations.AndroidTvFeedCatalogService
import com.nexio.tv.core.sync.CatalogPriorityHydrationNotifier
import com.nexio.tv.data.local.AndroidTvRecommendationsDataStore
import com.nexio.tv.data.local.AndroidTvRecommendationsPreferences
import com.nexio.tv.data.local.KitsuCatalogIds
import com.nexio.tv.data.local.KitsuCatalogPreferences
import com.nexio.tv.data.local.KitsuCatalogSettingsDataStore
import com.nexio.tv.data.local.LayoutPreferenceDataStore
import com.nexio.tv.data.local.MDBListCatalogPreferences
import com.nexio.tv.data.local.MDBListSettingsDataStore
import com.nexio.tv.data.local.SimklCatalogPreferences
import com.nexio.tv.data.local.SimklSettingsDataStore
import com.nexio.tv.data.local.TmdbCatalogPreferences
import com.nexio.tv.data.local.TmdbCatalogSettingsDataStore
import com.nexio.tv.data.local.TraktCatalogIds
import com.nexio.tv.data.local.TraktCatalogPreferences
import com.nexio.tv.data.local.TraktSettingsDataStore
import com.nexio.tv.data.repository.MDBListDiscoveryService
import com.nexio.tv.data.repository.MDBListDiscoverySnapshot
import com.nexio.tv.data.repository.TraktDiscoveryService
import com.nexio.tv.data.repository.TraktDiscoverySnapshot
import com.nexio.tv.domain.model.HomeCatalogRail
import com.nexio.tv.domain.repository.AddonRepository
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class CatalogOrderViewModelTest {
    private val dispatcher = StandardTestDispatcher()

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `re-enabling a disabled catalog fires priority hydration notifier`() = runTest(dispatcher) {
        val notifier = CatalogPriorityHydrationNotifier()
        val layoutPreferenceDataStore = mockk<LayoutPreferenceDataStore>(relaxed = true)
        every { layoutPreferenceDataStore.homeCatalogOrderKeys } returns MutableStateFlow(emptyList())
        every { layoutPreferenceDataStore.homeCatalogRails } returns MutableStateFlow(emptyList())
        every {
            layoutPreferenceDataStore.disabledHomeCatalogKeys
        } returns MutableStateFlow(listOf(TraktCatalogIds.TRENDING_MOVIES))

        val viewModel = buildViewModel(
            layoutPreferenceDataStore = layoutPreferenceDataStore,
            notifier = notifier
        )
        val events = mutableListOf<Long>()
        val job = launch { notifier.events.collect { events.add(it) } }
        advanceUntilIdle()

        viewModel.toggleCatalogEnabled(TraktCatalogIds.TRENDING_MOVIES)
        advanceUntilIdle()

        assertTrue("Notifier should fire when re-enabling a hidden catalog", events.isNotEmpty())
        coVerify(exactly = 1) {
            layoutPreferenceDataStore.setDisabledHomeCatalogKeys(emptyList())
        }
        job.cancel()
    }

    @Test
    fun `disabling an enabled catalog does not fire priority hydration notifier`() = runTest(dispatcher) {
        val notifier = CatalogPriorityHydrationNotifier()
        val layoutPreferenceDataStore = mockk<LayoutPreferenceDataStore>(relaxed = true)
        every { layoutPreferenceDataStore.homeCatalogOrderKeys } returns MutableStateFlow(emptyList())
        every { layoutPreferenceDataStore.homeCatalogRails } returns MutableStateFlow(emptyList())
        every { layoutPreferenceDataStore.disabledHomeCatalogKeys } returns MutableStateFlow(emptyList())

        val viewModel = buildViewModel(
            layoutPreferenceDataStore = layoutPreferenceDataStore,
            notifier = notifier
        )
        val events = mutableListOf<Long>()
        val job = launch { notifier.events.collect { events.add(it) } }
        advanceUntilIdle()

        viewModel.toggleCatalogEnabled(TraktCatalogIds.TRENDING_MOVIES)
        advanceUntilIdle()

        assertTrue("Notifier should not fire when hiding a catalog", events.isEmpty())
        coVerify(exactly = 1) {
            layoutPreferenceDataStore.setDisabledHomeCatalogKeys(listOf(TraktCatalogIds.TRENDING_MOVIES))
        }
        job.cancel()
    }

    @Test
    fun `reorder writes home catalog rails`() = runTest(dispatcher) {
        val notifier = CatalogPriorityHydrationNotifier()
        val layoutPreferenceDataStore = mockk<LayoutPreferenceDataStore>(relaxed = true)
        val traktSettingsDataStore = mockk<TraktSettingsDataStore>(relaxed = true)
        val railsFlow = MutableStateFlow(
            listOf(
                HomeCatalogRail(
                    key = TraktCatalogIds.TRENDING_MOVIES,
                    family = "trakt",
                    source = "provider_catalog",
                    title = "Trakt Trending Movies"
                ),
                HomeCatalogRail(
                    key = TraktCatalogIds.TRENDING_SHOWS,
                    family = "trakt",
                    source = "provider_catalog",
                    title = "Trakt Trending Shows"
                )
            )
        )
        every { layoutPreferenceDataStore.homeCatalogOrderKeys } returns MutableStateFlow(emptyList())
        every { layoutPreferenceDataStore.homeCatalogRails } returns railsFlow
        every { layoutPreferenceDataStore.disabledHomeCatalogKeys } returns MutableStateFlow(emptyList())
        every { traktSettingsDataStore.catalogPreferences } returns MutableStateFlow(
            TraktCatalogPreferences(
                enabledCatalogs = setOf(
                    TraktCatalogIds.TRENDING_MOVIES,
                    TraktCatalogIds.TRENDING_SHOWS
                ),
                catalogOrder = TraktCatalogIds.BUILT_IN_ORDER
            )
        )

        val viewModel = buildViewModel(
            layoutPreferenceDataStore = layoutPreferenceDataStore,
            notifier = notifier,
            traktSettingsDataStore = traktSettingsDataStore
        )
        advanceUntilIdle()

        viewModel.moveDown(TraktCatalogIds.TRENDING_MOVIES)
        advanceUntilIdle()

        coVerify {
            layoutPreferenceDataStore.setHomeCatalogRails(match { rails ->
                rails.map { it.key } == listOf(TraktCatalogIds.TRENDING_SHOWS, TraktCatalogIds.TRENDING_MOVIES)
            })
        }
    }

    @Test
    fun `enabling a kitsu rail uses kitsu settings store and leaves layout disabled keys alone`() = runTest(dispatcher) {
        val notifier = CatalogPriorityHydrationNotifier()
        val layoutPreferenceDataStore = mockk<LayoutPreferenceDataStore>(relaxed = true)
        val kitsuCatalogSettingsDataStore = mockk<KitsuCatalogSettingsDataStore>(relaxed = true)
        every { layoutPreferenceDataStore.homeCatalogOrderKeys } returns MutableStateFlow(emptyList())
        every { layoutPreferenceDataStore.homeCatalogRails } returns MutableStateFlow(emptyList())
        every { layoutPreferenceDataStore.disabledHomeCatalogKeys } returns MutableStateFlow(emptyList())
        every { kitsuCatalogSettingsDataStore.catalogPreferences } returns MutableStateFlow(KitsuCatalogPreferences())

        val viewModel = buildViewModel(
            layoutPreferenceDataStore = layoutPreferenceDataStore,
            notifier = notifier,
            kitsuCatalogSettingsDataStore = kitsuCatalogSettingsDataStore
        )
        advanceUntilIdle()

        viewModel.toggleCatalogEnabled(KitsuCatalogIds.TRENDING_ANIME)
        advanceUntilIdle()

        coVerify(exactly = 1) {
            kitsuCatalogSettingsDataStore.setCatalogEnabled(KitsuCatalogIds.TRENDING_ANIME, true)
        }
        coVerify(exactly = 0) {
            layoutPreferenceDataStore.setDisabledHomeCatalogKeys(any())
        }
    }

    @Test
    fun `observeCatalogs reflects home catalog rail state changes after reorder`() = runTest(dispatcher) {
        val notifier = CatalogPriorityHydrationNotifier()
        val layoutPreferenceDataStore = mockk<LayoutPreferenceDataStore>(relaxed = true)
        val traktSettingsDataStore = mockk<TraktSettingsDataStore>(relaxed = true)
        val railsFlow = MutableStateFlow(
            listOf(
                HomeCatalogRail(
                    key = TraktCatalogIds.TRENDING_MOVIES,
                    family = "trakt",
                    source = "provider_catalog",
                    title = "Trakt Trending Movies"
                ),
                HomeCatalogRail(
                    key = TraktCatalogIds.TRENDING_SHOWS,
                    family = "trakt",
                    source = "provider_catalog",
                    title = "Trakt Trending Shows"
                )
            )
        )
        every { layoutPreferenceDataStore.homeCatalogOrderKeys } returns MutableStateFlow(emptyList())
        every { layoutPreferenceDataStore.homeCatalogRails } returns railsFlow
        every { layoutPreferenceDataStore.disabledHomeCatalogKeys } returns MutableStateFlow(emptyList())
        every { traktSettingsDataStore.catalogPreferences } returns MutableStateFlow(
            TraktCatalogPreferences(
                enabledCatalogs = setOf(
                    TraktCatalogIds.TRENDING_MOVIES,
                    TraktCatalogIds.TRENDING_SHOWS
                ),
                catalogOrder = TraktCatalogIds.BUILT_IN_ORDER
            )
        )

        val viewModel = buildViewModel(
            layoutPreferenceDataStore = layoutPreferenceDataStore,
            notifier = notifier,
            traktSettingsDataStore = traktSettingsDataStore
        )
        advanceUntilIdle()

        val initialKeys = viewModel.uiState.value.items.map { it.key }
        assertEquals(
            listOf(TraktCatalogIds.TRENDING_MOVIES, TraktCatalogIds.TRENDING_SHOWS),
            initialKeys.filter { it in setOf(TraktCatalogIds.TRENDING_MOVIES, TraktCatalogIds.TRENDING_SHOWS) }
        )

        railsFlow.value = listOf(
            HomeCatalogRail(
                key = TraktCatalogIds.TRENDING_SHOWS,
                family = "trakt",
                source = "provider_catalog",
                title = "Trakt Trending Shows"
            ),
            HomeCatalogRail(
                key = TraktCatalogIds.TRENDING_MOVIES,
                family = "trakt",
                source = "provider_catalog",
                title = "Trakt Trending Movies"
            )
        )
        advanceUntilIdle()

        val updatedKeys = viewModel.uiState.value.items.map { it.key }
        assertEquals(
            listOf(TraktCatalogIds.TRENDING_SHOWS, TraktCatalogIds.TRENDING_MOVIES),
            updatedKeys.filter { it in setOf(TraktCatalogIds.TRENDING_MOVIES, TraktCatalogIds.TRENDING_SHOWS) }
        )
    }

    @Test
    fun `catalog management shows home rails as visible and stock rails as add candidates`() = runTest(dispatcher) {
        val layoutPreferenceDataStore = mockk<LayoutPreferenceDataStore>(relaxed = true)
        every { layoutPreferenceDataStore.homeCatalogOrderKeys } returns MutableStateFlow(emptyList())
        every { layoutPreferenceDataStore.disabledHomeCatalogKeys } returns MutableStateFlow(emptyList())
        every { layoutPreferenceDataStore.homeCatalogRails } returns MutableStateFlow(
            listOf(
                HomeCatalogRail(
                    key = "tmdb_trending_movies",
                    family = "tmdb",
                    source = "provider_catalog",
                    title = "Trending Movies"
                )
            )
        )
        val tmdbCatalogSettingsDataStore = mockk<TmdbCatalogSettingsDataStore>(relaxed = true)
        every { tmdbCatalogSettingsDataStore.catalogPreferences } returns MutableStateFlow(
            TmdbCatalogPreferences(
                enabledCatalogs = setOf("tmdb_trending_movies", "tmdb_popular_movies"),
                catalogOrder = listOf("tmdb_trending_movies", "tmdb_popular_movies")
            )
        )

        val viewModel = buildViewModel(
            layoutPreferenceDataStore = layoutPreferenceDataStore,
            notifier = CatalogPriorityHydrationNotifier(),
            tmdbCatalogSettingsDataStore = tmdbCatalogSettingsDataStore
        )
        advanceUntilIdle()

        assertEquals(listOf("tmdb_trending_movies"), viewModel.uiState.value.items.map { it.key })
        assertTrue(viewModel.uiState.value.availableItems.map { it.key }.contains("tmdb_popular_movies"))
    }

    @Test
    fun `catalog management exposes stock add candidates independent of provider enabled sets`() = runTest(dispatcher) {
        val layoutPreferenceDataStore = mockk<LayoutPreferenceDataStore>(relaxed = true)
        every { layoutPreferenceDataStore.homeCatalogOrderKeys } returns MutableStateFlow(emptyList())
        every { layoutPreferenceDataStore.disabledHomeCatalogKeys } returns MutableStateFlow(emptyList())
        every { layoutPreferenceDataStore.homeCatalogRails } returns MutableStateFlow(emptyList())
        val tmdbCatalogSettingsDataStore = mockk<TmdbCatalogSettingsDataStore>(relaxed = true)
        every { tmdbCatalogSettingsDataStore.catalogPreferences } returns MutableStateFlow(
            TmdbCatalogPreferences(enabledCatalogs = emptySet())
        )
        val kitsuCatalogSettingsDataStore = mockk<KitsuCatalogSettingsDataStore>(relaxed = true)
        every { kitsuCatalogSettingsDataStore.catalogPreferences } returns MutableStateFlow(
            KitsuCatalogPreferences(enabledCatalogs = emptySet())
        )

        val viewModel = buildViewModel(
            layoutPreferenceDataStore = layoutPreferenceDataStore,
            notifier = CatalogPriorityHydrationNotifier(),
            tmdbCatalogSettingsDataStore = tmdbCatalogSettingsDataStore,
            kitsuCatalogSettingsDataStore = kitsuCatalogSettingsDataStore
        )
        advanceUntilIdle()

        val availableKeys = viewModel.uiState.value.availableItems.map { it.key }
        assertTrue(availableKeys.contains("tmdb_popular_movies"))
        assertTrue(availableKeys.contains(KitsuCatalogIds.TRENDING_ANIME))
    }

    @Test
    fun `removing rail updates home catalog rails without disabling provider`() = runTest(dispatcher) {
        val layoutPreferenceDataStore = mockk<LayoutPreferenceDataStore>(relaxed = true)
        every { layoutPreferenceDataStore.homeCatalogOrderKeys } returns MutableStateFlow(emptyList())
        every { layoutPreferenceDataStore.disabledHomeCatalogKeys } returns MutableStateFlow(emptyList())
        every { layoutPreferenceDataStore.homeCatalogRails } returns MutableStateFlow(
            listOf(
                HomeCatalogRail(
                    key = "tmdb_trending_movies",
                    family = "tmdb",
                    source = "provider_catalog",
                    title = "Trending Movies"
                )
            )
        )

        val viewModel = buildViewModel(
            layoutPreferenceDataStore = layoutPreferenceDataStore,
            notifier = CatalogPriorityHydrationNotifier()
        )
        advanceUntilIdle()

        viewModel.removeFromHome("tmdb_trending_movies")
        advanceUntilIdle()

        coVerify(exactly = 1) {
            layoutPreferenceDataStore.setHomeCatalogRails(emptyList())
        }
        coVerify(exactly = 0) {
            layoutPreferenceDataStore.setDisabledHomeCatalogKeys(any())
        }
    }

    private fun buildViewModel(
        layoutPreferenceDataStore: LayoutPreferenceDataStore,
        notifier: CatalogPriorityHydrationNotifier,
        kitsuCatalogSettingsDataStore: KitsuCatalogSettingsDataStore = mockk(relaxed = true),
        tmdbCatalogSettingsDataStore: TmdbCatalogSettingsDataStore = mockk(relaxed = true),
        traktSettingsDataStore: TraktSettingsDataStore? = null
    ): CatalogOrderViewModel {
        val addonRepository = mockk<AddonRepository>(relaxed = true)
        val traktDiscoveryService = mockk<TraktDiscoveryService>(relaxed = true)
        val resolvedTraktSettingsDataStore = traktSettingsDataStore ?: mockk<TraktSettingsDataStore>(relaxed = true).also {
            every { it.catalogPreferences } returns MutableStateFlow(
                TraktCatalogPreferences(
                    enabledCatalogs = setOf(TraktCatalogIds.TRENDING_MOVIES),
                    catalogOrder = TraktCatalogIds.BUILT_IN_ORDER
                )
            )
        }
        val simklSettingsDataStore = mockk<SimklSettingsDataStore>(relaxed = true)
        val mdbListDiscoveryService = mockk<MDBListDiscoveryService>(relaxed = true)
        val mdbListSettingsDataStore = mockk<MDBListSettingsDataStore>(relaxed = true)
        val androidTvRecommendationsDataStore = mockk<AndroidTvRecommendationsDataStore>(relaxed = true)
        val androidTvFeedCatalogService = mockk<AndroidTvFeedCatalogService>(relaxed = true)

        every { addonRepository.getInstalledAddons() } returns MutableStateFlow(emptyList())
        every { traktDiscoveryService.observeSnapshot(any()) } returns MutableStateFlow(TraktDiscoverySnapshot())
        every { simklSettingsDataStore.catalogPreferences } returns MutableStateFlow(SimklCatalogPreferences())
        every { mdbListDiscoveryService.observeSnapshot() } returns MutableStateFlow(MDBListDiscoverySnapshot())
        every { mdbListSettingsDataStore.catalogPreferences } returns MutableStateFlow(MDBListCatalogPreferences())
        every { kitsuCatalogSettingsDataStore.catalogPreferences } returns MutableStateFlow(KitsuCatalogPreferences())
        every { tmdbCatalogSettingsDataStore.catalogPreferences } returns MutableStateFlow(TmdbCatalogPreferences())
        every { androidTvRecommendationsDataStore.preferences } returns MutableStateFlow(
            AndroidTvRecommendationsPreferences()
        )
        every { androidTvFeedCatalogService.observeFeedOptions() } returns MutableStateFlow(emptyList())

        return CatalogOrderViewModel(
            addonRepository = addonRepository,
            layoutPreferenceDataStore = layoutPreferenceDataStore,
            traktDiscoveryService = traktDiscoveryService,
            traktSettingsDataStore = resolvedTraktSettingsDataStore,
            simklSettingsDataStore = simklSettingsDataStore,
            mdbListDiscoveryService = mdbListDiscoveryService,
            mdbListSettingsDataStore = mdbListSettingsDataStore,
            kitsuCatalogSettingsDataStore = kitsuCatalogSettingsDataStore,
            tmdbCatalogSettingsDataStore = tmdbCatalogSettingsDataStore,
            androidTvRecommendationsDataStore = androidTvRecommendationsDataStore,
            androidTvFeedCatalogService = androidTvFeedCatalogService,
            catalogPriorityHydrationNotifier = notifier
        )
    }
}
