package com.nexio.tv.ui.screens.addon

import android.content.Context
import android.content.res.Resources
import com.nexio.tv.data.local.LayoutPreferenceDataStore
import com.nexio.tv.domain.repository.AddonRepository
import io.mockk.coVerify
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
class AddonManagerViewModelAnimeToggleTest {

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
    fun `updateAddonIsAnime delegates to repository`() = runTest(dispatcher) {
        val addonRepository = mockk<AddonRepository>(relaxed = true)
        val layoutPreferenceDataStore = mockk<LayoutPreferenceDataStore>(relaxed = true)
        val context = mockk<Context>(relaxed = true)

        every { addonRepository.getInstalledAddons() } returns MutableStateFlow(emptyList())
        every { layoutPreferenceDataStore.homeCatalogOrderKeys } returns MutableStateFlow(emptyList())
        every { layoutPreferenceDataStore.disabledHomeCatalogKeys } returns MutableStateFlow(emptyList())
        every { context.resources } throws Resources.NotFoundException()

        val viewModel = AddonManagerViewModel(
            addonRepository = addonRepository,
            layoutPreferenceDataStore = layoutPreferenceDataStore,
            context = context
        )

        viewModel.updateAddonIsAnime("https://example.com/anime", true)
        advanceUntilIdle()

        coVerify(exactly = 1) {
            addonRepository.updateAddonIsAnime("https://example.com/anime", true)
        }
    }
}
