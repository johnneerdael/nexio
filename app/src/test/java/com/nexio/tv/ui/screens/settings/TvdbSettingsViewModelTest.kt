package com.nexio.tv.ui.screens.settings

import com.nexio.tv.core.tvdb.TvdbAuthResult
import com.nexio.tv.core.tvdb.TvdbAuthService
import com.nexio.tv.core.tvdb.TvdbValidationStatus
import com.nexio.tv.data.local.TvdbSettings
import com.nexio.tv.data.local.TvdbSettingsDataStore
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
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
class TvdbSettingsViewModelTest {
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
    fun `enabling TVDB with no API key keeps provider inactive and emits MissingApiKey`() = runTest(dispatcher) {
        val settingsFlow = MutableStateFlow(TvdbSettings(enabled = false, apiKey = ""))
        val dataStore = mockk<TvdbSettingsDataStore>(relaxed = true)
        val authService = mockk<TvdbAuthService>()
        every { dataStore.settings } returns settingsFlow

        val viewModel = TvdbSettingsViewModel(dataStore = dataStore, authService = authService)
        advanceUntilIdle()

        viewModel.onEvent(TvdbSettingsEvent.ToggleEnabled(true))
        advanceUntilIdle()

        assertFalse(viewModel.uiState.value.isProviderActive)
        assertEquals(TvdbValidationError.MissingApiKey, viewModel.validationError.first())
        coVerify(exactly = 1) { dataStore.setEnabled(false) }
    }

    @Test
    fun `invalid credentials set INVALID status with last failure and do not enable provider use`() = runTest(dispatcher) {
        val settingsFlow = MutableStateFlow(TvdbSettings(enabled = false, apiKey = ""))
        val dataStore = mockk<TvdbSettingsDataStore>(relaxed = true)
        val authService = mockk<TvdbAuthService>()
        every { dataStore.settings } returns settingsFlow
        coEvery {
            authService.loginAndCacheToken("tvdb-key", "subscriber-pin")
        } returns TvdbAuthResult.InvalidCredentials("Invalid credentials")

        val viewModel = TvdbSettingsViewModel(dataStore = dataStore, authService = authService)
        advanceUntilIdle()

        viewModel.validateAndSaveCredentials(
            apiKey = " tvdb-key ",
            pin = " subscriber-pin "
        )
        advanceUntilIdle()

        assertEquals(TvdbValidationStatus.INVALID, viewModel.uiState.value.validationStatus)
        assertEquals("Invalid credentials", viewModel.uiState.value.lastFailure)
        assertFalse(viewModel.uiState.value.isProviderActive)
        coVerify(exactly = 1) {
            dataStore.saveValidationFailure(
                status = TvdbValidationStatus.INVALID,
                lastFailure = "Invalid credentials"
            )
        }
        coVerify(exactly = 0) { dataStore.setEnabled(true) }
    }

    @Test
    fun `successful validation sets VALID masks API key outside dialog never exposes PIN and shows Fallback active copy`() =
        runTest(dispatcher) {
            val settingsFlow = MutableStateFlow(TvdbSettings(enabled = false, apiKey = ""))
            val dataStore = mockk<TvdbSettingsDataStore>(relaxed = true)
            val authService = mockk<TvdbAuthService>()
            every { dataStore.settings } returns settingsFlow
            coEvery {
                authService.loginAndCacheToken("tvdb-key", "subscriber-pin")
            } returns TvdbAuthResult.Valid(
                authorizationHeader = "Bearer tvdb-token",
                expiresAtEpochMillis = 1_702_592_000_000L
            )

            val viewModel = TvdbSettingsViewModel(dataStore = dataStore, authService = authService)
            advanceUntilIdle()

            viewModel.validateAndSaveCredentials(
                apiKey = " tvdb-key ",
                pin = " subscriber-pin "
            )
            advanceUntilIdle()

            assertEquals(TvdbValidationStatus.VALID, viewModel.uiState.value.validationStatus)
            assertTrue(viewModel.uiState.value.maskedApiKey.endsWith("key"))
            assertFalse(viewModel.uiState.value.maskedApiKey.contains("tvdb-key"))
            assertFalse(viewModel.uiState.value.pinDisplayText.contains("subscriber-pin"))
            assertTrue(viewModel.uiState.value.providerPrecedenceCopy.contains("Fallback active"))
            coVerify(exactly = 1) {
                dataStore.saveCredentials(
                    apiKey = "tvdb-key",
                    pin = "subscriber-pin",
                    validationStatus = TvdbValidationStatus.VALID
                )
            }
        }
}
