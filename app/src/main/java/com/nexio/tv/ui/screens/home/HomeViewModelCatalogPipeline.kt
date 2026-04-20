package com.nexio.tv.ui.screens.home

import android.util.Log
import com.nexio.tv.data.local.MDBListCatalogPreferences
import com.nexio.tv.data.local.PersistedSyntheticCatalogGroup
import com.nexio.tv.data.local.SimklCatalogIds
import com.nexio.tv.data.local.SimklCatalogPreferences
import androidx.lifecycle.viewModelScope
import com.nexio.tv.core.network.NetworkResult
import com.nexio.tv.data.local.SyntheticHomeCatalogStore
import com.nexio.tv.data.local.TmdbCatalogPreferences
import com.nexio.tv.data.local.TraktCatalogIds
import com.nexio.tv.data.local.TraktCatalogPreferences
import com.nexio.tv.data.repository.MDBListCustomCatalog
import com.nexio.tv.data.repository.TraktCustomListCatalog
import com.nexio.tv.domain.model.Addon
import com.nexio.tv.domain.model.CatalogDescriptor
import com.nexio.tv.domain.model.CatalogRow
import com.nexio.tv.domain.model.ContentType
import com.nexio.tv.domain.model.HomeLayout
import com.nexio.tv.domain.model.MetaPreview
import com.nexio.tv.domain.model.PosterShape
import com.nexio.tv.domain.model.skipStep
import com.nexio.tv.domain.model.supportsExtra
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.supervisorScope
import com.nexio.tv.core.util.filterReleasedItems
import kotlinx.coroutines.withContext
import java.time.LocalDate
import java.util.concurrent.atomic.AtomicInteger

private data class CatalogUpdateResult(
    val displayRows: List<CatalogRow>,
    val heroItems: List<com.nexio.tv.domain.model.MetaPreview>,
    val gridItems: List<GridItem>,
    val fullRows: List<CatalogRow>,
    val orderedGroupKeys: List<String>,
    val truncatedCache: Map<String, HomeViewModel.TruncatedRowCacheEntry>,
    val orderDiagnosticsSignature: String,
    val orderDiagnosticsMessage: String
)

private data class SyntheticCatalogOrderGroup(
    val orderKey: String,
    val rows: List<CatalogRow>
)

private const val TRAKT_RAIL_ADDON_ID = "trakt"
private const val TRAKT_RAIL_ADDON_NAME = "Trakt"
private const val TRAKT_RAIL_ADDON_BASE_URL = "https://api.trakt.tv"

private const val TRAKT_ROW_NAME_UP_NEXT = "Trakt Up Next"
private const val TRAKT_ROW_NAME_TRENDING_MOVIES = "Trakt Trending Movies"
private const val TRAKT_ROW_NAME_TRENDING_SHOWS = "Trakt Trending Shows"
private const val TRAKT_ROW_NAME_POPULAR_MOVIES = "Trakt Popular Movies"
private const val TRAKT_ROW_NAME_POPULAR_SHOWS = "Trakt Popular Shows"
private const val TRAKT_ROW_NAME_RECOMMENDED_MOVIES = "Trakt Recommended Movies"
private const val TRAKT_ROW_NAME_RECOMMENDED_SHOWS = "Trakt Recommended Shows"
private const val TRAKT_ROW_NAME_CALENDAR = "Trakt Calendar (Next 7 Days)"

private const val MDBLIST_RAIL_ADDON_ID = "mdblist"
private const val MDBLIST_RAIL_ADDON_NAME = "MDBList"
private const val MDBLIST_RAIL_ADDON_BASE_URL = "https://api.mdblist.com"

private const val SIMKL_RAIL_ADDON_ID = "simkl"
private const val SIMKL_RAIL_ADDON_NAME = "SIMKL"
private const val SIMKL_RAIL_ADDON_BASE_URL = "https://data.simkl.in"

private const val SIMKL_ROW_NAME_TV_TRENDING_TODAY = "SIMKL Trending TV (Today)"
private const val SIMKL_ROW_NAME_TV_TRENDING_WEEK = "SIMKL Trending TV (Week)"
private const val SIMKL_ROW_NAME_TV_TRENDING_MONTH = "SIMKL Trending TV (Month)"
private const val SIMKL_ROW_NAME_ANIME_TRENDING_TODAY = "SIMKL Trending Anime (Today)"
private const val SIMKL_ROW_NAME_ANIME_TRENDING_WEEK = "SIMKL Trending Anime (Week)"
private const val SIMKL_ROW_NAME_ANIME_TRENDING_MONTH = "SIMKL Trending Anime (Month)"
private const val SIMKL_ROW_NAME_MOVIE_TRENDING_TODAY = "SIMKL Trending Movies (Today)"
private const val SIMKL_ROW_NAME_MOVIE_TRENDING_WEEK = "SIMKL Trending Movies (Week)"
private const val SIMKL_ROW_NAME_MOVIE_TRENDING_MONTH = "SIMKL Trending Movies (Month)"
private const val SIMKL_ROW_NAME_DVD_RELEASES = "SIMKL Popular DVD Releases"

private const val TMDB_RAIL_ADDON_ID = "tmdb"

internal fun HomeViewModel.restorePersistedCatalogSnapshotPipeline() {
    viewModelScope.launch(Dispatchers.IO) {
        val profileId = profileManager.activeProfileId.value
        val posterToken = homeCatalogSnapshotStore.currentPosterProviderToken()
        val snapshot = homeCatalogSnapshotStore.read(posterToken, profileId = profileId)
        if (snapshot == null) {
            Log.d(HomeViewModel.TAG, "Restored merged home snapshot null")
            return@launch
        }
        Log.d(
            HomeViewModel.TAG,
            "Restored merged home snapshot rows=${snapshot.catalogRows.size} fullRows=${snapshot.fullCatalogRows.size} " +
                "hero=${snapshot.heroItems.size} orderedKeys=${snapshot.orderedGroupKeys.size}"
        )
        if (snapshot.catalogRows.isEmpty() && snapshot.fullCatalogRows.isEmpty() && snapshot.heroItems.isEmpty()) {
            return@launch
        }

        withContext(Dispatchers.Main.immediate) {
            val hasRenderedContent = _uiState.value.catalogRows.any { it.items.isNotEmpty() } ||
                _uiState.value.heroItems.isNotEmpty()
            if (hasRenderedContent) {
                return@withContext
            }

            hasPersistedCatalogSnapshot = true
            startupRefreshPending = true
            restoredCatalogSnapshotActive = true
            inMemoryHomeSnapshot = snapshot
            pendingRestoredCatalogSnapshot = snapshot
            pendingHomeSnapshotPersist = null
            applyPendingPersistedHomeSnapshotIfPossiblePipeline("restore_merged_snapshot")
        }
    }
}

internal fun HomeViewModel.restorePersistedSyntheticCatalogRowsPipeline() {
    viewModelScope.launch(Dispatchers.IO) {
        val profileId = profileManager.activeProfileId.value
        val snapshot = syntheticHomeCatalogStore.read(profileId = profileId)
        if (snapshot == null) {
            Log.d(HomeViewModel.TAG, "Restored synthetic snapshot null")
            return@launch
        }
        val providerState = trackingProviderStateService.currentState()
        Log.d(
            HomeViewModel.TAG,
            "Restored synthetic snapshot traktGroups=${snapshot.traktGroups.size} traktRows=${snapshot.traktGroups.sumOf { it.rows.size }} " +
                "simklGroups=${snapshot.simklGroups.size} simklRows=${snapshot.simklGroups.sumOf { it.rows.size }} " +
                "mdbGroups=${snapshot.mdbListGroups.size} mdbRows=${snapshot.mdbListGroups.sumOf { it.rows.size }} " +
                "tmdbGroups=${snapshot.tmdbGroups.size} tmdbRows=${snapshot.tmdbGroups.sumOf { it.rows.size }} " +
                "traktAuthenticated=${providerState.traktAuthenticated}"
        )
        withContext(Dispatchers.Main.immediate) {
            persistedTraktSyntheticGroups = if (providerState.traktAuthenticated) snapshot.traktGroups else emptyList()
            persistedSimklSyntheticGroups = snapshot.simklGroups
            persistedMDBListSyntheticGroups = snapshot.mdbListGroups
            applyPersistedTmdbSyntheticSnapshot(snapshot)
            applyPendingPersistedHomeSnapshotIfPossiblePipeline("restore_synthetic_snapshot")
        }
    }
}

internal fun HomeViewModel.resetProfileScopedHomeState(reason: String) {
    Log.d(HomeViewModel.TAG, "Resetting profile-scoped home state reason=$reason")
    cancelInFlightCatalogLoads()
    deferredStartupRefreshJob?.cancel()
    deferredStartupRefreshJob = null
    pendingSerializedHomeRefreshReason = null
    catalogUpdateJob?.cancel()
    continueWatchingEnrichmentJob?.cancel()
    catalogsMap.clear()
    catalogOrder.clear()
    truncatedRowCache.clear()
    persistedTraktSyntheticGroups = emptyList()
    persistedSimklSyntheticGroups = emptyList()
    persistedMDBListSyntheticGroups = emptyList()
    clearPersistedTmdbSyntheticGroups()
    traktDiscoverySnapshot = com.nexio.tv.data.repository.TraktDiscoverySnapshot()
    persistedTraktDiscoverySnapshot = com.nexio.tv.data.repository.TraktDiscoverySnapshot()
    simklDiscoverySnapshot = com.nexio.tv.data.repository.SimklDiscoverySnapshot()
    persistedSimklDiscoverySnapshot = com.nexio.tv.data.repository.SimklDiscoverySnapshot()
    mdbListDiscoverySnapshot = com.nexio.tv.data.repository.MDBListDiscoverySnapshot()
    persistedMDBListDiscoverySnapshot = com.nexio.tv.data.repository.MDBListDiscoverySnapshot()
    tmdbDiscoverySnapshot = com.nexio.tv.data.repository.TmdbDiscoverySnapshot()
    tmdbDiscoveryRefreshInProgress = false
    tmdbCatalogPreferencesObserved = false
    inMemoryHomeSnapshot = null
    pendingRestoredCatalogSnapshot = null
    pendingHomeSnapshotPersist = null
    homeSnapshotPersistJob?.cancel()
    modernCarouselRowBuildCache.continueWatchingItems = emptyList()
    modernCarouselRowBuildCache.continueWatchingRow = null
    modernCarouselRowBuildCache.catalogRows.clear()
    modernCarouselRowBuildCache.catalogItemCache.clear()
    restoredCatalogSnapshotActive = false
    hasPersistedCatalogSnapshot = false
    hasRenderedFirstCatalog = false
    catalogsLoadInProgress = false
    startupRefreshPending = false
    lastCatalogComputationSignature = null
    lastCatalogOrderDiagnosticsSignature = null
    _fullCatalogRows.value = emptyList()
    _uiState.update { state ->
        state.copy(
            catalogRows = emptyList(),
            heroItems = emptyList(),
            heroCatalogKeys = emptyList(),
            continueWatchingItems = emptyList(),
            traktUpNextItems = emptyList(),
            modernHomePresentation = ModernHomePresentationState(),
            initialContinueWatchingResolved = false,
            traktRecommendationRefs = emptyMap(),
            gridItems = emptyList(),
            isLoading = true,
            error = null
        )
    }
}

internal fun HomeViewModel.clearTraktHomeState(reason: String) {
    Log.d(HomeViewModel.TAG, "Clearing Trakt home state reason=$reason")
    traktDiscoverySnapshot = com.nexio.tv.data.repository.TraktDiscoverySnapshot()
    persistedTraktDiscoverySnapshot = com.nexio.tv.data.repository.TraktDiscoverySnapshot()
    persistedTraktSyntheticGroups = emptyList()
    lastCatalogComputationSignature = null
    _uiState.update { state ->
        state.copy(
            traktUpNextItems = emptyList(),
            traktRecommendationRefs = emptyMap()
        )
    }
}

internal suspend fun HomeViewModel.loadActiveProfileDiskBackedHomeState(
    reason: String,
    expectedGeneration: Long? = null
): Boolean {
    val profileId = profileManager.activeProfileId.value
    val diskState = withContext(Dispatchers.IO) {
        val providerState = trackingProviderStateService.currentState()
        val syntheticSnapshot = syntheticHomeCatalogStore.read(profileId = profileId)
        val traktSnapshot = traktDiscoverySnapshotStore.read(profileId = profileId)
        val simklSnapshot = simklDiscoverySnapshotStore.read(profileId = profileId)
        val mdbSnapshot = mdbListDiscoverySnapshotStore.read(profileId = profileId)
        val posterProviderToken = homeCatalogSnapshotStore.currentPosterProviderToken()
        val homeSnapshot = homeCatalogSnapshotStore.read(posterProviderToken, profileId = profileId)
        DiskBackedHomeState(
            traktAuthenticated = providerState.traktAuthenticated,
            simklAuthenticated = providerState.simklAuthenticated,
            syntheticSnapshot = syntheticSnapshot,
            traktSnapshot = traktSnapshot,
            simklSnapshot = simklSnapshot,
            mdbSnapshot = mdbSnapshot,
            homeSnapshot = homeSnapshot
        )
    }
    val hasDiskCacheState = diskState.hasDiskCacheState()
    if (expectedGeneration != null && !isCurrentHomeProfileGeneration(expectedGeneration)) {
        Log.d(HomeViewModel.TAG, "Skipping stale disk-backed home state reason=$reason generation=$expectedGeneration")
        return false
    }

    withContext(Dispatchers.Main.immediate) {
        if (expectedGeneration != null && !isCurrentHomeProfileGeneration(expectedGeneration)) {
            Log.d(HomeViewModel.TAG, "Skipping stale disk-backed home state on main reason=$reason generation=$expectedGeneration")
            return@withContext
        }
        val hasTraktDiskState = diskState.hasTraktDiskState()
        activeProfileTraktAuthenticated = diskState.traktAuthenticated || hasTraktDiskState
        activeProfileSimklAuthenticated = diskState.simklAuthenticated
        diskState.syntheticSnapshot?.let { snapshot ->
            persistedTraktSyntheticGroups = if (diskState.traktAuthenticated || hasTraktDiskState) snapshot.traktGroups else emptyList()
            persistedSimklSyntheticGroups = snapshot.simklGroups
            persistedMDBListSyntheticGroups = snapshot.mdbListGroups
            applyPersistedTmdbSyntheticSnapshot(snapshot)
        }
        diskState.traktSnapshot?.let { snapshot ->
            val hydrated = applyTomatoesOverridesToTraktSnapshot(snapshot, syntheticTomatoesOverridesByItemId)
            persistedTraktDiscoverySnapshot = hydrated
            traktDiscoverySnapshot = hydrated
            traktDiscoveryObserved = true
        }
        diskState.simklSnapshot?.let { snapshot ->
            persistedSimklDiscoverySnapshot = snapshot
            simklDiscoverySnapshot = snapshot
            simklDiscoveryObserved = true
        }
        diskState.mdbSnapshot?.let { snapshot ->
            val hydrated = applyTomatoesOverridesToMDBListSnapshot(snapshot, syntheticTomatoesOverridesByItemId)
            persistedMDBListDiscoverySnapshot = hydrated
            mdbListDiscoverySnapshot = hydrated
            mdbListDiscoveryObserved = true
        }
        if (diskState.homeSnapshot != null) {
            applyPersistedHomeSnapshotIfEligiblePipeline(
                snapshot = diskState.homeSnapshot,
                requireSourceCachesReady = false
            )
        } else {
            scheduleUpdateCatalogRows()
        }
        if (hasDiskCacheState) {
            scheduleUpdateCatalogRows()
        }
        Log.d(HomeViewModel.TAG, "Loaded active profile disk-backed home state reason=$reason")
    }
    return hasDiskCacheState
}

internal suspend fun HomeViewModel.reloadDiskCachedAddonCatalogsForActiveProfileSwitchPipeline(
    allowNetworkRefresh: Boolean
) {
    val addons = addonsCache
    if (addons.isEmpty()) {
        scheduleUpdateCatalogRows()
        return
    }
    loadAllCatalogsPipeline(addons, allowNetworkRefresh = allowNetworkRefresh)
}

private data class DiskBackedHomeState(
    val traktAuthenticated: Boolean,
    val simklAuthenticated: Boolean,
    val syntheticSnapshot: com.nexio.tv.data.local.SyntheticHomeCatalogStore.Snapshot?,
    val traktSnapshot: com.nexio.tv.data.repository.TraktDiscoverySnapshot?,
    val simklSnapshot: com.nexio.tv.data.repository.SimklDiscoverySnapshot?,
    val mdbSnapshot: com.nexio.tv.data.repository.MDBListDiscoverySnapshot?,
    val homeSnapshot: com.nexio.tv.data.local.HomeCatalogSnapshotStore.Snapshot?
) {
    fun hasTraktDiskState(): Boolean {
        return syntheticSnapshot?.traktGroups?.isNotEmpty() == true ||
            traktSnapshot?.hasRenderableContent() == true
    }

    fun hasDiskCacheState(): Boolean {
        return syntheticSnapshot != null ||
            traktSnapshot != null ||
            simklSnapshot != null ||
            mdbSnapshot != null ||
            homeSnapshot != null
    }
}

private fun com.nexio.tv.data.repository.TraktDiscoverySnapshot.hasRenderableContent(): Boolean {
    return updatedAtMs > 0L ||
        calendarItems.isNotEmpty() ||
        recommendationMovieItems.isNotEmpty() ||
        recommendationShowItems.isNotEmpty() ||
        trendingMovieItems.isNotEmpty() ||
        trendingShowItems.isNotEmpty() ||
        popularMovieItems.isNotEmpty() ||
        popularShowItems.isNotEmpty() ||
        customListCatalogs.isNotEmpty() ||
        popularLists.isNotEmpty()
}

internal fun HomeViewModel.restorePersistedDiscoverySnapshotsPipeline() {
    viewModelScope.launch(Dispatchers.IO) {
        val profileId = profileManager.activeProfileId.value
        val capturedGeneration = homeProfileGeneration
        val traktSnapshot = traktDiscoverySnapshotStore.read(profileId = profileId)
        val simklSnapshot = simklDiscoverySnapshotStore.read(profileId = profileId)
        val mdbSnapshot = mdbListDiscoverySnapshotStore.read(profileId = profileId)
        val providerState = trackingProviderStateService.currentState()
        Log.d(
            HomeViewModel.TAG,
            "Restored discovery snapshots trakt=" +
                if (traktSnapshot == null) {
                    "null"
                } else {
                    "updated=${traktSnapshot.updatedAtMs} custom=${traktSnapshot.customListCatalogs.size} " +
                        "trendingMovies=${traktSnapshot.trendingMovieItems.size} trendingShows=${traktSnapshot.trendingShowItems.size} " +
                        "popularMovies=${traktSnapshot.popularMovieItems.size} popularShows=${traktSnapshot.popularShowItems.size} " +
                        "recommendMovie=${traktSnapshot.recommendationMovieItems.size} recommendShow=${traktSnapshot.recommendationShowItems.size} " +
                        "calendar=${traktSnapshot.calendarItems.size}"
                } +
                " simkl=" +
                if (simklSnapshot == null) {
                    "null"
                } else {
                    "updated=${simklSnapshot.updatedAtMs} catalogs=${simklSnapshot.itemsByCatalog.size} " +
                        "nonEmpty=${simklSnapshot.itemsByCatalog.count { it.value.isNotEmpty() }}"
                } +
                " mdb=" +
                if (mdbSnapshot == null) {
                    "null"
                } else {
                    "updated=${mdbSnapshot.updatedAtMs} personal=${mdbSnapshot.personalLists.size} top=${mdbSnapshot.topLists.size} " +
                        "custom=${mdbSnapshot.customListCatalogs.size}"
                }
        )
        withContext(Dispatchers.Main.immediate) {
            if (!isCurrentHomeProfileGeneration(capturedGeneration)) {
                Log.d(HomeViewModel.TAG, "Skipping stale discovery snapshot generation=$capturedGeneration")
                return@withContext
            }
            activeProfileTraktAuthenticated = providerState.traktAuthenticated
            activeProfileSimklAuthenticated = providerState.simklAuthenticated
            if (traktSnapshot != null) {
                val hydratedTraktSnapshot = applyTomatoesOverridesToTraktSnapshot(
                    traktSnapshot,
                    syntheticTomatoesOverridesByItemId
                )
                persistedTraktDiscoverySnapshot = hydratedTraktSnapshot
                if (traktDiscoverySnapshot.updatedAtMs <= 0L) {
                    traktDiscoverySnapshot = hydratedTraktSnapshot
                }
            }
            if (simklSnapshot != null) {
                persistedSimklDiscoverySnapshot = simklSnapshot
                if (simklDiscoverySnapshot.updatedAtMs <= 0L) {
                    simklDiscoverySnapshot = simklSnapshot
                }
            }
            if (mdbSnapshot != null) {
                val hydratedMdbSnapshot = applyTomatoesOverridesToMDBListSnapshot(
                    mdbSnapshot,
                    syntheticTomatoesOverridesByItemId
                )
                persistedMDBListDiscoverySnapshot = hydratedMdbSnapshot
                if (mdbListDiscoverySnapshot.updatedAtMs <= 0L) {
                    mdbListDiscoverySnapshot = hydratedMdbSnapshot
                }
            }
            applyPendingPersistedHomeSnapshotIfPossiblePipeline("restore_discovery_snapshots")
        }
    }
}

internal fun HomeViewModel.observeTraktDiscoveryPipeline() {
    viewModelScope.launch {
        val autoRefreshOnStart = !shouldDeferStartupNetworkWork()
        traktDiscoveryService.observeSnapshot(autoRefreshOnStart = autoRefreshOnStart).collectLatest { snapshot ->
            val capturedGeneration = homeProfileGeneration
            val hydratedSnapshot = applyTomatoesOverridesToTraktSnapshot(
                snapshot,
                syntheticTomatoesOverridesByItemId
            )
            if (!isCurrentHomeProfileGeneration(capturedGeneration)) {
                Log.d(HomeViewModel.TAG, "Skipping stale discovery snapshot generation=$capturedGeneration")
                return@collectLatest
            }
            // During profile switch, discovery flows re-emit an empty snapshot before
            // onStart loads disk data. Accepting that empty emission would overwrite the
            // disk-cached data that loadActiveProfileDiskBackedHomeState just set.
            // Skip empty emissions while the profile-switch suppress window is active.
            if (hydratedSnapshot.updatedAtMs <= 0L && shouldSuppressProfileSwitchRefresh("trakt_discovery")) {
                Log.d(HomeViewModel.TAG, "Skipping empty Trakt discovery emission during profile switch")
                return@collectLatest
            }
            if (!activeProfileTraktAuthenticated) {
                val providerState = withContext(Dispatchers.IO) {
                    trackingProviderStateService.currentState()
                }
                if (!isCurrentHomeProfileGeneration(capturedGeneration)) {
                    Log.d(HomeViewModel.TAG, "Skipping stale discovery snapshot generation=$capturedGeneration")
                    return@collectLatest
                }
                activeProfileTraktAuthenticated = providerState.traktAuthenticated
                activeProfileSimklAuthenticated = providerState.simklAuthenticated
                if (!providerState.traktAuthenticated) {
                    clearTraktHomeState("observe_trakt_discovery_unauthenticated")
                    return@collectLatest
                }
            }
            if (traktDiscoveryObserved && hydratedSnapshot == traktDiscoverySnapshot) return@collectLatest
            traktDiscoveryObserved = true
            traktDiscoverySnapshot = hydratedSnapshot
            persistedTraktDiscoverySnapshot = hydratedSnapshot
            startupRefreshPending = true
            applyPendingPersistedHomeSnapshotIfPossiblePipeline("observe_trakt_discovery")
            if (!shouldDeferStartupNetworkWork()) {
                runSerializedHomeRefreshIfNeeded("trakt_discovery")
            }
        }
    }
}

internal fun HomeViewModel.observeTraktCatalogPreferencesPipeline() {
    viewModelScope.launch {
        traktSettingsDataStore.catalogPreferences.collectLatest { prefs ->
            if (prefs == traktCatalogPreferences) return@collectLatest
            traktCatalogPreferences = prefs
            applyPendingPersistedHomeSnapshotIfPossiblePipeline("observe_trakt_prefs")
            if (activeProfileTraktAuthenticated &&
                shouldRefreshTraktDiscoveryForState(prefs, traktDiscoverySnapshot) &&
                !shouldSuppressProfileSwitchRefresh("trakt_pref_change") &&
                isNonPlaybackHomeWorkAllowed()
            ) {
                if (shouldDeferStartupNetworkWork()) {
                    startupRefreshPending = true
                    logStartupPerf("catalog_refresh_deferred", "reason=trakt_pref_change")
                } else {
                    traktDiscoveryService.ensureFresh(force = false)
                }
            }
            startupRefreshPending = true
            if (!shouldDeferStartupNetworkWork()) {
                runSerializedHomeRefreshIfNeeded("trakt_pref_change")
            }
        }
    }
}

internal fun HomeViewModel.observeSimklDiscoveryPipeline() {
    viewModelScope.launch {
        val autoRefreshOnStart = !shouldDeferStartupNetworkWork()
        simklDiscoveryService.observeSnapshot(autoRefreshOnStart = autoRefreshOnStart).collectLatest { snapshot ->
            val capturedGeneration = homeProfileGeneration
            if (!isCurrentHomeProfileGeneration(capturedGeneration)) {
                Log.d(HomeViewModel.TAG, "Skipping stale discovery snapshot generation=$capturedGeneration")
                return@collectLatest
            }
            if (simklDiscoveryObserved && snapshot == simklDiscoverySnapshot) return@collectLatest
            // During profile switch, discovery flows re-emit an empty snapshot before
            // onStart loads disk data. Accepting that empty emission would overwrite the
            // disk-cached data that loadActiveProfileDiskBackedHomeState just set.
            // Skip empty emissions while the profile-switch suppress window is active.
            if (snapshot.updatedAtMs <= 0L && shouldSuppressProfileSwitchRefresh("simkl_discovery")) {
                Log.d(HomeViewModel.TAG, "Skipping empty Simkl discovery emission during profile switch")
                return@collectLatest
            }
            simklDiscoveryObserved = true
            simklDiscoverySnapshot = snapshot
            persistedSimklDiscoverySnapshot = snapshot
            startupRefreshPending = true
            applyPendingPersistedHomeSnapshotIfPossiblePipeline("observe_simkl_discovery")
            if (!shouldDeferStartupNetworkWork()) {
                runCatching { renewSimklSyntheticSnapshotPipeline(snapshot) }
                    .onFailure { error ->
                        Log.w(HomeViewModel.TAG, "Failed to renew SIMKL synthetic snapshot after discovery update", error)
                    }
                runSerializedHomeRefreshIfNeeded("simkl_discovery")
            }
        }
    }
}

internal fun HomeViewModel.observeSimklCatalogPreferencesPipeline() {
    viewModelScope.launch {
        simklSettingsDataStore.catalogPreferences.collectLatest { prefs ->
            if (prefs == simklCatalogPreferences) return@collectLatest
            simklCatalogPreferences = prefs
            applyPendingPersistedHomeSnapshotIfPossiblePipeline("observe_simkl_prefs")
            if (shouldRefreshSimklDiscoveryForState(prefs, simklDiscoverySnapshot) &&
                !shouldSuppressProfileSwitchRefresh("simkl_pref_change") &&
                isNonPlaybackHomeWorkAllowed()
            ) {
                if (shouldDeferStartupNetworkWork()) {
                    startupRefreshPending = true
                    logStartupPerf("catalog_refresh_deferred", "reason=simkl_pref_change")
                } else {
                    runCatching { simklDiscoveryService.ensureFresh(force = false) }
                        .onFailure { error ->
                            Log.w(HomeViewModel.TAG, "Failed to refresh SIMKL discovery after settings change", error)
                        }
                }
            }
            startupRefreshPending = true
            if (!shouldDeferStartupNetworkWork()) {
                runSerializedHomeRefreshIfNeeded("simkl_pref_change")
            }
        }
    }
}

internal fun HomeViewModel.observeMDBListDiscoveryPipeline() {
    viewModelScope.launch {
        val autoRefreshOnStart = !shouldDeferStartupNetworkWork()
        mdbListDiscoveryService.observeSnapshot(autoRefreshOnStart = autoRefreshOnStart).collectLatest { snapshot ->
            val capturedGeneration = homeProfileGeneration
            val hydratedSnapshot = applyTomatoesOverridesToMDBListSnapshot(
                snapshot,
                syntheticTomatoesOverridesByItemId
            )
            if (!isCurrentHomeProfileGeneration(capturedGeneration)) {
                Log.d(HomeViewModel.TAG, "Skipping stale discovery snapshot generation=$capturedGeneration")
                return@collectLatest
            }
            if (mdbListDiscoveryObserved && hydratedSnapshot == mdbListDiscoverySnapshot) return@collectLatest
            // During profile switch, discovery flows re-emit an empty snapshot before
            // onStart loads disk data. Accepting that empty emission would overwrite the
            // disk-cached data that loadActiveProfileDiskBackedHomeState just set.
            // Skip empty emissions while the profile-switch suppress window is active.
            if (hydratedSnapshot.updatedAtMs <= 0L && shouldSuppressProfileSwitchRefresh("mdblist_discovery")) {
                Log.d(HomeViewModel.TAG, "Skipping empty MDBList discovery emission during profile switch")
                return@collectLatest
            }
            mdbListDiscoveryObserved = true
            mdbListDiscoverySnapshot = hydratedSnapshot
            persistedMDBListDiscoverySnapshot = hydratedSnapshot
            Log.d(
                HomeViewModel.TAG,
                "MDBList snapshot personal=${hydratedSnapshot.personalLists.size} top=${hydratedSnapshot.topLists.size} custom=${hydratedSnapshot.customListCatalogs.size}"
            )
            startupRefreshPending = true
            applyPendingPersistedHomeSnapshotIfPossiblePipeline("observe_mdblist_discovery")
            if (!shouldDeferStartupNetworkWork()) {
                runSerializedHomeRefreshIfNeeded("mdblist_discovery")
            }
        }
    }
}

internal fun HomeViewModel.observeMDBListSettingsPipeline() {
    viewModelScope.launch {
        mdbListSettingsDataStore.settings
            .distinctUntilChanged()
            .collectLatest { settings ->
                if (settings.enabled && settings.apiKey.isNotBlank() &&
                    shouldRefreshMDBListDiscoveryForState(mdbListCatalogPreferences, mdbListDiscoverySnapshot) &&
                    !shouldSuppressProfileSwitchRefresh("mdblist_settings_change") &&
                    isNonPlaybackHomeWorkAllowed()
                ) {
                    if (shouldDeferStartupNetworkWork()) {
                        startupRefreshPending = true
                        logStartupPerf("catalog_refresh_deferred", "reason=mdblist_settings_change")
                    } else {
                        runCatching { mdbListDiscoveryService.ensureFresh(force = false) }
                            .onFailure { error ->
                                Log.w(HomeViewModel.TAG, "Failed to refresh MDBList discovery after settings change", error)
                            }
                    }
                } else if ((!settings.enabled || settings.apiKey.isBlank()) &&
                    !shouldBlockProfileSwitchDiskSnapshotRefresh("mdblist_settings_disabled")
                ) {
                    mdbListDiscoverySnapshot = com.nexio.tv.data.repository.MDBListDiscoverySnapshot()
                    persistedMDBListDiscoverySnapshot = mdbListDiscoverySnapshot
                    startupRefreshPending = true
                    applyPendingPersistedHomeSnapshotIfPossiblePipeline("observe_mdblist_settings_disabled")
                    if (!shouldDeferStartupNetworkWork()) {
                        runSerializedHomeRefreshIfNeeded("mdblist_settings_disabled")
                    }
                }
            }
    }
}

internal fun HomeViewModel.observeMDBListCatalogPreferencesPipeline() {
    viewModelScope.launch {
        mdbListSettingsDataStore.catalogPreferences.collectLatest { prefs ->
            if (prefs == mdbListCatalogPreferences) return@collectLatest
            mdbListCatalogPreferences = prefs
            applyPendingPersistedHomeSnapshotIfPossiblePipeline("observe_mdblist_prefs")
            if (shouldRefreshMDBListDiscoveryForState(prefs, mdbListDiscoverySnapshot) &&
                !shouldSuppressProfileSwitchRefresh("mdblist_pref_change") &&
                isNonPlaybackHomeWorkAllowed()
            ) {
                if (shouldDeferStartupNetworkWork()) {
                    startupRefreshPending = true
                    logStartupPerf("catalog_refresh_deferred", "reason=mdblist_pref_change")
                } else {
                    mdbListDiscoveryService.ensureFresh(force = false)
                }
            }
            startupRefreshPending = true
            if (!shouldDeferStartupNetworkWork()) {
                runSerializedHomeRefreshIfNeeded("mdblist_pref_change")
            }
        }
    }
}

internal fun HomeViewModel.observeTmdbDiscoveryPipeline() {
    viewModelScope.launch {
        tmdbDiscoveryService.observeSnapshot().collectLatest { snapshot ->
            val capturedGeneration = homeProfileGeneration
            if (!isCurrentHomeProfileGeneration(capturedGeneration)) {
                Log.d(HomeViewModel.TAG, "Skipping stale TMDB discovery snapshot generation=$capturedGeneration")
                return@collectLatest
            }
            if (tmdbDiscoveryObserved && snapshot == tmdbDiscoverySnapshot) return@collectLatest
            if (snapshot.updatedAtMs <= 0L && shouldSuppressProfileSwitchRefresh("tmdb_discovery")) {
                Log.d(HomeViewModel.TAG, "Skipping empty TMDB discovery emission during profile switch")
                return@collectLatest
            }
            tmdbDiscoveryObserved = true
            tmdbDiscoverySnapshot = snapshot
            startupRefreshPending = true
            applyPendingPersistedHomeSnapshotIfPossiblePipeline("observe_tmdb_discovery")
            if (!shouldDeferStartupNetworkWork()) {
                runCatching { renewTmdbSyntheticSnapshotPipeline(snapshot) }
                    .onFailure { error ->
                        Log.w(HomeViewModel.TAG, "Failed to renew TMDB synthetic snapshot after discovery update", error)
                    }
                runSerializedHomeRefreshIfNeeded("tmdb_discovery")
            }
        }
    }
}

internal fun HomeViewModel.observeTmdbCatalogPreferencesPipeline() {
    viewModelScope.launch {
        tmdbCatalogSettingsDataStore.catalogPreferences.collectLatest { prefs ->
            val firstObservation = !tmdbCatalogPreferencesObserved
            if (!firstObservation && prefs == tmdbCatalogPreferences) return@collectLatest
            tmdbCatalogPreferencesObserved = true
            tmdbCatalogPreferences = prefs
            applyPendingPersistedHomeSnapshotIfPossiblePipeline("observe_tmdb_prefs")
            if (shouldRefreshTmdbDiscoveryForState(prefs, tmdbDiscoverySnapshot) &&
                !shouldSuppressProfileSwitchRefresh("tmdb_pref_change") &&
                isNonPlaybackHomeWorkAllowed()
            ) {
                if (shouldDeferStartupNetworkWork()) {
                    startupRefreshPending = true
                    logStartupPerf("catalog_refresh_deferred", "reason=tmdb_pref_change")
                } else {
                    runCatching { tmdbDiscoveryService.refreshCatalogs(prefs, force = false) }
                        .onFailure { error ->
                            Log.w(HomeViewModel.TAG, "Failed to refresh TMDB discovery after settings change", error)
                        }
                }
            }
            startupRefreshPending = true
            if (!shouldDeferStartupNetworkWork()) {
                runSerializedHomeRefreshIfNeeded("tmdb_pref_change")
            }
        }
    }
}

internal fun HomeViewModel.dismissTraktRecommendationPipeline(
    ref: com.nexio.tv.data.repository.TraktRecommendationRef
) {
    viewModelScope.launch {
        clearProfileSwitchDiskSnapshotMode("dismiss_trakt_recommendation")
        runCatching {
            traktDiscoveryService.dismissRecommendation(ref)
            traktDiscoveryService.ensureFresh(force = true)
        }.onFailure { error ->
            Log.w(HomeViewModel.TAG, "Failed to dismiss Trakt recommendation ${ref.recommendationKey}", error)
        }
    }
}

internal fun HomeViewModel.loadHomeCatalogOrderPreferencePipeline() {
    viewModelScope.launch {
        layoutPreferenceDataStore.homeCatalogOrderKeys.collectLatest { keys ->
            homeCatalogOrderKeys = keys
            rebuildCatalogOrder(addonsCache)
            applyPendingPersistedHomeSnapshotIfPossiblePipeline("observe_home_catalog_order")
            scheduleUpdateCatalogRows()
        }
    }
}

internal fun HomeViewModel.loadDisabledHomeCatalogPreferencePipeline() {
    viewModelScope.launch {
        layoutPreferenceDataStore.disabledHomeCatalogKeys.collectLatest { keys ->
            val newKeys = keys.toSet()
            if (newKeys == disabledHomeCatalogKeys) return@collectLatest
            disabledHomeCatalogKeys = newKeys
            rebuildCatalogOrder(addonsCache)
            applyPendingPersistedHomeSnapshotIfPossiblePipeline("observe_disabled_home_catalogs")
            catalogUpdateJob?.cancel()
            catalogUpdateJob = viewModelScope.launch {
                updateCatalogRowsPipeline()
            }
            if (addonsCache.isNotEmpty()) {
                val blockNetworkRefresh = shouldBlockProfileSwitchDiskSnapshotRefresh("observe_disabled_home_catalogs")
                loadAllCatalogsPipeline(addonsCache, allowNetworkRefresh = !blockNetworkRefresh)
            }
        }
    }
}

internal fun HomeViewModel.observeTmdbSettingsPipeline() {
    viewModelScope.launch {
        tmdbSettingsDataStore.settings
            .distinctUntilChanged()
            .collectLatest { settings ->
                currentTmdbSettings = settings
                scheduleUpdateCatalogRows()
                enrichContinueWatchingWithCurrentSettings()
            }
    }
}

internal fun HomeViewModel.observeInstalledAddonsPipeline() {
    viewModelScope.launch {
        addonRepository.getInstalledAddons()
            .distinctUntilChanged()
            .collectLatest { addons ->
                installedAddonsObserved = true
                addonsCache = addons
                applyPendingPersistedHomeSnapshotIfPossiblePipeline("observe_installed_addons")
                if (diskFirstHomeStartupEnabled) {
                    openStartupDeferralWindowIfNeeded("installed_addons")
                }
                val blockNetworkRefresh = shouldBlockProfileSwitchDiskSnapshotRefresh("observe_installed_addons")
                loadAllCatalogsPipeline(addons, allowNetworkRefresh = !blockNetworkRefresh)
            }
    }
}

internal suspend fun HomeViewModel.runSerializedPostStartupRefreshPipeline(expectedGeneration: Long, reason: String) {
    if (!isCurrentHomeProfileGeneration(expectedGeneration)) {
        Log.d(HomeViewModel.TAG, "Skipping stale serialized home refresh generation=$expectedGeneration")
        return
    }
    if (shouldBlockProfileSwitchDiskSnapshotRefresh(reason)) return
    if (!isNonPlaybackHomeWorkAllowed()) {
        startupRefreshPending = false
        Log.d(HomeViewModel.TAG, "Skipping post-startup refresh during active playback reason=$reason")
        return
    }
    Log.d(
        HomeViewModel.TAG,
        "Post-startup refresh pipeline begin addons=${addonsCache.size} persistedTraktGroups=${persistedTraktSyntheticGroups.size} " +
            "persistedSimklGroups=${persistedSimklSyntheticGroups.size} " +
            "persistedMdbGroups=${persistedMDBListSyntheticGroups.size} " +
            "persistedTmdbGroups=${persistedTmdbSyntheticGroups.size}"
    )
    val beforeTraktSnapshot = traktDiscoveryService.observeSnapshot(autoRefreshOnStart = false).first()
    val beforeSimklSnapshot = simklDiscoveryService.observeSnapshot(autoRefreshOnStart = false).first()
    val beforeMdbSnapshot = mdbListDiscoveryService.observeSnapshot(autoRefreshOnStart = false).first()
    val beforeTmdbSnapshot = tmdbDiscoveryService.observeSnapshot().first()

    logStartupPerf(
        "synthetic_refresh_start",
        "trakt_items=${traktSnapshotItemKeys(beforeTraktSnapshot).size} simkl_items=${simklSnapshotItemKeys(beforeSimklSnapshot).size} " +
            "mdb_items=${mdbSnapshotItemKeys(beforeMdbSnapshot).size} tmdb_items=${tmdbSnapshotItemKeys(beforeTmdbSnapshot).size}"
    )

    val refreshedCatalogCount = AtomicInteger(0)
    val refreshTraktDiscovery = activeProfileTraktAuthenticated &&
        shouldAttemptSerializedTraktDiscoveryRefresh(traktCatalogPreferences)
    val refreshSimklDiscovery = shouldRefreshSimklDiscoveryForState(simklCatalogPreferences, beforeSimklSnapshot)
    val refreshMdbDiscovery = shouldRefreshMDBListDiscoveryForState(mdbListCatalogPreferences, beforeMdbSnapshot)
    val refreshTmdbDiscovery = shouldRefreshTmdbDiscoveryForState(tmdbCatalogPreferences, beforeTmdbSnapshot)
    supervisorScope {
        val refreshJobs = mutableListOf<Job>()
        refreshJobs.add(
            launch(Dispatchers.IO) {
                try {
                    Log.d(HomeViewModel.TAG, "Post-startup refresh step begin source=trakt_discovery")
                    if (refreshTraktDiscovery) {
                        try {
                            traktDiscoveryService.ensureFresh(force = false)
                        } catch (e: kotlinx.coroutines.CancellationException) {
                            throw e
                        } catch (t: Throwable) {
                            Log.w(HomeViewModel.TAG, "Failed synthetic Trakt refresh in serialized startup pipeline", t)
                        }
                    }
                    val afterTraktSnapshot = if (activeProfileTraktAuthenticated) {
                        applyTomatoesOverridesToTraktSnapshot(
                            traktDiscoveryService.observeSnapshot(autoRefreshOnStart = false).first(),
                            syntheticTomatoesOverridesByItemId
                        )
                    } else {
                        com.nexio.tv.data.repository.TraktDiscoverySnapshot()
                    }
                    val traktBeforeKeys = traktSnapshotItemKeys(beforeTraktSnapshot)
                    val traktAfterKeys = traktSnapshotItemKeys(afterTraktSnapshot)
                    withContext(Dispatchers.Main.immediate) {
                        if (!isCurrentHomeProfileGeneration(expectedGeneration)) return@withContext
                        if (activeProfileTraktAuthenticated) {
                            traktDiscoverySnapshot = afterTraktSnapshot
                            persistedTraktDiscoverySnapshot = afterTraktSnapshot
                        }
                    }
                    logStartupPerf(
                        "synthetic_refresh_provider_ready",
                        "source=trakt total=${traktAfterKeys.size} added=${(traktAfterKeys - traktBeforeKeys).size} retained=${(traktAfterKeys intersect traktBeforeKeys).size}"
                    )
                    Log.d(HomeViewModel.TAG, "Post-startup refresh step end source=trakt_discovery")
                    logStartupPerf("synthetic_refresh_step_start", "source=trakt")
                    if (isCurrentHomeProfileGeneration(expectedGeneration) && activeProfileTraktAuthenticated) {
                        renewTraktSyntheticSnapshotPipeline(afterTraktSnapshot)
                    }
                    logStartupPerf("synthetic_refresh_step_end", "source=trakt rows=${persistedTraktSyntheticGroups.sumOf { it.rows.size }}")
                    withContext(Dispatchers.Main.immediate) {
                        if (!isCurrentHomeProfileGeneration(expectedGeneration)) return@withContext
                        scheduleUpdateCatalogRows()
                    }
                } catch (e: kotlinx.coroutines.CancellationException) {
                    throw e
                } catch (t: Throwable) {
                    Log.w(HomeViewModel.TAG, "Unexpected failure during Trakt refresh in serialized startup pipeline", t)
                }
            }
        )

        refreshJobs.add(
            launch(Dispatchers.IO) {
                if (refreshTmdbDiscovery) {
                    withContext(Dispatchers.Main.immediate) {
                        if (!isCurrentHomeProfileGeneration(expectedGeneration)) return@withContext
                        tmdbDiscoveryRefreshInProgress = true
                        startupRefreshPending = true
                        scheduleUpdateCatalogRows()
                    }
                }
                try {
                    Log.d(HomeViewModel.TAG, "Post-startup refresh step begin source=tmdb_discovery")
                    if (refreshTmdbDiscovery) {
                        try {
                            tmdbDiscoveryService.refreshCatalogs(tmdbCatalogPreferences, force = false)
                        } catch (e: kotlinx.coroutines.CancellationException) {
                            throw e
                        } catch (t: Throwable) {
                            Log.w(HomeViewModel.TAG, "Failed synthetic TMDB refresh in serialized startup pipeline", t)
                        }
                    }
                    val afterTmdbSnapshot = tmdbDiscoveryService.observeSnapshot().first()
                    val tmdbBeforeKeys = tmdbSnapshotItemKeys(beforeTmdbSnapshot)
                    val tmdbAfterKeys = tmdbSnapshotItemKeys(afterTmdbSnapshot)
                    withContext(Dispatchers.Main.immediate) {
                        if (!isCurrentHomeProfileGeneration(expectedGeneration)) return@withContext
                        tmdbDiscoverySnapshot = afterTmdbSnapshot
                    }
                    logStartupPerf(
                        "synthetic_refresh_provider_ready",
                        "source=tmdb total=${tmdbAfterKeys.size} added=${(tmdbAfterKeys - tmdbBeforeKeys).size} retained=${(tmdbAfterKeys intersect tmdbBeforeKeys).size}"
                    )
                    Log.d(HomeViewModel.TAG, "Post-startup refresh step end source=tmdb_discovery")
                    logStartupPerf("synthetic_refresh_step_start", "source=tmdb")
                    if (isCurrentHomeProfileGeneration(expectedGeneration)) {
                        renewTmdbSyntheticSnapshotPipeline(afterTmdbSnapshot)
                    }
                    logStartupPerf("synthetic_refresh_step_end", "source=tmdb rows=${persistedTmdbSyntheticGroups.sumOf { it.rows.size }}")
                    withContext(Dispatchers.Main.immediate) {
                        if (!isCurrentHomeProfileGeneration(expectedGeneration)) return@withContext
                        scheduleUpdateCatalogRows()
                    }
                } catch (e: kotlinx.coroutines.CancellationException) {
                    throw e
                } catch (t: Throwable) {
                    Log.w(HomeViewModel.TAG, "Unexpected failure during TMDB refresh in serialized startup pipeline", t)
                } finally {
                    if (refreshTmdbDiscovery) {
                        withContext(Dispatchers.Main.immediate) {
                            if (!isCurrentHomeProfileGeneration(expectedGeneration)) return@withContext
                            tmdbDiscoveryRefreshInProgress = false
                            scheduleUpdateCatalogRows()
                        }
                    }
                }
            }
        )

        refreshJobs.add(
            launch(Dispatchers.IO) {
                try {
                    Log.d(HomeViewModel.TAG, "Post-startup refresh step begin source=simkl_discovery")
                    if (refreshSimklDiscovery) {
                        try {
                            simklDiscoveryService.ensureFresh(force = false)
                        } catch (e: kotlinx.coroutines.CancellationException) {
                            throw e
                        } catch (t: Throwable) {
                            Log.w(HomeViewModel.TAG, "Failed synthetic SIMKL refresh in serialized startup pipeline", t)
                        }
                    }
                    val afterSimklSnapshot = simklDiscoveryService.observeSnapshot(autoRefreshOnStart = false).first()
                    val simklBeforeKeys = simklSnapshotItemKeys(beforeSimklSnapshot)
                    val simklAfterKeys = simklSnapshotItemKeys(afterSimklSnapshot)
                    withContext(Dispatchers.Main.immediate) {
                        if (!isCurrentHomeProfileGeneration(expectedGeneration)) return@withContext
                        simklDiscoverySnapshot = afterSimklSnapshot
                        persistedSimklDiscoverySnapshot = afterSimklSnapshot
                    }
                    logStartupPerf(
                        "synthetic_refresh_provider_ready",
                        "source=simkl total=${simklAfterKeys.size} added=${(simklAfterKeys - simklBeforeKeys).size} retained=${(simklAfterKeys intersect simklBeforeKeys).size}"
                    )
                    Log.d(HomeViewModel.TAG, "Post-startup refresh step end source=simkl_discovery")
                    logStartupPerf("synthetic_refresh_step_start", "source=simkl")
                    if (isCurrentHomeProfileGeneration(expectedGeneration)) {
                        renewSimklSyntheticSnapshotPipeline(afterSimklSnapshot)
                    }
                    logStartupPerf("synthetic_refresh_step_end", "source=simkl rows=${persistedSimklSyntheticGroups.sumOf { it.rows.size }}")
                    withContext(Dispatchers.Main.immediate) {
                        if (!isCurrentHomeProfileGeneration(expectedGeneration)) return@withContext
                        scheduleUpdateCatalogRows()
                    }
                } catch (e: kotlinx.coroutines.CancellationException) {
                    throw e
                } catch (t: Throwable) {
                    Log.w(HomeViewModel.TAG, "Unexpected failure during SIMKL refresh in serialized startup pipeline", t)
                }
            }
        )

        refreshJobs.add(
            launch(Dispatchers.IO) {
                try {
                    Log.d(HomeViewModel.TAG, "Post-startup refresh step begin source=mdblist_discovery")
                    if (refreshMdbDiscovery) {
                        try {
                            mdbListDiscoveryService.ensureFresh(force = false)
                        } catch (e: kotlinx.coroutines.CancellationException) {
                            throw e
                        } catch (t: Throwable) {
                            Log.w(HomeViewModel.TAG, "Failed synthetic MDBList refresh in serialized startup pipeline", t)
                        }
                    }
                    val afterMdbSnapshot = applyTomatoesOverridesToMDBListSnapshot(
                        mdbListDiscoveryService.observeSnapshot(autoRefreshOnStart = false).first(),
                        syntheticTomatoesOverridesByItemId
                    )
                    val mdbBeforeKeys = mdbSnapshotItemKeys(beforeMdbSnapshot)
                    val mdbAfterKeys = mdbSnapshotItemKeys(afterMdbSnapshot)
                    withContext(Dispatchers.Main.immediate) {
                        if (!isCurrentHomeProfileGeneration(expectedGeneration)) return@withContext
                        mdbListDiscoverySnapshot = afterMdbSnapshot
                        persistedMDBListDiscoverySnapshot = afterMdbSnapshot
                    }
                    logStartupPerf(
                        "synthetic_refresh_provider_ready",
                        "source=mdblist total=${mdbAfterKeys.size} added=${(mdbAfterKeys - mdbBeforeKeys).size} retained=${(mdbAfterKeys intersect mdbBeforeKeys).size}"
                    )
                    Log.d(HomeViewModel.TAG, "Post-startup refresh step end source=mdblist_discovery")
                    logStartupPerf("synthetic_refresh_step_start", "source=mdblist")
                    if (isCurrentHomeProfileGeneration(expectedGeneration)) {
                        renewMDBListSyntheticSnapshotPipeline(afterMdbSnapshot)
                    }
                    logStartupPerf("synthetic_refresh_step_end", "source=mdblist rows=${persistedMDBListSyntheticGroups.sumOf { it.rows.size }}")
                    withContext(Dispatchers.Main.immediate) {
                        scheduleUpdateCatalogRows()
                    }
                } catch (e: kotlinx.coroutines.CancellationException) {
                    throw e
                } catch (t: Throwable) {
                    Log.w(HomeViewModel.TAG, "Unexpected failure during MDBList refresh in serialized startup pipeline", t)
                }
            }
        )

        refreshJobs.add(
            launch(Dispatchers.IO) {
                try {
                    val addons = addonsCache
                    refreshedCatalogCount.set(
                        homeCatalogRefreshCoordinator.refreshSerially(
                            addons = addons,
                            telemetryEnabled = startupPerfTelemetryEnabled,
                            isCatalogDisabled = { addon, catalog ->
                                isCatalogDisabled(
                                    addonBaseUrl = addon.baseUrl,
                                    addonId = addon.id,
                                    type = catalog.apiType,
                                    catalogId = catalog.id,
                                    catalogName = catalog.name
                                )
                            },
                            getCurrentRow = { key ->
                                withContext(Dispatchers.Main.immediate) {
                                    catalogsMap[key]
                                }
                            },
                            isItemReferencedElsewhere = { itemKey, sourceCatalogKey ->
                                withContext(Dispatchers.Main.immediate) {
                                    catalogsMap.any { (catalogKey, row) ->
                                        if (catalogKey == sourceCatalogKey) {
                                            false
                                        } else {
                                            row.items.any { "${it.apiType}:${it.id}" == itemKey }
                                        }
                                    }
                                }
                            },
                            onCatalogReady = { catalogKey, row, diff ->
                                withContext(Dispatchers.Main.immediate) {
                                    if (!isCurrentHomeProfileGeneration(expectedGeneration)) return@withContext
                                    catalogsMap[catalogKey] = row
                                    if (diff.addedOrChanged.isNotEmpty()) {
                                        logStartupPerf(
                                            "catalog_publish_ready",
                                            "catalogKey=$catalogKey items_added=${diff.addedOrChanged.size}"
                                        )
                                    }
                                    scheduleUpdateCatalogRows()
                                }
                            },
                            onLog = { event, details -> logStartupPerf(event, details) }
                        )
                    )
                    if (refreshedCatalogCount.get() == 0) {
                        logStartupPerf("catalog_refresh_noop", "reason=no_refreshable_addon_catalogs")
                    }
                    Log.d(HomeViewModel.TAG, "Post-startup refresh addon catalogs refreshed=${refreshedCatalogCount.get()}")
                    withContext(Dispatchers.Main.immediate) {
                        if (!isCurrentHomeProfileGeneration(expectedGeneration)) return@withContext
                        scheduleUpdateCatalogRows()
                    }
                } catch (e: kotlinx.coroutines.CancellationException) {
                    throw e
                } catch (t: Throwable) {
                    Log.w(HomeViewModel.TAG, "Unexpected failure during addon refresh in serialized startup pipeline", t)
                }
            }
        )
        refreshJobs.joinAll()
    }
    if (!isCurrentHomeProfileGeneration(expectedGeneration)) {
        Log.d(HomeViewModel.TAG, "Skipping stale serialized home refresh settlement generation=$expectedGeneration")
        return
    }

    val afterTraktSnapshot = if (activeProfileTraktAuthenticated) {
        traktDiscoveryService.observeSnapshot(autoRefreshOnStart = false).first()
    } else {
        com.nexio.tv.data.repository.TraktDiscoverySnapshot()
    }
    val afterSimklSnapshot = simklDiscoveryService.observeSnapshot(autoRefreshOnStart = false).first()
    val afterMdbSnapshot = mdbListDiscoveryService.observeSnapshot(autoRefreshOnStart = false).first()
    val afterTmdbSnapshot = tmdbDiscoveryService.observeSnapshot().first()
    val hydratedAfterTraktSnapshot = applyTomatoesOverridesToTraktSnapshot(
        afterTraktSnapshot,
        syntheticTomatoesOverridesByItemId
    )
    val hydratedAfterMdbSnapshot = applyTomatoesOverridesToMDBListSnapshot(
        afterMdbSnapshot,
        syntheticTomatoesOverridesByItemId
    )
    val traktBeforeKeys = traktSnapshotItemKeys(beforeTraktSnapshot)
    val traktAfterKeys = traktSnapshotItemKeys(hydratedAfterTraktSnapshot)
    val simklBeforeKeys = simklSnapshotItemKeys(beforeSimklSnapshot)
    val simklAfterKeys = simklSnapshotItemKeys(afterSimklSnapshot)
    val mdbBeforeKeys = mdbSnapshotItemKeys(beforeMdbSnapshot)
    val mdbAfterKeys = mdbSnapshotItemKeys(hydratedAfterMdbSnapshot)
    val tmdbBeforeKeys = tmdbSnapshotItemKeys(beforeTmdbSnapshot)
    val tmdbAfterKeys = tmdbSnapshotItemKeys(afterTmdbSnapshot)
    if (activeProfileTraktAuthenticated) {
        traktDiscoverySnapshot = hydratedAfterTraktSnapshot
        persistedTraktDiscoverySnapshot = hydratedAfterTraktSnapshot
    }
    simklDiscoverySnapshot = afterSimklSnapshot
    persistedSimklDiscoverySnapshot = afterSimklSnapshot
    mdbListDiscoverySnapshot = hydratedAfterMdbSnapshot
    persistedMDBListDiscoverySnapshot = hydratedAfterMdbSnapshot
    tmdbDiscoverySnapshot = afterTmdbSnapshot

    logStartupPerf(
        "synthetic_refresh_end",
        "trakt_total=${traktAfterKeys.size} trakt_added=${(traktAfterKeys - traktBeforeKeys).size} trakt_retained=${(traktAfterKeys intersect traktBeforeKeys).size} " +
            "simkl_total=${simklAfterKeys.size} simkl_added=${(simklAfterKeys - simklBeforeKeys).size} simkl_retained=${(simklAfterKeys intersect simklBeforeKeys).size} " +
            "mdb_total=${mdbAfterKeys.size} mdb_added=${(mdbAfterKeys - mdbBeforeKeys).size} mdb_retained=${(mdbAfterKeys intersect mdbBeforeKeys).size} " +
            "tmdb_total=${tmdbAfterKeys.size} tmdb_added=${(tmdbAfterKeys - tmdbBeforeKeys).size} tmdb_retained=${(tmdbAfterKeys intersect tmdbBeforeKeys).size}"
    )

    Log.d(
        HomeViewModel.TAG,
        "Post-startup refresh settled synthetic snapshot traktGroups=${persistedTraktSyntheticGroups.size} traktRows=${persistedTraktSyntheticGroups.sumOf { it.rows.size }} " +
            "simklGroups=${persistedSimklSyntheticGroups.size} simklRows=${persistedSimklSyntheticGroups.sumOf { it.rows.size }} " +
            "mdbGroups=${persistedMDBListSyntheticGroups.size} mdbRows=${persistedMDBListSyntheticGroups.sumOf { it.rows.size }} " +
            "tmdbGroups=${persistedTmdbSyntheticGroups.size} tmdbRows=${persistedTmdbSyntheticGroups.sumOf { it.rows.size }}"
    )

    // Recompute rows once at the end so Home settles on the renewed merged snapshot.
    runCatching {
        lastCatalogComputationSignature = null
        updateCatalogRowsPipeline()
    }

    val activeCatalogItemKeys = (_fullCatalogRows.value.asSequence() + catalogsMap.values.asSequence())
        .flatMap { row -> row.items.asSequence() }
        .map { item -> "${item.apiType}:${item.id}" }
        .toSet()
    if (refreshedCatalogCount.get() == 0 && activeCatalogItemKeys.isNotEmpty()) {
        val visibleItems = _fullCatalogRows.value
            .asSequence()
            .flatMap { row -> row.items.asSequence() }
            .toList()
        homeCatalogRefreshCoordinator.hydrateAndPrefetchVisibleItems(
            items = visibleItems,
            telemetryEnabled = startupPerfTelemetryEnabled,
            onLog = { event, details -> logStartupPerf(event, details) }
        )
    }
    val activeContinueWatchingItemKeys = _uiState.value.continueWatchingItems
        .map { item -> "${item.contentType()}:${item.contentId()}" }
        .toSet()
    if (activeCatalogItemKeys.isEmpty() && activeContinueWatchingItemKeys.isEmpty()) {
        logStartupPerf("metadata_cleanup_skipped", "reason=active_items_empty")
    } else {
        metadataDiskCacheStore.replaceHomeFeedReferences(
            feedKey = "home_catalog_snapshot",
            itemKeys = activeCatalogItemKeys
        )
        metadataDiskCacheStore.replaceHomeFeedReferences(
            feedKey = "continue_watching",
            itemKeys = activeContinueWatchingItemKeys
        )
        val removedCleanupUrls = metadataDiskCacheStore.removeHomeUnreferencedMetaEntries(
            maxEntries = 800
        )
        homeCatalogRefreshCoordinator.evictCachedImageUrls(removedCleanupUrls)
        logStartupPerf(
            "metadata_cleanup_end",
            "active_items=${activeCatalogItemKeys.size + activeContinueWatchingItemKeys.size} removed_image_urls=${removedCleanupUrls.size}"
        )
    }
    Log.d(
        HomeViewModel.TAG,
        "Post-startup refresh pipeline end activeCatalogItems=${activeCatalogItemKeys.size} activeContinueWatching=${activeContinueWatchingItemKeys.size}"
    )
    runCatching { warmContinueWatchingRuntimeIfNeededPipeline() }
        .onFailure { error ->
            Log.w(HomeViewModel.TAG, "Failed continue watching runtime warmup", error)
        }
    startupRefreshPending = false
}

private fun traktSnapshotItemKeys(
    snapshot: com.nexio.tv.data.repository.TraktDiscoverySnapshot
): Set<String> {
    return buildSet {
        snapshot.calendarItems.forEach { add("${it.apiType}:${it.id}") }
        snapshot.recommendationMovieItems.forEach { add("${it.apiType}:${it.id}") }
        snapshot.recommendationShowItems.forEach { add("${it.apiType}:${it.id}") }
        snapshot.trendingMovieItems.forEach { add("${it.apiType}:${it.id}") }
        snapshot.trendingShowItems.forEach { add("${it.apiType}:${it.id}") }
        snapshot.popularMovieItems.forEach { add("${it.apiType}:${it.id}") }
        snapshot.popularShowItems.forEach { add("${it.apiType}:${it.id}") }
        snapshot.customListCatalogs.forEach { catalog ->
            catalog.items.forEach { add("${it.apiType}:${it.id}") }
        }
    }
}

private fun simklSnapshotItemKeys(
    snapshot: com.nexio.tv.data.repository.SimklDiscoverySnapshot
): Set<String> {
    return buildSet {
        snapshot.itemsByCatalog.values.flatten().forEach { add("${it.apiType}:${it.id}") }
    }
}

private fun mdbSnapshotItemKeys(
    snapshot: com.nexio.tv.data.repository.MDBListDiscoverySnapshot
): Set<String> {
    return buildSet {
        snapshot.customListCatalogs.forEach { catalog ->
            catalog.items.forEach { add("${it.apiType}:${it.id}") }
        }
    }
}

private fun tmdbSnapshotItemKeys(
    snapshot: com.nexio.tv.data.repository.TmdbDiscoverySnapshot
): Set<String> {
    return buildSet {
        snapshot.rowsByCatalog.values.forEach { row ->
            row.items.forEach { add("${it.apiType}:${it.id}") }
        }
    }
}

internal suspend fun HomeViewModel.reloadPersistedSyntheticCatalogRowsPipeline() {
    val profileId = profileManager.activeProfileId.value
    val (snapshot, providerState) = withContext(Dispatchers.IO) {
        val restoredSnapshot = syntheticHomeCatalogStore.read(profileId = profileId)
            ?: com.nexio.tv.data.local.SyntheticHomeCatalogStore.Snapshot()
        restoredSnapshot to trackingProviderStateService.currentState()
    }
    withContext(Dispatchers.Main.immediate) {
        persistedTraktSyntheticGroups = if (providerState.traktAuthenticated) snapshot.traktGroups else emptyList()
        persistedSimklSyntheticGroups = snapshot.simklGroups
        persistedMDBListSyntheticGroups = snapshot.mdbListGroups
        applyPersistedTmdbSyntheticSnapshot(snapshot)
    }
}

internal fun HomeViewModel.applyPersistedTmdbSyntheticSnapshot(snapshot: SyntheticHomeCatalogStore.Snapshot) {
    persistedTmdbSyntheticGroups = snapshot.tmdbGroups
    persistedTmdbSyntheticIncludeAdult = snapshot.tmdbIncludeAdult
    persistedTmdbSyntheticHideUnreleasedDigital = snapshot.tmdbHideUnreleasedDigital
}

internal fun HomeViewModel.clearPersistedTmdbSyntheticGroups() {
    persistedTmdbSyntheticGroups = emptyList()
    persistedTmdbSyntheticIncludeAdult = null
    persistedTmdbSyntheticHideUnreleasedDigital = null
}

private fun HomeViewModel.persistedTmdbSyntheticGroupsMatchingPreferences(
    prefs: TmdbCatalogPreferences
): List<PersistedSyntheticCatalogGroup> {
    return tmdbGroupsMatchPreferences(
        groups = persistedTmdbSyntheticGroups,
        includeAdult = persistedTmdbSyntheticIncludeAdult,
        hideUnreleasedDigital = persistedTmdbSyntheticHideUnreleasedDigital,
        prefs = prefs
    )
}

private fun HomeViewModel.syntheticHomeSnapshotFallback(
    traktGroups: List<PersistedSyntheticCatalogGroup> = persistedTraktSyntheticGroups,
    simklGroups: List<PersistedSyntheticCatalogGroup> = persistedSimklSyntheticGroups,
    mdbListGroups: List<PersistedSyntheticCatalogGroup> = persistedMDBListSyntheticGroups,
    tmdbGroups: List<PersistedSyntheticCatalogGroup> = persistedTmdbSyntheticGroups
): SyntheticHomeCatalogStore.Snapshot {
    return SyntheticHomeCatalogStore.Snapshot(
        traktGroups = traktGroups,
        simklGroups = simklGroups,
        mdbListGroups = mdbListGroups,
        tmdbGroups = tmdbGroups,
        tmdbIncludeAdult = persistedTmdbSyntheticIncludeAdult,
        tmdbHideUnreleasedDigital = persistedTmdbSyntheticHideUnreleasedDigital
    )
}

private fun SyntheticHomeCatalogStore.Snapshot.withCurrentTmdbPreferenceProvenance(
    groups: List<PersistedSyntheticCatalogGroup>,
    prefs: TmdbCatalogPreferences
): SyntheticHomeCatalogStore.Snapshot {
    val sanitized = prefs.sanitized()
    return copy(
        tmdbGroups = groups,
        tmdbIncludeAdult = sanitized.includeAdult,
        tmdbHideUnreleasedDigital = sanitized.hideUnreleasedDigital
    )
}

internal suspend fun HomeViewModel.renewTraktSyntheticSnapshotPipeline(
    snapshot: com.nexio.tv.data.repository.TraktDiscoverySnapshot
) {
    val profileId = profileManager.activeProfileId.value
    if (!activeProfileTraktAuthenticated) {
        clearTraktHomeState("renew_trakt_synthetic_unauthenticated")
        syntheticCatalogStoreMutex.withLock {
            withContext(Dispatchers.IO) {
                val existingSnapshot = syntheticHomeCatalogStore.read(profileId = profileId)
                    ?: syntheticHomeSnapshotFallback(
                        traktGroups = emptyList(),
                        simklGroups = persistedSimklSyntheticGroups,
                        mdbListGroups = persistedMDBListSyntheticGroups
                    )
                syntheticHomeCatalogStore.write(existingSnapshot.copy(traktGroups = emptyList()), profileId = profileId)
            }
        }
        return
    }
    val traktUpNextItems = _uiState.value.traktUpNextItems
        .take(20)
        .map(::nextUpToMetaPreview)
    val traktPrefsSnapshot = traktCatalogPreferences
    val telemetryEnabled = startupPerfTelemetryEnabled
    var appliedTraktGroups: List<PersistedSyntheticCatalogGroup>? = null

    syntheticCatalogStoreMutex.withLock {
        withContext(Dispatchers.IO) {
            val existingSnapshot = syntheticHomeCatalogStore.read(profileId = profileId)
                ?: syntheticHomeSnapshotFallback()
            val liveGroups = buildConfiguredCatalogPlan(
                addons = emptyList(),
                disabledHomeCatalogKeys = emptySet(),
                availableAddonOrderKeys = emptySet(),
                traktPrefs = traktPrefsSnapshot,
                traktSnapshot = snapshot,
                hasTraktUpNextItems = traktUpNextItems.isNotEmpty(),
                traktUpNextItems = traktUpNextItems,
                simklPrefs = SimklCatalogPreferences(),
                simklSnapshot = com.nexio.tv.data.repository.SimklDiscoverySnapshot(),
                mdbPrefs = MDBListCatalogPreferences(),
                mdbSnapshot = com.nexio.tv.data.repository.MDBListDiscoverySnapshot()
            ).rails
                .filter { rail -> rail.descriptor.addonId == TRAKT_HOME_ADDON_ID }
                .mapNotNull { rail ->
                    val rows = rail.toPopulatedRows()
                    if (rows.isEmpty()) null else SyntheticCatalogOrderGroup(orderKey = rail.orderKey, rows = rows)
                }
            val existingRowsByKey = (
                existingSnapshot.traktGroups +
                    existingSnapshot.simklGroups +
                    existingSnapshot.mdbListGroups +
                    existingSnapshot.tmdbGroups
                )
                .flatMap { it.rows }
                .associateBy(::homeCatalogGlobalKey)
            val hydratedRows = homeCatalogRefreshCoordinator.hydrateAndPrefetchRows(
                rows = liveGroups.flatMap { it.rows },
                existingRowsByKey = existingRowsByKey,
                telemetryEnabled = telemetryEnabled,
                onLog = { event, details -> logStartupPerf(event, details) }
            )
            val renewedTraktGroups = liveGroups
                .replaceRows(hydratedRows)
                .toPersistedSyntheticCatalogGroups()
            val effectiveTraktGroups = if (
                renewedTraktGroups.isEmpty() &&
                existingSnapshot.traktGroups.isNotEmpty() &&
                shouldRefreshTraktDiscoveryForState(traktPrefsSnapshot, snapshot)
            ) {
                existingSnapshot.traktGroups
            } else {
                renewedTraktGroups
            }
            val renewedSnapshot = existingSnapshot.copy(traktGroups = effectiveTraktGroups)
            if (renewedSnapshot == existingSnapshot) {
                return@withContext
            }
            syntheticHomeCatalogStore.write(renewedSnapshot, profileId = profileId)
            appliedTraktGroups = effectiveTraktGroups
        }
    }
    appliedTraktGroups?.let { groups ->
        withContext(Dispatchers.Main.immediate) {
            persistedTraktSyntheticGroups = groups
        }
    }
}

internal suspend fun HomeViewModel.renewSimklSyntheticSnapshotPipeline(
    snapshot: com.nexio.tv.data.repository.SimklDiscoverySnapshot
) {
    val profileId = profileManager.activeProfileId.value
    val simklPrefsSnapshot = simklCatalogPreferences
    val telemetryEnabled = startupPerfTelemetryEnabled
    var appliedSimklGroups: List<PersistedSyntheticCatalogGroup>? = null

    syntheticCatalogStoreMutex.withLock {
        withContext(Dispatchers.IO) {
            val existingSnapshot = syntheticHomeCatalogStore.read(profileId = profileId)
                ?: syntheticHomeSnapshotFallback()
            val liveGroups = buildConfiguredCatalogPlan(
                addons = emptyList(),
                disabledHomeCatalogKeys = emptySet(),
                availableAddonOrderKeys = emptySet(),
                traktPrefs = TraktCatalogPreferences(enabledCatalogs = emptySet(), catalogOrder = emptyList()),
                traktSnapshot = com.nexio.tv.data.repository.TraktDiscoverySnapshot(),
                hasTraktUpNextItems = false,
                simklPrefs = simklPrefsSnapshot,
                simklSnapshot = snapshot,
                mdbPrefs = MDBListCatalogPreferences(),
                mdbSnapshot = com.nexio.tv.data.repository.MDBListDiscoverySnapshot()
            ).rails
                .filter { rail -> rail.descriptor.addonId == SIMKL_HOME_ADDON_ID }
                .mapNotNull { rail ->
                    val rows = rail.toPopulatedRows()
                    if (rows.isEmpty()) null else SyntheticCatalogOrderGroup(orderKey = rail.orderKey, rows = rows)
                }
            val existingRowsByKey = (
                existingSnapshot.traktGroups +
                    existingSnapshot.simklGroups +
                    existingSnapshot.mdbListGroups +
                    existingSnapshot.tmdbGroups
                )
                .flatMap { it.rows }
                .associateBy(::homeCatalogGlobalKey)
            val hydratedRows = homeCatalogRefreshCoordinator.hydrateAndPrefetchRows(
                rows = liveGroups.flatMap { it.rows },
                existingRowsByKey = existingRowsByKey,
                telemetryEnabled = telemetryEnabled,
                onLog = { event, details -> logStartupPerf(event, details) }
            )
            val renewedSimklGroups = liveGroups
                .replaceRows(hydratedRows)
                .toPersistedSyntheticCatalogGroups()
            val effectiveSimklGroups = if (
                renewedSimklGroups.isEmpty() &&
                existingSnapshot.simklGroups.isNotEmpty() &&
                shouldRefreshSimklDiscoveryForState(simklPrefsSnapshot, snapshot)
            ) {
                existingSnapshot.simklGroups
            } else {
                renewedSimklGroups
            }
            val renewedSnapshot = existingSnapshot.copy(simklGroups = effectiveSimklGroups)
            if (renewedSnapshot == existingSnapshot) {
                return@withContext
            }
            syntheticHomeCatalogStore.write(renewedSnapshot, profileId = profileId)
            appliedSimklGroups = effectiveSimklGroups
        }
    }
    appliedSimklGroups?.let { groups ->
        withContext(Dispatchers.Main.immediate) {
            persistedSimklSyntheticGroups = groups
        }
    }
}

internal suspend fun HomeViewModel.renewMDBListSyntheticSnapshotPipeline(
    snapshot: com.nexio.tv.data.repository.MDBListDiscoverySnapshot
) {
    val profileId = profileManager.activeProfileId.value
    val mdbPrefsSnapshot = mdbListCatalogPreferences
    val telemetryEnabled = startupPerfTelemetryEnabled
    var appliedMDBListGroups: List<PersistedSyntheticCatalogGroup>? = null

    syntheticCatalogStoreMutex.withLock {
        withContext(Dispatchers.IO) {
            val existingSnapshot = syntheticHomeCatalogStore.read(profileId = profileId)
                ?: syntheticHomeSnapshotFallback()
            val liveGroups = buildSyntheticMDBListRows(
                prefs = mdbPrefsSnapshot,
                snapshot = snapshot
            )
            val existingRowsByKey = (
                existingSnapshot.traktGroups +
                    existingSnapshot.simklGroups +
                    existingSnapshot.mdbListGroups +
                    existingSnapshot.tmdbGroups
                )
                .flatMap { it.rows }
                .associateBy(::homeCatalogGlobalKey)
            val hydratedRows = homeCatalogRefreshCoordinator.hydrateAndPrefetchRows(
                rows = liveGroups.flatMap { it.rows },
                existingRowsByKey = existingRowsByKey,
                telemetryEnabled = telemetryEnabled,
                onLog = { event, details -> logStartupPerf(event, details) }
            )
            val renewedMDBListGroups = liveGroups
                .replaceRows(hydratedRows)
                .toPersistedSyntheticCatalogGroups()
            val effectiveMDBListGroups = if (
                renewedMDBListGroups.isEmpty() &&
                existingSnapshot.mdbListGroups.isNotEmpty() &&
                shouldRefreshMDBListDiscoveryForState(mdbPrefsSnapshot, snapshot)
            ) {
                existingSnapshot.mdbListGroups
            } else {
                renewedMDBListGroups
            }
            val renewedSnapshot = existingSnapshot.copy(mdbListGroups = effectiveMDBListGroups)
            if (renewedSnapshot == existingSnapshot) {
                return@withContext
            }
            syntheticHomeCatalogStore.write(renewedSnapshot, profileId = profileId)
            appliedMDBListGroups = effectiveMDBListGroups
        }
    }
    appliedMDBListGroups?.let { groups ->
        withContext(Dispatchers.Main.immediate) {
            persistedMDBListSyntheticGroups = groups
        }
    }
}

internal suspend fun HomeViewModel.renewTmdbSyntheticSnapshotPipeline(
    snapshot: com.nexio.tv.data.repository.TmdbDiscoverySnapshot
) {
    val profileId = profileManager.activeProfileId.value
    val tmdbPrefsSnapshot = tmdbCatalogPreferences
    val telemetryEnabled = startupPerfTelemetryEnabled
    var appliedTmdbSnapshot: SyntheticHomeCatalogStore.Snapshot? = null

    syntheticCatalogStoreMutex.withLock {
        withContext(Dispatchers.IO) {
            val existingSnapshot = syntheticHomeCatalogStore.read(profileId = profileId)
                ?: syntheticHomeSnapshotFallback()
            val liveGroups = buildConfiguredCatalogPlan(
                addons = emptyList(),
                disabledHomeCatalogKeys = emptySet(),
                availableAddonOrderKeys = emptySet(),
                traktPrefs = TraktCatalogPreferences(enabledCatalogs = emptySet(), catalogOrder = emptyList()),
                traktSnapshot = com.nexio.tv.data.repository.TraktDiscoverySnapshot(),
                hasTraktUpNextItems = false,
                simklPrefs = SimklCatalogPreferences(enabledCatalogs = emptySet(), catalogOrder = emptyList()),
                simklSnapshot = com.nexio.tv.data.repository.SimklDiscoverySnapshot(),
                mdbPrefs = MDBListCatalogPreferences(),
                mdbSnapshot = com.nexio.tv.data.repository.MDBListDiscoverySnapshot(),
                tmdbPrefs = tmdbPrefsSnapshot,
                tmdbSnapshot = snapshot
            ).rails
                .filter { rail -> rail.descriptor.addonId == TMDB_HOME_ADDON_ID }
                .mapNotNull { rail ->
                    val rows = rail.toPopulatedRows()
                    if (rows.isEmpty()) null else SyntheticCatalogOrderGroup(orderKey = rail.orderKey, rows = rows)
                }
            val existingRowsByKey = (
                existingSnapshot.traktGroups +
                    existingSnapshot.simklGroups +
                    existingSnapshot.mdbListGroups +
                    existingSnapshot.tmdbGroups
                )
                .flatMap { it.rows }
                .associateBy(::homeCatalogGlobalKey)
            val hydratedRows = homeCatalogRefreshCoordinator.hydrateAndPrefetchRows(
                rows = liveGroups.flatMap { it.rows },
                existingRowsByKey = existingRowsByKey,
                telemetryEnabled = telemetryEnabled,
                onLog = { event, details -> logStartupPerf(event, details) }
            )
            val renewedTmdbGroups = liveGroups
                .replaceRows(hydratedRows)
                .toPersistedSyntheticCatalogGroups()
            val effectiveTmdbGroups = resolveEffectiveTmdbSyntheticGroups(
                renewedTmdbGroups = renewedTmdbGroups,
                existingSnapshot = existingSnapshot,
                prefs = tmdbPrefsSnapshot,
                snapshot = snapshot
            )
            val preservedExistingTmdbGroups = renewedTmdbGroups.isEmpty() &&
                effectiveTmdbGroups.isNotEmpty() &&
                effectiveTmdbGroups == existingSnapshot.tmdbGroupsMatchingPreferences(tmdbPrefsSnapshot)
            val renewedSnapshot = if (preservedExistingTmdbGroups) {
                existingSnapshot.copy(tmdbGroups = effectiveTmdbGroups)
            } else {
                existingSnapshot.withCurrentTmdbPreferenceProvenance(
                    groups = effectiveTmdbGroups,
                    prefs = tmdbPrefsSnapshot
                )
            }
            if (renewedSnapshot == existingSnapshot) {
                return@withContext
            }
            syntheticHomeCatalogStore.write(renewedSnapshot, profileId = profileId)
            appliedTmdbSnapshot = renewedSnapshot
        }
    }
    appliedTmdbSnapshot?.let { renewedSnapshot ->
        withContext(Dispatchers.Main.immediate) {
            applyPersistedTmdbSyntheticSnapshot(renewedSnapshot)
        }
    }
}

private fun List<SyntheticCatalogOrderGroup>.replaceRows(
    hydratedRows: List<CatalogRow>
): List<SyntheticCatalogOrderGroup> {
    val hydratedByKey = hydratedRows.associateBy(::homeCatalogGlobalKey)
    return map { group ->
        group.copy(
            rows = group.rows.map { row ->
                hydratedByKey[homeCatalogGlobalKey(row)] ?: row
            }
        )
    }
}

private fun List<SyntheticCatalogOrderGroup>.toPersistedSyntheticCatalogGroups(): List<PersistedSyntheticCatalogGroup> {
    return map { group ->
        PersistedSyntheticCatalogGroup(
            orderKey = group.orderKey,
            rows = group.rows
        )
    }
}

private fun List<PersistedSyntheticCatalogGroup>.toSyntheticCatalogOrderGroups(): List<SyntheticCatalogOrderGroup> {
    return map { group ->
        SyntheticCatalogOrderGroup(
            orderKey = group.orderKey,
            rows = group.rows
        )
    }
}

internal suspend fun HomeViewModel.loadAllCatalogsPipeline(
    addons: List<Addon>,
    forceReload: Boolean = false,
    allowNetworkRefresh: Boolean = true
) {
    fun hasSyntheticHomeSourcesConfigured(): Boolean {
        if ((activeProfileTraktAuthenticated && persistedTraktSyntheticGroups.isNotEmpty()) ||
            persistedSimklSyntheticGroups.isNotEmpty() ||
            persistedMDBListSyntheticGroups.isNotEmpty() ||
            persistedTmdbSyntheticGroups.isNotEmpty()
        ) {
            return true
        }
        if (activeProfileTraktAuthenticated && traktCatalogPreferences.enabledCatalogs.isNotEmpty()) {
            return true
        }
        if (simklCatalogPreferences.enabledCatalogs.isNotEmpty()) {
            return true
        }
        return mdbListCatalogPreferences.selectedTopListKeys.isNotEmpty() ||
            mdbListDiscoverySnapshot.personalLists.isNotEmpty() ||
            mdbListDiscoverySnapshot.customListCatalogs.isNotEmpty() ||
            tmdbCatalogPreferences.enabledCatalogs.isNotEmpty()
    }

    val signature = buildHomeCatalogLoadSignature(addons)
    if (!forceReload &&
        signature == activeCatalogLoadSignature &&
        (catalogsLoadInProgress || catalogsMap.isNotEmpty())
    ) {
        return
    }

    activeCatalogLoadSignature = signature
    catalogsLoadInProgress = true
    catalogLoadGeneration += 1
    val generation = catalogLoadGeneration
    cancelInFlightCatalogLoads()

    _uiState.update { it.copy(isLoading = true, error = null, installedAddonsCount = addons.size) }
    posterStatusReconcileJob?.cancel()
    externalMetaPrefetchJob?.cancel()
    pendingExternalMetaPrefetchItemId = null
    prefetchedExternalMetaIds.clear()
    externalMetaPrefetchInFlightIds.clear()
    prefetchedTomatoesIds.clear()
    tomatoesEnrichmentInFlightIds.clear()
    prefetchedTmdbIds.clear()
    tmdbEnrichFocusJob?.cancel()
    pendingTmdbEnrichItemId = null
    setEnrichingItemId(null)

    try {
        val hasRestoredContent = _uiState.value.catalogRows.any { it.items.isNotEmpty() } ||
            _uiState.value.heroItems.isNotEmpty()
        val activeRefreshInProgress = isConfiguredHomeRefreshInProgress(
            catalogsLoadInProgress = catalogsLoadInProgress,
            traktDiscoveryRefreshInProgress = traktDiscoveryRefreshInProgress,
            simklDiscoveryRefreshInProgress = simklDiscoveryRefreshInProgress,
            mdbListDiscoveryRefreshInProgress = mdbListDiscoveryRefreshInProgress,
            tmdbDiscoveryRefreshInProgress = tmdbDiscoveryRefreshInProgress
        )
        val refreshInProgress = startupRefreshPending || activeRefreshInProgress
        val shouldPreserveCachedHome =
            hasPersistedCatalogSnapshot && (restoredCatalogSnapshotActive || hasRestoredContent || refreshInProgress)
        val syntheticHomeConfigured = hasSyntheticHomeSourcesConfigured()

        if (addons.isEmpty()) {
            if (shouldPreserveCachedHome || syntheticHomeConfigured) {
                catalogsLoadInProgress = false
                _uiState.update { it.copy(isLoading = true, error = null, installedAddonsCount = 0) }
                scheduleUpdateCatalogRows()
                return
            }
            catalogOrder.clear()
            catalogsMap.clear()
            reconcilePosterStatusObserversPipeline(emptyList())
            _fullCatalogRows.value = emptyList()
            homeSnapshotPersistJob?.cancel()
            pendingHomeSnapshotPersist = null
            inMemoryHomeSnapshot = null
            pendingRestoredCatalogSnapshot = null
            homeSnapshotPersistGeneration += 1
            hasPersistedCatalogSnapshot = false
            restoredCatalogSnapshotActive = false
            homeCatalogSnapshotStore.clear(profileId = profileManager.activeProfileId.value)
            truncatedRowCache.clear()
            hasRenderedFirstCatalog = false
            trailerPreviewLoadingIds.clear()
            trailerPreviewNegativeCache.clear()
            trailerPreviewUrlsState.clear()
            trailerPreviewAudioUrlsState.clear()
            trailerPreviewExternalUrlsState.clear()
            clearTrailerMetadataAvailabilityPipeline()
            activeTrailerPreviewItemId = null
            trailerPreviewRequestVersion = 0L
            lastCatalogComputationSignature = null
            lastCatalogOrderDiagnosticsSignature = null
            lastHeroEnrichmentSignature = null
            lastHeroEnrichedItems = emptyList()
            catalogsLoadInProgress = false
            _uiState.update { it.copy(isLoading = false, error = "No addons installed") }
            return
        }

        rebuildCatalogOrder(addons)

        if (catalogOrder.isEmpty()) {
            if (shouldPreserveCachedHome || syntheticHomeConfigured) {
                catalogsLoadInProgress = false
                _uiState.update { it.copy(isLoading = true, error = null, installedAddonsCount = addons.size) }
                scheduleUpdateCatalogRows()
                return
            }
            hasPersistedCatalogSnapshot = false
            startupRefreshPending = false
            homeSnapshotPersistJob?.cancel()
            pendingHomeSnapshotPersist = null
            inMemoryHomeSnapshot = null
            pendingRestoredCatalogSnapshot = null
            homeSnapshotPersistGeneration += 1
            lastCatalogComputationSignature = null
            lastCatalogOrderDiagnosticsSignature = null
            restoredCatalogSnapshotActive = false
            homeCatalogSnapshotStore.clear(profileId = profileManager.activeProfileId.value)
            trailerPreviewLoadingIds.clear()
            trailerPreviewNegativeCache.clear()
            trailerPreviewUrlsState.clear()
            trailerPreviewAudioUrlsState.clear()
            trailerPreviewExternalUrlsState.clear()
            clearTrailerMetadataAvailabilityPipeline()
            activeTrailerPreviewItemId = null
            trailerPreviewRequestVersion = 0L
            catalogsLoadInProgress = false
            _uiState.update { it.copy(isLoading = false, error = "No catalog addons installed") }
            return
        }

        val catalogsToLoad = addons.flatMap { addon ->
            addon.catalogs
                .filterNot {
                    it.isSearchOnlyCatalog() || isCatalogDisabled(
                        addonBaseUrl = addon.baseUrl,
                        addonId = addon.id,
                        type = it.apiType,
                        catalogId = it.id,
                        catalogName = it.name
                    )
                }
                .map { catalog -> addon to catalog }
        }
        val allowedCatalogKeys = catalogsToLoad
            .mapTo(linkedSetOf()) { (addon, catalog) ->
                catalogKey(
                    addonId = addon.id,
                    type = catalog.apiType,
                    catalogId = catalog.id
                )
            }
        val staleCatalogKeys = catalogsMap.keys.filterNot { it in allowedCatalogKeys }
        if (staleCatalogKeys.isNotEmpty()) {
            staleCatalogKeys.forEach { catalogsMap.remove(it) }
            scheduleUpdateCatalogRows()
        }
        trailerPreviewLoadingIds.clear()
        trailerPreviewNegativeCache.clear()
        trailerPreviewUrlsState.clear()
        trailerPreviewAudioUrlsState.clear()
        trailerPreviewExternalUrlsState.clear()
        activeTrailerPreviewItemId = null
        trailerPreviewRequestVersion = 0L
        pendingCatalogLoads = catalogsToLoad.size
        catalogsToLoad.forEach { (addon, catalog) ->
            loadCatalogPipeline(addon, catalog, generation, allowNetworkRefresh = allowNetworkRefresh)
        }
    } catch (e: Exception) {
        catalogsLoadInProgress = false
        _uiState.update { it.copy(isLoading = false, error = e.message) }
    }
}

internal fun HomeViewModel.loadCatalogPipeline(
    addon: Addon,
    catalog: CatalogDescriptor,
    generation: Long,
    allowNetworkRefresh: Boolean = true
) {
    val loadJob = viewModelScope.launch {
        var hasCountedCompletion = false
        val withStartupGate = shouldDeferStartupNetworkWork()
        suspend fun runCatalogLoad() {
            if (generation != catalogLoadGeneration) return
            if (!isNonPlaybackHomeWorkAllowed()) {
                if (!hasCountedCompletion) {
                    pendingCatalogLoads = (pendingCatalogLoads - 1).coerceAtLeast(0)
                    hasCountedCompletion = true
                }
                if (pendingCatalogLoads == 0) {
                    catalogsLoadInProgress = false
                }
                return
            }
            val supportsSkip = catalog.supportsExtra("skip")
            val skipStep = catalog.skipStep()
            Log.d(
                HomeViewModel.TAG,
                "Loading home catalog addonId=${addon.id} addonName=${addon.name} type=${catalog.apiType} catalogId=${catalog.id} catalogName=${catalog.name} supportsSkip=$supportsSkip skipStep=$skipStep"
            )
            catalogRepository.getCatalogCachedFirst(
                addonBaseUrl = addon.baseUrl,
                addonId = addon.id,
                addonName = addon.displayName,
                catalogId = catalog.id,
                catalogName = catalog.name,
                type = catalog.apiType,
                skip = 0,
                skipStep = skipStep,
                supportsSkip = supportsSkip,
                allowNetworkRefresh = allowNetworkRefresh && !shouldDeferStartupNetworkWork()
            ).collect { result ->
                if (generation != catalogLoadGeneration) return@collect
                when (result) {
                    is NetworkResult.Success -> {
                        val key = catalogKey(
                            addonId = addon.id,
                            type = catalog.apiType,
                            catalogId = catalog.id
                        )
                        catalogsMap[key] = result.data
                        if (!hasCountedCompletion) {
                            pendingCatalogLoads = (pendingCatalogLoads - 1).coerceAtLeast(0)
                            hasCountedCompletion = true
                        }
                        Log.d(
                            HomeViewModel.TAG,
                            "Home catalog loaded addonId=${addon.id} type=${catalog.apiType} catalogId=${catalog.id} items=${result.data.items.size} pending=$pendingCatalogLoads"
                        )
                        if (withStartupGate) {
                            logStartupPerf(
                                "catalog_cache_applied",
                                "catalogKey=$key items=${result.data.items.size}"
                            )
                        }
                        if (pendingCatalogLoads == 0) {
                            catalogsLoadInProgress = false
                        }
                        scheduleUpdateCatalogRows()
                    }
                    is NetworkResult.Error -> {
                        if (!hasCountedCompletion) {
                            pendingCatalogLoads = (pendingCatalogLoads - 1).coerceAtLeast(0)
                            hasCountedCompletion = true
                        }
                        Log.w(
                            HomeViewModel.TAG,
                            "Home catalog failed addonId=${addon.id} type=${catalog.apiType} catalogId=${catalog.id} code=${result.code} message=${result.message}"
                        )
                        if (pendingCatalogLoads == 0) {
                            catalogsLoadInProgress = false
                        }
                        scheduleUpdateCatalogRows()
                    }
                    NetworkResult.Loading -> {
                        /* Handled by individual row */
                    }
                }
            }
        }
        if (withStartupGate) {
            startupCatalogLoadSemaphore.withPermit {
                catalogLoadSemaphore.withPermit {
                    runCatalogLoad()
                }
            }
        } else {
            catalogLoadSemaphore.withPermit {
                runCatalogLoad()
            }
        }
    }
    registerCatalogLoadJob(loadJob)
}

internal fun HomeViewModel.loadMoreCatalogItemsPipeline(catalogId: String, addonId: String, type: String) {
    val key = catalogKey(addonId = addonId, type = type, catalogId = catalogId)
    val currentRow = catalogsMap[key] ?: return

    if (currentRow.isLoading || !currentRow.hasMore) return
    if (key in _loadingCatalogs.value) return

    catalogsMap[key] = currentRow.copy(isLoading = true)
    _loadingCatalogs.update { it + key }

    viewModelScope.launch {
        val addon = addonsCache.find { it.id == addonId } ?: run {
            catalogsMap[key] = currentRow.copy(isLoading = false)
            _loadingCatalogs.update { it - key }
            scheduleUpdateCatalogRows()
            return@launch
        }

        val nextSkip = (currentRow.currentPage + 1) * currentRow.skipStep
        catalogRepository.getCatalog(
            addonBaseUrl = addon.baseUrl,
            addonId = addon.id,
            addonName = addon.displayName,
            catalogId = catalogId,
            catalogName = currentRow.catalogName,
            type = currentRow.apiType,
            skip = nextSkip,
            skipStep = currentRow.skipStep,
            supportsSkip = currentRow.supportsSkip
        ).collect { result ->
            when (result) {
                is NetworkResult.Success -> {
                    val existingIds = currentRow.items.asSequence()
                        .map { "${it.apiType}:${it.id}" }
                        .toHashSet()
                    val newUniqueItems = result.data.items.filter { item ->
                        "${item.apiType}:${item.id}" !in existingIds
                    }
                    val mergedItems = currentRow.items + newUniqueItems
                    val hasMore = if (newUniqueItems.isEmpty()) false else result.data.hasMore
                    catalogsMap[key] = result.data.copy(items = mergedItems, hasMore = hasMore)
                    _loadingCatalogs.update { it - key }
                    scheduleUpdateCatalogRows()
                }
                is NetworkResult.Error -> {
                    catalogsMap[key] = currentRow.copy(isLoading = false)
                    _loadingCatalogs.update { it - key }
                    scheduleUpdateCatalogRows()
                }
                NetworkResult.Loading -> { }
            }
        }
    }
}

internal suspend fun HomeViewModel.updateCatalogRowsPipeline() {
    catalogRowsComputationMutex.withLock {
    val orderedKeys = catalogOrder.toList()
    val catalogSnapshot = catalogsMap.toMap()
    val heroCatalogKeys = currentHeroCatalogKeys
    val currentState = _uiState.value
    val currentLayout = currentState.homeLayout
    val currentGridItems = currentState.gridItems
    val continueWatchingItems = currentState.continueWatchingItems
    val heroSectionEnabled = currentState.heroSectionEnabled
    val traktSnapshot = if (activeProfileTraktAuthenticated) {
        traktDiscoverySnapshot
    } else {
        com.nexio.tv.data.repository.TraktDiscoverySnapshot()
    }
    val traktPrefs = traktCatalogPreferences.onlyWhenAuthenticated(activeProfileTraktAuthenticated)
    val simklSnapshot = simklDiscoverySnapshot
    val simklPrefs = simklCatalogPreferences
    val mdbListSnapshot = mdbListDiscoverySnapshot
    val mdbListPrefs = mdbListCatalogPreferences
    val tmdbSnapshot = tmdbDiscoverySnapshot
    val tmdbPrefs = tmdbCatalogPreferences
    val currentVisibleFullRows = _fullCatalogRows.value
    val previousTruncatedRowCache = truncatedRowCache.toMap()
    val startupHydrationPending = !installedAddonsObserved ||
        !traktDiscoveryObserved ||
        !simklDiscoveryObserved ||
        !mdbListDiscoveryObserved ||
        !tmdbDiscoveryObserved
    val effectiveTraktSnapshot = if (
        traktSnapshot.updatedAtMs > 0L ||
        traktSnapshot.customListCatalogs.isNotEmpty() ||
        traktSnapshot.trendingMovieItems.isNotEmpty() ||
        traktSnapshot.trendingShowItems.isNotEmpty() ||
        traktSnapshot.popularMovieItems.isNotEmpty() ||
        traktSnapshot.popularShowItems.isNotEmpty() ||
        traktSnapshot.recommendationMovieItems.isNotEmpty() ||
        traktSnapshot.recommendationShowItems.isNotEmpty() ||
        traktSnapshot.calendarItems.isNotEmpty()
    ) {
        traktSnapshot
    } else {
        if (activeProfileTraktAuthenticated) persistedTraktDiscoverySnapshot else com.nexio.tv.data.repository.TraktDiscoverySnapshot()
    }
    val effectiveSimklSnapshot = if (
        simklSnapshot.updatedAtMs > 0L ||
        simklSnapshot.itemsByCatalog.values.any { it.isNotEmpty() }
    ) {
        simklSnapshot
    } else {
        persistedSimklDiscoverySnapshot
    }
    val effectiveMDBListSnapshot = if (
        mdbListSnapshot.updatedAtMs > 0L ||
        mdbListSnapshot.customListCatalogs.isNotEmpty() ||
        mdbListSnapshot.personalLists.isNotEmpty() ||
        mdbListSnapshot.topLists.isNotEmpty()
    ) {
        mdbListSnapshot
    } else {
        persistedMDBListDiscoverySnapshot
    }
    val effectiveTmdbSnapshot = tmdbSnapshot
    val recommendationRefMap = effectiveTraktSnapshot.recommendationRefsByStatusKey
    val addonExpectedOrderKeys = buildExpectedConfiguredAddonOrderKeys(
        addons = addonsCache,
        disabledHomeCatalogKeys = disabledHomeCatalogKeys
    )
    val catalogPlan = buildConfiguredCatalogPlan(
        addons = addonsCache,
        disabledHomeCatalogKeys = disabledHomeCatalogKeys,
        availableAddonOrderKeys = catalogSnapshot.keys,
        traktPrefs = traktPrefs,
        traktSnapshot = effectiveTraktSnapshot,
        hasTraktUpNextItems = activeProfileTraktAuthenticated && currentState.traktUpNextItems.isNotEmpty(),
        simklPrefs = simklPrefs,
        simklSnapshot = effectiveSimklSnapshot,
        mdbPrefs = mdbListPrefs,
        mdbSnapshot = effectiveMDBListSnapshot,
        tmdbPrefs = tmdbPrefs,
        tmdbSnapshot = effectiveTmdbSnapshot
    )
    val expectedConfiguredOrderKeys = catalogPlan.expectedOrderKeys
    val publishableExpectedOrderKeys = catalogPlan.publishableOrderKeys
    val activeRefreshInProgress = isConfiguredHomeRefreshInProgress(
        catalogsLoadInProgress = catalogsLoadInProgress,
        traktDiscoveryRefreshInProgress = traktDiscoveryRefreshInProgress,
        simklDiscoveryRefreshInProgress = simklDiscoveryRefreshInProgress,
        mdbListDiscoveryRefreshInProgress = mdbListDiscoveryRefreshInProgress,
        tmdbDiscoveryRefreshInProgress = tmdbDiscoveryRefreshInProgress
    )
    val refreshInProgress = startupRefreshPending || activeRefreshInProgress
    pendingRestoredCatalogSnapshot?.let { snapshot ->
        applyPersistedHomeSnapshotIfEligiblePipeline(snapshot, requireSourceCachesReady = false)
    }
    val currentPreferencePersistedTmdbSyntheticGroups = persistedTmdbSyntheticGroupsMatchingPreferences(tmdbPrefs)
    val computationSignature = withContext(Dispatchers.Default) {
        buildCatalogComputationSignature(
            orderedKeys = orderedKeys,
            catalogSnapshot = catalogSnapshot,
            heroCatalogKeys = heroCatalogKeys,
            currentLayout = currentLayout,
            heroSectionEnabled = heroSectionEnabled,
            continueWatchingItems = continueWatchingItems,
            traktSnapshot = effectiveTraktSnapshot,
            traktPrefs = traktPrefs,
            persistedTraktSyntheticGroups = if (activeProfileTraktAuthenticated) persistedTraktSyntheticGroups else emptyList(),
            simklSnapshot = effectiveSimklSnapshot,
            simklPrefs = simklPrefs,
            persistedSimklSyntheticGroups = persistedSimklSyntheticGroups,
            mdbListSnapshot = effectiveMDBListSnapshot,
            mdbListPrefs = mdbListPrefs,
            persistedMDBListSyntheticGroups = persistedMDBListSyntheticGroups,
            tmdbSnapshot = effectiveTmdbSnapshot,
            tmdbPrefs = tmdbPrefs,
            persistedTmdbSyntheticGroups = currentPreferencePersistedTmdbSyntheticGroups,
            disabledHomeCatalogKeys = disabledHomeCatalogKeys,
            startupHydrationPending = startupHydrationPending,
            refreshInProgress = refreshInProgress,
            hasPersistedCatalogSnapshot = hasPersistedCatalogSnapshot,
            restoredCatalogSnapshotActive = restoredCatalogSnapshotActive
        )
    }
    if (computationSignature == lastCatalogComputationSignature && !refreshInProgress) {
        return
    }
    lastCatalogComputationSignature = computationSignature

    val updateResult = withContext(Dispatchers.Default) {
        val syntheticTraktGroups = (if (activeProfileTraktAuthenticated) persistedTraktSyntheticGroups else emptyList())
            .toSyntheticCatalogOrderGroups()
            .filterNot { isSyntheticHomeCatalogDisabled(it.orderKey, disabledHomeCatalogKeys) }
        val syntheticSimklGroups = persistedSimklSyntheticGroups.toSyntheticCatalogOrderGroups()
            .filterNot { isSyntheticHomeCatalogDisabled(it.orderKey, disabledHomeCatalogKeys) }
        val syntheticMDBListGroups = persistedMDBListSyntheticGroups.toSyntheticCatalogOrderGroups()
            .filterNot { isSyntheticHomeCatalogDisabled(it.orderKey, disabledHomeCatalogKeys) }
        val syntheticTmdbGroups = currentPreferencePersistedTmdbSyntheticGroups.toSyntheticCatalogOrderGroups()
            .filterNot { isSyntheticHomeCatalogDisabled(it.orderKey, disabledHomeCatalogKeys) }
        val liveSyntheticGroups = catalogPlan.toPersistedSyntheticCatalogGroups()
            .toSyntheticCatalogOrderGroups()
            .filterNot { isSyntheticHomeCatalogDisabled(it.orderKey, disabledHomeCatalogKeys) }
        val rawRowsByKey = orderedKeys
            .mapNotNull { key ->
                catalogSnapshot[key]?.let { row ->
                    homeCatalogGlobalKey(row) to row
                }
            }
            .toMap(linkedMapOf())

        val persistedSyntheticGroups = syntheticTraktGroups + syntheticSimklGroups + syntheticMDBListGroups + syntheticTmdbGroups
        val persistedSyntheticOrderKeys = persistedSyntheticGroups.mapTo(mutableSetOf()) { it.orderKey }
        val syntheticGroups = persistedSyntheticGroups + liveSyntheticGroups.filterNot { it.orderKey in persistedSyntheticOrderKeys }
        val syntheticRowsByKey = linkedMapOf<String, List<CatalogRow>>().apply {
            syntheticGroups.forEach { group -> put(group.orderKey, group.rows) }
        }
        val existingRowsByOrderKey = linkedMapOf<String, CatalogRow>().apply {
            putAll(rawRowsByKey)
            syntheticGroups.forEach { group ->
                group.rows.firstOrNull()?.let { row -> put(group.orderKey, row) }
            }
        }
        val pendingRowsByKey = buildConfiguredCatalogPlan(
            addons = addonsCache,
            disabledHomeCatalogKeys = disabledHomeCatalogKeys,
            availableAddonOrderKeys = catalogSnapshot.keys,
            traktPrefs = traktPrefs,
            traktSnapshot = effectiveTraktSnapshot,
            hasTraktUpNextItems = activeProfileTraktAuthenticated && currentState.traktUpNextItems.isNotEmpty(),
            simklPrefs = simklPrefs,
            simklSnapshot = effectiveSimklSnapshot,
            mdbPrefs = mdbListPrefs,
            mdbSnapshot = effectiveMDBListSnapshot,
            tmdbPrefs = tmdbPrefs,
            tmdbSnapshot = effectiveTmdbSnapshot,
            existingRowsByOrderKey = existingRowsByOrderKey
        ).descriptors
            .filterNot { descriptor ->
                descriptor.orderKey in rawRowsByKey || descriptor.orderKey in syntheticRowsByKey
            }
            .associate { descriptor -> descriptor.orderKey to descriptor.toLoadingCatalogRow() }
        val rowOrderKeyByGlobalKey = linkedMapOf<String, String>().apply {
            rawRowsByKey.keys.forEach { globalKey -> put(globalKey, globalKey) }
            syntheticGroups.forEach { group ->
                group.rows.forEach { row ->
                    put(homeCatalogGlobalKey(row), group.orderKey)
                }
            }
            pendingRowsByKey.forEach { (orderKey, row) ->
                put(homeCatalogGlobalKey(row), orderKey)
            }
        }

        val defaultOrderKeys = buildList {
            addAll(syntheticGroups.map { it.orderKey })
            addAll(rawRowsByKey.keys)
            addAll(pendingRowsByKey.keys)
        }.distinct()

        val savedOrderKeys = homeCatalogOrderKeys
            .asSequence()
            .mapNotNull { rawKey -> resolveHomeOrderedKey(rawKey, defaultOrderKeys.toSet()) }
            .distinct()
            .toList()

        val savedOrderSet = savedOrderKeys.toSet()
        val effectiveOrderKeys = savedOrderKeys + defaultOrderKeys.filterNot { it in savedOrderSet }
        val orderDiagnosticsSignature = "${savedOrderKeys.hashCode()}:${defaultOrderKeys.hashCode()}:${effectiveOrderKeys.hashCode()}"
        val orderDiagnosticsMessage =
            "Catalog order reconciliation saved=${savedOrderKeys.size} default=${defaultOrderKeys.size} effective=${effectiveOrderKeys.size}"
        val combinedRows = buildList {
            effectiveOrderKeys.forEach { key ->
                syntheticRowsByKey[key]?.let { addAll(it) }
                    ?: rawRowsByKey[key]?.let { add(it) }
                    ?: pendingRowsByKey[key]?.let { add(it) }
            }
        }
        val liveOrderedRows = combinedRows

        val preservationState = CachedHomePreservationState(
            preserveAddonRows = hasPersistedCatalogSnapshot &&
                (restoredCatalogSnapshotActive || startupHydrationPending || startupRefreshPending || catalogsLoadInProgress),
            preserveTraktRows = shouldPreserveTraktCachedRows(
                snapshot = effectiveTraktSnapshot,
                refreshInProgress = startupHydrationPending || startupRefreshPending || traktDiscoveryRefreshInProgress
            ),
            preserveSimklRows = shouldPreserveSimklCachedRows(
                snapshot = effectiveSimklSnapshot,
                refreshInProgress = startupHydrationPending || startupRefreshPending || simklDiscoveryRefreshInProgress
            ),
            preserveMDBListRows = shouldPreserveMDBListCachedRows(
                snapshot = effectiveMDBListSnapshot,
                refreshInProgress = startupHydrationPending || startupRefreshPending || mdbListDiscoveryRefreshInProgress
            ),
            preserveTmdbRows = shouldPreserveTmdbCachedRows(
                snapshot = effectiveTmdbSnapshot,
                refreshInProgress = startupHydrationPending || startupRefreshPending || tmdbDiscoveryRefreshInProgress
            ),
            retainUnorderedRows = restoredCatalogSnapshotActive || startupHydrationPending || startupRefreshPending
        )
        val effectiveOrderedRows = mergeCachedRowsWithLiveRows(
            cachedRows = currentVisibleFullRows,
            liveRows = liveOrderedRows,
            preservationState = preservationState,
            orderedGroupKeys = effectiveOrderKeys,
            rowOrderKeyByGlobalKey = rowOrderKeyByGlobalKey
        )
        val selectedHeroCatalogSet = heroCatalogKeys.toSet()
        val selectedHeroRows = if (selectedHeroCatalogSet.isNotEmpty()) {
            effectiveOrderedRows.filter { row ->
                val key = "${row.addonId}_${row.apiType}_${row.catalogId}"
                key in selectedHeroCatalogSet
            }
        } else {
            emptyList()
        }
        val heroItemsFromSelectedCatalogs = selectedHeroRows
            .asSequence()
            .flatMap { row -> row.items.asSequence() }
            .filter { item -> item.hasHeroArtwork() }
            .shuffled()
            .take(7)
            .toList()
        val fallbackHeroItemsFromSelectedCatalogs = selectedHeroRows
            .asSequence()
            .flatMap { row -> row.items.asSequence() }
            .shuffled()
            .take(7)
            .toList()

        val fallbackHeroItemsWithArtwork = effectiveOrderedRows
            .asSequence()
            .flatMap { it.items.asSequence() }
            .filter { it.hasHeroArtwork() }
            .shuffled()
            .take(7)
            .toList()

        val computedHeroItems = when {
            heroItemsFromSelectedCatalogs.isNotEmpty() -> heroItemsFromSelectedCatalogs
            fallbackHeroItemsFromSelectedCatalogs.isNotEmpty() -> fallbackHeroItemsFromSelectedCatalogs
            fallbackHeroItemsWithArtwork.isNotEmpty() -> fallbackHeroItemsWithArtwork
            else -> emptyList()
        }

        val nextTruncatedCache = mutableMapOf<String, HomeViewModel.TruncatedRowCacheEntry>()
        val computedDisplayRows = effectiveOrderedRows.map { row ->
            val shouldKeepFullRowInModern = currentLayout == HomeLayout.MODERN && row.supportsSkip
            if (row.items.size > 25 && !shouldKeepFullRowInModern) {
                val key = "${row.addonId}_${row.apiType}_${row.catalogId}"
                val cachedEntry = previousTruncatedRowCache[key]
                if (cachedEntry != null && cachedEntry.sourceRow === row) {
                    nextTruncatedCache[key] = cachedEntry
                    cachedEntry.truncatedRow
                } else {
                    val truncatedRow = row.copy(items = row.items.take(25))
                    val nextEntry = HomeViewModel.TruncatedRowCacheEntry(
                        sourceRow = row,
                        truncatedRow = truncatedRow
                    )
                    nextTruncatedCache[key] = nextEntry
                    truncatedRow
                }
            } else {
                row
            }
        }

        val computedGridItems = if (currentLayout == HomeLayout.GRID) {
            buildGridItemsFromRowsPipeline(
                rows = computedDisplayRows,
                heroItems = computedHeroItems,
                heroSectionEnabled = heroSectionEnabled
            )
        } else {
            currentGridItems
        }

        CatalogUpdateResult(
            displayRows = computedDisplayRows,
            heroItems = computedHeroItems,
            gridItems = computedGridItems,
            fullRows = effectiveOrderedRows,
            orderedGroupKeys = effectiveOrderKeys,
            truncatedCache = nextTruncatedCache,
            orderDiagnosticsSignature = orderDiagnosticsSignature,
            orderDiagnosticsMessage = orderDiagnosticsMessage
        )
    }

    if (lastCatalogOrderDiagnosticsSignature != updateResult.orderDiagnosticsSignature) {
        lastCatalogOrderDiagnosticsSignature = updateResult.orderDiagnosticsSignature
        Log.d(HomeViewModel.TAG, updateResult.orderDiagnosticsMessage)
    }

    val displayRows = updateResult.displayRows
    val baseHeroItems = updateResult.heroItems
    val fullRowsFiltered = updateResult.fullRows
    val orderedGroupKeys = updateResult.orderedGroupKeys
    val nextTruncatedRowCache = updateResult.truncatedCache
    val persistableDisplayRows = persistableHomeCatalogRows(displayRows)
    val persistableFullRows = persistableHomeCatalogRows(fullRowsFiltered)
    val hasTransientLoadingRows = persistableDisplayRows.size != displayRows.size ||
        persistableFullRows.size != fullRowsFiltered.size
    val candidateSnapshotComplete =
        publishableExpectedOrderKeys.isNotEmpty() &&
            isConfiguredHomeSnapshotComplete(
                snapshotOrderedGroupKeys = orderedGroupKeys,
                expectedConfiguredOrderKeys = publishableExpectedOrderKeys
            )

    truncatedRowCache.clear()
    truncatedRowCache.putAll(nextTruncatedRowCache)

    val hasCurrentRenderedContent = hasRenderableHomeContent(currentState)
    val shouldKeepVisibleContent =
        hasCurrentRenderedContent &&
            displayRows.isEmpty() &&
            baseHeroItems.isEmpty() &&
            fullRowsFiltered.isEmpty() &&
            refreshInProgress

    if (shouldKeepVisibleContent) {
        _uiState.update { it.copy(isLoading = true, error = null) }
        return
    }

    if (
        diskFirstHomeStartupEnabled &&
        expectedConfiguredOrderKeys.isNotEmpty() &&
        !candidateSnapshotComplete &&
        !hasTransientLoadingRows
    ) {
        val hasVisibleContent = hasRenderableHomeContent(_uiState.value)
        if (!hasVisibleContent) {
            _uiState.update { it.copy(isLoading = true, error = null) }
        }
        return
    }

    if (displayRows.isNotEmpty() || baseHeroItems.isNotEmpty() || fullRowsFiltered.isNotEmpty()) {
        if (!startupHydrationPending) {
            restoredCatalogSnapshotActive = false
        }
        if (!activeRefreshInProgress && !startupHydrationPending) {
            startupRefreshPending = false
        }
    } else if (!activeRefreshInProgress && !startupHydrationPending) {
        startupRefreshPending = false
    }

    if (displayRows.isNotEmpty() || baseHeroItems.isNotEmpty() || fullRowsFiltered.isNotEmpty()) {
        val transientSnapshot = com.nexio.tv.data.local.HomeCatalogSnapshotStore.Snapshot(
            catalogRows = displayRows,
            fullCatalogRows = fullRowsFiltered,
            heroItems = baseHeroItems,
            orderedGroupKeys = orderedGroupKeys
        )
        applyHomeSnapshotToUiPipeline(transientSnapshot)
        if (!hasTransientLoadingRows) {
            hasPersistedCatalogSnapshot = persistAndApplyHomeSnapshotPipeline(transientSnapshot)
        }
        pendingRestoredCatalogSnapshot = null
    }

    _uiState.update { state ->
        state.copy(
            traktRecommendationRefs = if (state.traktRecommendationRefs == recommendationRefMap) {
                state.traktRecommendationRefs
            } else {
                recommendationRefMap
            },
            isLoading = false
        )
    }

    val tmdbSettings = currentTmdbSettings
    val shouldUseEnrichedHeroItems = tmdbSettings.isActive &&
        (tmdbSettings.useArtwork || tmdbSettings.useBasicInfo || tmdbSettings.useDetails)

    if (shouldUseEnrichedHeroItems && baseHeroItems.isNotEmpty() && isNonPlaybackHomeWorkAllowed()) {
        heroEnrichmentJob?.cancel()
        heroEnrichmentJob = viewModelScope.launch {
            if (!isNonPlaybackHomeWorkAllowed()) return@launch
            val enrichmentSignature = heroEnrichmentSignaturePipeline(baseHeroItems, tmdbSettings)
            if (lastHeroEnrichmentSignature == enrichmentSignature) {
                val cached = lastHeroEnrichedItems
                updateInMemoryHomeSnapshotPipeline { snapshot ->
                    snapshot.copy(heroItems = cached)
                }
            } else {
                if (!isNonPlaybackHomeWorkAllowed()) return@launch
                val enrichedItems = enrichHeroItemsPipeline(baseHeroItems, tmdbSettings)
                if (!isNonPlaybackHomeWorkAllowed()) return@launch
                lastHeroEnrichmentSignature = enrichmentSignature
                lastHeroEnrichedItems = enrichedItems
                updateInMemoryHomeSnapshotPipeline { snapshot ->
                    snapshot.copy(heroItems = enrichedItems)
                }
            }
        }
    } else {
        lastHeroEnrichmentSignature = null
        lastHeroEnrichedItems = emptyList()
    }

    if (isNonPlaybackHomeWorkAllowed()) {
        refreshTrailerMetadataAvailabilityPipeline(displayRows)
        schedulePosterStatusReconcilePipeline(displayRows)
    }
    }
}

internal fun HomeViewModel.applyHomeSnapshotToUiPipeline(
    snapshot: com.nexio.tv.data.local.HomeCatalogSnapshotStore.Snapshot
) {
    val filteredSnapshot = snapshot.filterDisabledHomeCatalogRows(
        disabledHomeCatalogKeys = disabledHomeCatalogKeys,
        isAddonRowDisabled = { row ->
            isCatalogDisabled(
                addonBaseUrl = row.addonBaseUrl,
                addonId = row.addonId,
                type = row.apiType,
                catalogId = row.catalogId,
                catalogName = row.catalogName
            )
        }
    )
    _fullCatalogRows.value = filteredSnapshot.fullCatalogRows
    _uiState.update { state ->
        val snapshotGridItems = if (state.homeLayout == HomeLayout.GRID) {
            buildGridItemsFromRowsPipeline(
                rows = filteredSnapshot.catalogRows,
                heroItems = filteredSnapshot.heroItems,
                heroSectionEnabled = state.heroSectionEnabled
            )
        } else {
            state.gridItems
        }

        state.copy(
            catalogRows = filteredSnapshot.catalogRows,
            heroItems = filteredSnapshot.heroItems,
            gridItems = if (state.gridItems == snapshotGridItems) state.gridItems else snapshotGridItems,
            isLoading = false,
            error = null
        )
    }
    refreshTrailerMetadataAvailabilityPipeline(filteredSnapshot.catalogRows)
}

private fun com.nexio.tv.data.local.HomeCatalogSnapshotStore.Snapshot.filterDisabledHomeCatalogRows(
    disabledHomeCatalogKeys: Set<String>,
    isAddonRowDisabled: (CatalogRow) -> Boolean
): com.nexio.tv.data.local.HomeCatalogSnapshotStore.Snapshot {
    fun isDisabled(row: CatalogRow): Boolean {
        return when (row.addonId) {
            TRAKT_RAIL_ADDON_ID,
            SIMKL_RAIL_ADDON_ID,
            MDBLIST_RAIL_ADDON_ID,
            TMDB_RAIL_ADDON_ID -> {
                isSyntheticHomeCatalogDisabled(row.catalogId, disabledHomeCatalogKeys) ||
                    isSyntheticHomeCatalogDisabled(homeCatalogGlobalKey(row), disabledHomeCatalogKeys) ||
                    disabledHomeCatalogKeys.any { disabledKey ->
                        val disabledSlug = slugifySyntheticHomeCatalogKey(disabledKey)
                        disabledSlug != "custom" && row.catalogId.lowercase().contains(disabledSlug)
                    }
            }
            else -> isAddonRowDisabled(row)
        }
    }

    val filteredFullRows = fullCatalogRows.filterNot(::isDisabled)
    val filteredDisplayRows = catalogRows.filterNot(::isDisabled)
    if (filteredFullRows.size == fullCatalogRows.size && filteredDisplayRows.size == catalogRows.size) {
        return this
    }

    val retainedItemKeys = filteredFullRows
        .asSequence()
        .flatMap { row -> row.items.asSequence() }
        .map { item -> "${item.apiType}:${item.id}" }
        .toSet()
    return copy(
        catalogRows = filteredDisplayRows,
        fullCatalogRows = filteredFullRows,
        heroItems = heroItems.filter { item -> "${item.apiType}:${item.id}" in retainedItemKeys },
        orderedGroupKeys = orderedGroupKeys.filterNot { key ->
            isSyntheticHomeCatalogDisabled(key, disabledHomeCatalogKeys)
        }
    )
}

private fun slugifySyntheticHomeCatalogKey(value: String): String {
    return canonicalSyntheticCatalogOrderKey(value)
        .lowercase()
        .replace(Regex("[^a-z0-9]+"), "_")
        .trim('_')
        .ifBlank { "custom" }
}

internal fun HomeViewModel.applyPendingPersistedHomeSnapshotIfPossiblePipeline(reason: String) {
    val snapshot = pendingRestoredCatalogSnapshot ?: return
    val applied = applyPersistedHomeSnapshotIfEligiblePipeline(
        snapshot = snapshot,
        requireSourceCachesReady = false
    )
    Log.d(
        HomeViewModel.TAG,
        if (applied) {
            "Pending persisted snapshot applied trigger=$reason"
        } else {
            "Pending persisted snapshot still deferred trigger=$reason"
        }
    )
}

internal fun HomeViewModel.applyPersistedHomeSnapshotIfEligiblePipeline(
    snapshot: com.nexio.tv.data.local.HomeCatalogSnapshotStore.Snapshot,
    requireSourceCachesReady: Boolean
): Boolean {
    val simklExpectedOrderKeys = buildExpectedConfiguredSimklOrderKeys(simklCatalogPreferences)
    val effectiveTraktPrefs = traktCatalogPreferences.onlyWhenAuthenticated(activeProfileTraktAuthenticated)
    val effectiveTraktSnapshot = if (activeProfileTraktAuthenticated) {
        traktDiscoverySnapshot
    } else {
        com.nexio.tv.data.repository.TraktDiscoverySnapshot()
    }
    val addonExpectedOrderKeys = buildExpectedConfiguredAddonOrderKeys(
        addons = addonsCache,
        disabledHomeCatalogKeys = disabledHomeCatalogKeys
    )
    val traktExpectedOrderKeys = buildExpectedConfiguredTraktOrderKeys(effectiveTraktPrefs)
    val mdbExpectedOrderKeys = buildExpectedConfiguredMDBListOrderKeys(
        mdbListCatalogPreferences,
        mdbListDiscoverySnapshot
    )
    val tmdbExpectedOrderKeys = buildExpectedConfiguredTmdbOrderKeys(tmdbCatalogPreferences)
    val catalogPlan = buildConfiguredCatalogPlan(
        addons = addonsCache,
        disabledHomeCatalogKeys = disabledHomeCatalogKeys,
        availableAddonOrderKeys = catalogsMap.keys,
        traktPrefs = effectiveTraktPrefs,
        traktSnapshot = effectiveTraktSnapshot,
        hasTraktUpNextItems = activeProfileTraktAuthenticated && _uiState.value.traktUpNextItems.isNotEmpty(),
        simklPrefs = simklCatalogPreferences,
        simklSnapshot = simklDiscoverySnapshot,
        mdbPrefs = mdbListCatalogPreferences,
        mdbSnapshot = mdbListDiscoverySnapshot,
        tmdbPrefs = tmdbCatalogPreferences,
        tmdbSnapshot = tmdbDiscoverySnapshot
    )
    val expectedConfiguredOrderKeys = catalogPlan.expectedOrderKeys
    val publishableExpectedOrderKeys = catalogPlan.publishableOrderKeys
    val sourceCachesReady = areConfiguredHomeSourceCachesReady(
        addonExpectedOrderKeys = addonExpectedOrderKeys,
        availableAddonOrderKeys = catalogsMap.keys,
        traktExpectedOrderKeys = traktExpectedOrderKeys,
        traktPrefs = effectiveTraktPrefs,
        traktSnapshot = effectiveTraktSnapshot,
        simklExpectedOrderKeys = simklExpectedOrderKeys,
        simklPrefs = simklCatalogPreferences,
        simklSnapshot = simklDiscoverySnapshot,
        mdbExpectedOrderKeys = mdbExpectedOrderKeys,
        mdbPrefs = mdbListCatalogPreferences,
        mdbSnapshot = mdbListDiscoverySnapshot,
        tmdbExpectedOrderKeys = tmdbExpectedOrderKeys,
        tmdbPrefs = tmdbCatalogPreferences,
        tmdbSnapshot = tmdbDiscoverySnapshot
    )
    if (requireSourceCachesReady && !sourceCachesReady) {
        Log.d(
            HomeViewModel.TAG,
            "Persisted snapshot deferred reason=source_caches_not_ready requireSourceCachesReady=true " +
                "snapshotKeys=${snapshot.orderedGroupKeys.size} expectedKeys=${expectedConfiguredOrderKeys.size}"
        )
        pendingRestoredCatalogSnapshot = snapshot
        return false
    }
    val snapshotComplete = isConfiguredHomeSnapshotComplete(
        snapshotOrderedGroupKeys = snapshot.orderedGroupKeys,
        expectedConfiguredOrderKeys = publishableExpectedOrderKeys
    )
    if (publishableExpectedOrderKeys.isNotEmpty() && !snapshotComplete) {
        val missingKeys = publishableExpectedOrderKeys.filterNot { it in snapshot.orderedGroupKeys.toSet() }
        Log.d(
            HomeViewModel.TAG,
            "Persisted snapshot deferred reason=incomplete expected=${publishableExpectedOrderKeys.size} " +
                "actual=${snapshot.orderedGroupKeys.size} missing=${missingKeys.joinToString(limit = 12)}"
        )
        pendingRestoredCatalogSnapshot = snapshot
        return false
    }
    Log.d(
        HomeViewModel.TAG,
        "Persisted snapshot applied orderedKeys=${snapshot.orderedGroupKeys.size} expected=${publishableExpectedOrderKeys.size} " +
            "sourceCachesReady=$sourceCachesReady rows=${snapshot.catalogRows.size} fullRows=${snapshot.fullCatalogRows.size}"
    )
    inMemoryHomeSnapshot = snapshot
    pendingRestoredCatalogSnapshot = null
    hasPersistedCatalogSnapshot = true
    applyHomeSnapshotToUiPipeline(snapshot)
    return true
}

internal fun HomeViewModel.persistAndApplyHomeSnapshotPipeline(
    snapshot: com.nexio.tv.data.local.HomeCatalogSnapshotStore.Snapshot
): Boolean {
    inMemoryHomeSnapshot = snapshot
    persistHomeSnapshotDebouncedPipeline(snapshot)
    return true
}

internal fun HomeViewModel.updateInMemoryHomeSnapshotPipeline(
    transform: (
        com.nexio.tv.data.local.HomeCatalogSnapshotStore.Snapshot
    ) -> com.nexio.tv.data.local.HomeCatalogSnapshotStore.Snapshot
): Boolean {
    val currentSnapshot = inMemoryHomeSnapshot ?: return false
    val nextSnapshot = transform(currentSnapshot)
    if (nextSnapshot == currentSnapshot) return false
    inMemoryHomeSnapshot = nextSnapshot
    persistHomeSnapshotDebouncedPipeline(nextSnapshot)
    return true
}

internal fun HomeViewModel.persistHomeSnapshotDebouncedPipeline(
    snapshot: com.nexio.tv.data.local.HomeCatalogSnapshotStore.Snapshot
) {
    val profileId = profileManager.activeProfileId.value
    val profileGeneration = homeProfileGeneration
    pendingHomeSnapshotPersist = snapshot
    homeSnapshotPersistGeneration += 1
    val persistGeneration = homeSnapshotPersistGeneration
    homeSnapshotPersistJob?.cancel()
    homeSnapshotPersistJob = viewModelScope.launch(Dispatchers.IO) {
        delay(HomeViewModel.HOME_SNAPSHOT_PERSIST_DEBOUNCE_MS)
        if (homeSnapshotPersistGeneration != persistGeneration) return@launch
        if (!isCurrentHomeProfileGeneration(profileGeneration)) return@launch
        val latestSnapshot = pendingHomeSnapshotPersist ?: return@launch
        val posterToken = homeCatalogSnapshotStore.currentPosterProviderToken()
        homeCatalogSnapshotStore.write(latestSnapshot, posterToken, profileId = profileId)
        val persistedSnapshot = homeCatalogSnapshotStore.read(posterToken, profileId = profileId) ?: latestSnapshot
        Log.d(
            HomeViewModel.TAG,
            "Persisted merged home snapshot write rows=${latestSnapshot.catalogRows.size} fullRows=${latestSnapshot.fullCatalogRows.size} " +
                "hero=${latestSnapshot.heroItems.size} orderedKeys=${latestSnapshot.orderedGroupKeys.size}; " +
                "readbackRows=${persistedSnapshot.catalogRows.size} readbackFullRows=${persistedSnapshot.fullCatalogRows.size} " +
                "readbackHero=${persistedSnapshot.heroItems.size} readbackOrderedKeys=${persistedSnapshot.orderedGroupKeys.size}"
        )
        val referencedItemKeys = latestSnapshot.fullCatalogRows
            .asSequence()
            .flatMap { row -> row.items.asSequence() }
            .map { item -> "${item.apiType}:${item.id}" }
            .toSet()
        metadataDiskCacheStore.replaceHomeFeedReferences(
            feedKey = "home_catalog_snapshot",
            itemKeys = referencedItemKeys
        )
        val removedImageUrls = metadataDiskCacheStore.removeHomeUnreferencedMetaEntries(maxEntries = 800)
        homeCatalogRefreshCoordinator.evictCachedImageUrls(removedImageUrls)
        withContext(Dispatchers.Main.immediate) {
            if (!isCurrentHomeProfileGeneration(profileGeneration)) return@withContext
            if (homeSnapshotPersistGeneration == persistGeneration) {
                pendingHomeSnapshotPersist = null
            }
            applyPersistedHomeSnapshotIfEligiblePipeline(
                snapshot = persistedSnapshot,
                requireSourceCachesReady = false
            )
        }
    }
}

private fun buildCatalogComputationSignature(
    orderedKeys: List<String>,
    catalogSnapshot: Map<String, CatalogRow>,
    heroCatalogKeys: List<String>,
    currentLayout: HomeLayout,
    heroSectionEnabled: Boolean,
    continueWatchingItems: List<ContinueWatchingItem>,
    traktSnapshot: com.nexio.tv.data.repository.TraktDiscoverySnapshot,
    traktPrefs: TraktCatalogPreferences,
    persistedTraktSyntheticGroups: List<PersistedSyntheticCatalogGroup>,
    simklSnapshot: com.nexio.tv.data.repository.SimklDiscoverySnapshot,
    simklPrefs: com.nexio.tv.data.local.SimklCatalogPreferences,
    persistedSimklSyntheticGroups: List<PersistedSyntheticCatalogGroup>,
    mdbListSnapshot: com.nexio.tv.data.repository.MDBListDiscoverySnapshot,
    mdbListPrefs: MDBListCatalogPreferences,
    persistedMDBListSyntheticGroups: List<PersistedSyntheticCatalogGroup>,
    tmdbSnapshot: com.nexio.tv.data.repository.TmdbDiscoverySnapshot,
    tmdbPrefs: TmdbCatalogPreferences,
    persistedTmdbSyntheticGroups: List<PersistedSyntheticCatalogGroup>,
    disabledHomeCatalogKeys: Set<String>,
    startupHydrationPending: Boolean,
    refreshInProgress: Boolean,
    hasPersistedCatalogSnapshot: Boolean,
    restoredCatalogSnapshotActive: Boolean
): String {
    var signature = 17
    orderedKeys.forEach { key ->
        signature = (signature * 31) + key.hashCode()
        signature = (signature * 31) + (catalogSnapshot[key]?.hashCode() ?: 0)
    }
    signature = (signature * 31) + heroCatalogKeys.hashCode()
    signature = (signature * 31) + currentLayout.hashCode()
    signature = (signature * 31) + heroSectionEnabled.hashCode()
    signature = (signature * 31) + continueWatchingItems.hashCode()
    signature = (signature * 31) + traktSnapshot.hashCode()
    signature = (signature * 31) + traktPrefs.hashCode()
    signature = (signature * 31) + persistedTraktSyntheticGroups.hashCode()
    signature = (signature * 31) + simklSnapshot.hashCode()
    signature = (signature * 31) + simklPrefs.hashCode()
    signature = (signature * 31) + persistedSimklSyntheticGroups.hashCode()
    signature = (signature * 31) + mdbListSnapshot.hashCode()
    signature = (signature * 31) + mdbListPrefs.hashCode()
    signature = (signature * 31) + persistedMDBListSyntheticGroups.hashCode()
    signature = (signature * 31) + tmdbSnapshot.hashCode()
    signature = (signature * 31) + tmdbPrefs.hashCode()
    signature = (signature * 31) + persistedTmdbSyntheticGroups.hashCode()
    signature = (signature * 31) + disabledHomeCatalogKeys.hashCode()
    signature = (signature * 31) + startupHydrationPending.hashCode()
    signature = (signature * 31) + refreshInProgress.hashCode()
    signature = (signature * 31) + hasPersistedCatalogSnapshot.hashCode()
    signature = (signature * 31) + restoredCatalogSnapshotActive.hashCode()
    return signature.toString()
}

private data class CachedHomePreservationState(
    val preserveAddonRows: Boolean,
    val preserveTraktRows: Boolean,
    val preserveSimklRows: Boolean,
    val preserveMDBListRows: Boolean,
    val preserveTmdbRows: Boolean,
    val retainUnorderedRows: Boolean
)

private fun shouldPreserveTraktCachedRows(
    snapshot: com.nexio.tv.data.repository.TraktDiscoverySnapshot,
    refreshInProgress: Boolean
): Boolean {
    if (refreshInProgress) return true
    // Only keep stale Trakt rows while hydrating the first discovery snapshot.
    if (snapshot.updatedAtMs <= 0L) return true
    return false
}

private fun shouldPreserveMDBListCachedRows(
    snapshot: com.nexio.tv.data.repository.MDBListDiscoverySnapshot,
    refreshInProgress: Boolean
): Boolean {
    if (refreshInProgress) return true
    // Only keep stale MDBList rows while hydrating the first discovery snapshot.
    if (snapshot.updatedAtMs <= 0L) return true
    return false
}

private fun shouldPreserveSimklCachedRows(
    snapshot: com.nexio.tv.data.repository.SimklDiscoverySnapshot,
    refreshInProgress: Boolean
): Boolean {
    if (refreshInProgress) return true
    if (snapshot.updatedAtMs <= 0L) return true
    return false
}

private fun shouldPreserveTmdbCachedRows(
    snapshot: com.nexio.tv.data.repository.TmdbDiscoverySnapshot,
    refreshInProgress: Boolean
): Boolean {
    if (refreshInProgress) return true
    if (snapshot.updatedAtMs <= 0L) return true
    return false
}

private fun mergeCachedRowsWithLiveRows(
    cachedRows: List<CatalogRow>,
    liveRows: List<CatalogRow>,
    preservationState: CachedHomePreservationState,
    orderedGroupKeys: List<String>,
    rowOrderKeyByGlobalKey: Map<String, String>
): List<CatalogRow> {
    fun canRetainCachedRow(row: CatalogRow): Boolean {
        if (!shouldPreserveCachedRow(row, preservationState)) return false
        return resolveMergedRowOrderKey(
            row = row,
            orderedGroupKeys = orderedGroupKeys,
            rowOrderKeyByGlobalKey = rowOrderKeyByGlobalKey
        ) != null
    }

    val mergedRowsInRetentionOrder = when {
        cachedRows.isEmpty() -> liveRows
        liveRows.isEmpty() -> cachedRows.filter(::canRetainCachedRow)
        else -> {
            val liveByKey = liveRows.associateBy(::homeCatalogGlobalKey)
            val usedKeys = mutableSetOf<String>()
            val mergedRows = cachedRows.mapNotNull { cachedRow ->
                val key = homeCatalogGlobalKey(cachedRow)
                val liveReplacement = liveByKey[key]
                when {
                    liveReplacement != null -> {
                        usedKeys += key
                        liveReplacement
                    }
                    canRetainCachedRow(cachedRow) -> cachedRow
                    else -> null
                }
            }.toMutableList()

            liveRows.forEach { liveRow ->
                val key = homeCatalogGlobalKey(liveRow)
                if (usedKeys.add(key)) {
                    mergedRows += liveRow
                }
            }
            mergedRows
        }
    }

    if (mergedRowsInRetentionOrder.isEmpty()) {
        return emptyList()
    }

    val groupedRows = linkedMapOf<String, MutableList<CatalogRow>>()
    val unresolvedRows = mutableListOf<CatalogRow>()
    mergedRowsInRetentionOrder.forEach { row ->
        val groupKey = resolveMergedRowOrderKey(
            row = row,
            orderedGroupKeys = orderedGroupKeys,
            rowOrderKeyByGlobalKey = rowOrderKeyByGlobalKey
        )
        if (groupKey == null) {
            unresolvedRows += row
        } else {
            groupedRows.getOrPut(groupKey) { mutableListOf() }.add(row)
        }
    }

    val orderedRows = buildList {
        orderedGroupKeys.forEach { groupKey ->
            groupedRows[groupKey]?.let { addAll(it) }
        }
        if (preservationState.retainUnorderedRows) {
            addAll(unresolvedRows)
        }
    }
    return orderedRows
}

private fun resolveMergedRowOrderKey(
    row: CatalogRow,
    orderedGroupKeys: List<String>,
    rowOrderKeyByGlobalKey: Map<String, String>
): String? {
    val globalKey = homeCatalogGlobalKey(row)
    rowOrderKeyByGlobalKey[globalKey]?.let { return it }

    if (globalKey in orderedGroupKeys) {
        return globalKey
    }
    if (row.catalogId in orderedGroupKeys) {
        return row.catalogId
    }

    return when (row.addonId) {
        TRAKT_RAIL_ADDON_ID -> {
            val prefixedCatalogId = "trakt_${row.catalogId}"
            when {
                prefixedCatalogId in orderedGroupKeys -> prefixedCatalogId
                else -> null
            }
        }
        SIMKL_RAIL_ADDON_ID -> {
            val prefixedCatalogId = "simkl_${row.catalogId}"
            when {
                prefixedCatalogId in orderedGroupKeys -> prefixedCatalogId
                else -> null
            }
        }
        MDBLIST_RAIL_ADDON_ID -> {
            val prefixedCatalogId = "mdblist_${row.catalogId}"
            when {
                prefixedCatalogId in orderedGroupKeys -> prefixedCatalogId
                else -> null
            }
        }
        TMDB_RAIL_ADDON_ID -> {
            val prefixedCatalogId = "tmdb_${row.catalogId}"
            when {
                row.catalogId in orderedGroupKeys -> row.catalogId
                prefixedCatalogId in orderedGroupKeys -> prefixedCatalogId
                else -> null
            }
        }
        else -> null
    }
}

private fun shouldPreserveCachedRow(
    row: CatalogRow,
    preservationState: CachedHomePreservationState
): Boolean {
    return when (row.addonId) {
        TRAKT_RAIL_ADDON_ID -> preservationState.preserveTraktRows
        SIMKL_RAIL_ADDON_ID -> preservationState.preserveSimklRows
        MDBLIST_RAIL_ADDON_ID -> preservationState.preserveMDBListRows
        TMDB_RAIL_ADDON_ID -> preservationState.preserveTmdbRows
        else -> preservationState.preserveAddonRows
    }
}

private fun HomeViewModel.buildGridItemsFromRowsPipeline(
    rows: List<CatalogRow>,
    heroItems: List<MetaPreview>,
    heroSectionEnabled: Boolean
): List<GridItem> = buildList {
    if (heroSectionEnabled && heroItems.isNotEmpty()) {
        add(GridItem.Hero(heroItems))
    }
    rows.filter { it.items.isNotEmpty() }.forEach { row ->
        add(
            GridItem.SectionDivider(
                catalogName = row.catalogName,
                catalogId = row.catalogId,
                addonBaseUrl = row.addonBaseUrl,
                addonId = row.addonId,
                type = row.apiType
            )
        )
        val hasEnoughForSeeAll = row.items.size >= 15
        val displayItems = if (hasEnoughForSeeAll) row.items.take(14) else row.items.take(15)
        displayItems.forEach { item ->
            add(
                GridItem.Content(
                    item = item,
                    addonBaseUrl = row.addonBaseUrl,
                    catalogId = row.catalogId,
                    catalogName = row.catalogName
                )
            )
        }
        if (hasEnoughForSeeAll) {
            add(
                GridItem.SeeAll(
                    catalogId = row.catalogId,
                    addonId = row.addonId,
                    type = row.apiType
                )
            )
        }
    }
}

private fun buildSyntheticMDBListRows(
    prefs: MDBListCatalogPreferences,
    snapshot: com.nexio.tv.data.repository.MDBListDiscoverySnapshot
): List<SyntheticCatalogOrderGroup> {
    if (snapshot.customListCatalogs.isEmpty()) return emptyList()

    val availableKeys = buildSet {
        addAll(
            snapshot.personalLists
                .filter { prefs.isPersonalListEnabled(it.key) }
                .map { it.key }
        )
        addAll(
            snapshot.topLists
                .filter { prefs.isTopListSelected(it.key) }
                .map { it.key }
        )
        addAll(
            snapshot.customListCatalogs
                .filter { it.key in prefs.selectedTopListKeys }
                .map { it.key }
        )
    }
    if (availableKeys.isEmpty()) return emptyList()

    val orderedKeys = if (prefs.catalogOrder.isEmpty()) {
        availableKeys.toList()
    } else {
        prefs.catalogOrder.filter { it in availableKeys } + availableKeys.filterNot { it in prefs.catalogOrder }
    }

    val groupedByKey = snapshot.customListCatalogs.groupBy { it.key }
    return orderedKeys.mapNotNull { key ->
        val rows = groupedByKey[key].orEmpty().mapNotNull { custom -> custom.toCatalogRow() }
        if (rows.isEmpty()) null else SyntheticCatalogOrderGroup(orderKey = key, rows = rows)
    }.also { groups ->
        Log.d(
            HomeViewModel.TAG,
            "MDBList synthetic rows available=${availableKeys.size} grouped=${groupedByKey.size} emitted=${groups.size}"
        )
    }
}

internal fun shouldRefreshTraktDiscoveryForState(
    prefs: TraktCatalogPreferences,
    snapshot: com.nexio.tv.data.repository.TraktDiscoverySnapshot
): Boolean {
    if (snapshot.updatedAtMs <= 0L) {
        return true
    }
    if (TraktCatalogIds.TRENDING_MOVIES in prefs.enabledCatalogs && snapshot.trendingMovieItems.isEmpty()) return true
    if (TraktCatalogIds.TRENDING_SHOWS in prefs.enabledCatalogs && snapshot.trendingShowItems.isEmpty()) return true
    if (TraktCatalogIds.POPULAR_MOVIES in prefs.enabledCatalogs && snapshot.popularMovieItems.isEmpty()) return true
    if (TraktCatalogIds.POPULAR_SHOWS in prefs.enabledCatalogs && snapshot.popularShowItems.isEmpty()) return true
    if (TraktCatalogIds.RECOMMENDED_MOVIES in prefs.enabledCatalogs && snapshot.recommendationMovieItems.isEmpty()) return true
    if (TraktCatalogIds.RECOMMENDED_SHOWS in prefs.enabledCatalogs && snapshot.recommendationShowItems.isEmpty()) return true
    if (TraktCatalogIds.CALENDAR in prefs.enabledCatalogs && snapshot.calendarItems.isEmpty()) return true

    if (prefs.selectedPopularListKeys.isEmpty()) {
        return false
    }
    if (snapshot.popularLists.isEmpty()) {
        return true
    }
    val customKeys = snapshot.customListCatalogs.map { it.key }.toSet()
    return prefs.selectedPopularListKeys.any { it !in customKeys }
}

internal fun shouldAttemptSerializedTraktDiscoveryRefresh(
    prefs: TraktCatalogPreferences
): Boolean {
    return prefs.enabledCatalogs.any { it != TraktCatalogIds.UP_NEXT } ||
        prefs.selectedPopularListKeys.isNotEmpty()
}

internal fun shouldRefreshMDBListDiscoveryForState(
    prefs: MDBListCatalogPreferences,
    snapshot: com.nexio.tv.data.repository.MDBListDiscoverySnapshot
): Boolean {
    if (snapshot.personalLists.isEmpty() && snapshot.topLists.isEmpty()) {
        return true
    }

    val enabledPersonalCount = snapshot.personalLists.count { prefs.isPersonalListEnabled(it.key) }
    val selectedTopCount = snapshot.topLists.count { prefs.isTopListSelected(it.key) }

    val requiredKeys = buildSet {
        addAll(snapshot.personalLists.filter { prefs.isPersonalListEnabled(it.key) }.map { it.key })
        addAll(snapshot.topLists.filter { prefs.isTopListSelected(it.key) }.map { it.key })
        addAll(prefs.selectedTopListKeys)
    }
    if (requiredKeys.isEmpty()) {
        return enabledPersonalCount == 0 && selectedTopCount > 0
    }

    val customKeys = snapshot.customListCatalogs.map { it.key }.toSet()
    return requiredKeys.any { it !in customKeys }
}

private fun buildSyntheticTraktRows(
    prefs: TraktCatalogPreferences,
    upNextItems: List<MetaPreview>,
    snapshot: com.nexio.tv.data.repository.TraktDiscoverySnapshot
): List<SyntheticCatalogOrderGroup> {
    val builtInRows = linkedMapOf<String, CatalogRow>()

    if (TraktCatalogIds.UP_NEXT in prefs.enabledCatalogs && upNextItems.isNotEmpty()) {
        builtInRows[TraktCatalogIds.UP_NEXT] = buildTraktCatalogRow(
            catalogId = TraktCatalogIds.UP_NEXT,
            catalogName = TRAKT_ROW_NAME_UP_NEXT,
            type = ContentType.SERIES,
            items = upNextItems
        )
    }
    if (TraktCatalogIds.TRENDING_MOVIES in prefs.enabledCatalogs && snapshot.trendingMovieItems.isNotEmpty()) {
        builtInRows[TraktCatalogIds.TRENDING_MOVIES] = buildTraktCatalogRow(
            catalogId = TraktCatalogIds.TRENDING_MOVIES,
            catalogName = TRAKT_ROW_NAME_TRENDING_MOVIES,
            type = ContentType.MOVIE,
            items = snapshot.trendingMovieItems
        )
    }
    if (TraktCatalogIds.TRENDING_SHOWS in prefs.enabledCatalogs && snapshot.trendingShowItems.isNotEmpty()) {
        builtInRows[TraktCatalogIds.TRENDING_SHOWS] = buildTraktCatalogRow(
            catalogId = TraktCatalogIds.TRENDING_SHOWS,
            catalogName = TRAKT_ROW_NAME_TRENDING_SHOWS,
            type = ContentType.SERIES,
            items = snapshot.trendingShowItems
        )
    }
    if (TraktCatalogIds.POPULAR_MOVIES in prefs.enabledCatalogs && snapshot.popularMovieItems.isNotEmpty()) {
        builtInRows[TraktCatalogIds.POPULAR_MOVIES] = buildTraktCatalogRow(
            catalogId = TraktCatalogIds.POPULAR_MOVIES,
            catalogName = TRAKT_ROW_NAME_POPULAR_MOVIES,
            type = ContentType.MOVIE,
            items = snapshot.popularMovieItems
        )
    }
    if (TraktCatalogIds.POPULAR_SHOWS in prefs.enabledCatalogs && snapshot.popularShowItems.isNotEmpty()) {
        builtInRows[TraktCatalogIds.POPULAR_SHOWS] = buildTraktCatalogRow(
            catalogId = TraktCatalogIds.POPULAR_SHOWS,
            catalogName = TRAKT_ROW_NAME_POPULAR_SHOWS,
            type = ContentType.SERIES,
            items = snapshot.popularShowItems
        )
    }
    if (TraktCatalogIds.RECOMMENDED_MOVIES in prefs.enabledCatalogs && snapshot.recommendationMovieItems.isNotEmpty()) {
        builtInRows[TraktCatalogIds.RECOMMENDED_MOVIES] = buildTraktCatalogRow(
            catalogId = TraktCatalogIds.RECOMMENDED_MOVIES,
            catalogName = TRAKT_ROW_NAME_RECOMMENDED_MOVIES,
            type = ContentType.MOVIE,
            items = snapshot.recommendationMovieItems
        )
    }
    if (TraktCatalogIds.RECOMMENDED_SHOWS in prefs.enabledCatalogs && snapshot.recommendationShowItems.isNotEmpty()) {
        builtInRows[TraktCatalogIds.RECOMMENDED_SHOWS] = buildTraktCatalogRow(
            catalogId = TraktCatalogIds.RECOMMENDED_SHOWS,
            catalogName = TRAKT_ROW_NAME_RECOMMENDED_SHOWS,
            type = ContentType.SERIES,
            items = snapshot.recommendationShowItems
        )
    }
    if (TraktCatalogIds.CALENDAR in prefs.enabledCatalogs && snapshot.calendarItems.isNotEmpty()) {
        builtInRows[TraktCatalogIds.CALENDAR] = buildTraktCatalogRow(
            catalogId = TraktCatalogIds.CALENDAR,
            catalogName = TRAKT_ROW_NAME_CALENDAR,
            type = ContentType.SERIES,
            items = snapshot.calendarItems
        )
    }

    val orderedBuiltInKeys = prefs.catalogOrder.filter { it in builtInRows }
    val remainingBuiltInKeys = builtInRows.keys.filterNot { it in orderedBuiltInKeys }
    val orderedBuiltIns = (orderedBuiltInKeys + remainingBuiltInKeys).mapNotNull { id ->
        builtInRows[id]?.let { row -> SyntheticCatalogOrderGroup(orderKey = id, rows = listOf(row)) }
    }
    val selectedCustomKeys = prefs.selectedPopularListKeys.toSet()
    val customListRows = snapshot.customListCatalogs
        .groupBy { it.key }
        .mapNotNull { (key, catalogs) ->
            if (key !in selectedCustomKeys) return@mapNotNull null
            val rows = catalogs.mapNotNull { custom -> custom.toCatalogRow() }
            if (rows.isEmpty()) null else SyntheticCatalogOrderGroup(orderKey = key, rows = rows)
        }
    return orderedBuiltIns + customListRows
}

private fun TraktCustomListCatalog.toCatalogRow(): CatalogRow? {
    if (items.isEmpty()) return null
    return buildTraktCatalogRow(
        catalogId = catalogId,
        catalogName = catalogName,
        type = type,
        items = items
    )
}

private fun buildTraktCatalogRow(
    catalogId: String,
    catalogName: String,
    type: ContentType,
    items: List<MetaPreview>
): CatalogRow {
    return CatalogRow(
        addonId = TRAKT_RAIL_ADDON_ID,
        addonName = TRAKT_RAIL_ADDON_NAME,
        addonBaseUrl = TRAKT_RAIL_ADDON_BASE_URL,
        catalogId = catalogId,
        catalogName = catalogName,
        type = type,
        items = items,
        isLoading = false,
        hasMore = false,
        supportsSkip = false
    )
}

private fun buildSyntheticSimklRows(
    prefs: com.nexio.tv.data.local.SimklCatalogPreferences,
    snapshot: com.nexio.tv.data.repository.SimklDiscoverySnapshot
): List<SyntheticCatalogOrderGroup> {
    val builtInRows = linkedMapOf<String, CatalogRow>()
    prefs.enabledCatalogs.forEach { catalogId ->
        val items = snapshot.itemsByCatalog[catalogId].orEmpty()
        if (items.isEmpty()) return@forEach
        builtInRows[catalogId] = buildSimklCatalogRow(
            catalogId = catalogId,
            catalogName = simklCatalogRowName(catalogId),
            type = simklCatalogContentType(catalogId),
            items = items
        )
    }
    val orderedBuiltInKeys = prefs.catalogOrder.filter { it in builtInRows }
    val remainingBuiltInKeys = builtInRows.keys.filterNot { it in orderedBuiltInKeys }
    return (orderedBuiltInKeys + remainingBuiltInKeys).mapNotNull { id ->
        builtInRows[id]?.let { row -> SyntheticCatalogOrderGroup(orderKey = id, rows = listOf(row)) }
    }
}

private fun simklCatalogRowName(catalogId: String): String {
    return when (catalogId) {
        SimklCatalogIds.TV_TRENDING_TODAY -> SIMKL_ROW_NAME_TV_TRENDING_TODAY
        SimklCatalogIds.TV_TRENDING_WEEK -> SIMKL_ROW_NAME_TV_TRENDING_WEEK
        SimklCatalogIds.TV_TRENDING_MONTH -> SIMKL_ROW_NAME_TV_TRENDING_MONTH
        SimklCatalogIds.ANIME_TRENDING_TODAY -> SIMKL_ROW_NAME_ANIME_TRENDING_TODAY
        SimklCatalogIds.ANIME_TRENDING_WEEK -> SIMKL_ROW_NAME_ANIME_TRENDING_WEEK
        SimklCatalogIds.ANIME_TRENDING_MONTH -> SIMKL_ROW_NAME_ANIME_TRENDING_MONTH
        SimklCatalogIds.MOVIE_TRENDING_TODAY -> SIMKL_ROW_NAME_MOVIE_TRENDING_TODAY
        SimklCatalogIds.MOVIE_TRENDING_WEEK -> SIMKL_ROW_NAME_MOVIE_TRENDING_WEEK
        SimklCatalogIds.MOVIE_TRENDING_MONTH -> SIMKL_ROW_NAME_MOVIE_TRENDING_MONTH
        SimklCatalogIds.DVD_RELEASES -> SIMKL_ROW_NAME_DVD_RELEASES
        else -> catalogId
    }
}

private fun simklCatalogContentType(catalogId: String): ContentType {
    return when (catalogId) {
        SimklCatalogIds.MOVIE_TRENDING_TODAY,
        SimklCatalogIds.MOVIE_TRENDING_WEEK,
        SimklCatalogIds.MOVIE_TRENDING_MONTH,
        SimklCatalogIds.DVD_RELEASES -> ContentType.MOVIE
        else -> ContentType.SERIES
    }
}

private fun buildSimklCatalogRow(
    catalogId: String,
    catalogName: String,
    type: ContentType,
    items: List<MetaPreview>
): CatalogRow {
    return CatalogRow(
        addonId = SIMKL_RAIL_ADDON_ID,
        addonName = SIMKL_RAIL_ADDON_NAME,
        addonBaseUrl = SIMKL_RAIL_ADDON_BASE_URL,
        catalogId = catalogId,
        catalogName = catalogName,
        type = type,
        items = items,
        isLoading = false,
        hasMore = false,
        supportsSkip = false
    )
}

private fun MDBListCustomCatalog.toCatalogRow(): CatalogRow? {
    if (items.isEmpty()) return null
    return CatalogRow(
        addonId = MDBLIST_RAIL_ADDON_ID,
        addonName = MDBLIST_RAIL_ADDON_NAME,
        addonBaseUrl = MDBLIST_RAIL_ADDON_BASE_URL,
        catalogId = catalogId,
        catalogName = catalogName,
        type = type,
        items = items,
        isLoading = false,
        hasMore = false,
        supportsSkip = false
    )
}

internal fun nextUpToMetaPreview(nextUp: ContinueWatchingItem.NextUp): MetaPreview {
    val info = nextUp.info
    val displayMetadata = info.displayMetadata
    val episodeSuffix = buildString {
        append("S")
        append(info.season)
        append("E")
        append(info.episode)
        if (!info.episodeTitle.isNullOrBlank()) {
            append(" ")
            append(info.episodeTitle)
        }
    }
    return MetaPreview(
        id = info.contentId,
        type = ContentType.SERIES,
        rawType = info.contentType,
        name = "${displayMetadata?.title ?: info.name} • $episodeSuffix",
        poster = displayMetadata?.poster ?: info.poster ?: info.thumbnail,
        posterShape = PosterShape.LANDSCAPE,
        background = displayMetadata?.backdrop ?: info.backdrop ?: info.thumbnail,
        logo = displayMetadata?.logo ?: info.logo,
        description = info.episodeDescription ?: displayMetadata?.description,
        releaseInfo = displayMetadata?.releaseInfo ?: info.releaseInfo ?: info.released,
        imdbRating = info.imdbRating ?: displayMetadata?.imdbRating,
        tomatoesRating = displayMetadata?.tomatoesRating,
        genres = info.genres.ifEmpty { displayMetadata?.genres.orEmpty() }
    )
}

internal fun HomeViewModel.schedulePosterStatusReconcilePipeline(rows: List<CatalogRow>) {
    if (!isNonPlaybackHomeWorkAllowed()) return

    posterStatusReconcileJob?.cancel()
    if (rows.isEmpty()) {
        reconcilePosterStatusObserversPipeline(rows)
        return
    }
    posterStatusReconcileJob = viewModelScope.launch {
        delay(500)
        reconcilePosterStatusObserversPipeline(rows)
    }
}

internal fun HomeViewModel.reconcilePosterStatusObserversPipeline(rows: List<CatalogRow>) {
    val desiredItemsByKey = linkedMapOf<String, Pair<String, String>>()
    rows.asSequence()
        .flatMap { row -> row.items.asSequence() }
        .take(HomeViewModel.MAX_POSTER_STATUS_OBSERVERS)
        .forEach { item ->
            val key = homeItemStatusKey(item.id, item.apiType)
            if (key !in desiredItemsByKey) {
                desiredItemsByKey[key] = item.id to item.apiType
            }
        }
    val desiredKeys = desiredItemsByKey.keys
    val desiredMovieKeys = desiredItemsByKey
        .filterValues { (_, itemType) -> itemType.equals("movie", ignoreCase = true) }
        .keys

    posterLibraryObserverJobs.keys
        .filterNot { it in desiredKeys }
        .forEach { staleKey ->
            posterLibraryObserverJobs.remove(staleKey)?.cancel()
        }
    movieWatchedObserverJobs.keys
        .filterNot { it in desiredMovieKeys }
        .forEach { staleKey ->
            movieWatchedObserverJobs.remove(staleKey)?.cancel()
        }

    desiredItemsByKey.forEach { (statusKey, itemRef) ->
        val itemId = itemRef.first
        val itemType = itemRef.second

        if (statusKey !in posterLibraryObserverJobs) {
            posterLibraryObserverJobs[statusKey] = viewModelScope.launch {
                libraryRepository.isInLibrary(itemId = itemId, itemType = itemType)
                    .distinctUntilChanged()
                    .collectLatest { isInLibrary ->
                        _uiState.update { state ->
                            if (state.posterLibraryMembership[statusKey] == isInLibrary) {
                                state
                            } else {
                                state.copy(
                                    posterLibraryMembership = state.posterLibraryMembership + (statusKey to isInLibrary)
                                )
                            }
                        }
                    }
            }
        }

        if (itemType.equals("movie", ignoreCase = true)) {
            if (statusKey !in movieWatchedObserverJobs) {
                movieWatchedObserverJobs[statusKey] = viewModelScope.launch {
                    watchProgressRepository.isWatched(contentId = itemId)
                        .distinctUntilChanged()
                        .collectLatest { watched ->
                            _uiState.update { state ->
                                if (state.movieWatchedStatus[statusKey] == watched) {
                                    state
                                } else {
                                    state.copy(
                                        movieWatchedStatus = state.movieWatchedStatus + (statusKey to watched)
                                    )
                                }
                            }
                        }
                }
            }
        }
    }

    _uiState.update { state ->
        val trimmedLibraryMembership =
            state.posterLibraryMembership.filterKeys { it in desiredKeys }
        val trimmedMovieWatchedStatus =
            state.movieWatchedStatus.filterKeys { it in desiredMovieKeys }
        val trimmedLibraryPending =
            state.posterLibraryPending.filterTo(linkedSetOf()) { it in desiredKeys }
        val trimmedMovieWatchedPending =
            state.movieWatchedPending.filterTo(linkedSetOf()) { it in desiredMovieKeys }

        if (
            trimmedLibraryMembership == state.posterLibraryMembership &&
            trimmedMovieWatchedStatus == state.movieWatchedStatus &&
            trimmedLibraryPending == state.posterLibraryPending &&
            trimmedMovieWatchedPending == state.movieWatchedPending
        ) {
            state
        } else {
            state.copy(
                posterLibraryMembership = trimmedLibraryMembership,
                movieWatchedStatus = trimmedMovieWatchedStatus,
                posterLibraryPending = trimmedLibraryPending,
                movieWatchedPending = trimmedMovieWatchedPending
            )
        }
    }
}
