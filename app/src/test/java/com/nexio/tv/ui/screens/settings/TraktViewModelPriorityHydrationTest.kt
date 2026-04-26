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
import io.mockk.coEvery
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
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class TraktViewModelPriorityHydrationTest {
    private val dispatcher = StandardTestDispatcher()

    @Before fun setUp() { Dispatchers.setMain(dispatcher) }
    @After fun tearDown() { Dispatchers.resetMain() }

    private fun buildViewModel(
        notifier: CatalogPriorityHydrationNotifier,
        discoveryServiceOverride: TraktDiscoveryService? = null
    ): TraktViewModel {
        val authService = mockk<TraktAuthService>(relaxed = true)
        val authDataStore = mockk<TraktAuthDataStore>()
        val progressService = mockk<TraktProgressService>(relaxed = true)
        val discoveryService = discoveryServiceOverride ?: mockk<TraktDiscoveryService>(relaxed = true)
        val scrobbleService = mockk<TraktScrobbleService>(relaxed = true)
        val settingsDataStore = mockk<TraktSettingsDataStore>(relaxed = true)
        val context = mockk<Context>(relaxed = true)

        every { authService.hasRequiredCredentials() } returns false
        every { authDataStore.state } returns MutableStateFlow(TraktAuthState())
        every { settingsDataStore.catalogPreferences } returns MutableStateFlow(TraktCatalogPreferences())
        every { discoveryService.observeSnapshot() } returns MutableStateFlow(TraktDiscoverySnapshot())
        every { scrobbleService.observeWatchingNowState() } returns MutableStateFlow(
            TraktScrobbleService.WatchingNowState()
        )

        return TraktViewModel(
            TraktSettingsAuthGateway(authService), authDataStore, progressService, discoveryService,
            scrobbleService, settingsDataStore, notifier, testProfileManager(), context
        )
    }

    @Test
    fun `onCatalogEnabledChanged with enabled true fires priority hydration notifier`() = runTest(dispatcher) {
        val notifier = CatalogPriorityHydrationNotifier()
        val viewModel = buildViewModel(notifier)

        val events = mutableListOf<Long>()
        val job = launch { notifier.events.collect { events.add(it) } }

        viewModel.onCatalogEnabledChanged(TraktCatalogIds.TRENDING_MOVIES, enabled = true)
        advanceUntilIdle()

        assertTrue("Notifier should fire when enabling catalog", events.isNotEmpty())
        job.cancel()
    }

    @Test
    fun `onCatalogEnabledChanged with enabled false does not fire priority hydration notifier`() = runTest(dispatcher) {
        val notifier = CatalogPriorityHydrationNotifier()
        val viewModel = buildViewModel(notifier)

        val events = mutableListOf<Long>()
        val job = launch { notifier.events.collect { events.add(it) } }

        viewModel.onCatalogEnabledChanged(TraktCatalogIds.TRENDING_MOVIES, enabled = false)
        advanceUntilIdle()

        assertTrue("Notifier should NOT fire when disabling catalog", events.isEmpty())
        job.cancel()
    }

    @Test
    fun `onPopularListSelected with selected true fires priority hydration notifier`() = runTest(dispatcher) {
        val notifier = CatalogPriorityHydrationNotifier()
        val viewModel = buildViewModel(notifier)

        val events = mutableListOf<Long>()
        val job = launch { notifier.events.collect { events.add(it) } }

        viewModel.onPopularListSelected(listKey = "some-list-key", selected = true)
        advanceUntilIdle()

        assertTrue("Notifier should fire when selecting a popular list", events.isNotEmpty())
        job.cancel()
    }

    @Test
    fun `onPopularListSelected with selected false does not fire priority hydration notifier`() = runTest(dispatcher) {
        val notifier = CatalogPriorityHydrationNotifier()
        val viewModel = buildViewModel(notifier)

        val events = mutableListOf<Long>()
        val job = launch { notifier.events.collect { events.add(it) } }

        viewModel.onPopularListSelected(listKey = "some-list-key", selected = false)
        advanceUntilIdle()

        assertTrue("Notifier should NOT fire when deselecting a popular list", events.isEmpty())
        job.cancel()
    }

    @Test
    fun `onCatalogManagementOpened forces discovery refresh so personal lists are current`() = runTest(dispatcher) {
        val notifier = CatalogPriorityHydrationNotifier()
        val discoveryService = mockk<TraktDiscoveryService>(relaxed = true)
        val viewModel = buildViewModel(notifier = notifier, discoveryServiceOverride = discoveryService)

        viewModel.onCatalogManagementOpened()
        advanceUntilIdle()

        coVerify(exactly = 1) {
            discoveryService.ensureFresh(force = true, profileId = 1)
        }
    }
}
