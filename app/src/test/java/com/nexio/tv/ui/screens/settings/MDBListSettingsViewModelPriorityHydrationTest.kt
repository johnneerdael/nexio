package com.nexio.tv.ui.screens.settings

import com.nexio.tv.core.sync.CatalogPriorityHydrationNotifier
import com.nexio.tv.data.local.MDBListCatalogPreferences
import com.nexio.tv.data.local.MDBListSettingsDataStore
import com.nexio.tv.data.repository.MDBListDiscoveryService
import com.nexio.tv.data.repository.MDBListDiscoverySnapshot
import com.nexio.tv.data.repository.ProviderSettingsRepository
import com.nexio.tv.domain.model.MDBListSettings
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
class MDBListSettingsViewModelPriorityHydrationTest {
    private val dispatcher = StandardTestDispatcher()

    @Before fun setUp() { Dispatchers.setMain(dispatcher) }
    @After fun tearDown() { Dispatchers.resetMain() }

    private fun buildViewModel(notifier: CatalogPriorityHydrationNotifier): MDBListSettingsViewModel {
        val dataStore = mockk<MDBListSettingsDataStore>(relaxed = true)
        val providerSettingsRepository = mockk<ProviderSettingsRepository>(relaxed = true)
        val discoveryService = mockk<MDBListDiscoveryService>(relaxed = true)

        every { dataStore.settings } returns MutableStateFlow(MDBListSettings())
        every { dataStore.catalogPreferences } returns MutableStateFlow(MDBListCatalogPreferences())
        every { discoveryService.observeSnapshot() } returns MutableStateFlow(MDBListDiscoverySnapshot())

        return MDBListSettingsViewModel(dataStore, providerSettingsRepository, discoveryService, notifier)
    }

    @Test
    fun `TogglePersonalList with enabled true fires priority hydration notifier`() = runTest(dispatcher) {
        val notifier = CatalogPriorityHydrationNotifier()
        val viewModel = buildViewModel(notifier)

        val events = mutableListOf<Long>()
        val job = launch { notifier.events.collect { events.add(it) } }

        viewModel.onEvent(MDBListSettingsEvent.TogglePersonalList(listKey = "mylist", enabled = true))
        advanceUntilIdle()

        assertTrue("Notifier should fire when enabling a personal list", events.isNotEmpty())
        job.cancel()
    }

    @Test
    fun `TogglePersonalList with enabled false does not fire priority hydration notifier`() = runTest(dispatcher) {
        val notifier = CatalogPriorityHydrationNotifier()
        val viewModel = buildViewModel(notifier)

        val events = mutableListOf<Long>()
        val job = launch { notifier.events.collect { events.add(it) } }

        viewModel.onEvent(MDBListSettingsEvent.TogglePersonalList(listKey = "mylist", enabled = false))
        advanceUntilIdle()

        assertTrue("Notifier should NOT fire when disabling a personal list", events.isEmpty())
        job.cancel()
    }

    @Test
    fun `ToggleTopList with enabled true fires priority hydration notifier`() = runTest(dispatcher) {
        val notifier = CatalogPriorityHydrationNotifier()
        val viewModel = buildViewModel(notifier)

        val events = mutableListOf<Long>()
        val job = launch { notifier.events.collect { events.add(it) } }

        viewModel.onEvent(MDBListSettingsEvent.ToggleTopList(listKey = "top250", enabled = true))
        advanceUntilIdle()

        assertTrue("Notifier should fire when enabling a top list", events.isNotEmpty())
        job.cancel()
    }

    @Test
    fun `ToggleTopList with enabled false does not fire priority hydration notifier`() = runTest(dispatcher) {
        val notifier = CatalogPriorityHydrationNotifier()
        val viewModel = buildViewModel(notifier)

        val events = mutableListOf<Long>()
        val job = launch { notifier.events.collect { events.add(it) } }

        viewModel.onEvent(MDBListSettingsEvent.ToggleTopList(listKey = "top250", enabled = false))
        advanceUntilIdle()

        assertTrue("Notifier should NOT fire when disabling a top list", events.isEmpty())
        job.cancel()
    }
}
