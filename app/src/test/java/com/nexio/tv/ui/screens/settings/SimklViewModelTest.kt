package com.nexio.tv.ui.screens.settings

import com.nexio.tv.core.sync.CatalogPriorityHydrationNotifier
import com.nexio.tv.data.local.SimklAuthDataStore
import com.nexio.tv.data.local.SimklAuthState
import com.nexio.tv.data.local.SimklSettingsDataStore
import com.nexio.tv.data.repository.SimklAuthService
import com.nexio.tv.data.repository.SimklSettingsAuthGateway
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
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class SimklViewModelTest {
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
    fun `authenticated state updates ui to connected`() = runTest(dispatcher) {
        val authState = MutableStateFlow(
            SimklAuthState(
                accessToken = "token",
                username = "alice",
                accountType = "vip"
            )
        )
        val service = mockk<SimklAuthService>()
        val dataStore = mockk<SimklAuthDataStore>()
        val settingsDataStore = mockk<SimklSettingsDataStore>()
        every { service.hasRequiredCredentials() } returns true
        every { dataStore.state } returns authState
        every { settingsDataStore.catalogPreferences } returns MutableStateFlow(com.nexio.tv.data.local.SimklCatalogPreferences())

        val viewModel = SimklViewModel(SimklSettingsAuthGateway(service), dataStore, settingsDataStore, mockk(relaxed = true), testProfileManager())
        advanceUntilIdle()

        assertEquals(SimklConnectionMode.CONNECTED, viewModel.uiState.value.mode)
        assertEquals("alice", viewModel.uiState.value.username)
        assertEquals("vip", viewModel.uiState.value.accountType)
    }

    @Test
    fun `connect click without credentials exposes error`() = runTest(dispatcher) {
        val authState = MutableStateFlow(SimklAuthState())
        val service = mockk<SimklAuthService>()
        val dataStore = mockk<SimklAuthDataStore>(relaxed = true)
        val settingsDataStore = mockk<SimklSettingsDataStore>()
        every { service.hasRequiredCredentials() } returns false
        every { dataStore.state } returns authState
        every { settingsDataStore.catalogPreferences } returns MutableStateFlow(com.nexio.tv.data.local.SimklCatalogPreferences())

        val viewModel = SimklViewModel(SimklSettingsAuthGateway(service), dataStore, settingsDataStore, mockk(relaxed = true), testProfileManager())
        viewModel.onConnectClick()
        advanceUntilIdle()

        assertEquals(SimklConnectionMode.DISCONNECTED, viewModel.uiState.value.mode)
        assertFalse(viewModel.uiState.value.credentialsConfigured)
        assertTrue(viewModel.uiState.value.errorMessage?.contains("SIMKL_CLIENT_ID") == true)
    }

    @Test
    fun `disconnect clears auth through service`() = runTest(dispatcher) {
        val authState = MutableStateFlow(SimklAuthState(accessToken = "token"))
        val service = mockk<SimklAuthService>()
        val dataStore = mockk<SimklAuthDataStore>(relaxed = true)
        val settingsDataStore = mockk<SimklSettingsDataStore>()
        every { service.hasRequiredCredentials() } returns true
        every { dataStore.state } returns authState
        every { settingsDataStore.catalogPreferences } returns MutableStateFlow(com.nexio.tv.data.local.SimklCatalogPreferences())
        coEvery { service.revokeAndLogout() } returns Unit

        val viewModel = SimklViewModel(SimklSettingsAuthGateway(service), dataStore, settingsDataStore, mockk(relaxed = true), testProfileManager())
        viewModel.onDisconnectClick()
        advanceUntilIdle()

        coVerify(exactly = 1) { service.revokeAndLogout() }
    }

    @Test
    fun `onCatalogEnabledChanged with enabled true fires priority hydration notifier`() = runTest(dispatcher) {
        val notifier = CatalogPriorityHydrationNotifier()
        val authState = MutableStateFlow(SimklAuthState())
        val service = mockk<SimklAuthService>()
        val dataStore = mockk<SimklAuthDataStore>()
        val settingsDataStore = mockk<SimklSettingsDataStore>(relaxed = true)
        every { service.hasRequiredCredentials() } returns true
        every { dataStore.state } returns authState
        every { settingsDataStore.catalogPreferences } returns MutableStateFlow(com.nexio.tv.data.local.SimklCatalogPreferences())

        val viewModel = SimklViewModel(SimklSettingsAuthGateway(service), dataStore, settingsDataStore, notifier, testProfileManager())

        val events = mutableListOf<Long>()
        val job = launch { notifier.events.collect { events.add(it) } }

        viewModel.onCatalogEnabledChanged(com.nexio.tv.data.local.SimklCatalogIds.TV_TRENDING_TODAY, enabled = true)
        advanceUntilIdle()

        assertTrue(events.isNotEmpty())
        job.cancel()
    }

    @Test
    fun `onCatalogEnabledChanged with enabled false does not fire priority hydration notifier`() = runTest(dispatcher) {
        val notifier = CatalogPriorityHydrationNotifier()
        val authState = MutableStateFlow(SimklAuthState())
        val service = mockk<SimklAuthService>()
        val dataStore = mockk<SimklAuthDataStore>()
        val settingsDataStore = mockk<SimklSettingsDataStore>(relaxed = true)
        every { service.hasRequiredCredentials() } returns true
        every { dataStore.state } returns authState
        every { settingsDataStore.catalogPreferences } returns MutableStateFlow(com.nexio.tv.data.local.SimklCatalogPreferences())

        val viewModel = SimklViewModel(SimklSettingsAuthGateway(service), dataStore, settingsDataStore, notifier, testProfileManager())

        val events = mutableListOf<Long>()
        val job = launch { notifier.events.collect { events.add(it) } }

        viewModel.onCatalogEnabledChanged(com.nexio.tv.data.local.SimklCatalogIds.TV_TRENDING_TODAY, enabled = false)
        advanceUntilIdle()

        assertTrue(events.isEmpty())
        job.cancel()
    }
}
