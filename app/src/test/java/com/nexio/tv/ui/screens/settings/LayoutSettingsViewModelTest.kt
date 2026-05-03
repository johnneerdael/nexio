package com.nexio.tv.ui.screens.settings

import com.nexio.tv.data.local.DebugSettingsDataStore
import com.nexio.tv.data.local.LayoutPreferenceDataStore
import com.nexio.tv.domain.model.FocusedPosterTrailerPlaybackTarget
import com.nexio.tv.domain.model.HomeLayout
import com.nexio.tv.domain.repository.AddonRepository
import com.nexio.tv.domain.repository.MetaRepository
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
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class LayoutSettingsViewModelTest {

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
    fun `trailer screensaver state mirrors stored preference`() = runTest(dispatcher) {
        val trailerScreensaverEnabled = MutableStateFlow(false)
        val viewModel = createViewModel(trailerScreensaverEnabled)

        advanceUntilIdle()
        assertFalse(viewModel.uiState.value.trailerScreensaverEnabled)

        trailerScreensaverEnabled.value = true
        advanceUntilIdle()

        assertTrue(viewModel.uiState.value.trailerScreensaverEnabled)
    }

    @Test
    fun `trailer screensaver event persists through data store`() = runTest(dispatcher) {
        val layoutPreferenceDataStore = mockk<LayoutPreferenceDataStore>(relaxed = true)
        val trailerScreensaverEnabled = MutableStateFlow(false)

        val viewModel = createViewModel(
            trailerScreensaverEnabled = trailerScreensaverEnabled,
            layoutPreferenceDataStore = layoutPreferenceDataStore
        )

        viewModel.onEvent(LayoutSettingsEvent.SetTrailerScreensaverEnabled(true))
        advanceUntilIdle()

        coVerify(exactly = 1) { layoutPreferenceDataStore.setTrailerScreensaverEnabled(true) }
    }

    private fun createViewModel(
        trailerScreensaverEnabled: MutableStateFlow<Boolean>,
        layoutPreferenceDataStore: LayoutPreferenceDataStore = mockk(relaxed = true),
        debugSettingsDataStore: DebugSettingsDataStore = mockk(relaxed = true),
        addonRepository: AddonRepository = mockk(relaxed = true),
        metaRepository: MetaRepository = mockk(relaxed = true)
    ): LayoutSettingsViewModel {
        stubLayoutPreferences(layoutPreferenceDataStore, trailerScreensaverEnabled)
        every { debugSettingsDataStore.diskFirstHomeStartupEnabled } returns flowOf(true)
        every { addonRepository.getInstalledAddons() } returns flowOf(emptyList<com.nexio.tv.domain.model.Addon>())

        return LayoutSettingsViewModel(
            layoutPreferenceDataStore = layoutPreferenceDataStore,
            debugSettingsDataStore = debugSettingsDataStore,
            addonRepository = addonRepository,
            metaRepository = metaRepository
        )
    }

    private fun stubLayoutPreferences(
        layoutPreferenceDataStore: LayoutPreferenceDataStore,
        trailerScreensaverEnabled: MutableStateFlow<Boolean>
    ) {
        every { layoutPreferenceDataStore.selectedLayout } returns flowOf(HomeLayout.MODERN)
        every { layoutPreferenceDataStore.hasChosenLayout } returns flowOf(false)
        every { layoutPreferenceDataStore.heroCatalogSelections } returns flowOf(emptyList<String>())
        every { layoutPreferenceDataStore.modernLandscapePostersEnabled } returns flowOf(false)
        every { layoutPreferenceDataStore.heroSectionEnabled } returns flowOf(true)
        every { layoutPreferenceDataStore.searchDiscoverEnabled } returns flowOf(true)
        every { layoutPreferenceDataStore.posterLabelsEnabled } returns flowOf(true)
        every { layoutPreferenceDataStore.catalogAddonNameEnabled } returns flowOf(true)
        every { layoutPreferenceDataStore.catalogTypeSuffixEnabled } returns flowOf(true)
        every { layoutPreferenceDataStore.focusedPosterBackdropExpandEnabled } returns flowOf(false)
        every { layoutPreferenceDataStore.focusedPosterBackdropExpandDelaySeconds } returns flowOf(3)
        every { layoutPreferenceDataStore.focusedPosterBackdropTrailerEnabled } returns flowOf(false)
        every { layoutPreferenceDataStore.focusedPosterBackdropTrailerMuted } returns flowOf(true)
        every { layoutPreferenceDataStore.focusedPosterBackdropTrailerPlaybackTarget } returns
            flowOf(FocusedPosterTrailerPlaybackTarget.HERO_MEDIA)
        every { layoutPreferenceDataStore.posterCardWidthDp } returns flowOf(126)
        every { layoutPreferenceDataStore.posterCardHeightDp } returns flowOf(189)
        every { layoutPreferenceDataStore.posterCardCornerRadiusDp } returns flowOf(12)
        every { layoutPreferenceDataStore.blurUnwatchedEpisodes } returns flowOf(false)
        every { layoutPreferenceDataStore.detailPageTrailerButtonEnabled } returns flowOf(false)
        every { layoutPreferenceDataStore.preferExternalMetaAddonDetail } returns flowOf(false)
        every { layoutPreferenceDataStore.trailerScreensaverEnabled } returns trailerScreensaverEnabled
        every { layoutPreferenceDataStore.screensaverDelaySeconds } returns flowOf(300)
    }
}
