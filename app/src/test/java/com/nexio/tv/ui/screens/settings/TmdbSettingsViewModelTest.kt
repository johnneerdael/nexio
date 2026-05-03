package com.nexio.tv.ui.screens.settings

import com.nexio.tv.data.local.TmdbCatalogIds
import com.nexio.tv.data.local.TmdbCatalogSettingsDataStore
import com.nexio.tv.data.local.TmdbSettingsDataStore
import com.nexio.tv.data.remote.api.TmdbApi
import com.nexio.tv.domain.model.TmdbSettings
import com.nexio.tv.testutil.profileDataStoreFactoryForTest
import com.nexio.tv.testutil.testProfileManager
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
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
class TmdbSettingsViewModelTest {
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
    fun `default ui state exposes tmdb catalog preferences`() = runTest(dispatcher) {
        val viewModel = createViewModel()

        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertTrue(TmdbCatalogIds.TRENDING_MOVIES in state.enabledCatalogKeys)
        assertTrue(TmdbCatalogIds.POPULAR_SERIES in state.enabledCatalogKeys)
        assertFalse(TmdbCatalogIds.YEAR_MOVIES in state.enabledCatalogKeys)
        assertFalse(state.includeAdult)
    }

    @Test
    fun `toggling catalog updates store and ui state`() = runTest(dispatcher) {
        val viewModel = createViewModel()

        advanceUntilIdle()
        viewModel.onEvent(TmdbSettingsEvent.ToggleCatalog(TmdbCatalogIds.POPULAR_MOVIES, enabled = true))
        advanceUntilIdle()

        assertTrue(TmdbCatalogIds.POPULAR_MOVIES in viewModel.uiState.value.enabledCatalogKeys)
    }

    @Test
    fun `toggling adult content updates ui state`() = runTest(dispatcher) {
        val viewModel = createViewModel()

        advanceUntilIdle()
        viewModel.onEvent(TmdbSettingsEvent.ToggleAdultContent(enabled = true))
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertTrue(state.includeAdult)
    }

    @Test
    fun `catalog preferences remain editable when metadata enrichment is inactive`() = runTest(dispatcher) {
        val viewModel = createViewModel(tmdbSettings = TmdbSettings(enabled = false))

        advanceUntilIdle()
        val inactiveState = viewModel.uiState.value
        assertFalse(inactiveState.isActive)
        assertTrue(inactiveState.catalogControlsEditable)
        assertTrue(TmdbCatalogIds.TRENDING_MOVIES in inactiveState.enabledCatalogKeys)
        assertTrue(TmdbCatalogIds.POPULAR_SERIES in inactiveState.enabledCatalogKeys)
        assertFalse(TmdbCatalogIds.YEAR_MOVIES in inactiveState.enabledCatalogKeys)

        viewModel.onEvent(TmdbSettingsEvent.ToggleCatalog(TmdbCatalogIds.YEAR_MOVIES, enabled = true))
        viewModel.onEvent(TmdbSettingsEvent.ToggleAdultContent(enabled = true))
        advanceUntilIdle()

        val updatedState = viewModel.uiState.value
        assertFalse(updatedState.isActive)
        assertTrue(TmdbCatalogIds.YEAR_MOVIES in updatedState.enabledCatalogKeys)
        assertTrue(updatedState.includeAdult)
    }

    private fun TestScope.createViewModel(tmdbSettings: TmdbSettings = TmdbSettings()): TmdbSettingsViewModel {
        val tmdbSettingsDataStore = mockk<TmdbSettingsDataStore> {
            every { settings } returns MutableStateFlow(tmdbSettings)
        }
        val catalogSettingsDataStore = TmdbCatalogSettingsDataStore(
            factory = profileDataStoreFactoryForTest(),
            profileManager = testProfileManager()
        )
        return TmdbSettingsViewModel(
            dataStore = tmdbSettingsDataStore,
            tmdbCatalogSettingsDataStore = catalogSettingsDataStore,
            tmdbApi = mockk<TmdbApi>(relaxed = true)
        )
    }
}
