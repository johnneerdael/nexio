package com.nexio.tv.ui.screens.settings

import android.content.Context
import com.nexio.tv.R
import com.nexio.tv.data.local.MDBListCatalogPreferences
import com.nexio.tv.data.local.MDBListSettingsDataStore
import com.nexio.tv.data.local.TraktAuthDataStore
import com.nexio.tv.data.local.TraktAuthState
import com.nexio.tv.data.local.TraktCatalogPreferences
import com.nexio.tv.data.local.TraktSettingsDataStore
import com.nexio.tv.data.repository.MDBListDiscoveryService
import com.nexio.tv.data.repository.MDBListDiscoverySnapshot
import com.nexio.tv.data.repository.ProviderSettingsRepository
import com.nexio.tv.data.repository.TraktDiscoveryService
import com.nexio.tv.data.repository.TraktDiscoverySnapshot
import com.nexio.tv.data.repository.TraktProgressService
import com.nexio.tv.data.repository.TraktAuthService
import com.nexio.tv.data.repository.TraktScrobbleService
import com.nexio.tv.data.repository.TraktSettingsAuthGateway
import com.nexio.tv.domain.model.MDBListSettings
import com.nexio.tv.testutil.testProfileManager
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class CatalogSelectionPersistenceTest {

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
    fun `trakt popular list selection persists and refreshes discovery`() = runTest(dispatcher) {
        val traktAuthService = mockk<TraktAuthService>(relaxed = true)
        every { traktAuthService.hasRequiredCredentials() } returns true

        val traktAuthDataStore = mockk<TraktAuthDataStore>()
        every {
            traktAuthDataStore.state
        } returns flowOf(TraktAuthState(accessToken = "token", refreshToken = "refresh"))

        val traktSettingsDataStore = mockk<TraktSettingsDataStore>(relaxed = true)
        every {
            traktSettingsDataStore.catalogPreferences
        } returns flowOf(TraktCatalogPreferences())

        val traktDiscoveryService = mockk<TraktDiscoveryService>(relaxed = true)
        every {
            traktDiscoveryService.observeSnapshot(any())
        } returns flowOf(TraktDiscoverySnapshot())

        val traktScrobbleService = mockk<TraktScrobbleService>()
        every {
            traktScrobbleService.observeWatchingNowState()
        } returns MutableStateFlow(TraktScrobbleService.WatchingNowState())

        val progressService = mockk<TraktProgressService>(relaxed = true)
        val context = mockk<Context>()
        every { context.getString(R.string.trakt_catalogs_updated) } returns "Catalogs updated"

        val viewModel = TraktViewModel(
            traktAuthService = TraktSettingsAuthGateway(traktAuthService),
            traktAuthDataStore = traktAuthDataStore,
            traktProgressService = progressService,
            traktDiscoveryService = traktDiscoveryService,
            traktScrobbleService = traktScrobbleService,
            traktSettingsDataStore = traktSettingsDataStore,
            catalogPriorityHydrationNotifier = mockk(relaxed = true),
            profileManager = testProfileManager(),
            context = context
        )

        viewModel.onPopularListSelected(listKey = "popular:anime", selected = true)
        advanceUntilIdle()

        coVerify(exactly = 1) {
            traktSettingsDataStore.setPopularListSelected("popular:anime", true)
        }
    }

    @Test
    fun `mdblist top list selection persists and refreshes discovery`() = runTest(dispatcher) {
        val dataStore = mockk<MDBListSettingsDataStore>(relaxed = true)
        every { dataStore.settings } returns flowOf(MDBListSettings(enabled = true, apiKey = "api-key"))
        every {
            dataStore.catalogPreferences
        } returns flowOf(MDBListCatalogPreferences())

        val discoveryService = mockk<MDBListDiscoveryService>(relaxed = true)
        every {
            discoveryService.observeSnapshot()
        } returns flowOf(MDBListDiscoverySnapshot())

        val viewModel = MDBListSettingsViewModel(
            dataStore = dataStore,
            providerSettingsRepository = mockk<ProviderSettingsRepository>(relaxed = true),
            mdbListDiscoveryService = discoveryService,
            catalogPriorityHydrationNotifier = mockk(relaxed = true)
        )

        viewModel.onEvent(MDBListSettingsEvent.ToggleTopList(listKey = "top:thrillers", enabled = true))
        advanceUntilIdle()

        coVerify(exactly = 1) {
            dataStore.setTopListSelected("top:thrillers", true)
        }
    }
}
