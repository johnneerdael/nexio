package com.nexio.tv.ui.screens.home

import com.nexio.tv.core.integration.RailKeyFactory
import com.nexio.tv.data.local.KitsuCatalogSettingsDataStore
import com.nexio.tv.data.local.TmdbCatalogSettingsDataStore
import com.nexio.tv.data.local.integration.RailCacheEntity
import com.nexio.tv.data.repository.ContinueWatchingSnapshotService
import com.nexio.tv.data.repository.KitsuDiscoveryService
import com.nexio.tv.data.repository.MDBListDiscoveryService
import com.nexio.tv.data.repository.SimklDiscoveryService
import com.nexio.tv.data.repository.TmdbDiscoveryService
import com.nexio.tv.data.repository.TraktDiscoveryService
import com.nexio.tv.core.profile.ProfileManager
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.first

interface HomeRailHydrationExecutor {
    suspend fun hydrate(rails: List<RailCacheEntity>)
}

object NoOpHomeRailHydrationExecutor : HomeRailHydrationExecutor {
    override suspend fun hydrate(rails: List<RailCacheEntity>) = Unit
}

@Singleton
class DefaultHomeRailHydrationExecutor @Inject constructor(
    private val tmdbDiscoveryService: TmdbDiscoveryService,
    private val tmdbCatalogSettingsDataStore: TmdbCatalogSettingsDataStore,
    private val kitsuDiscoveryService: KitsuDiscoveryService,
    private val kitsuCatalogSettingsDataStore: KitsuCatalogSettingsDataStore,
    private val traktDiscoveryService: TraktDiscoveryService,
    private val simklDiscoveryService: SimklDiscoveryService,
    private val mdbListDiscoveryService: MDBListDiscoveryService,
    private val continueWatchingSnapshotService: ContinueWatchingSnapshotService,
    private val profileManager: ProfileManager
) : HomeRailHydrationExecutor {
    override suspend fun hydrate(rails: List<RailCacheEntity>) {
        if (rails.isEmpty()) return

        val profileId = profileManager.activeProfileId.value
        val homeNamespace = RailKeyFactory.homeCatalogNamespace(profileId)
        val catalogIds = rails.mapNotNull { rail ->
            rail.railKey.removePrefix(homeNamespace)
                .takeIf { it != rail.railKey }
        }

        val tmdbCatalogIds = catalogIds.filter { it.startsWith("tmdb_") }.toSet()
        if (tmdbCatalogIds.isNotEmpty()) {
            tmdbDiscoveryService.refreshCatalogs(
                preferences = tmdbCatalogSettingsDataStore.catalogPreferences.first(),
                force = true,
                catalogIds = tmdbCatalogIds
            )
        }

        val kitsuCatalogIds = catalogIds.filter { it.startsWith("kitsu_") }.toSet()
        if (kitsuCatalogIds.isNotEmpty()) {
            kitsuDiscoveryService.refreshCatalogs(
                preferences = kitsuCatalogSettingsDataStore.catalogPreferences.first(),
                force = true,
                catalogIds = kitsuCatalogIds
            )
        }

        if (catalogIds.any { it.startsWith("trakt_") }) {
            traktDiscoveryService.ensureFresh(force = true, profileId = profileId)
        }
        if (catalogIds.any { it.startsWith("simkl_") }) {
            simklDiscoveryService.ensureFresh(force = true, profileId = profileId)
        }
        if (catalogIds.any { it.startsWith("mdblist_") }) {
            mdbListDiscoveryService.ensureFresh(force = true, profileId = profileId)
        }
        if (rails.any { it.railKey == RailKeyFactory.continueWatching(profileId) }) {
            continueWatchingSnapshotService.ensureFresh(force = true)
        }
    }
}
