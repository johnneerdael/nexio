package com.nexio.tv.ui.screens.settings

import android.content.Context
import com.nexio.tv.core.artwork.ArtworkDecisionCache
import com.nexio.tv.core.artwork.ArtworkType
import com.nexio.tv.core.artwork.PremiumArtworkInvalidationNotifier
import com.nexio.tv.core.integration.IntegrationOwnershipService
import com.nexio.tv.core.integration.RailKeyFactory
import com.nexio.tv.core.profile.ProfileManager
import com.nexio.tv.data.local.HydratedHomeOverlayStore
import com.nexio.tv.data.local.HomeCatalogSnapshotStore
import com.nexio.tv.data.local.MetadataDiskCacheStore
import com.nexio.tv.data.local.PosterRatingsSettingsDataStore
import com.nexio.tv.data.repository.ProviderSettingsRepository
import com.nexio.tv.domain.model.ArtworkProviderChoiceKey
import com.nexio.tv.domain.model.ArtworkProviderSelectionSettings
import com.nexio.tv.domain.model.ArtworkProviderSettings
import com.nexio.tv.domain.model.ArtworkTypeKey
import com.nexio.tv.domain.model.PosterRatingsSettings
import com.nexio.tv.domain.model.TopPostersEntitlementSnapshot
import com.nexio.tv.domain.model.toArtworkProviderSettings
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
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
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
    fun `ui contract exposes provider selectors without toggle semantics`() = runTest(dispatcher) {
        val fixture = Fixture()
        val viewModel = fixture.createViewModel()
        advanceUntilIdle()

        val fieldNames = PosterRatingsSettingsUiState::class.java.declaredFields.map { it.name }.toSet()
        assertFalse(fieldNames.contains("rpdbEnabled"))
        assertFalse(fieldNames.contains("topPostersEnabled"))

        val eventNames = PosterRatingsSettingsEvent::class.java.declaredClasses.map { it.simpleName }.toSet()
        assertFalse(eventNames.contains("ToggleRpdb"))
        assertFalse(eventNames.contains("ToggleTopPosters"))

        val uiState = viewModel.uiState.value
        assertEquals(ArtworkProviderChoiceKey.DEFAULT, uiState.posterProvider)
        assertEquals(ArtworkProviderChoiceKey.DEFAULT, uiState.logoProvider)
        assertEquals(ArtworkProviderChoiceKey.DEFAULT, uiState.backdropProvider)
        assertEquals(ArtworkProviderChoiceKey.DEFAULT, uiState.thumbnailProvider)
    }

    @Test
    fun `API keys configure available provider choices by artwork type`() = runTest(dispatcher) {
        val fixture = Fixture(
            initialSettings = ArtworkProviderSettings(
                rpdbApiKey = "rpdb-key",
                topPostersApiKey = "top-key",
                selection = ArtworkProviderSelectionSettings(
                    posterProvider = ArtworkProviderChoiceKey.RPDB
                )
            )
        )
        val viewModel = fixture.createViewModel()
        advanceUntilIdle()

        val uiState = viewModel.uiState.value
        assertEquals(ArtworkProviderChoiceKey.RPDB, uiState.posterProvider)
        assertEquals(
            listOf(
                ArtworkProviderChoiceKey.DEFAULT,
                ArtworkProviderChoiceKey.TOP_POSTERS,
                ArtworkProviderChoiceKey.RPDB
            ),
            uiState.availableChoicesFor(ArtworkType.POSTER)
        )
        assertEquals(listOf(ArtworkProviderChoiceKey.DEFAULT), uiState.availableChoicesFor(ArtworkType.LOGO))
        assertEquals(listOf(ArtworkProviderChoiceKey.DEFAULT), uiState.availableChoicesFor(ArtworkType.BACKDROP))
        assertEquals(listOf(ArtworkProviderChoiceKey.DEFAULT), uiState.availableChoicesFor(ArtworkType.THUMBNAIL))
    }

    @Test
    fun `Top Posters premium entitlement exposes thumbnail choice`() = runTest(dispatcher) {
        val fixture = Fixture(
            initialSettings = ArtworkProviderSettings(
                topPostersApiKey = "top-key",
                selection = ArtworkProviderSelectionSettings(
                    thumbnailProvider = ArtworkProviderChoiceKey.TOP_POSTERS
                ),
                topPostersEntitlement = premiumTopPostersEntitlement()
            )
        )
        val viewModel = fixture.createViewModel()
        advanceUntilIdle()

        val uiState = viewModel.uiState.value
        assertEquals(ArtworkProviderChoiceKey.TOP_POSTERS, uiState.thumbnailProvider)
        assertEquals(
            listOf(ArtworkProviderChoiceKey.DEFAULT, ArtworkProviderChoiceKey.TOP_POSTERS),
            uiState.availableChoicesFor(ArtworkType.THUMBNAIL)
        )
        assertEquals("Premium", uiState.topPostersEntitlementLabel)
        assertTrue(uiState.topPostersThumbnailAvailable)
    }

    @Test
    fun `setting provider selection invalidates artwork display state without clearing primary metadata caches`() = runTest(dispatcher) {
        val fixture = Fixture()
        val viewModel = fixture.createViewModel()
        val invalidationEvent = async { fixture.premiumArtworkInvalidationNotifier.events.first() }

        viewModel.onEvent(
            PosterRatingsSettingsEvent.SetProviderSelection(
                type = ArtworkTypeKey.THUMBNAIL,
                provider = ArtworkProviderChoiceKey.TOP_POSTERS
            )
        )
        advanceUntilIdle()
        invalidationEvent.await()

        coVerify(exactly = 1) {
            fixture.dataStore.setProviderSelection(ArtworkTypeKey.THUMBNAIL, ArtworkProviderChoiceKey.TOP_POSTERS)
        }
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
    fun `Top Posters validation saves entitlement and invalidates artwork display state without clearing primary metadata caches`() = runTest(dispatcher) {
        val fixture = Fixture()
        val viewModel = fixture.createViewModel()
        val snapshot = premiumTopPostersEntitlement()
        coEvery { fixture.providerSettingsRepository.validateTopPostersApiKey("top-key") } returns snapshot

        viewModel.validateAndSaveTopPostersApiKey(" top-key ") {}
        advanceUntilIdle()

        coVerify(exactly = 1) { fixture.dataStore.setTopPostersApiKey("top-key") }
        coVerify(exactly = 1) { fixture.dataStore.setTopPostersEntitlement(snapshot) }
        assertEquals("Premium", viewModel.uiState.value.topPostersEntitlementLabel)
        assertTrue(viewModel.uiState.value.topPostersThumbnailAvailable)
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
    fun `blank Top Posters API key clears entitlement`() = runTest(dispatcher) {
        val fixture = Fixture()
        val viewModel = fixture.createViewModel()

        viewModel.validateAndSaveTopPostersApiKey("   ") {}
        advanceUntilIdle()

        coVerify(exactly = 1) { fixture.dataStore.setTopPostersApiKey("") }
        coVerify(exactly = 1) { fixture.dataStore.setTopPostersEntitlement(null) }
    }

    @Test
    fun `failed Top Posters API key validation clears stale entitlement`() = runTest(dispatcher) {
        val fixture = Fixture()
        val viewModel = fixture.createViewModel()
        coEvery { fixture.providerSettingsRepository.validateTopPostersApiKey("invalid-key") } returns null

        viewModel.validateAndSaveTopPostersApiKey(" invalid-key ") {}
        advanceUntilIdle()

        coVerify(exactly = 0) { fixture.dataStore.setTopPostersApiKey(any()) }
        coVerify(exactly = 1) { fixture.dataStore.setTopPostersEntitlement(null) }
    }

    private fun premiumTopPostersEntitlement(): TopPostersEntitlementSnapshot =
        TopPostersEntitlementSnapshot(
            valid = true,
            isActive = true,
            tier = 1,
            tierName = "Premium",
            episodeThumbnails = true,
            verifiedAtMs = 1_700_000_000_000L,
            expiresAtMs = System.currentTimeMillis() + 86_400_000L
        )

    private class Fixture(
        initialSettings: ArtworkProviderSettings = PosterRatingsSettings().toArtworkProviderSettings()
    ) {
        private val settings = MutableStateFlow(initialSettings)
        val dataStore = mockk<PosterRatingsSettingsDataStore> {
            every { settings } returns this@Fixture.settings
            coEvery { setProviderSelection(any(), any()) } coAnswers {
                this@Fixture.settings.value = this@Fixture.settings.value.copy(
                    selection = this@Fixture.settings.value.selection.withProvider(
                        type = firstArg(),
                        provider = ArtworkProviderChoiceKey(secondArg<String>())
                    )
                )
            }
            coEvery { setRpdbApiKey(any()) } coAnswers {
                this@Fixture.settings.value = this@Fixture.settings.value.copy(rpdbApiKey = firstArg<String>().trim())
            }
            coEvery { setTopPostersApiKey(any()) } coAnswers {
                this@Fixture.settings.value = this@Fixture.settings.value.copy(topPostersApiKey = firstArg<String>().trim())
            }
            coEvery { setTopPostersEntitlement(any()) } coAnswers {
                this@Fixture.settings.value = this@Fixture.settings.value.copy(
                    topPostersEntitlement = firstArg<TopPostersEntitlementSnapshot?>()
                )
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
