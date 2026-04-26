package com.nexio.tv.ui.screens.settings

import com.nexio.tv.core.tvdb.TvdbAuthResult
import com.nexio.tv.core.tvdb.TvdbAuthService
import com.nexio.tv.core.tvdb.TvdbSettingsAuthGateway
import com.nexio.tv.core.tvdb.TvdbValidationStatus
import com.nexio.tv.data.local.TvdbDiagnosticsDataStore
import com.nexio.tv.data.local.TvdbDiagnosticsSnapshot
import com.nexio.tv.data.local.TvdbSettings
import com.nexio.tv.data.local.TvdbSettingsDataStore
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class TvdbSettingsViewModelTest {
    private val dispatcher = StandardTestDispatcher()

    /** Default empty diagnostics mock for tests that don't care about diagnostics. */
    private val emptyDiagnosticsDataStore = mockk<TvdbDiagnosticsDataStore>().also {
        every { it.snapshot } returns flowOf(TvdbDiagnosticsSnapshot())
    }

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `enabling TVDB with no API key keeps provider active through builtin access`() = runTest(dispatcher) {
        val settingsFlow = MutableStateFlow(TvdbSettings(enabled = false, apiKey = ""))
        val dataStore = mockk<TvdbSettingsDataStore>(relaxed = true)
        val authService = mockk<TvdbAuthService>()
        every { dataStore.settings } returns settingsFlow

        val viewModel = TvdbSettingsViewModel(dataStore = dataStore, authService = TvdbSettingsAuthGateway(authService), diagnosticsDataStore = emptyDiagnosticsDataStore)
        advanceUntilIdle()

        viewModel.onEvent(TvdbSettingsEvent.ToggleEnabled(true))
        advanceUntilIdle()

        coVerify(exactly = 1) { dataStore.setEnabled(true) }
        coVerify(exactly = 0) { dataStore.setEnabled(false) }
    }

    @Test
    fun `saveCredentials validates through auth service and closes dialog only on success`() = runTest(dispatcher) {
        val settingsFlow = MutableStateFlow(TvdbSettings(enabled = false, apiKey = ""))
        val dataStore = mockk<TvdbSettingsDataStore>(relaxed = true)
        val authService = mockk<TvdbAuthService>()
        val validation = CompletableDeferred<Boolean>()
        var successCount = 0
        every { dataStore.settings } returns settingsFlow
        coEvery { authService.validateCredentialsResult("tvdb-key", "subscriber-pin") } coAnswers {
            validation.await()
            TvdbAuthResult.Valid(
                authorizationHeader = "Bearer tvdb-token",
                expiresAtEpochMillis = 1_700_000_000_000L
            )
        }

        val viewModel = TvdbSettingsViewModel(dataStore = dataStore, authService = TvdbSettingsAuthGateway(authService), diagnosticsDataStore = emptyDiagnosticsDataStore)
        advanceUntilIdle()

        viewModel.saveCredentials(
            apiKey = " tvdb-key ",
            subscriberPin = " subscriber-pin ",
            onSuccess = { successCount++ }
        )
        runCurrent()

        assertEquals(TvdbValidationStatus.VALIDATING, viewModel.uiState.value.validationStatus)

        validation.complete(true)
        advanceUntilIdle()

        assertEquals(1, successCount)
        assertEquals(TvdbValidationStatus.VALID, viewModel.uiState.value.validationStatus)
        assertEquals("••••••-key", viewModel.uiState.value.credentialDisplayValue)
        coVerify(exactly = 1) { authService.validateCredentialsResult("tvdb-key", "subscriber-pin") }
    }

    @Test
    fun `invalid custom credentials set INVALID status but keep builtin provider active`() = runTest(dispatcher) {
        val settingsFlow = MutableStateFlow(TvdbSettings(enabled = true, apiKey = "old-key"))
        val dataStore = mockk<TvdbSettingsDataStore>(relaxed = true)
        val authService = mockk<TvdbAuthService>()
        var successCount = 0
        every { dataStore.settings } returns settingsFlow
        coEvery { authService.validateCredentialsResult("tvdb-key", "subscriber-pin") } returns TvdbAuthResult.InvalidCredentials(
            lastFailure = "Invalid TVDB credentials"
        )

        val viewModel = TvdbSettingsViewModel(dataStore = dataStore, authService = TvdbSettingsAuthGateway(authService), diagnosticsDataStore = emptyDiagnosticsDataStore)
        advanceUntilIdle()

        viewModel.saveCredentials(
            apiKey = " tvdb-key ",
            subscriberPin = " subscriber-pin ",
            onSuccess = { successCount++ }
        )
        advanceUntilIdle()

        assertEquals(0, successCount)
        assertEquals(TvdbValidationStatus.INVALID, viewModel.uiState.value.validationStatus)
        assertEquals("Invalid TVDB credentials", viewModel.uiState.value.lastFailure)
        assertTrue(viewModel.uiState.value.isProviderActive)
        assertEquals(TvdbValidationError.InvalidCredentials, viewModel.validationError.first())
        coVerify(exactly = 0) { dataStore.setEnabled(false) }
    }

    @Test
    fun `configured fallback active settings remain enabled while observed`() = runTest(dispatcher) {
        val settingsFlow = MutableStateFlow(
            TvdbSettings(
                enabled = true,
                apiKey = "tvdb-key",
                subscriberPin = "subscriber-pin",
                validationStatus = TvdbValidationStatus.FALLBACK_ACTIVE,
                lastFailure = "TVDB login failed with HTTP 500"
            )
        )
        val dataStore = mockk<TvdbSettingsDataStore>(relaxed = true)
        val authService = mockk<TvdbAuthService>()
        every { dataStore.settings } returns settingsFlow

        val viewModel = TvdbSettingsViewModel(dataStore = dataStore, authService = TvdbSettingsAuthGateway(authService), diagnosticsDataStore = emptyDiagnosticsDataStore)
        advanceUntilIdle()

        assertTrue(viewModel.uiState.value.enabled)
        assertEquals(TvdbValidationStatus.FALLBACK_ACTIVE, viewModel.uiState.value.validationStatus)
        assertTrue(viewModel.uiState.value.isProviderActive)
        coVerify(exactly = 0) { dataStore.setEnabled(false) }
    }

    @Test
    fun `clearCredentials clears local credentials and cached token state`() = runTest(dispatcher) {
        val settingsFlow = MutableStateFlow(
            TvdbSettings(
                enabled = true,
                apiKey = "tvdb-key-1234",
                subscriberPin = "subscriber-pin",
                validationStatus = TvdbValidationStatus.VALID
            )
        )
        val dataStore = mockk<TvdbSettingsDataStore>(relaxed = true)
        val authService = mockk<TvdbAuthService>()
        every { dataStore.settings } returns settingsFlow

        val viewModel = TvdbSettingsViewModel(dataStore = dataStore, authService = TvdbSettingsAuthGateway(authService), diagnosticsDataStore = emptyDiagnosticsDataStore)
        advanceUntilIdle()

        viewModel.clearCredentials()
        advanceUntilIdle()

        coVerify(exactly = 1) { dataStore.clearCredentials() }
        assertTrue(viewModel.uiState.value.enabled)
        assertEquals(TvdbValidationStatus.VALID, viewModel.uiState.value.validationStatus)
        assertTrue(viewModel.uiState.value.isProviderActive)
    }

    @Test
    fun `credential display masks API key and never includes PIN`() = runTest(dispatcher) {
        val settingsFlow = MutableStateFlow(
            TvdbSettings(
                apiKey = "tvdb-api-key-1234",
                subscriberPin = "subscriber-pin",
                validationStatus = TvdbValidationStatus.VALID
            )
        )
        val dataStore = mockk<TvdbSettingsDataStore>(relaxed = true)
        val authService = mockk<TvdbAuthService>()
        every { dataStore.settings } returns settingsFlow

        val viewModel = TvdbSettingsViewModel(dataStore = dataStore, authService = TvdbSettingsAuthGateway(authService), diagnosticsDataStore = emptyDiagnosticsDataStore)
        advanceUntilIdle()

        assertEquals("••••••1234", viewModel.uiState.value.credentialDisplayValue)
        assertFalse(viewModel.uiState.value.credentialDisplayValue.contains("tvdb-api-key"))
        assertFalse(viewModel.uiState.value.credentialDisplayValue.contains("subscriber-pin"))
    }

    @Test
    fun `credential display uses Not set when API key is blank`() = runTest(dispatcher) {
        val settingsFlow = MutableStateFlow(TvdbSettings(apiKey = "", subscriberPin = "subscriber-pin"))
        val dataStore = mockk<TvdbSettingsDataStore>(relaxed = true)
        val authService = mockk<TvdbAuthService>()
        every { dataStore.settings } returns settingsFlow

        val viewModel = TvdbSettingsViewModel(dataStore = dataStore, authService = TvdbSettingsAuthGateway(authService), diagnosticsDataStore = emptyDiagnosticsDataStore)
        advanceUntilIdle()

        assertEquals("Not set", viewModel.uiState.value.credentialDisplayValue)
    }

    @Test
    fun `settings status shows invalid credentials stale cache and refresh failures`() = runTest(dispatcher) {
        val settingsFlow = MutableStateFlow(
            TvdbSettings(enabled = true, apiKey = "tvdb-key", validationStatus = TvdbValidationStatus.VALID)
        )
        val dataStore = mockk<TvdbSettingsDataStore>(relaxed = true)
        val authService = mockk<TvdbAuthService>()
        every { dataStore.settings } returns settingsFlow

        val diagnosticsDataStore = mockk<TvdbDiagnosticsDataStore>()
        every { diagnosticsDataStore.snapshot } returns flowOf(
            TvdbDiagnosticsSnapshot(
                lastInvalidCredentialStatus = "invalid_credentials",
                lastStaleCacheStatus = "stale_cache_served",
                lastUpdateRefreshStatus = "update_refresh_failed",
                lastReferenceRefreshStatus = "reference_refresh_failed"
            )
        )

        val viewModel = TvdbSettingsViewModel(
            dataStore = dataStore,
            authService = TvdbSettingsAuthGateway(authService),
            diagnosticsDataStore = diagnosticsDataStore
        )
        advanceUntilIdle()

        assertNotNull(viewModel.uiState.value.tvdbStatusLine)
    }
}
