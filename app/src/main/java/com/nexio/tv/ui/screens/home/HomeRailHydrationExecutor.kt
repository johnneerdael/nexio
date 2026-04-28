package com.nexio.tv.ui.screens.home

import android.util.Log
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
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.supervisorScope

private const val HOME_RAIL_HYDRATION_TAG = "HomeRailHydration"

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

        supervisorScope {
            buildList {
                val tmdbCatalogIds = catalogIds.filter { it.startsWith("tmdb_") }.toSet()
                if (tmdbCatalogIds.isNotEmpty()) {
                    add(async {
                        refreshProviderGroup("tmdb") {
                            tmdbDiscoveryService.refreshCatalogs(
                                preferences = tmdbCatalogSettingsDataStore.catalogPreferences.first(),
                                force = true,
                                catalogIds = tmdbCatalogIds
                            )
                        }
                    })
                }

                val kitsuCatalogIds = catalogIds.filter { it.startsWith("kitsu_") }.toSet()
                if (kitsuCatalogIds.isNotEmpty()) {
                    add(async {
                        refreshProviderGroup("kitsu") {
                            kitsuDiscoveryService.refreshCatalogs(
                                preferences = kitsuCatalogSettingsDataStore.catalogPreferences.first(),
                                force = true,
                                catalogIds = kitsuCatalogIds
                            )
                        }
                    })
                }

                if (catalogIds.any { it.startsWith("trakt_") }) {
                    add(async {
                        refreshProviderGroup("trakt") {
                            traktDiscoveryService.ensureFresh(force = true, profileId = profileId)
                        }
                    })
                }
                if (catalogIds.any { it.startsWith("simkl_") }) {
                    add(async {
                        refreshProviderGroup("simkl") {
                            simklDiscoveryService.ensureFresh(force = true, profileId = profileId)
                        }
                    })
                }
                if (catalogIds.any { it.startsWith("mdblist_") }) {
                    add(async {
                        refreshProviderGroup("mdblist") {
                            mdbListDiscoveryService.ensureFresh(force = true, profileId = profileId)
                        }
                    })
                }
                if (rails.any { it.railKey == RailKeyFactory.continueWatching(profileId) }) {
                    add(async {
                        refreshProviderGroup("continue_watching") {
                            continueWatchingSnapshotService.ensureFresh(force = true)
                        }
                    })
                }
            }.awaitAll()
        }
    }

    private suspend fun refreshProviderGroup(
        providerGroup: String,
        block: suspend () -> Unit
    ) {
        try {
            block()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(HOME_RAIL_HYDRATION_TAG, "Active rail refresh failed for $providerGroup: ${e.message}", e)
        }
    }
}
