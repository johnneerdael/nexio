package com.nexio.tv.ui.screens.settings

import com.nexio.tv.data.local.TmdbCatalogIds
import com.nexio.tv.data.local.TmdbCatalogPreferences
import com.nexio.tv.data.local.TmdbCatalogSettingsDataStore
import com.nexio.tv.data.local.TmdbSettingsDataStore
import com.nexio.tv.data.repository.ProviderSettingsRepository
import com.nexio.tv.domain.model.TmdbSettings
import com.nexio.tv.ui.screens.home.order.HomeRailKey
import com.nexio.tv.ui.screens.home.order.HomeRailOrderStore
import com.nexio.tv.ui.screens.home.order.RailFamily
import com.nexio.tv.ui.screens.home.order.RailOrderMutationSource
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class TmdbSettingsViewModelHomeRailOrderWriteThroughTest {
    private val dispatcher = StandardTestDispatcher()

    @Before fun setUp() { Dispatchers.setMain(dispatcher) }
    @After fun tearDown() { Dispatchers.resetMain() }

    @Test
    fun `MoveCatalogDown routes through HomeRailOrderStore reorderProviderKeys`() = runTest(dispatcher) {
        val newOrder = listOf(
            TmdbCatalogIds.POPULAR_SERIES,
            TmdbCatalogIds.TRENDING_MOVIES,
        ) + TmdbCatalogIds.BUILT_IN_ORDER.filterNot {
            it == TmdbCatalogIds.TRENDING_MOVIES || it == TmdbCatalogIds.POPULAR_SERIES
        }
        val tmdbSettingsDataStore = mockk<TmdbSettingsDataStore>(relaxed = true) {
            every { settings } returns MutableStateFlow(TmdbSettings())
        }
        val catalogSettingsDataStore = mockk<TmdbCatalogSettingsDataStore>(relaxed = true)
        every { catalogSettingsDataStore.catalogPreferences } returns MutableStateFlow(
            TmdbCatalogPreferences(catalogOrder = newOrder)
        )
        val homeRailOrderStore = mockk<HomeRailOrderStore>(relaxed = true)

        val viewModel = TmdbSettingsViewModel(
            dataStore = tmdbSettingsDataStore,
            tmdbCatalogSettingsDataStore = catalogSettingsDataStore,
            providerSettingsRepository = mockk<ProviderSettingsRepository>(relaxed = true),
            homeRailOrderStore = homeRailOrderStore,
        )

        advanceUntilIdle()
        viewModel.onEvent(TmdbSettingsEvent.MoveCatalogDown(TmdbCatalogIds.TRENDING_MOVIES))
        advanceUntilIdle()

        coVerify {
            homeRailOrderStore.reorderProviderKeys(
                family = RailFamily.TMDB,
                providerOrder = newOrder.map(::HomeRailKey),
                source = RailOrderMutationSource.PROVIDER_SETTINGS_SCREEN,
            )
        }
    }
}
