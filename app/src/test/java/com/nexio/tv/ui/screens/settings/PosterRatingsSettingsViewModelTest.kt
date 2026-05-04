package com.nexio.tv.ui.screens.settings

import android.content.Context
import com.nexio.tv.core.artwork.ArtworkDecisionCache
import com.nexio.tv.core.artwork.PremiumArtworkInvalidationNotifier
import com.nexio.tv.core.integration.IntegrationOwnershipService
import com.nexio.tv.core.integration.RailKeyFactory
import com.nexio.tv.core.profile.ProfileManager
import com.nexio.tv.data.local.HydratedHomeOverlayStore
import com.nexio.tv.data.local.HomeCatalogSnapshotStore
import com.nexio.tv.data.local.MetadataDiskCacheStore
import com.nexio.tv.data.local.PosterRatingsSettingsDataStore
import com.nexio.tv.data.repository.ProviderSettingsRepository
import com.nexio.tv.domain.model.PosterRatingsSettings
import com.nexio.tv.domain.repository.CatalogRepository
import com.nexio.tv.domain.repository.MetaRepository
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.async
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
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class PosterRatingsSettingsViewModelTest {
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
    fun `enabling Top Posters invalidates artwork display state without clearing primary metadata caches`() = runTest(dispatcher) {
        val fixture = Fixture()
        val viewModel = fixture.createViewModel()
        val invalidationEvent = async { fixture.premiumArtworkInvalidationNotifier.events.first() }

        viewModel.onEvent(PosterRatingsSettingsEvent.ToggleTopPosters(true))
        advanceUntilIdle()
        invalidationEvent.await()

        coVerify(exactly = 1) {
            fixture.integrationOwnershipService.syncRails(
                RailKeyFactory.homeCatalogNamespace(7),
                emptyList()
            )
        }
        verify(exactly = 1) { fixture.homeCatalogSnapshotStore.clear(profileId = 7) }
        coVerify(exactly = 1) { fixture.hydratedHomeOverlayStore.clearAll() }
        verify(exactly = 1) { fixture.artworkDecisionCache.invalidatePremiumArtworkPolicy() }
        fixture.verifyPrimaryMetadataCachesNotCleared()
    }

    @Test
    fun `switching Top Posters to RPDB invalidates artwork display state without clearing primary metadata caches`() = runTest(dispatcher) {
        val fixture = Fixture(
            initialSettings = PosterRatingsSettings(
                topPostersEnabled = true,
                topPostersApiKey = "top-key"
            )
        )
        val viewModel = fixture.createViewModel()

        viewModel.onEvent(PosterRatingsSettingsEvent.ToggleRpdb(true))
        advanceUntilIdle()

        coVerify(exactly = 1) {
            fixture.integrationOwnershipService.syncRails(
                RailKeyFactory.homeCatalogNamespace(7),
                emptyList()
            )
        }
        verify(exactly = 1) { fixture.homeCatalogSnapshotStore.clear(profileId = 7) }
        coVerify(exactly = 1) { fixture.hydratedHomeOverlayStore.clearAll() }
        verify(exactly = 1) { fixture.artworkDecisionCache.invalidatePremiumArtworkPolicy() }
        fixture.verifyPrimaryMetadataCachesNotCleared()
    }

    @Test
    fun `RPDB API key changes invalidate artwork display state without clearing primary metadata caches`() = runTest(dispatcher) {
        val fixture = Fixture()
        val viewModel = fixture.createViewModel()
        coEvery { fixture.providerSettingsRepository.validateRpdbApiKey("rpdb-key") } returns true

        viewModel.validateAndSaveRpdbApiKey(" rpdb-key ") {}
        advanceUntilIdle()

        coVerify(exactly = 1) {
            fixture.integrationOwnershipService.syncRails(
                RailKeyFactory.homeCatalogNamespace(7),
                emptyList()
            )
        }
        verify(exactly = 1) { fixture.homeCatalogSnapshotStore.clear(profileId = 7) }
        coVerify(exactly = 1) { fixture.hydratedHomeOverlayStore.clearAll() }
        verify(exactly = 1) { fixture.artworkDecisionCache.invalidatePremiumArtworkPolicy() }
        fixture.verifyPrimaryMetadataCachesNotCleared()
    }

    @Test
    fun `Top Posters API key changes invalidate artwork display state without clearing primary metadata caches`() = runTest(dispatcher) {
        val fixture = Fixture()
        val viewModel = fixture.createViewModel()
        coEvery { fixture.providerSettingsRepository.validateTopPostersApiKey("top-key") } returns true

        viewModel.validateAndSaveTopPostersApiKey(" top-key ") {}
        advanceUntilIdle()

        coVerify(exactly = 1) {
            fixture.integrationOwnershipService.syncRails(
                RailKeyFactory.homeCatalogNamespace(7),
                emptyList()
            )
        }
        verify(exactly = 1) { fixture.homeCatalogSnapshotStore.clear(profileId = 7) }
        coVerify(exactly = 1) { fixture.hydratedHomeOverlayStore.clearAll() }
        verify(exactly = 1) { fixture.artworkDecisionCache.invalidatePremiumArtworkPolicy() }
        fixture.verifyPrimaryMetadataCachesNotCleared()
    }

    private class Fixture(
        initialSettings: PosterRatingsSettings = PosterRatingsSettings()
    ) {
        private val settings = MutableStateFlow(initialSettings)
        val dataStore = mockk<PosterRatingsSettingsDataStore> {
            every { settings } returns this@Fixture.settings
            coEvery { setRpdbEnabled(any()) } coAnswers {
                val enabled = firstArg<Boolean>()
                this@Fixture.settings.value = this@Fixture.settings.value.copy(
                    rpdbEnabled = enabled,
                    topPostersEnabled = if (enabled) false else this@Fixture.settings.value.topPostersEnabled
                )
            }
            coEvery { setTopPostersEnabled(any()) } coAnswers {
                val enabled = firstArg<Boolean>()
                this@Fixture.settings.value = this@Fixture.settings.value.copy(
                    topPostersEnabled = enabled,
                    rpdbEnabled = if (enabled) false else this@Fixture.settings.value.rpdbEnabled
                )
            }
            coEvery { setRpdbApiKey(any()) } coAnswers {
                this@Fixture.settings.value = this@Fixture.settings.value.copy(rpdbApiKey = firstArg<String>().trim())
            }
            coEvery { setTopPostersApiKey(any()) } coAnswers {
                this@Fixture.settings.value = this@Fixture.settings.value.copy(topPostersApiKey = firstArg<String>().trim())
            }
        }
        val providerSettingsRepository = mockk<ProviderSettingsRepository>(relaxed = true)
        val metadataDiskCacheStore = mockk<MetadataDiskCacheStore>(relaxed = true)
        val homeCatalogSnapshotStore = mockk<HomeCatalogSnapshotStore>(relaxed = true)
        val hydratedHomeOverlayStore = mockk<HydratedHomeOverlayStore>(relaxed = true)
        val profileManager = mockk<ProfileManager> {
            every { activeProfileId } returns MutableStateFlow(7)
        }
        val integrationOwnershipService = mockk<IntegrationOwnershipService>(relaxed = true)
        val artworkDecisionCache = mockk<ArtworkDecisionCache>(relaxed = true)
        val premiumArtworkInvalidationNotifier = PremiumArtworkInvalidationNotifier()
        val metaRepository = mockk<MetaRepository>(relaxed = true)
        val catalogRepository = mockk<CatalogRepository>(relaxed = true)

        fun createViewModel(): PosterRatingsSettingsViewModel =
            PosterRatingsSettingsViewModel(
                appContext = mockk<Context>(relaxed = true),
                dataStore = dataStore,
                providerSettingsRepository = providerSettingsRepository,
                metadataDiskCacheStore = metadataDiskCacheStore,
                homeCatalogSnapshotStore = homeCatalogSnapshotStore,
                hydratedHomeOverlayStore = hydratedHomeOverlayStore,
                profileManager = profileManager,
                integrationOwnershipService = integrationOwnershipService,
                artworkDecisionCache = artworkDecisionCache,
                premiumArtworkInvalidationNotifier = premiumArtworkInvalidationNotifier,
                metaRepository = metaRepository,
                catalogRepository = catalogRepository
            )

        fun verifyPrimaryMetadataCachesNotCleared() {
            verify(exactly = 0) { metadataDiskCacheStore.clearAll() }
            verify(exactly = 0) { metaRepository.clearCache() }
            verify(exactly = 0) { catalogRepository.clearCache() }
        }
    }
}
