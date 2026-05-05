package com.nexio.tv.ui.screens.settings

import com.nexio.tv.core.sync.CatalogPriorityHydrationNotifier
import com.nexio.tv.data.local.MDBListCatalogPreferences
import com.nexio.tv.data.local.MDBListSettingsDataStore
import com.nexio.tv.data.repository.MDBListDiscoveryService
import com.nexio.tv.data.repository.MDBListDiscoverySnapshot
import com.nexio.tv.data.repository.MDBListListOption
import com.nexio.tv.data.repository.ProviderSettingsRepository
import com.nexio.tv.domain.model.MDBListSettings
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
class MDBListSettingsViewModelHomeRailOrderWriteThroughTest {
    private val dispatcher = StandardTestDispatcher()

    @Before fun setUp() { Dispatchers.setMain(dispatcher) }
    @After fun tearDown() { Dispatchers.resetMain() }

    @Test
    fun `moveCatalog routes through HomeRailOrderStore reorderProviderKeys`() = runTest(dispatcher) {
        val newOrder = listOf("listB", "listA")
        val dataStore = mockk<MDBListSettingsDataStore>(relaxed = true)
        every { dataStore.settings } returns MutableStateFlow(MDBListSettings())
        every { dataStore.catalogPreferences } returns MutableStateFlow(
            MDBListCatalogPreferences(catalogOrder = newOrder)
        )
        val discoveryService = mockk<MDBListDiscoveryService>(relaxed = true)
        every { discoveryService.observeSnapshot() } returns MutableStateFlow(
            MDBListDiscoverySnapshot(
                personalLists = listOf(
                    MDBListListOption(
                        key = "listA", owner = "u", listId = "1",
                        title = "A", itemCount = 0, isPersonal = true,
                    ),
                    MDBListListOption(
                        key = "listB", owner = "u", listId = "2",
                        title = "B", itemCount = 0, isPersonal = true,
                    ),
                )
            )
        )
        val homeRailOrderStore = mockk<HomeRailOrderStore>(relaxed = true)

        val viewModel = MDBListSettingsViewModel(
            dataStore = dataStore,
            providerSettingsRepository = mockk<ProviderSettingsRepository>(relaxed = true),
            mdbListDiscoveryService = discoveryService,
            catalogPriorityHydrationNotifier = CatalogPriorityHydrationNotifier(),
            homeRailOrderStore = homeRailOrderStore,
        )

        advanceUntilIdle()
        viewModel.onEvent(MDBListSettingsEvent.MoveCatalogDown(listKey = "listA"))
        advanceUntilIdle()

        coVerify {
            homeRailOrderStore.reorderProviderKeys(
                family = RailFamily.MDBLIST,
                providerOrder = newOrder.map(::HomeRailKey),
                source = RailOrderMutationSource.PROVIDER_SETTINGS_SCREEN,
            )
        }
    }
}
