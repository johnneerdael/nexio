package com.nexio.tv.ui.screens.home

import com.nexio.tv.core.integration.RailKeyFactory
import com.nexio.tv.data.local.KitsuCatalogPreferences
import com.nexio.tv.data.local.KitsuCatalogSettingsDataStore
import com.nexio.tv.data.local.TmdbCatalogPreferences
import com.nexio.tv.data.local.TmdbCatalogSettingsDataStore
import com.nexio.tv.data.local.integration.RailCacheEntity
import com.nexio.tv.data.repository.ContinueWatchingSnapshotService
import com.nexio.tv.data.repository.KitsuDiscoveryService
import com.nexio.tv.data.repository.MDBListDiscoveryService
import com.nexio.tv.data.repository.SimklDiscoveryService
import com.nexio.tv.data.repository.TmdbDiscoveryService
import com.nexio.tv.data.repository.TraktDiscoveryService
import com.nexio.tv.core.profile.ProfileManager
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Test

class HomeRailHydrationExecutorTest {
    @Test
    fun `executor refreshes only providers represented by planned home rails`() = runTest {
        val tmdbDiscovery = mockk<TmdbDiscoveryService>(relaxed = true)
        val kitsuDiscovery = mockk<KitsuDiscoveryService>(relaxed = true)
        val traktDiscovery = mockk<TraktDiscoveryService>(relaxed = true)
        val simklDiscovery = mockk<SimklDiscoveryService>(relaxed = true)
        val mdbListDiscovery = mockk<MDBListDiscoveryService>(relaxed = true)
        val continueWatching = mockk<ContinueWatchingSnapshotService>(relaxed = true)
        val tmdbSettings = mockk<TmdbCatalogSettingsDataStore>()
        val kitsuSettings = mockk<KitsuCatalogSettingsDataStore>()
        val profileManager = mockk<ProfileManager> {
            every { activeProfileId } returns MutableStateFlow(7)
            every { profileSwitched } returns MutableSharedFlow(extraBufferCapacity = 1)
        }
        every { tmdbSettings.catalogPreferences } returns flowOf(
            TmdbCatalogPreferences(enabledCatalogs = setOf("tmdb_trending_movies"))
        )
        every { kitsuSettings.catalogPreferences } returns flowOf(
            KitsuCatalogPreferences(enabledCatalogs = setOf("kitsu_trending_anime"))
        )

        val executor = DefaultHomeRailHydrationExecutor(
            tmdbDiscoveryService = tmdbDiscovery,
            tmdbCatalogSettingsDataStore = tmdbSettings,
            kitsuDiscoveryService = kitsuDiscovery,
            kitsuCatalogSettingsDataStore = kitsuSettings,
            traktDiscoveryService = traktDiscovery,
            simklDiscoveryService = simklDiscovery,
            mdbListDiscoveryService = mdbListDiscovery,
            continueWatchingSnapshotService = continueWatching,
            profileManager = profileManager
        )

        executor.hydrate(
            listOf(
                RailCacheEntity(
                    railKey = RailKeyFactory.homeCatalog(7, "tmdb_trending_movies"),
                    provider = "TMDB",
                    kind = "MOVIE",
                    paramsHash = "tmdb",
                    fetchedAtEpochMs = 0L,
                    expiresAtEpochMs = 0L,
                    staleUntilEpochMs = 0L
                ),
                RailCacheEntity(
                    railKey = RailKeyFactory.homeCatalog(7, "kitsu_trending_anime"),
                    provider = "KITSU",
                    kind = "SERIES",
                    paramsHash = "kitsu",
                    fetchedAtEpochMs = 0L,
                    expiresAtEpochMs = 0L,
                    staleUntilEpochMs = 0L
                )
            )
        )

        coVerify(exactly = 1) {
            tmdbDiscovery.refreshCatalogs(any(), force = true, catalogIds = setOf("tmdb_trending_movies"))
        }
        coVerify(exactly = 1) {
            kitsuDiscovery.refreshCatalogs(any(), force = true, catalogIds = setOf("kitsu_trending_anime"))
        }
        coVerify(exactly = 0) { traktDiscovery.ensureFresh(any(), any()) }
        coVerify(exactly = 0) { simklDiscovery.ensureFresh(any(), any()) }
        coVerify(exactly = 0) { mdbListDiscovery.ensureFresh(any(), any()) }
        coVerify(exactly = 0) { continueWatching.ensureFresh(any()) }
    }
}
