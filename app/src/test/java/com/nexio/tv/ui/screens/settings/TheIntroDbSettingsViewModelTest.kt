package com.nexio.tv.ui.screens.settings

import com.nexio.tv.data.local.TheIntroDbSettings
import com.nexio.tv.data.local.TheIntroDbSettingsDataStore
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.Assert.assertEquals

@OptIn(ExperimentalCoroutinesApi::class)
class TheIntroDbSettingsViewModelTest {
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
    fun `enabled settings without api key are immediately disabled`() = runTest(dispatcher) {
        val flow = MutableStateFlow(
            TheIntroDbSettings(
                enabled = true,
                apiKey = ""
            )
        )
        val dataStore = mockk<TheIntroDbSettingsDataStore>(relaxed = true)
        every { dataStore.settings } returns flow
        coEvery { dataStore.setEnabled(any()) } returns Unit

        TheIntroDbSettingsViewModel(dataStore)
        advanceUntilIdle()

        coVerify { dataStore.setEnabled(false) }
    }

    @Test
    fun `enabling without api key emits validation error and keeps disabled`() = runTest(dispatcher) {
        val flow = MutableStateFlow(TheIntroDbSettings(enabled = false, apiKey = ""))
        val dataStore = mockk<TheIntroDbSettingsDataStore>(relaxed = true)
        every { dataStore.settings } returns flow
        coEvery { dataStore.setEnabled(any()) } returns Unit

        val viewModel = TheIntroDbSettingsViewModel(dataStore)
        advanceUntilIdle()

        val error = async { viewModel.validationError.first() }
        viewModel.setEnabled(true)
        advanceUntilIdle()

        assertEquals(TheIntroDbValidationError.MissingApiKey, error.await())
        coVerify(atLeast = 1) { dataStore.setEnabled(false) }
    }

    @Test
    fun `enabling with api key stores enabled true`() = runTest(dispatcher) {
        val flow = MutableStateFlow(TheIntroDbSettings(enabled = false, apiKey = "tidb-key"))
        val dataStore = mockk<TheIntroDbSettingsDataStore>(relaxed = true)
        every { dataStore.settings } returns flow
        coEvery { dataStore.setEnabled(any()) } returns Unit

        val viewModel = TheIntroDbSettingsViewModel(dataStore)
        advanceUntilIdle()

        viewModel.setEnabled(true)
        advanceUntilIdle()

        coVerify { dataStore.setEnabled(true) }
    }

    @Test
    fun `clearing api key also disables integration`() = runTest(dispatcher) {
        val flow = MutableStateFlow(TheIntroDbSettings(enabled = true, apiKey = "tidb-key"))
        val dataStore = mockk<TheIntroDbSettingsDataStore>(relaxed = true)
        every { dataStore.settings } returns flow
        coEvery { dataStore.setApiKey(any()) } returns Unit
        coEvery { dataStore.setEnabled(any()) } returns Unit

        val viewModel = TheIntroDbSettingsViewModel(dataStore)
        advanceUntilIdle()

        viewModel.setApiKey("   ")
        advanceUntilIdle()

        coVerify { dataStore.setApiKey("   ") }
        coVerify { dataStore.setEnabled(false) }
    }
}
