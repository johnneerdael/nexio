package com.nexio.tv.ui.screens.settings

import android.content.Context
import com.nexio.tv.core.sync.CatalogPriorityHydrationNotifier
import com.nexio.tv.data.local.TraktAuthDataStore
import com.nexio.tv.data.local.TraktAuthState
import com.nexio.tv.data.local.TraktCatalogIds
import com.nexio.tv.data.local.TraktCatalogPreferences
import com.nexio.tv.data.local.TraktSettingsDataStore
import com.nexio.tv.data.repository.TraktAuthService
import com.nexio.tv.data.repository.TraktDiscoveryService
import com.nexio.tv.data.repository.TraktDiscoverySnapshot
import com.nexio.tv.data.repository.TraktProgressService
import com.nexio.tv.data.repository.TraktScrobbleService
import com.nexio.tv.data.repository.TraktSettingsAuthGateway
import com.nexio.tv.testutil.testProfileManager
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
class TraktViewModelHomeRailOrderWriteThroughTest {
    private val dispatcher = StandardTestDispatcher()

    @Before fun setUp() { Dispatchers.setMain(dispatcher) }
    @After fun tearDown() { Dispatchers.resetMain() }

    @Test
    fun `moveCatalog routes through HomeRailOrderStore reorderProviderKeys`() = runTest(dispatcher) {
        val authService = mockk<TraktAuthService>(relaxed = true)
        every { authService.hasRequiredCredentials() } returns true
        val authDataStore = mockk<TraktAuthDataStore>()
        every { authDataStore.state } returns MutableStateFlow(TraktAuthState())
        val progressService = mockk<TraktProgressService>(relaxed = true)
        val discoveryService = mockk<TraktDiscoveryService>(relaxed = true)
        every { discoveryService.observeSnapshot() } returns MutableStateFlow(TraktDiscoverySnapshot())
        val scrobbleService = mockk<TraktScrobbleService>(relaxed = true)
        every { scrobbleService.observeWatchingNowState() } returns MutableStateFlow(
            TraktScrobbleService.WatchingNowState()
        )
        val settingsDataStore = mockk<TraktSettingsDataStore>(relaxed = true)
        val newOrder = listOf(
            TraktCatalogIds.TRENDING_SHOWS,
            TraktCatalogIds.TRENDING_MOVIES,
        ) + TraktCatalogIds.BUILT_IN_ORDER.filterNot {
            it == TraktCatalogIds.TRENDING_MOVIES || it == TraktCatalogIds.TRENDING_SHOWS
        }
        every { settingsDataStore.catalogPreferences } returns MutableStateFlow(
            TraktCatalogPreferences(catalogOrder = newOrder)
        )
        val notifier = CatalogPriorityHydrationNotifier()
        val homeRailOrderStore = mockk<HomeRailOrderStore>(relaxed = true)
        val context = mockk<Context>(relaxed = true)

        val viewModel = TraktViewModel(
            TraktSettingsAuthGateway(authService), authDataStore, progressService, discoveryService,
            scrobbleService, settingsDataStore, notifier, homeRailOrderStore,
            testProfileManager(), context
        )

        viewModel.onMoveCatalogDown(TraktCatalogIds.TRENDING_MOVIES)
        advanceUntilIdle()

        coVerify {
            homeRailOrderStore.reorderProviderKeys(
                family = RailFamily.TRAKT,
                providerOrder = newOrder.map(::HomeRailKey),
                source = RailOrderMutationSource.PROVIDER_SETTINGS_SCREEN,
            )
        }
    }
}
