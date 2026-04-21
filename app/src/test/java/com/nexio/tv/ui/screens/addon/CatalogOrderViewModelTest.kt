package com.nexio.tv.ui.screens.addon

import com.nexio.tv.core.recommendations.AndroidTvFeedCatalogService
import com.nexio.tv.core.sync.CatalogPriorityHydrationNotifier
import com.nexio.tv.data.local.AndroidTvRecommendationsDataStore
import com.nexio.tv.data.local.AndroidTvRecommendationsPreferences
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

    private fun buildViewModel(
        layoutPreferenceDataStore: LayoutPreferenceDataStore,
        notifier: CatalogPriorityHydrationNotifier
    ): CatalogOrderViewModel {
        val addonRepository = mockk<AddonRepository>(relaxed = true)
        val traktDiscoveryService = mockk<TraktDiscoveryService>(relaxed = true)
        val traktSettingsDataStore = mockk<TraktSettingsDataStore>(relaxed = true)
        val simklSettingsDataStore = mockk<SimklSettingsDataStore>(relaxed = true)
        val mdbListDiscoveryService = mockk<MDBListDiscoveryService>(relaxed = true)
        val mdbListSettingsDataStore = mockk<MDBListSettingsDataStore>(relaxed = true)
        val tmdbCatalogSettingsDataStore = mockk<TmdbCatalogSettingsDataStore>(relaxed = true)
        val androidTvRecommendationsDataStore = mockk<AndroidTvRecommendationsDataStore>(relaxed = true)
        val androidTvFeedCatalogService = mockk<AndroidTvFeedCatalogService>(relaxed = true)

        every { addonRepository.getInstalledAddons() } returns MutableStateFlow(emptyList())
        every { traktDiscoveryService.observeSnapshot(any()) } returns MutableStateFlow(TraktDiscoverySnapshot())
        every { traktSettingsDataStore.catalogPreferences } returns MutableStateFlow(
            TraktCatalogPreferences(
                enabledCatalogs = setOf(TraktCatalogIds.TRENDING_MOVIES),
                catalogOrder = TraktCatalogIds.BUILT_IN_ORDER
            )
        )
        every { simklSettingsDataStore.catalogPreferences } returns MutableStateFlow(SimklCatalogPreferences())
        every { mdbListDiscoveryService.observeSnapshot() } returns MutableStateFlow(MDBListDiscoverySnapshot())
        every { mdbListSettingsDataStore.catalogPreferences } returns MutableStateFlow(MDBListCatalogPreferences())
        every { tmdbCatalogSettingsDataStore.catalogPreferences } returns MutableStateFlow(TmdbCatalogPreferences())
        every { androidTvRecommendationsDataStore.preferences } returns MutableStateFlow(
            AndroidTvRecommendationsPreferences()
        )
        every { androidTvFeedCatalogService.observeFeedOptions() } returns MutableStateFlow(emptyList())

        return CatalogOrderViewModel(
            addonRepository = addonRepository,
            layoutPreferenceDataStore = layoutPreferenceDataStore,
            traktDiscoveryService = traktDiscoveryService,
            traktSettingsDataStore = traktSettingsDataStore,
            simklSettingsDataStore = simklSettingsDataStore,
            mdbListDiscoveryService = mdbListDiscoveryService,
            mdbListSettingsDataStore = mdbListSettingsDataStore,
            tmdbCatalogSettingsDataStore = tmdbCatalogSettingsDataStore,
            androidTvRecommendationsDataStore = androidTvRecommendationsDataStore,
            androidTvFeedCatalogService = androidTvFeedCatalogService,
            catalogPriorityHydrationNotifier = notifier
        )
    }
}
