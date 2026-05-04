package com.nexio.tv.ui.screens.settings

import com.nexio.tv.data.local.TheIntroDbSettings
import com.nexio.tv.data.local.TheIntroDbSettingsDataStore
import io.mockk.coEvery
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
    fun `show intro toggle persists`() = runTest(dispatcher) {
        val flow = MutableStateFlow(TheIntroDbSettings())
        val dataStore = mockk<TheIntroDbSettingsDataStore>(relaxed = true)
        every { dataStore.settings } returns flow
        coEvery { dataStore.setShowIntroButton(any()) } returns Unit

        val viewModel = TheIntroDbSettingsViewModel(dataStore)
        advanceUntilIdle()

        viewModel.setShowIntroButton(false)
        advanceUntilIdle()

        io.mockk.coVerify { dataStore.setShowIntroButton(false) }
    }
}
