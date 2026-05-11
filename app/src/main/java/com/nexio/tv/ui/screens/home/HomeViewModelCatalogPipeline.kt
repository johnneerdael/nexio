package com.nexio.tv.ui.screens.home

import android.util.Log
import com.nexio.tv.core.integration.ActiveProfileSession
import com.nexio.tv.core.integration.RailKeyFactory
import com.nexio.tv.core.metadata.router.StableIdResolutionTrigger
import com.nexio.tv.data.local.KitsuCatalogPreferences
import com.nexio.tv.data.local.MDBListCatalogPreferences
import com.nexio.tv.data.local.PersistedSyntheticCatalogGroup
import com.nexio.tv.data.local.SimklCatalogIds
import com.nexio.tv.data.local.SimklCatalogPreferences
import androidx.lifecycle.viewModelScope
import com.nexio.tv.core.network.NetworkResult
import com.nexio.tv.data.local.SyntheticHomeCatalogStore
import com.nexio.tv.data.local.TmdbCatalogIds
import com.nexio.tv.data.local.TmdbCatalogPreferences
import com.nexio.tv.data.local.TraktCatalogIds
import com.nexio.tv.data.local.TraktCatalogPreferences
import com.nexio.tv.data.repository.ResolvedDisplaySurfaceRepository
import com.nexio.tv.data.repository.MDBListCustomCatalog
import com.nexio.tv.data.repository.TmdbDiscoverySnapshot
import com.nexio.tv.data.repository.TraktCustomListCatalog
import com.nexio.tv.domain.model.Addon
import com.nexio.tv.domain.model.CatalogDescriptor
import com.nexio.tv.domain.model.CatalogRow
import com.nexio.tv.domain.model.ContentType
import com.nexio.tv.domain.model.HomeLayout
import com.nexio.tv.domain.model.HydratedHomeOverlay
import com.nexio.tv.domain.model.MetaPreview
import com.nexio.tv.domain.model.PosterShape
import com.nexio.tv.domain.model.TmdbSettings
import com.nexio.tv.domain.model.contentEquals
import com.nexio.tv.domain.model.toArtworkBundleFromDisplayFields
import com.nexio.tv.domain.model.skipStep
import com.nexio.tv.domain.model.supportsExtra
import com.nexio.tv.ui.screens.home.order.HomeRailKey
import com.nexio.tv.ui.screens.home.order.RailPublishPolicy
import com.nexio.tv.ui.screens.home.order.toHomeRailDefinitions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
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

private fun collectPersistedSyntheticOrderKeys(
    traktGroups: List<PersistedSyntheticCatalogGroup>,
    simklGroups: List<PersistedSyntheticCatalogGroup>,
    mdblistGroups: List<PersistedSyntheticCatalogGroup>,
    tmdbGroups: List<PersistedSyntheticCatalogGroup>,
    kitsuGroups: List<PersistedSyntheticCatalogGroup>,
): List<HomeRailKey> = (traktGroups + simklGroups + mdblistGroups + kitsuGroups + tmdbGroups)
    .map { HomeRailKey(it.orderKey) }

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

internal fun tmdbTrendingScreensaverRows(
    tmdbSnapshot: TmdbDiscoverySnapshot,
    persistedTmdbGroups: List<PersistedSyntheticCatalogGroup>
): List<CatalogRow> {
    val liveRowsByCatalog = tmdbSnapshot.rowsByCatalog
    val persistedRowsByCatalog = persistedTmdbGroups
        .flatMap { group -> group.rows }
        .associateBy { row -> row.catalogId }
    return TMDB_TRENDING_SCREENSAVER_CATALOG_IDS.mapNotNull { catalogId ->
        liveRowsByCatalog[catalogId] ?: persistedRowsByCatalog[catalogId]
    }.filter { row -> row.items.isNotEmpty() }
}

internal data class HydratedHomeOverlaySnapshotComponents(
    val displayRows: List<CatalogRow>,
    val fullRows: List<CatalogRow>,
    val heroItems: List<MetaPreview>
)

private const val TRAKT_RAIL_ADDON_ID = "trakt"
private const val TRAKT_RAIL_ADDON_NAME = "Trakt"
private const val TRAKT_RAIL_ADDON_BASE_URL = "https://api.trakt.tv"

private const val TRAKT_ROW_NAME_UP_NEXT = "Trakt Up Next"
private const val TRAKT_ROW_NAME_TRENDING_MOVIES = "Trakt Trending Movies"
private const val TRAKT_ROW_NAME_TRENDING_SHOWS = "Trakt Trending Shows"
private const val TRAKT_ROW_NAME_POPULAR_MOVIES = "Trakt Popular Movies"
private val TMDB_TRENDING_SCREENSAVER_CATALOG_IDS = listOf(
    TmdbCatalogIds.TRENDING_MOVIES,
    TmdbCatalogIds.TRENDING_SERIES
)
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
private const val HOME_HYDRATED_OVERLAY_POLICY_VERSION = 1

internal fun HomeViewModel.invalidateHomeCatalogConfigurationPipeline(reason: String) {
    lastCatalogComputationSignature = null
    lastCatalogOrderDiagnosticsSignature = null
    truncatedRowCache.clear()
    Log.d(HomeViewModel.TAG, "Home catalog configuration invalidated reason=$reason")
}

internal fun HomeViewModel.restorePersistedCatalogSnapshotPipeline() {
    viewModelScope.launch(Dispatchers.IO) {
        val profileId = profileManager.activeProfileId.value
        val posterToken = homeCatalogSnapshotStore.currentPosterProviderToken()
        val snapshot = homeCatalogSnapshotStore.read(posterToken, profileId = profileId)
        // Phase 3.7 — restore the typed authority's state from disk so the
        // post-3.6.5 typed surfaces (ModernHomeRowItem / HeroDisplayItem)
        // render with hydrated content immediately on cold-start. The legacy
        // snapshot.catalogRows path continues to drive _internalCatalogRows
        // for now; the typed cache provides the rule #1-compliant overlay.
        runCatching {
            val typedCache = resolvedDisplaySnapshotStore.read(profileId)
            if (typedCache.isNotEmpty()) {
                resolvedDisplaySurfaceRepository.restoreFromDisk(items = typedCache, profileId = profileId)
            }
        }.onFailure { error ->
            android.util.Log.w(HomeViewModel.TAG, "Failed to restore typed cache from disk", error)
        }
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
            val hasRenderedContent = _internalCatalogRows.value.any { it.items.isNotEmpty() } ||
                _heroItemKeys.value.isNotEmpty()
            if (hasRenderedContent) {
                return@withContext
            }

            val restoredSnapshot = filterRestoredHomeSnapshotKitsuRows(
                snapshot = filterRestoredHomeSnapshotTmdbRows(
                    snapshot = snapshot,
                    tmdbPrefs = tmdbCatalogPreferences,
                    tmdbSnapshot = tmdbDiscoverySnapshot,
                    currentSyntheticTmdbGroups = persistedTmdbSyntheticGroupsMatchingPreferences(tmdbCatalogPreferences)
                ),
                kitsuPrefs = kitsuCatalogPreferences,
                kitsuSnapshot = kitsuDiscoverySnapshot,
                currentSyntheticKitsuGroups = persistedKitsuSyntheticGroupsMatchingPreferences(kitsuCatalogPreferences)
            )
            if (
                restoredSnapshot.catalogRows.isEmpty() &&
                restoredSnapshot.fullCatalogRows.isEmpty() &&
                restoredSnapshot.heroItems.isEmpty()
            ) {
                return@withContext
            }

            hasPersistedCatalogSnapshot = true
            startupRefreshPending = true
            restoredCatalogSnapshotActive = true
            inMemoryHomeSnapshot = restoredSnapshot
            pendingRestoredCatalogSnapshot = restoredSnapshot
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
                "kitsuGroups=${snapshot.kitsuGroups.size} kitsuRows=${snapshot.kitsuGroups.sumOf { it.rows.size }} " +
                "tmdbGroups=${snapshot.tmdbGroups.size} tmdbRows=${snapshot.tmdbGroups.sumOf { it.rows.size }} " +
                "traktAuthenticated=${providerState.traktAuthenticated}"
        )
        withContext(Dispatchers.Main.immediate) {
            persistedTraktSyntheticGroups = if (providerState.traktAuthenticated) snapshot.traktGroups else emptyList()
            persistedSimklSyntheticGroups = snapshot.simklGroups
            persistedMDBListSyntheticGroups = snapshot.mdbListGroups
            persistedKitsuSyntheticGroups = snapshot.kitsuGroups
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
    heroEnrichmentJob?.cancel()
    heroEnrichmentJob = null
    lastHeroEnrichmentSignature = null
    lastHeroEnrichedItems = emptyList()
    continueWatchingEnrichmentJob?.cancel()
    catalogsMap.clear()
    catalogOrder.clear()
    truncatedRowCache.clear()
    persistedTraktSyntheticGroups = emptyList()
    persistedSimklSyntheticGroups = emptyList()
    persistedMDBListSyntheticGroups = emptyList()
    persistedKitsuSyntheticGroups = emptyList()
    clearPersistedTmdbSyntheticGroups()
    traktDiscoverySnapshot = com.nexio.tv.data.repository.TraktDiscoverySnapshot()
    persistedTraktDiscoverySnapshot = com.nexio.tv.data.repository.TraktDiscoverySnapshot()
    simklDiscoverySnapshot = com.nexio.tv.data.repository.SimklDiscoverySnapshot()
    persistedSimklDiscoverySnapshot = com.nexio.tv.data.repository.SimklDiscoverySnapshot()
    mdbListDiscoverySnapshot = com.nexio.tv.data.repository.MDBListDiscoverySnapshot()
    persistedMDBListDiscoverySnapshot = com.nexio.tv.data.repository.MDBListDiscoverySnapshot()
    kitsuDiscoverySnapshot = com.nexio.tv.data.repository.KitsuDiscoverySnapshot()
    tmdbDiscoverySnapshot = com.nexio.tv.data.repository.TmdbDiscoverySnapshot()
    kitsuDiscoveryObserved = false
    tmdbDiscoveryRefreshInProgress = false
    kitsuCatalogPreferencesObserved = false
    tmdbCatalogPreferencesObserved = false
    tmdbCredentialRefreshPending = false
    inMemoryHomeSnapshot = null
    pendingRestoredCatalogSnapshot = null
    pendingHomeSnapshotPersist = null
    homeSnapshotPersistJob?.cancel()
    invalidateHydratedHomeOverlayScope(scheduleRows = false)
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
    catalogInventoryRepository.clear()
    _internalCatalogRows.value = emptyList()
    publishCatalogStructureFromRows(emptyList())
    _metaByItemKey.value = emptyMap()
    _heroItemKeys.value = emptyList()
    _displayContinueWatchingItems.value = emptyList()
    _uiState.update { state ->
        state.copy(
            heroCatalogKeys = emptyList(),
            traktUpNextItems = emptyList(),
            modernHomePresentation = ModernHomePresentationState(),
            homeReadiness = HomeInitialReadiness.started(
                sessionId = activeHomeProfileSessionSnapshot.sessionId,
                profileId = activeHomeProfileSessionSnapshot.profileId
            ),
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
            persistedKitsuSyntheticGroups = snapshot.kitsuGroups
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

internal fun hydratedHomeOverlayItemKeysForRows(rows: List<CatalogRow>): Set<String> {
    return rows
        .asSequence()
        .flatMap { row -> row.items.asSequence() }
        .flatMap { item ->
            HomeArtworkOverlayKeys.aliasesFor(
                rowItemKey = item.homeOverlayItemKey(),
                contentId = item.id,
                itemType = item.apiType,
                providerIds = item.firstPaintStableIds,
                canonicalProvider = null,
                canonicalId = null
            ).asSequence()
        }
        .toSet()
}

internal fun shouldPublishHydratedHomeOverlays(
    current: Map<String, HydratedHomeOverlay>,
    next: Map<String, HydratedHomeOverlay>
): Boolean {
    if (current.size != next.size) return true
    if (current.keys != next.keys) return true
    return current.any { (key, overlay) ->
        val nextOverlay = next[key] ?: return true
        !overlay.contentEquals(nextOverlay)
    }
}

internal fun HomeViewModel.invalidateHydratedHomeOverlayScope(scheduleRows: Boolean = true) {
    hydratedHomeOverlayObserverJob?.cancel()
    hydratedHomeOverlayObserverJob = null
    hydratedHomeOverlayObserverSignature = null
    hydratedHomeOverlaysByItemKey.value = emptyMap()
    lastCatalogComputationSignature = null
    if (scheduleRows) {
        scheduleUpdateCatalogRows()
    }
}

internal fun HomeViewModel.isCurrentHomeHydrationScope(
    expectedGeneration: Long,
    expectedLanguageTag: String,
    expectedProfileSession: ActiveProfileSession? = null
): Boolean {
    return homeHydrationScopeMismatchReason(
        expectedGeneration = expectedGeneration,
        expectedLanguageTag = expectedLanguageTag,
        expectedProfileSession = expectedProfileSession
    ) == null
}

internal fun HomeViewModel.applyHydratedHomeOverlayFromCoordinator(
    overlay: HydratedHomeOverlay,
    expectedGeneration: Long,
    expectedLanguageTag: String,
    expectedProfileSession: ActiveProfileSession,
    trigger: StableIdResolutionTrigger
): Boolean {
    val mismatchReason = homeHydrationScopeMismatchReason(
        expectedGeneration = expectedGeneration,
        expectedLanguageTag = expectedLanguageTag,
        expectedProfileSession = expectedProfileSession
    )
    if (mismatchReason != null) {
        traceEvents.emitHomeHydrationIgnored(
            itemKey = overlay.itemKey,
            reason = mismatchReason,
            trigger = trigger.name
        )
        return false
    }

    var changed = false
    hydratedHomeOverlaysByItemKey.update { current ->
        val existing = current[overlay.itemKey]
        if (existing != null && existing.contentEquals(overlay)) {
            current
        } else {
            changed = true
            current + (overlay.itemKey to overlay)
        }
    }
    if (changed) {
        lastCatalogComputationSignature = null
        scheduleUpdateCatalogRows()
        resolvedDisplaySurfaceRepository.publishResolvedItems(
            surfaceKey = com.nexio.tv.data.repository.ResolvedDisplaySurfaceRepository.HOME_SURFACE_KEY,
            profileSession = expectedProfileSession,
            items = listOf(overlay.toResolvedDisplayItem()),
            replace = false
        )
    }
    return true
}

private fun HomeViewModel.homeHydrationScopeMismatchReason(
    expectedGeneration: Long,
    expectedLanguageTag: String,
    expectedProfileSession: ActiveProfileSession? = null
): String? {
    if (expectedProfileSession != null && profileManager.activeProfileSession.value != expectedProfileSession) {
        return "profile_session_changed"
    }
    if (!isCurrentHomeProfileGeneration(expectedGeneration)) {
        return "generation_mismatch"
    }
    if (profileBoundary.currentLanguageTag() != expectedLanguageTag) {
        return "language_changed"
    }
    return null
}

internal suspend fun HomeViewModel.hydrateVisibleHomeItemsWithCoordinator(
    items: List<MetaPreview>,
    expectedGeneration: Long,
    expectedProfileSession: ActiveProfileSession? = null
) {
    if (!isNonPlaybackHomeWorkAllowed()) return

    val uniqueItems = items.distinctBy { it.homeOverlayItemKey() }
    if (uniqueItems.isEmpty()) return

    val languageTag = profileBoundary.currentLanguageTag()
    val capturedProfileSession = expectedProfileSession ?: profileManager.activeProfileSession.value
    if (
        homeHydrationScopeMismatchReason(
            expectedGeneration = expectedGeneration,
            expectedLanguageTag = languageTag,
            expectedProfileSession = capturedProfileSession
        ) != null
    ) {
        return
    }
    // Indexed iteration to avoid ArrayList$Itr capture in continuation. The
    // suspending homeHydrationCoordinator.hydrate(...) call inside the body would
    // otherwise save the iterator into the continuation's L$N field, pinning the
    // uniqueItems list for the lifetime of the (possibly cancelled) coroutine.
    for (i in uniqueItems.indices) {
        val item = uniqueItems[i]
        if (!isNonPlaybackHomeWorkAllowed()) return
        if (
            homeHydrationScopeMismatchReason(
                expectedGeneration = expectedGeneration,
                expectedLanguageTag = languageTag,
                expectedProfileSession = capturedProfileSession
            ) != null
        ) {
            return
        }
        val itemKey = item.homeOverlayItemKey()
        if (hydratedHomeOverlaysByItemKey.value[itemKey]?.languageTag == languageTag) continue
        if (!visibleHomeHydrationInFlightItemKeys.add(itemKey)) continue
        try {
            if (
                homeHydrationScopeMismatchReason(
                    expectedGeneration = expectedGeneration,
                    expectedLanguageTag = languageTag,
                    expectedProfileSession = capturedProfileSession
                ) != null
            ) {
                return
            }
            if (hydratedHomeOverlaysByItemKey.value[itemKey]?.languageTag == languageTag) continue
            homeHydrationCoordinator.hydrate(
                item = item,
                trigger = StableIdResolutionTrigger.VISIBLE_HOME_HYDRATION,
                priority = HomeHydrationPriority.VISIBLE,
                languageTag = languageTag,
                expectedGeneration = expectedGeneration,
                currentGeneration = { homeProfileGeneration },
                onOverlayApplied = { overlay ->
                    applyHydratedHomeOverlayFromCoordinator(
                        overlay = overlay,
                        expectedGeneration = expectedGeneration,
                        expectedLanguageTag = languageTag,
                        expectedProfileSession = capturedProfileSession,
                        trigger = StableIdResolutionTrigger.VISIBLE_HOME_HYDRATION
                    )
                }
            )
        } finally {
            visibleHomeHydrationInFlightItemKeys.remove(itemKey)
        }
    }
}

internal fun composeHydratedHomeOverlaySnapshot(
    displayRows: List<CatalogRow>,
    fullRows: List<CatalogRow>,
    heroItems: List<MetaPreview>,
    @Suppress("UNUSED_PARAMETER") overlaysByItemKey: Map<String, HydratedHomeOverlay>,
    @Suppress("UNUSED_PARAMETER") heroTmdbSettings: TmdbSettings = TmdbSettings()
): HydratedHomeOverlaySnapshotComponents {
    // Phase 3.6.5 — producer no longer applies overlays at compose time. The
    // typed authority (ResolvedDisplaySurfaceRepository) is the sole source of
    // hydrated content for visible Modern Home rendering; rails/hero structure
    // is the only thing the producer emits. Phases 3.6.1-3.6.4 expanded the
    // typed projections (ModernHomeRowItem, HeroDisplayItem) with description /
    // genres / releaseInfo / tomatoesRating / runtimeText, and migrated
    // buildCatalogItem + HeroCarouselSlide to read from those projections
    // first, MetaPreview as fallback only. The overlay map and tmdbSettings
    // remain in the signature so callers don't need to change shape — Phase 3.9
    // will retire the call site entirely. Phase 4 deletes the unreferenced
    // apply helpers (HomeHydrationOverlayApplier.kt, HomeDisplayMetadata.applyTo*,
    // applyToHeroItem, etc.).
    return HydratedHomeOverlaySnapshotComponents(
        displayRows = displayRows,
        fullRows = fullRows,
        heroItems = heroItems
    )
}

internal fun HomeViewModel.observeHydratedHomeOverlaysForRows(rows: List<CatalogRow>) {
    val itemKeys = hydratedHomeOverlayItemKeysForRows(rows)
    if (itemKeys.isEmpty()) {
        hydratedHomeOverlayObserverJob?.cancel()
        hydratedHomeOverlayObserverJob = null
        hydratedHomeOverlayObserverSignature = null
        if (hydratedHomeOverlaysByItemKey.value.isNotEmpty()) {
            hydratedHomeOverlaysByItemKey.value = emptyMap()
            scheduleUpdateCatalogRows()
        }
        return
    }

    val languageTag = profileBoundary.currentLanguageTag()
    val observerSignature = "$languageTag|${itemKeys.sorted().joinToString("|")}"
    if (hydratedHomeOverlayObserverSignature == observerSignature) return

    hydratedHomeOverlayObserverSignature = observerSignature
    hydratedHomeOverlayObserverJob?.cancel()
    hydratedHomeOverlayObserverJob = viewModelScope.launch {
        hydratedHomeOverlayStore.observeForItemKeys(
            itemKeys = itemKeys,
            languageTag = languageTag,
            policyVersion = HOME_HYDRATED_OVERLAY_POLICY_VERSION
        ).collectLatest { overlays ->
            // Keep the alias-shape the store emits — each rail row's requested alias is
            // its own key, so different rails of the same show coexist as separate map
            // entries pointing at the same overlay (e.g., series:trakt:139960 and
            // series:tmdb:223386 both → same overlay). Collapsing to overlay.itemKey
            // would lose rails whose alias differs from the row that originally
            // hydrated the overlay; the apply seam (Task 1) tolerates the asymmetry
            // via HomeArtworkOverlayKeys.aliasesFor lookup.
            if (!shouldPublishHydratedHomeOverlays(hydratedHomeOverlaysByItemKey.value, overlays)) {
                return@collectLatest
            }
            hydratedHomeOverlaysByItemKey.update { previous ->
                preserveStaleOverlays(previous = previous, next = overlays)
            }
            scheduleUpdateCatalogRows()
        }
    }
}

/**
 * Subscribes to [CatalogItemCrossIdEnricher.resolutionUpdates] and schedules a
 * debounced catalog-row re-emit on each event. The next pipeline pass calls
 * [CatalogMapper.toDomain] whose [CatalogItemCrossIdEnricher.enrichFromCache]
 * (sync, cache-only) now hits the IdMappingStore entry written by the resolver,
 * so the newly-enriched imdb id reaches the artwork pipeline without touching
 * the producer hot path.
 */
internal fun HomeViewModel.startCrossIdResolutionObserverPipeline() {
    viewModelScope.launch {
        catalogItemCrossIdEnricher.resolutionUpdates.collect {
            scheduleUpdateCatalogRows()
        }
    }
}

/**
 * Fires [CatalogItemCrossIdEnricher.enrichResolving] for every item in [rows]
 * that is still missing an imdb id. Runs in a fire-and-forget coroutine so the
 * caller's producer hot path is unblocked. Each [enrichResolving] call is a
 * suspending network operation; cooperative cancellation is respected via
 * [ensureActive] per item and by re-throwing [CancellationException].
 *
 * Indexed-for loops are used throughout (CLAUDE.md rule #4 — no suspending
 * forEach on lists; iterator allocation pinned across suspension points).
 *
 * Non-[CancellationException] errors are caught per-item so a single network
 * failure does not abort enrichment for the remaining items; the next emission
 * retries via the cache-miss path automatically.
 */
internal fun HomeViewModel.enrichCatalogRowItemsAsync(rows: List<CatalogRow>) {
    viewModelScope.launch {
        val enrichedByItemKey = mutableMapOf<String, com.nexio.tv.domain.model.MetaPreview>()
        for (rowIndex in rows.indices) {
            val row = rows[rowIndex]
            for (itemIndex in row.items.indices) {
                ensureActive()
                val item = row.items[itemIndex]
                if (item.firstPaintStableIds.imdb.isNullOrBlank()) {
                    try {
                        val enriched = catalogItemCrossIdEnricher.enrichResolving(item)
                        if (enriched !== item) {
                            enrichedByItemKey[com.nexio.tv.domain.model.homeDisplayItemKey(item.apiType, item.id)] = enriched
                        }
                    } catch (cancel: kotlinx.coroutines.CancellationException) {
                        throw cancel
                    } catch (_: Exception) {
                        // Non-fatal: cache miss on this item; the next emission will retry.
                    }
                }
            }
        }
        if (enrichedByItemKey.isEmpty()) return@launch
        // Apply enriched MetaPreviews back to _internalCatalogRows so the next
        // catalog-pipeline emission carries the resolver-populated imdb id into
        // the artwork pipeline (which queries RPDB). Without this re-apply, the
        // resolver populates the IdMappingStore but the in-memory rail items
        // stay frozen with `firstPaintStableIds.imdb = null`, the artwork
        // pipeline never gets the imdb it needs, and RPDB never queries.
        _internalCatalogRows.update { current ->
            applyEnrichedItemsToRows(current, enrichedByItemKey)
        }
        // Plan B Task 5e-pre — re-publish the surface-level MetaPreview lookup
        // so the presentation pipeline sees the resolver-enriched items
        // (otherwise the metaByItemKey signal would be stale relative to
        // _internalCatalogRows for one emission cycle).
        publishMetaByItemKeyFromRows(_internalCatalogRows.value)
    }
}

private fun applyEnrichedItemsToRows(
    rows: List<CatalogRow>,
    enrichedByItemKey: Map<String, com.nexio.tv.domain.model.MetaPreview>
): List<CatalogRow> {
    var anyChanged = false
    val updated = ArrayList<CatalogRow>(rows.size)
    for (rowIndex in rows.indices) {
        val row = rows[rowIndex]
        var rowChanged = false
        val newItems = ArrayList<com.nexio.tv.domain.model.MetaPreview>(row.items.size)
        for (itemIndex in row.items.indices) {
            val item = row.items[itemIndex]
            val key = com.nexio.tv.domain.model.homeDisplayItemKey(item.apiType, item.id)
            val enriched = enrichedByItemKey[key]
            if (enriched != null && enriched !== item) {
                newItems += enriched
                rowChanged = true
                anyChanged = true
            } else {
                newItems += item
            }
        }
        updated += if (rowChanged) row.copy(items = newItems) else row
    }
    return if (anyChanged) updated else rows
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
        traktDiscoveryService.observeSnapshot(autoRefreshOnStart = true).collectLatest { snapshot ->
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
            runSerializedHomeRefreshIfNeeded("trakt_discovery")
        }
    }
}

internal fun HomeViewModel.observeTraktCatalogPreferencesPipeline() {
    viewModelScope.launch {
        traktSettingsDataStore.catalogPreferences.collectLatest { prefs ->
            if (prefs == traktCatalogPreferences) return@collectLatest
            invalidateHomeCatalogConfigurationPipeline("trakt_pref_change")
            traktCatalogPreferences = prefs
            applyPendingPersistedHomeSnapshotIfPossiblePipeline("observe_trakt_prefs")
            if (activeProfileTraktAuthenticated &&
                shouldRefreshTraktDiscoveryForState(prefs, traktDiscoverySnapshot) &&
                !shouldSuppressProfileSwitchRefresh("trakt_pref_change") &&
                isNonPlaybackHomeWorkAllowed()
            ) {
                traktDiscoveryService.ensureFresh(force = false)
            }
            startupRefreshPending = true
            runSerializedHomeRefreshIfNeeded("trakt_pref_change")
        }
    }
}

internal fun HomeViewModel.observeSimklDiscoveryPipeline() {
    viewModelScope.launch {
        simklDiscoveryService.observeSnapshot(autoRefreshOnStart = true).collectLatest { snapshot ->
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
            runCatching { renewSimklSyntheticSnapshotPipeline(snapshot) }
                .onFailure { error ->
                    Log.w(HomeViewModel.TAG, "Failed to renew SIMKL synthetic snapshot after discovery update", error)
                }
            runSerializedHomeRefreshIfNeeded("simkl_discovery")
        }
    }
}

internal fun HomeViewModel.observeSimklCatalogPreferencesPipeline() {
    viewModelScope.launch {
        simklSettingsDataStore.catalogPreferences.collectLatest { prefs ->
            if (prefs == simklCatalogPreferences) return@collectLatest
            invalidateHomeCatalogConfigurationPipeline("simkl_pref_change")
            simklCatalogPreferences = prefs
            applyPendingPersistedHomeSnapshotIfPossiblePipeline("observe_simkl_prefs")
            if (shouldRefreshSimklDiscoveryForState(prefs, simklDiscoverySnapshot) &&
                !shouldSuppressProfileSwitchRefresh("simkl_pref_change") &&
                isNonPlaybackHomeWorkAllowed()
            ) {
                runCatching { simklDiscoveryService.ensureFresh(force = false) }
                    .onFailure { error ->
                        Log.w(HomeViewModel.TAG, "Failed to refresh SIMKL discovery after settings change", error)
                    }
            }
            startupRefreshPending = true
            runSerializedHomeRefreshIfNeeded("simkl_pref_change")
        }
    }
}

internal fun HomeViewModel.observeMDBListDiscoveryPipeline() {
    viewModelScope.launch {
        mdbListDiscoveryService.observeSnapshot(autoRefreshOnStart = true).collectLatest { snapshot ->
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
            runSerializedHomeRefreshIfNeeded("mdblist_discovery")
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
                    runCatching { mdbListDiscoveryService.ensureFresh(force = false) }
                        .onFailure { error ->
                            Log.w(HomeViewModel.TAG, "Failed to refresh MDBList discovery after settings change", error)
                        }
                } else if ((!settings.enabled || settings.apiKey.isBlank()) &&
                    !shouldBlockProfileSwitchDiskSnapshotRefresh("mdblist_settings_disabled")
                ) {
                    mdbListDiscoverySnapshot = com.nexio.tv.data.repository.MDBListDiscoverySnapshot()
                    persistedMDBListDiscoverySnapshot = mdbListDiscoverySnapshot
                    startupRefreshPending = true
                    applyPendingPersistedHomeSnapshotIfPossiblePipeline("observe_mdblist_settings_disabled")
                    runSerializedHomeRefreshIfNeeded("mdblist_settings_disabled")
                }
            }
    }
}

internal fun HomeViewModel.observeMDBListCatalogPreferencesPipeline() {
    viewModelScope.launch {
        mdbListSettingsDataStore.catalogPreferences.collectLatest { prefs ->
            if (prefs == mdbListCatalogPreferences) return@collectLatest
            invalidateHomeCatalogConfigurationPipeline("mdblist_pref_change")
            mdbListCatalogPreferences = prefs
            applyPendingPersistedHomeSnapshotIfPossiblePipeline("observe_mdblist_prefs")
            if (shouldRefreshMDBListDiscoveryForState(prefs, mdbListDiscoverySnapshot) &&
                !shouldSuppressProfileSwitchRefresh("mdblist_pref_change") &&
                isNonPlaybackHomeWorkAllowed()
            ) {
                mdbListDiscoveryService.ensureFresh(force = false)
            }
            startupRefreshPending = true
            runSerializedHomeRefreshIfNeeded("mdblist_pref_change")
        }
    }
}

internal fun HomeViewModel.observeKitsuDiscoveryPipeline() {
    viewModelScope.launch {
        kitsuDiscoveryService.observeSnapshot().collectLatest { snapshot ->
            val capturedGeneration = homeProfileGeneration
            if (!isCurrentHomeProfileGeneration(capturedGeneration)) {
                Log.d(HomeViewModel.TAG, "Skipping stale Kitsu discovery snapshot generation=$capturedGeneration")
                return@collectLatest
            }
            if (kitsuDiscoveryObserved && snapshot == kitsuDiscoverySnapshot) return@collectLatest
            kitsuDiscoveryObserved = true
            kitsuDiscoverySnapshot = snapshot
            startupRefreshPending = true
            applyPendingPersistedHomeSnapshotIfPossiblePipeline("observe_kitsu_discovery")
            runCatching { renewKitsuSyntheticSnapshotPipeline(snapshot) }
                .onFailure { error ->
                    Log.w(HomeViewModel.TAG, "Failed to renew Kitsu synthetic snapshot after discovery update", error)
                }
            runSerializedHomeRefreshIfNeeded("kitsu_discovery")
        }
    }
}

internal fun HomeViewModel.observeKitsuCatalogPreferencesPipeline() {
    viewModelScope.launch {
        kitsuCatalogSettingsDataStore.catalogPreferences.collectLatest { prefs ->
            val firstObservation = !kitsuCatalogPreferencesObserved
            if (!firstObservation && prefs == kitsuCatalogPreferences) return@collectLatest
            invalidateHomeCatalogConfigurationPipeline("kitsu_pref_change")
            kitsuCatalogPreferencesObserved = true
            kitsuCatalogPreferences = prefs
            applyPendingPersistedHomeSnapshotIfPossiblePipeline("observe_kitsu_prefs")
            if (shouldRefreshKitsuDiscoveryForState(prefs, kitsuDiscoverySnapshot) &&
                !shouldSuppressProfileSwitchRefresh("kitsu_pref_change") &&
                isNonPlaybackHomeWorkAllowed()
            ) {
                runCatching { kitsuDiscoveryService.refreshCatalogs(prefs, force = false) }
                    .onFailure { error ->
                        Log.w(HomeViewModel.TAG, "Failed to refresh Kitsu discovery after settings change", error)
                    }
            }
            startupRefreshPending = true
            runSerializedHomeRefreshIfNeeded("kitsu_pref_change")
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
            runCatching { renewTmdbSyntheticSnapshotPipeline(snapshot) }
                .onFailure { error ->
                    Log.w(HomeViewModel.TAG, "Failed to renew TMDB synthetic snapshot after discovery update", error)
                }
            runSerializedHomeRefreshIfNeeded("tmdb_discovery")
        }
    }
}

internal fun HomeViewModel.observeTmdbCatalogPreferencesPipeline() {
    viewModelScope.launch {
        tmdbCatalogSettingsDataStore.catalogPreferences.collectLatest { prefs ->
            val firstObservation = !tmdbCatalogPreferencesObserved
            if (!firstObservation && prefs == tmdbCatalogPreferences) return@collectLatest
            invalidateHomeCatalogConfigurationPipeline("tmdb_pref_change")
            tmdbCatalogPreferencesObserved = true
            tmdbCatalogPreferences = prefs
            applyPendingPersistedHomeSnapshotIfPossiblePipeline("observe_tmdb_prefs")
            refreshTmdbDiscoveryForPendingCredentialChangePipeline("tmdb_credential_change")
            if (shouldRefreshTmdbDiscoveryForState(prefs, tmdbDiscoverySnapshot) &&
                !shouldSuppressProfileSwitchRefresh("tmdb_pref_change") &&
                isNonPlaybackHomeWorkAllowed()
            ) {
                runCatching { tmdbDiscoveryService.refreshCatalogs(prefs, force = false) }
                    .onFailure { error ->
                        Log.w(HomeViewModel.TAG, "Failed to refresh TMDB discovery after settings change", error)
                    }
            }
            startupRefreshPending = true
            runSerializedHomeRefreshIfNeeded("tmdb_pref_change")
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
            invalidateHomeCatalogConfigurationPipeline("disabled_home_catalogs")
            disabledHomeCatalogKeys = newKeys
            rebuildCatalogOrder(addonsCache)
            applyPendingPersistedHomeSnapshotIfPossiblePipeline("observe_disabled_home_catalogs")
            val profileSessionForUpdate = profileManager.activeProfileSession.value
            catalogUpdateJob?.cancel()
            catalogUpdateJob = viewModelScope.launch {
                updateCatalogRowsPipeline(profileSessionForUpdate)
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
                val previousSettings = currentTmdbSettings
                currentTmdbSettings = settings
                if (shouldForceTmdbDiscoveryRefreshForCredentialChange(
                        previous = previousSettings,
                        current = settings,
                        prefs = tmdbCatalogPreferences
                    )
                ) {
                    tmdbCredentialRefreshPending = true
                    refreshTmdbDiscoveryForPendingCredentialChangePipeline("tmdb_credential_change")
                } else if (previousSettings.apiKey.trim() != settings.apiKey.trim()) {
                    tmdbCredentialRefreshPending = true
                }
                scheduleUpdateCatalogRows()
                enrichContinueWatchingWithCurrentSettings()
            }
    }
}

internal suspend fun HomeViewModel.refreshTmdbDiscoveryForPendingCredentialChangePipeline(reason: String) {
    if (!tmdbCredentialRefreshPending) return
    if (tmdbCatalogPreferences.enabledCatalogIds().isEmpty()) return
    if (shouldSuppressProfileSwitchRefresh(reason)) return
    if (!isNonPlaybackHomeWorkAllowed()) return

    tmdbCredentialRefreshPending = false
    startupRefreshPending = true
    runCatching { tmdbDiscoveryService.refreshCatalogs(tmdbCatalogPreferences, force = true) }
        .onFailure { error ->
            Log.w(HomeViewModel.TAG, "Failed to refresh TMDB discovery after credential change", error)
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
                val blockNetworkRefresh = shouldBlockProfileSwitchDiskSnapshotRefresh("observe_installed_addons")
                loadAllCatalogsPipeline(addons, allowNetworkRefresh = !blockNetworkRefresh)
            }
    }
}

internal suspend fun HomeViewModel.runSerializedPostStartupRefreshPipeline(
    expectedGeneration: Long,
    expectedProfileSession: ActiveProfileSession,
    reason: String
) {
    fun isCurrentSerializedRefreshScope(): Boolean {
        return isCurrentHomeProfileGeneration(expectedGeneration) &&
            profileManager.activeProfileSession.value == expectedProfileSession
    }

    if (!isCurrentSerializedRefreshScope()) {
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
    // Don't pre-fetch the four full discovery snapshots into outer-fun locals.
    // Every suspension inside any launch in the supervisorScope below saves the
    // entire outer-fun local set into the branch's continuation, including the
    // three snapshots that branch doesn't even use — heap dump showed a ~100k-
    // element ArrayList pinned by L$24 across the whole fan-out.
    //
    // Capture only the diff-baseline `Set<String>` keys here (small, ~tens of KB
    // each); these survive across supervisorScope.joinAll() and feed the final
    // telemetry. The decision flags need the full snapshot, but only briefly:
    // we wrap the fetch + decision in `let` so the snapshot has no named local,
    // letting it be GC'd as soon as the boolean is computed.
    val beforeTraktKeys = traktSnapshotItemKeys(
        traktDiscoveryService.observeSnapshot(autoRefreshOnStart = false).first()
    )
    val beforeSimklKeys = simklSnapshotItemKeys(
        simklDiscoveryService.observeSnapshot(autoRefreshOnStart = false).first()
    )
    val beforeMdbKeys = mdbSnapshotItemKeys(
        mdbListDiscoveryService.observeSnapshot(autoRefreshOnStart = false).first()
    )
    val beforeTmdbKeys = tmdbSnapshotItemKeys(
        tmdbDiscoveryService.observeSnapshot().first()
    )

    logStartupPerf(
        "synthetic_refresh_start",
        "trakt_items=${beforeTraktKeys.size} simkl_items=${beforeSimklKeys.size} " +
            "mdb_items=${beforeMdbKeys.size} tmdb_items=${beforeTmdbKeys.size}"
    )

    val refreshedCatalogCount = AtomicInteger(0)
    val refreshTraktDiscovery = activeProfileTraktAuthenticated &&
        shouldAttemptSerializedTraktDiscoveryRefresh(traktCatalogPreferences)
    // The decision helpers need a full snapshot for their predicate but only briefly:
    // wrap in `let` so the snapshot has no named local and is GC-eligible the moment
    // the boolean is computed. Without `let`, the snapshot would stay in scope as
    // an outer-fun local and get pinned in every launch's continuation below.
    val refreshSimklDiscovery = simklDiscoveryService.observeSnapshot(autoRefreshOnStart = false).first()
        .let { snap -> shouldRefreshSimklDiscoveryForState(simklCatalogPreferences, snap) }
    val refreshMdbDiscovery = mdbListDiscoveryService.observeSnapshot(autoRefreshOnStart = false).first()
        .let { snap -> shouldRefreshMDBListDiscoveryForState(mdbListCatalogPreferences, snap) }
    val refreshTmdbDiscovery = tmdbDiscoveryService.observeSnapshot().first()
        .let { snap -> shouldRefreshTmdbDiscoveryForState(tmdbCatalogPreferences, snap) }
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
                    val traktBeforeKeys = beforeTraktKeys
                    val traktAfterKeys = traktSnapshotItemKeys(afterTraktSnapshot)
                    withContext(Dispatchers.Main.immediate) {
                        if (!isCurrentSerializedRefreshScope()) return@withContext
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
                    if (isCurrentSerializedRefreshScope() && activeProfileTraktAuthenticated) {
                        renewTraktSyntheticSnapshotPipeline(
                            snapshot = afterTraktSnapshot,
                            expectedGeneration = expectedGeneration,
                            expectedProfileSession = expectedProfileSession
                        )
                    }
                    logStartupPerf("synthetic_refresh_step_end", "source=trakt rows=${persistedTraktSyntheticGroups.sumOf { it.rows.size }}")
                    withContext(Dispatchers.Main.immediate) {
                        if (!isCurrentSerializedRefreshScope()) return@withContext
                        scheduleUpdateCatalogRows(expectedProfileSession)
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
                        if (!isCurrentSerializedRefreshScope()) return@withContext
                        tmdbDiscoveryRefreshInProgress = true
                        startupRefreshPending = true
                        scheduleUpdateCatalogRows(expectedProfileSession)
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
                    val tmdbBeforeKeys = beforeTmdbKeys
                    val tmdbAfterKeys = tmdbSnapshotItemKeys(afterTmdbSnapshot)
                    withContext(Dispatchers.Main.immediate) {
                        if (!isCurrentSerializedRefreshScope()) return@withContext
                        tmdbDiscoverySnapshot = afterTmdbSnapshot
                    }
                    logStartupPerf(
                        "synthetic_refresh_provider_ready",
                        "source=tmdb total=${tmdbAfterKeys.size} added=${(tmdbAfterKeys - tmdbBeforeKeys).size} retained=${(tmdbAfterKeys intersect tmdbBeforeKeys).size}"
                    )
                    Log.d(HomeViewModel.TAG, "Post-startup refresh step end source=tmdb_discovery")
                    logStartupPerf("synthetic_refresh_step_start", "source=tmdb")
                    if (isCurrentSerializedRefreshScope()) {
                        renewTmdbSyntheticSnapshotPipeline(
                            snapshot = afterTmdbSnapshot,
                            expectedGeneration = expectedGeneration,
                            expectedProfileSession = expectedProfileSession
                        )
                    }
                    logStartupPerf("synthetic_refresh_step_end", "source=tmdb rows=${persistedTmdbSyntheticGroups.sumOf { it.rows.size }}")
                    withContext(Dispatchers.Main.immediate) {
                        if (!isCurrentSerializedRefreshScope()) return@withContext
                        scheduleUpdateCatalogRows(expectedProfileSession)
                    }
                } catch (e: kotlinx.coroutines.CancellationException) {
                    throw e
                } catch (t: Throwable) {
                    Log.w(HomeViewModel.TAG, "Unexpected failure during TMDB refresh in serialized startup pipeline", t)
                } finally {
                    if (refreshTmdbDiscovery) {
                        withContext(Dispatchers.Main.immediate) {
                            if (!isCurrentSerializedRefreshScope()) return@withContext
                            tmdbDiscoveryRefreshInProgress = false
                            scheduleUpdateCatalogRows(expectedProfileSession)
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
                    val simklBeforeKeys = beforeSimklKeys
                    val simklAfterKeys = simklSnapshotItemKeys(afterSimklSnapshot)
                    withContext(Dispatchers.Main.immediate) {
                        if (!isCurrentSerializedRefreshScope()) return@withContext
                        simklDiscoverySnapshot = afterSimklSnapshot
                        persistedSimklDiscoverySnapshot = afterSimklSnapshot
                    }
                    logStartupPerf(
                        "synthetic_refresh_provider_ready",
                        "source=simkl total=${simklAfterKeys.size} added=${(simklAfterKeys - simklBeforeKeys).size} retained=${(simklAfterKeys intersect simklBeforeKeys).size}"
                    )
                    Log.d(HomeViewModel.TAG, "Post-startup refresh step end source=simkl_discovery")
                    logStartupPerf("synthetic_refresh_step_start", "source=simkl")
                    if (isCurrentSerializedRefreshScope()) {
                        renewSimklSyntheticSnapshotPipeline(
                            snapshot = afterSimklSnapshot,
                            expectedGeneration = expectedGeneration,
                            expectedProfileSession = expectedProfileSession
                        )
                    }
                    logStartupPerf("synthetic_refresh_step_end", "source=simkl rows=${persistedSimklSyntheticGroups.sumOf { it.rows.size }}")
                    withContext(Dispatchers.Main.immediate) {
                        if (!isCurrentSerializedRefreshScope()) return@withContext
                        scheduleUpdateCatalogRows(expectedProfileSession)
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
                    val mdbBeforeKeys = beforeMdbKeys
                    val mdbAfterKeys = mdbSnapshotItemKeys(afterMdbSnapshot)
                    withContext(Dispatchers.Main.immediate) {
                        if (!isCurrentSerializedRefreshScope()) return@withContext
                        mdbListDiscoverySnapshot = afterMdbSnapshot
                        persistedMDBListDiscoverySnapshot = afterMdbSnapshot
                    }
                    logStartupPerf(
                        "synthetic_refresh_provider_ready",
                        "source=mdblist total=${mdbAfterKeys.size} added=${(mdbAfterKeys - mdbBeforeKeys).size} retained=${(mdbAfterKeys intersect mdbBeforeKeys).size}"
                    )
                    Log.d(HomeViewModel.TAG, "Post-startup refresh step end source=mdblist_discovery")
                    logStartupPerf("synthetic_refresh_step_start", "source=mdblist")
                    if (isCurrentSerializedRefreshScope()) {
                        renewMDBListSyntheticSnapshotPipeline(
                            snapshot = afterMdbSnapshot,
                            expectedGeneration = expectedGeneration,
                            expectedProfileSession = expectedProfileSession
                        )
                    }
                    logStartupPerf("synthetic_refresh_step_end", "source=mdblist rows=${persistedMDBListSyntheticGroups.sumOf { it.rows.size }}")
                    withContext(Dispatchers.Main.immediate) {
                        if (!isCurrentSerializedRefreshScope()) return@withContext
                        scheduleUpdateCatalogRows(expectedProfileSession)
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
                    var rawFirstPaintBatchActive = catalogInventoryRepository.isEmpty()
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
                                    if (!isCurrentSerializedRefreshScope()) return@withContext null
                                    catalogsMap[key]
                                }
                            },
                            isItemReferencedElsewhere = { itemKey, sourceCatalogKey ->
                                withContext(Dispatchers.Main.immediate) {
                                    if (!isCurrentSerializedRefreshScope()) return@withContext false
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
                                    if (!isCurrentSerializedRefreshScope()) return@withContext
                                    val shouldFlushFirstPaint = rawFirstPaintBatchActive && row.items.isNotEmpty()
                                    catalogsMap[catalogKey] = row
                                    if (diff.addedOrChanged.isNotEmpty()) {
                                        logStartupPerf(
                                            "catalog_publish_ready",
                                            "catalogKey=$catalogKey items_added=${diff.addedOrChanged.size}"
                                        )
                                    }
                                    if (shouldFlushFirstPaint) {
                                        flushCatalogRowsForFirstPaint(expectedProfileSession)
                                    } else {
                                        scheduleUpdateCatalogRows(expectedProfileSession)
                                    }
                                }
                            },
                            onRawCatalogBatchComplete = {
                                withContext(Dispatchers.Main.immediate) {
                                    if (!isCurrentSerializedRefreshScope()) return@withContext
                                    rawFirstPaintBatchActive = false
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
                        if (!isCurrentSerializedRefreshScope()) return@withContext
                        scheduleUpdateCatalogRows(expectedProfileSession)
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
    if (!isCurrentSerializedRefreshScope()) {
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
    if (!isCurrentSerializedRefreshScope()) {
        Log.d(HomeViewModel.TAG, "Skipping stale serialized home refresh settlement after snapshot read generation=$expectedGeneration")
        return
    }
    val hydratedAfterTraktSnapshot = applyTomatoesOverridesToTraktSnapshot(
        afterTraktSnapshot,
        syntheticTomatoesOverridesByItemId
    )
    val hydratedAfterMdbSnapshot = applyTomatoesOverridesToMDBListSnapshot(
        afterMdbSnapshot,
        syntheticTomatoesOverridesByItemId
    )
    val traktAfterKeys = traktSnapshotItemKeys(hydratedAfterTraktSnapshot)
    val simklAfterKeys = simklSnapshotItemKeys(afterSimklSnapshot)
    val mdbAfterKeys = mdbSnapshotItemKeys(hydratedAfterMdbSnapshot)
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
        "trakt_total=${traktAfterKeys.size} trakt_added=${(traktAfterKeys - beforeTraktKeys).size} trakt_retained=${(traktAfterKeys intersect beforeTraktKeys).size} " +
            "simkl_total=${simklAfterKeys.size} simkl_added=${(simklAfterKeys - beforeSimklKeys).size} simkl_retained=${(simklAfterKeys intersect beforeSimklKeys).size} " +
            "mdb_total=${mdbAfterKeys.size} mdb_added=${(mdbAfterKeys - beforeMdbKeys).size} mdb_retained=${(mdbAfterKeys intersect beforeMdbKeys).size} " +
            "tmdb_total=${tmdbAfterKeys.size} tmdb_added=${(tmdbAfterKeys - beforeTmdbKeys).size} tmdb_retained=${(tmdbAfterKeys intersect beforeTmdbKeys).size}"
    )

    Log.d(
        HomeViewModel.TAG,
        "Post-startup refresh settled synthetic snapshot traktGroups=${persistedTraktSyntheticGroups.size} traktRows=${persistedTraktSyntheticGroups.sumOf { it.rows.size }} " +
            "simklGroups=${persistedSimklSyntheticGroups.size} simklRows=${persistedSimklSyntheticGroups.sumOf { it.rows.size }} " +
            "mdbGroups=${persistedMDBListSyntheticGroups.size} mdbRows=${persistedMDBListSyntheticGroups.sumOf { it.rows.size }} " +
            "tmdbGroups=${persistedTmdbSyntheticGroups.size} tmdbRows=${persistedTmdbSyntheticGroups.sumOf { it.rows.size }}"
    )

    val visibleItemsBeforeSettle = _internalCatalogRows.value
        .asSequence()
        .flatMap { row -> row.items.asSequence() }
        .toList()

    // Recompute rows once at the end so Home settles on the renewed merged snapshot.
    runCatching {
        lastCatalogComputationSignature = null
        updateCatalogRowsPipeline(expectedProfileSession)
    }
    if (!isCurrentSerializedRefreshScope()) {
        Log.d(HomeViewModel.TAG, "Skipping stale serialized home refresh visible hydration generation=$expectedGeneration")
        return
    }

    // Inventory contribution from the repository; union with the live
    // catalogsMap (which holds rails not yet committed to the inventory
    // snapshot at this point in the refresh pipeline).
    val activeCatalogItemKeys = catalogInventoryRepository.activeItemKeys() +
        catalogsMap.values.asSequence()
            .flatMap { row -> row.items.asSequence() }
            .map { item -> "${item.apiType}:${item.id}" }
            .toSet()
    val visibleItems = _internalCatalogRows.value
        .asSequence()
        .flatMap { row -> row.items.asSequence() }
        .toList()
        .ifEmpty { visibleItemsBeforeSettle }
    if (visibleItems.isNotEmpty()) {
        hydrateVisibleHomeItemsWithCoordinator(
            items = visibleItems,
            expectedGeneration = expectedGeneration,
            expectedProfileSession = expectedProfileSession
        )
        if (!isCurrentSerializedRefreshScope()) {
            Log.d(HomeViewModel.TAG, "Skipping stale serialized home refresh visible prefetch generation=$expectedGeneration")
            return
        }
        homeCatalogRefreshCoordinator.prefetchVisibleImagesOnly(
            items = visibleItems,
            telemetryEnabled = startupPerfTelemetryEnabled,
            onLog = { event, details -> logStartupPerf(event, details) }
        )
    }
    val activeContinueWatchingItemKeys = _displayContinueWatchingItems.value
        .map { item -> "${item.contentType()}:${item.contentId()}" }
        .toSet()
    if (activeCatalogItemKeys.isEmpty() && activeContinueWatchingItemKeys.isEmpty()) {
        logStartupPerf("metadata_cleanup_skipped", "reason=active_items_empty")
    } else {
        logStartupPerf(
            "metadata_cleanup_end",
            "active_items=${activeCatalogItemKeys.size + activeContinueWatchingItemKeys.size} ownership=rail_store"
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
        persistedKitsuSyntheticGroups = snapshot.kitsuGroups
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
        prefs = prefs,
        preferencesObserved = tmdbCatalogPreferencesObserved
    )
}

private fun HomeViewModel.persistedKitsuSyntheticGroupsMatchingPreferences(
    prefs: KitsuCatalogPreferences
): List<PersistedSyntheticCatalogGroup> {
    if (!kitsuCatalogPreferencesObserved) return emptyList()
    return persistedKitsuSyntheticGroups.filterKitsuGroupsEnabledUnder(prefs)
}

private fun HomeViewModel.syntheticHomeSnapshotFallback(
    traktGroups: List<PersistedSyntheticCatalogGroup> = persistedTraktSyntheticGroups,
    simklGroups: List<PersistedSyntheticCatalogGroup> = persistedSimklSyntheticGroups,
    mdbListGroups: List<PersistedSyntheticCatalogGroup> = persistedMDBListSyntheticGroups,
    kitsuGroups: List<PersistedSyntheticCatalogGroup> = persistedKitsuSyntheticGroups,
    tmdbGroups: List<PersistedSyntheticCatalogGroup> = persistedTmdbSyntheticGroups
): SyntheticHomeCatalogStore.Snapshot {
    return SyntheticHomeCatalogStore.Snapshot(
        traktGroups = traktGroups,
        simklGroups = simklGroups,
        mdbListGroups = mdbListGroups,
        kitsuGroups = kitsuGroups,
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

private fun HomeViewModel.isCurrentSyntheticRenewalScope(
    expectedGeneration: Long?,
    expectedProfileSession: ActiveProfileSession?
): Boolean {
    if (expectedGeneration != null && !isCurrentHomeProfileGeneration(expectedGeneration)) return false
    if (expectedProfileSession != null && profileManager.activeProfileSession.value != expectedProfileSession) return false
    return true
}

internal suspend fun HomeViewModel.renewTraktSyntheticSnapshotPipeline(
    snapshot: com.nexio.tv.data.repository.TraktDiscoverySnapshot,
    expectedGeneration: Long? = null,
    expectedProfileSession: ActiveProfileSession? = null
) {
    if (expectedGeneration != null && !isCurrentHomeProfileGeneration(expectedGeneration)) return
    val profileId = expectedProfileSession?.profileId ?: profileManager.activeProfileId.value
    if (!activeProfileTraktAuthenticated) {
        withContext(Dispatchers.Main.immediate) {
            if (!isCurrentSyntheticRenewalScope(expectedGeneration, expectedProfileSession)) return@withContext
            clearTraktHomeState("renew_trakt_synthetic_unauthenticated")
        }
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
            val renewedTraktGroups = liveGroups.toPersistedSyntheticCatalogGroups()
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
            if (!isCurrentSyntheticRenewalScope(expectedGeneration, expectedProfileSession)) return@withContext
            persistedTraktSyntheticGroups = groups
        }
    }
}

internal suspend fun HomeViewModel.renewSimklSyntheticSnapshotPipeline(
    snapshot: com.nexio.tv.data.repository.SimklDiscoverySnapshot,
    expectedGeneration: Long? = null,
    expectedProfileSession: ActiveProfileSession? = null
) {
    if (expectedGeneration != null && !isCurrentHomeProfileGeneration(expectedGeneration)) return
    val profileId = expectedProfileSession?.profileId ?: profileManager.activeProfileId.value
    val simklPrefsSnapshot = simklCatalogPreferences
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
            val renewedSimklGroups = liveGroups.toPersistedSyntheticCatalogGroups()
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
            if (!isCurrentSyntheticRenewalScope(expectedGeneration, expectedProfileSession)) return@withContext
            persistedSimklSyntheticGroups = groups
        }
    }
}

internal suspend fun HomeViewModel.renewMDBListSyntheticSnapshotPipeline(
    snapshot: com.nexio.tv.data.repository.MDBListDiscoverySnapshot,
    expectedGeneration: Long? = null,
    expectedProfileSession: ActiveProfileSession? = null
) {
    if (expectedGeneration != null && !isCurrentHomeProfileGeneration(expectedGeneration)) return
    val profileId = expectedProfileSession?.profileId ?: profileManager.activeProfileId.value
    val mdbPrefsSnapshot = mdbListCatalogPreferences
    var appliedMDBListGroups: List<PersistedSyntheticCatalogGroup>? = null

    syntheticCatalogStoreMutex.withLock {
        withContext(Dispatchers.IO) {
            val existingSnapshot = syntheticHomeCatalogStore.read(profileId = profileId)
                ?: syntheticHomeSnapshotFallback()
            val liveGroups = buildSyntheticMDBListRows(
                prefs = mdbPrefsSnapshot,
                snapshot = snapshot
            )
            val renewedMDBListGroups = liveGroups.toPersistedSyntheticCatalogGroups()
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
            if (!isCurrentSyntheticRenewalScope(expectedGeneration, expectedProfileSession)) return@withContext
            persistedMDBListSyntheticGroups = groups
        }
    }
}

internal suspend fun HomeViewModel.renewKitsuSyntheticSnapshotPipeline(
    snapshot: com.nexio.tv.data.repository.KitsuDiscoverySnapshot,
    expectedGeneration: Long? = null,
    expectedProfileSession: ActiveProfileSession? = null
) {
    if (expectedGeneration != null && !isCurrentHomeProfileGeneration(expectedGeneration)) return
    val profileId = expectedProfileSession?.profileId ?: profileManager.activeProfileId.value
    val kitsuPrefsSnapshot = kitsuCatalogPreferences
    var appliedKitsuGroups: List<PersistedSyntheticCatalogGroup>? = null

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
                kitsuPrefs = kitsuPrefsSnapshot,
                kitsuSnapshot = snapshot
            ).rails
                .filter { rail -> rail.descriptor.addonId == KITSU_HOME_ADDON_ID }
                .mapNotNull { rail ->
                    val rows = rail.toPopulatedRows()
                    if (rows.isEmpty()) null else SyntheticCatalogOrderGroup(orderKey = rail.orderKey, rows = rows)
                }
            val renewedKitsuGroups = liveGroups.toPersistedSyntheticCatalogGroups()
            val effectiveKitsuGroups = if (
                renewedKitsuGroups.isEmpty() &&
                existingSnapshot.kitsuGroups.isNotEmpty() &&
                shouldRefreshKitsuDiscoveryForState(kitsuPrefsSnapshot, snapshot)
            ) {
                existingSnapshot.kitsuGroups
            } else {
                renewedKitsuGroups
            }
            val renewedSnapshot = existingSnapshot.copy(kitsuGroups = effectiveKitsuGroups)
            if (renewedSnapshot == existingSnapshot) {
                return@withContext
            }
            syntheticHomeCatalogStore.write(renewedSnapshot, profileId = profileId)
            appliedKitsuGroups = effectiveKitsuGroups
        }
    }
    appliedKitsuGroups?.let { groups ->
        withContext(Dispatchers.Main.immediate) {
            if (!isCurrentSyntheticRenewalScope(expectedGeneration, expectedProfileSession)) return@withContext
            persistedKitsuSyntheticGroups = groups
        }
    }
}

internal suspend fun HomeViewModel.renewTmdbSyntheticSnapshotPipeline(
    snapshot: com.nexio.tv.data.repository.TmdbDiscoverySnapshot,
    expectedGeneration: Long? = null,
    expectedProfileSession: ActiveProfileSession? = null
) {
    if (expectedGeneration != null && !isCurrentHomeProfileGeneration(expectedGeneration)) return
    val profileId = expectedProfileSession?.profileId ?: profileManager.activeProfileId.value
    val tmdbPrefsSnapshot = tmdbCatalogPreferences
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
            val renewedTmdbGroups = liveGroups.toPersistedSyntheticCatalogGroups()
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
            if (!isCurrentSyntheticRenewalScope(expectedGeneration, expectedProfileSession)) return@withContext
            applyPersistedTmdbSyntheticSnapshot(renewedSnapshot)
        }
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
            persistedKitsuSyntheticGroups.isNotEmpty() ||
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
            kitsuCatalogPreferences.enabledCatalogs.isNotEmpty() ||
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
    prefetchedExternalMetaIds.clear()
    prefetchedTomatoesIds.clear()
    tomatoesEnrichmentInFlightIds.clear()
    prefetchedTmdbIds.clear()
    tmdbEnrichFocusJob?.cancel()
    pendingTmdbEnrichItemId = null
    setEnrichingItemId(null)

    try {
        val hasRestoredContent = _internalCatalogRows.value.any { it.items.isNotEmpty() } ||
            _heroItemKeys.value.isNotEmpty()
        val activeRefreshInProgress = isConfiguredHomeRefreshInProgress(
            catalogsLoadInProgress = catalogsLoadInProgress,
            traktDiscoveryRefreshInProgress = traktDiscoveryRefreshInProgress,
            simklDiscoveryRefreshInProgress = simklDiscoveryRefreshInProgress,
            mdbListDiscoveryRefreshInProgress = mdbListDiscoveryRefreshInProgress,
            kitsuDiscoveryRefreshInProgress = kitsuDiscoveryRefreshInProgress,
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
            catalogInventoryRepository.clear()
            homeSnapshotPersistJob?.cancel()
            pendingHomeSnapshotPersist = null
            inMemoryHomeSnapshot = null
            pendingRestoredCatalogSnapshot = null
            homeSnapshotPersistGeneration += 1
            hasPersistedCatalogSnapshot = false
            restoredCatalogSnapshotActive = false
            integrationOwnershipService.syncRails(
                RailKeyFactory.homeCatalogNamespace(profileManager.activeProfileId.value),
                emptyList()
            )
            homeCatalogSnapshotStore.clear(profileId = profileManager.activeProfileId.value)
            truncatedRowCache.clear()
            hasRenderedFirstCatalog = false
            trailerPreviewLoadingIds.clear()
            trailerPreviewNegativeCache.clear()
            trailerPreviewUrlsState.clear()
            trailerPreviewAudioUrlsState.clear()
            trailerPreviewUserAgentsState.clear()
            trailerPreviewSigningClientKeysState.clear()
            trailerPreviewCaptionsState.clear()
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
            integrationOwnershipService.syncRails(
                RailKeyFactory.homeCatalogNamespace(profileManager.activeProfileId.value),
                emptyList()
            )
            homeCatalogSnapshotStore.clear(profileId = profileManager.activeProfileId.value)
            trailerPreviewLoadingIds.clear()
            trailerPreviewNegativeCache.clear()
            trailerPreviewUrlsState.clear()
            trailerPreviewAudioUrlsState.clear()
            trailerPreviewUserAgentsState.clear()
            trailerPreviewSigningClientKeysState.clear()
            trailerPreviewCaptionsState.clear()
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
        trailerPreviewUserAgentsState.clear()
        trailerPreviewSigningClientKeysState.clear()
        trailerPreviewCaptionsState.clear()
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
                allowNetworkRefresh = allowNetworkRefresh
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
        catalogLoadSemaphore.withPermit {
            runCatalogLoad()
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

internal suspend fun HomeViewModel.updateCatalogRowsPipeline(profileSessionForSurface: ActiveProfileSession) {
    catalogRowsComputationMutex.withLock {
    val orderedKeys = catalogOrder.toList()
    val catalogSnapshot = catalogsMap.toMap()
    val heroCatalogKeys = currentHeroCatalogKeys
    val currentState = _uiState.value
    val currentLayout = currentState.homeLayout
    val currentGridItems = currentState.gridItems
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
    // Inventory read used to live here as a function-head local capturing
    // the full inventory (27.98 MiB on the failing-state heap dump) into the
    // outer-fun continuation across the catalogRowsComputationMutex.withLock
    // wait + the entire withContext block. Heaptrail dominator-tree showed
    // updateCatalogRowsPipeline$2$updateResult$1.$currentVisibleFullRows
    // retaining 27.98 MiB across overlapping pipeline emissions (six such
    // ArrayLists live simultaneously = 168 MiB duplicate state). The read
    // is now inside withContext (CLAUDE.md hard rule #6) and goes through
    // catalogInventoryRepository.snapshot() since the legacy
    // _fullCatalogRows MutableStateFlow was deleted in this plan's Task 6.
    val currentHydratedHomeOverlays = hydratedHomeOverlaysByItemKey.value
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
    val effectiveKitsuSnapshot = kitsuDiscoverySnapshot
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
        tmdbSnapshot = effectiveTmdbSnapshot,
        kitsuPrefs = kitsuCatalogPreferences,
        kitsuSnapshot = effectiveKitsuSnapshot
    )
    val expectedConfiguredOrderKeys = catalogPlan.expectedOrderKeys
    val publishableExpectedOrderKeys = catalogPlan.publishableOrderKeys
    val activeRefreshInProgress = isConfiguredHomeRefreshInProgress(
        catalogsLoadInProgress = catalogsLoadInProgress,
        traktDiscoveryRefreshInProgress = traktDiscoveryRefreshInProgress,
        simklDiscoveryRefreshInProgress = simklDiscoveryRefreshInProgress,
        mdbListDiscoveryRefreshInProgress = mdbListDiscoveryRefreshInProgress,
        kitsuDiscoveryRefreshInProgress = kitsuDiscoveryRefreshInProgress,
        tmdbDiscoveryRefreshInProgress = tmdbDiscoveryRefreshInProgress
    )
    val refreshInProgress = startupRefreshPending || activeRefreshInProgress
    pendingRestoredCatalogSnapshot?.let { snapshot ->
        applyPersistedHomeSnapshotIfEligiblePipeline(snapshot, requireSourceCachesReady = false)
    }
    val currentPreferencePersistedTmdbSyntheticGroups = persistedTmdbSyntheticGroupsMatchingPreferences(tmdbPrefs)
    val currentPreferencePersistedKitsuSyntheticGroups = persistedKitsuSyntheticGroupsMatchingPreferences(kitsuCatalogPreferences)
    val computationSignature = withContext(Dispatchers.Default) {
        // Read continue-watching snapshot inside withContext so the value is not pinned
        // as an outer-fun local across the catalogRowsComputationMutex.withLock + suspend
        // surface (CLAUDE.md hard rule #6).
        val continueWatchingItems = _displayContinueWatchingItems.value
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
            kitsuSnapshot = effectiveKitsuSnapshot,
            kitsuPrefs = kitsuCatalogPreferences,
            persistedKitsuSyntheticGroups = currentPreferencePersistedKitsuSyntheticGroups,
            disabledHomeCatalogKeys = disabledHomeCatalogKeys,
            hydratedHomeOverlaysByItemKey = currentHydratedHomeOverlays,
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

        val syntheticKitsuGroups = currentPreferencePersistedKitsuSyntheticGroups.toSyntheticCatalogOrderGroups()
        val persistedSyntheticGroupsByKey: Map<String, List<CatalogRow>> = (
            syntheticTraktGroups + syntheticSimklGroups + syntheticMDBListGroups +
                syntheticKitsuGroups + syntheticTmdbGroups
        ).associate { it.orderKey to it.rows }
        val liveSyntheticGroupsByKey: Map<String, List<CatalogRow>> =
            liveSyntheticGroups.associate { it.orderKey to it.rows }
        val syntheticContent = buildSyntheticGroupContentMaps(
            persistedSyntheticGroupsByKey = persistedSyntheticGroupsByKey,
            liveSyntheticGroupsByKey = liveSyntheticGroupsByKey,
            rawRowsByOrderKey = rawRowsByKey,
            homeCatalogGlobalKey = ::homeCatalogGlobalKey,
        )
        val syntheticRowsByKey = syntheticContent.syntheticRowsByKey
        val existingRowsByOrderKey = syntheticContent.existingRowsByOrderKey
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
            kitsuPrefs = kitsuCatalogPreferences,
            kitsuSnapshot = effectiveKitsuSnapshot,
            existingRowsByOrderKey = existingRowsByOrderKey
        ).descriptors
            .filterNot { descriptor ->
                descriptor.orderKey in rawRowsByKey || descriptor.orderKey in syntheticRowsByKey
            }
            .associate { descriptor -> descriptor.orderKey to descriptor.toLoadingCatalogRow() }
        val rowOrderKeyByGlobalKey: Map<String, String> = buildMap {
            putAll(syntheticContent.rowOrderKeyByGlobalKey)
            pendingRowsByKey.forEach { (orderKey, row) ->
                put(homeCatalogGlobalKey(row), orderKey)
            }
        }

        // Build the live definition list from the catalog plan. This is the authoritative
        // input the HomeRailOrderStore reconciler uses to compute effective order.
        val liveDefinitions = catalogPlan.toHomeRailDefinitions()

        // Run migration once per profile per ViewModel lifecycle (legacy -> live default ->
        // synthetic-fallback). Tracking by profile id ensures profile switches re-attempt
        // migration for the new profile if its persisted state is empty and legacy keys exist.
        // Subsequent ticks skip this; onLiveDefinitionsArrived is cheap and runs every tick to
        // upgrade MIGRATION_SYNTHETIC_FALLBACK -> MIGRATION once live definitions arrive.
        val currentProfileId = profileSessionForSurface.profileId
        if (currentProfileId !in migrationAttempted) {
            homeRailOrderStore.tryMigrate(
                persistedSyntheticOrder = collectPersistedSyntheticOrderKeys(
                    traktGroups = if (activeProfileTraktAuthenticated) persistedTraktSyntheticGroups else emptyList(),
                    simklGroups = persistedSimklSyntheticGroups,
                    mdblistGroups = persistedMDBListSyntheticGroups,
                    tmdbGroups = currentPreferencePersistedTmdbSyntheticGroups,
                    kitsuGroups = currentPreferencePersistedKitsuSyntheticGroups,
                ),
                liveDefinitions = liveDefinitions,
            )
            migrationAttempted.add(currentProfileId)
        }
        homeRailOrderStore.onLiveDefinitionsArrived(liveDefinitions)

        // Compute effective order synchronously from the authoritative store.
        val effectiveOrder = homeRailOrderStore.reconcileNow(liveDefinitions)

        // Build content-by-key maps for the pure materializer. Synthetic groups remain
        // content sources only; their iteration order is no longer authoritative.
        val liveSyntheticByKey: Map<HomeRailKey, List<CatalogRow>> =
            liveSyntheticGroups.associate { HomeRailKey(it.orderKey) to it.rows }
        val persistedSyntheticByKey: Map<HomeRailKey, List<CatalogRow>> = (
            (if (activeProfileTraktAuthenticated) syntheticTraktGroups else emptyList()) +
            syntheticSimklGroups + syntheticMDBListGroups + syntheticKitsuGroups + syntheticTmdbGroups
        ).associate { HomeRailKey(it.orderKey) to it.rows }
        val rawRowsByRailKey: Map<HomeRailKey, CatalogRow> =
            rawRowsByKey.mapKeys { HomeRailKey(it.key) }
        val pendingRowsByRailKey: Map<HomeRailKey, CatalogRow> =
            pendingRowsByKey.mapKeys { HomeRailKey(it.key) }
        val publishPolicyByKey: Map<HomeRailKey, RailPublishPolicy> =
            liveDefinitions.associate { it.key to it.publishPolicy }

        val combinedRows = materializeHomeRows(
            effectiveOrder = effectiveOrder,
            liveSyntheticGroupsByKey = liveSyntheticByKey,
            persistedSyntheticGroupsByKey = persistedSyntheticByKey,
            rawRowsByKey = rawRowsByRailKey,
            pendingRowsByKey = pendingRowsByRailKey,
            publishPolicyByKey = publishPolicyByKey,
        )
        val liveOrderedRows = combinedRows

        // Emit a diagnostics event whenever a visible key falls back to persisted synthetic
        // content because no live synthetic group nor live raw row is available for that key.
        effectiveOrder.visibleKeys.forEach { key ->
            if (key !in liveSyntheticByKey && key !in rawRowsByRailKey && key in persistedSyntheticByKey) {
                homeRailOrderStore.emitPersistedSyntheticFallback(key)
            }
        }

        // Preserve diagnostics signatures for downstream call sites - same shape as before,
        // computed from the new effective order so CatalogUpdateResult continues to populate.
        // (Phase 9/Task 20 will replace these with home.rail_order_reconciled events.)
        val effectiveOrderKeys = effectiveOrder.visibleKeys.map { it.value }
        val newlyDiscoveredSet = effectiveOrder.newlyDiscoveredKeys.toSet()
        val savedOrderKeys = effectiveOrder.visibleKeys
            .filter { it !in newlyDiscoveredSet }
            .map { it.value }
        val defaultOrderKeys = liveDefinitions.map { it.key.value }
        val orderDiagnosticsSignature = "${savedOrderKeys.hashCode()}:${defaultOrderKeys.hashCode()}:${effectiveOrderKeys.hashCode()}"
        val orderDiagnosticsMessage =
            "Catalog order reconciliation saved=${savedOrderKeys.size} default=${defaultOrderKeys.size} effective=${effectiveOrderKeys.size}"

        val currentCachedTmdbCatalogIds = currentTmdbCatalogIds(
            tmdbPrefs = tmdbPrefs,
            tmdbSnapshot = effectiveTmdbSnapshot,
            currentSyntheticTmdbGroups = currentPreferencePersistedTmdbSyntheticGroups
        )
        val currentCachedKitsuCatalogIds = currentKitsuCatalogIds(
            kitsuPrefs = kitsuCatalogPreferences,
            kitsuSnapshot = effectiveKitsuSnapshot,
            currentSyntheticKitsuGroups = currentPreferencePersistedKitsuSyntheticGroups
        )
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
            preserveKitsuRows = shouldPreserveKitsuCachedRows(
                snapshot = effectiveKitsuSnapshot,
                refreshInProgress = startupHydrationPending || startupRefreshPending || kitsuDiscoveryRefreshInProgress
            ),
            retainUnorderedRows = restoredCatalogSnapshotActive || startupHydrationPending || startupRefreshPending
        )
        // Read catalogInventoryRepository.snapshot() INSIDE the withContext
        // block instead of capturing it as an outer-fun local. The outer fun
        // is suspend; if the local were declared at function-head scope,
        // every suspension above (including the catalogRowsComputationMutex
        // .withLock wait, which can be long when emissions overlap) would
        // pin the 27.98 MiB inventory in its continuation. Reading at
        // use-site limits the pin to this withContext block's continuation
        // only (CLAUDE.md hard rule #6).
        //
        // NOTE: this is a snapshot-at-use-site read, not a function-entry
        // read. If two pipeline emissions race for the mutex, the second one
        // observes whatever the first one published. mergeCachedRowsWithLiveRows
        // is designed to handle this — its job is to preserve cached rows
        // through transitional refreshes — and sampling at the merge call
        // site is generally more correct than function entry. .values is a
        // view over the LinkedHashMap; .toList() materializes once into the
        // list shape mergeCachedRowsWithLiveRows expects.
        val cachedFullRows = catalogInventoryRepository.snapshot().values.toList()
        val effectiveOrderedRows = catalogRowMemo.intern(
            mergeCachedRowsWithLiveRows(
                cachedRows = cachedFullRows,
                liveRows = liveOrderedRows,
                preservationState = preservationState,
                orderedGroupKeys = effectiveOrderKeys,
                rowOrderKeyByGlobalKey = rowOrderKeyByGlobalKey,
                currentTmdbCatalogIds = currentCachedTmdbCatalogIds,
                currentKitsuCatalogIds = currentCachedKitsuCatalogIds
            )
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

    val composedOverlaySnapshot = composeHydratedHomeOverlaySnapshot(
        displayRows = updateResult.displayRows,
        fullRows = updateResult.fullRows,
        heroItems = updateResult.heroItems,
        overlaysByItemKey = currentHydratedHomeOverlays,
        heroTmdbSettings = currentTmdbSettings
    )
    val displayRows = composedOverlaySnapshot.displayRows
    val baseHeroItems = composedOverlaySnapshot.heroItems
    val fullRowsFiltered = composedOverlaySnapshot.fullRows
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

    val hasCurrentRenderedContent = hasRenderableHomeContent(
        currentState,
        _catalogStructure.value,
        heroItemsNonEmpty = _heroItemKeys.value.isNotEmpty(),
        _displayContinueWatchingItems.value
    )
    val shouldKeepVisibleContent =
        hasCurrentRenderedContent &&
            displayRows.isEmpty() &&
            baseHeroItems.isEmpty() &&
            fullRowsFiltered.isEmpty() &&
            refreshInProgress
    val screensaverSourceRows = tmdbTrendingScreensaverRows(
        tmdbSnapshot = tmdbDiscoverySnapshot,
        persistedTmdbGroups = persistedTmdbSyntheticGroups
    )

    if (shouldKeepVisibleContent) {
        publishTmdbTrendingScreensaverSurface(
            profileSession = profileSessionForSurface,
            overlaysByItemKey = currentHydratedHomeOverlays,
            sourceRows = screensaverSourceRows
        )
        observeHydratedHomeOverlaysForRows(displayRows + fullRowsFiltered + screensaverSourceRows)
        _uiState.update { it.copy(isLoading = true, error = null) }
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
        val resolvedItemsForSurface = HomeResolvedDisplayMapper.toResolvedDisplayItemsEnriched(
            rows = _internalCatalogRows.value,
            overlaysByItemKey = currentHydratedHomeOverlays,
            idMappingStore = idMappingStore,
            resolveTrailer = null
        )
        resolvedDisplaySurfaceRepository.publishResolvedItems(
            surfaceKey = com.nexio.tv.data.repository.ResolvedDisplaySurfaceRepository.HOME_SURFACE_KEY,
            profileSession = profileSessionForSurface,
            items = resolvedItemsForSurface
        )
        if (!hasTransientLoadingRows) {
            hasPersistedCatalogSnapshot = persistAndApplyHomeSnapshotPipeline(transientSnapshot)
        }
        pendingRestoredCatalogSnapshot = null
    }

    publishTmdbTrendingScreensaverSurface(
        profileSession = profileSessionForSurface,
        overlaysByItemKey = currentHydratedHomeOverlays,
        sourceRows = screensaverSourceRows
    )

    observeHydratedHomeOverlaysForRows(displayRows + fullRowsFiltered + screensaverSourceRows)

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
        val expectedHeroGeneration = homeProfileGeneration
        val expectedHeroLanguageTag = profileBoundary.currentLanguageTag()
        heroEnrichmentJob = viewModelScope.launch {
            if (!isCurrentHomeHydrationScope(expectedHeroGeneration, expectedHeroLanguageTag, profileSessionForSurface)) {
                return@launch
            }
            if (!isNonPlaybackHomeWorkAllowed()) return@launch
            val enrichmentSignature = heroEnrichmentSignaturePipeline(baseHeroItems, tmdbSettings)
            if (lastHeroEnrichmentSignature == enrichmentSignature) {
                val cached = lastHeroEnrichedItems
                if (!isCurrentHomeHydrationScope(expectedHeroGeneration, expectedHeroLanguageTag, profileSessionForSurface)) {
                    return@launch
                }
                updateInMemoryHomeSnapshotPipeline { snapshot ->
                    snapshot.copy(heroItems = cached)
                }
            } else {
                if (!isNonPlaybackHomeWorkAllowed()) return@launch
                val enrichedItems = enrichHeroItemsPipeline(
                    items = baseHeroItems,
                    settings = tmdbSettings,
                    expectedGeneration = expectedHeroGeneration,
                    expectedLanguageTag = expectedHeroLanguageTag,
                    expectedProfileSession = profileSessionForSurface
                )
                if (!isCurrentHomeHydrationScope(expectedHeroGeneration, expectedHeroLanguageTag, profileSessionForSurface)) {
                    return@launch
                }
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

internal fun HomeViewModel.publishTmdbTrendingScreensaverSurface(
    profileSession: ActiveProfileSession,
    overlaysByItemKey: Map<String, HydratedHomeOverlay>,
    sourceRows: List<CatalogRow> = tmdbTrendingScreensaverRows(
        tmdbSnapshot = tmdbDiscoverySnapshot,
        persistedTmdbGroups = persistedTmdbSyntheticGroups
    )
) {
    // Phase 3.5: drop the redundant rowsForResolvedDisplaySurface pre-application.
    // HomeResolvedDisplayMapper.toResolvedDisplayItem already applies the overlay
    // via HomeRailProjectionReducer.reduce(firstPaint, overlay, existing, profile) —
    // pre-applying overlays at the row level (via deprecated HomeDisplayMetadata.applyTo)
    // is dead work. The reducer is the single non-downgrade authority.
    val resolvedItems = HomeResolvedDisplayMapper.toResolvedDisplayItems(
        rows = sourceRows,
        overlaysByItemKey = overlaysByItemKey,
        resolveTrailer = null
    )
    val published = resolvedDisplaySurfaceRepository.publishResolvedItems(
        surfaceKey = ResolvedDisplaySurfaceRepository.SCREENSAVER_SURFACE_KEY,
        profileSession = profileSession,
        items = resolvedItems
    )
    if (!published) return
    traceEvents.emitScreensaverSurfacePublished(
        surface = ResolvedDisplaySurfaceRepository.SCREENSAVER_SURFACE_KEY,
        published = published,
        itemCount = resolvedItems.size,
        logoCount = resolvedItems.count { item -> item.artwork.logo != null },
        trailerCandidateCount = resolvedItems.count { item -> item.hasScreensaverTrailerResolutionPath() },
        selectedRefCount = resolvedItems.count { item -> item.trailer.selectedPlaybackRef != null },
        fallbackIdCount = resolvedItems.sumOf { item -> item.trailer.fallbackTrailerYtIds.size }
    )
}

private fun com.nexio.tv.domain.model.ResolvedDisplayItem.hasScreensaverTrailerResolutionPath(): Boolean =
    trailer.selectedPlaybackRef != null ||
        trailer.fallbackTrailerYtIds.any { id -> id.isNotBlank() } ||
        stableIds.tvdb?.isNotBlank() == true ||
        stableIds.tmdb?.isNotBlank() == true ||
        stableIds.imdb?.isNotBlank() == true ||
        stableIds.kitsu?.isNotBlank() == true ||
        contentId.isNotBlank()

internal fun HomeViewModel.applyHomeSnapshotToUiPipeline(
    snapshot: com.nexio.tv.data.local.HomeCatalogSnapshotStore.Snapshot
) {
    val builtInSafeSnapshot = filterRestoredHomeSnapshotKitsuRows(
        snapshot = filterRestoredHomeSnapshotTmdbRows(
            snapshot = snapshot,
            tmdbPrefs = tmdbCatalogPreferences,
            tmdbSnapshot = tmdbDiscoverySnapshot,
            currentSyntheticTmdbGroups = persistedTmdbSyntheticGroupsMatchingPreferences(tmdbCatalogPreferences)
        ),
        kitsuPrefs = kitsuCatalogPreferences,
        kitsuSnapshot = kitsuDiscoverySnapshot,
        currentSyntheticKitsuGroups = persistedKitsuSyntheticGroupsMatchingPreferences(kitsuCatalogPreferences)
    )
    val filteredSnapshot = builtInSafeSnapshot.filterDisabledHomeCatalogRows(
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
    val composedSnapshot = composeHydratedHomeOverlaySnapshot(
        displayRows = filteredSnapshot.catalogRows,
        fullRows = filteredSnapshot.fullCatalogRows,
        heroItems = filteredSnapshot.heroItems,
        overlaysByItemKey = hydratedHomeOverlaysByItemKey.value,
        heroTmdbSettings = currentTmdbSettings
    )
    catalogInventoryRepository.publish(composedSnapshot.fullRows)
    _internalCatalogRows.value = composedSnapshot.displayRows
    publishCatalogStructureFromRows(composedSnapshot.displayRows)
    publishMetaByItemKeyFromRows(composedSnapshot.displayRows)
    publishHeroItemKeysFromMetas(composedSnapshot.heroItems)
    _uiState.update { state ->
        val snapshotGridItems = if (state.homeLayout == HomeLayout.GRID) {
            buildGridItemsFromRowsPipeline(
                rows = composedSnapshot.displayRows,
                heroItems = composedSnapshot.heroItems,
                heroSectionEnabled = state.heroSectionEnabled
            )
        } else {
            state.gridItems
        }

        state.copy(
            gridItems = if (state.gridItems == snapshotGridItems) state.gridItems else snapshotGridItems,
            isLoading = false,
            error = null
        )
    }
    val screensaverSourceRows = tmdbTrendingScreensaverRows(
        tmdbSnapshot = tmdbDiscoverySnapshot,
        persistedTmdbGroups = persistedTmdbSyntheticGroups
    )
    publishTmdbTrendingScreensaverSurface(
        profileSession = profileManager.activeProfileSession.value,
        overlaysByItemKey = hydratedHomeOverlaysByItemKey.value,
        sourceRows = screensaverSourceRows
    )
    observeHydratedHomeOverlaysForRows(composedSnapshot.displayRows + composedSnapshot.fullRows + screensaverSourceRows)
    enrichCatalogRowItemsAsync(composedSnapshot.displayRows)
    refreshTrailerMetadataAvailabilityPipeline(composedSnapshot.displayRows)
}

internal fun filterRestoredHomeSnapshotTmdbRows(
    snapshot: com.nexio.tv.data.local.HomeCatalogSnapshotStore.Snapshot,
    tmdbPrefs: TmdbCatalogPreferences,
    tmdbSnapshot: com.nexio.tv.data.repository.TmdbDiscoverySnapshot,
    currentSyntheticTmdbGroups: List<PersistedSyntheticCatalogGroup> = emptyList()
): com.nexio.tv.data.local.HomeCatalogSnapshotStore.Snapshot {
    val currentTmdbCatalogIds = currentTmdbCatalogIds(
        tmdbPrefs = tmdbPrefs,
        tmdbSnapshot = tmdbSnapshot,
        currentSyntheticTmdbGroups = currentSyntheticTmdbGroups
    )

    fun isRetained(row: CatalogRow): Boolean {
        return row.addonId != TMDB_RAIL_ADDON_ID || row.catalogId in currentTmdbCatalogIds
    }

    val filteredFullRows = snapshot.fullCatalogRows.filter(::isRetained)
    val filteredDisplayRows = snapshot.catalogRows.filter(::isRetained)
    if (
        filteredFullRows.size == snapshot.fullCatalogRows.size &&
        filteredDisplayRows.size == snapshot.catalogRows.size
    ) {
        return snapshot
    }

    val removedTmdbRows = (snapshot.fullCatalogRows.asSequence() + snapshot.catalogRows.asSequence())
        .filterNot(::isRetained)
        .filter { row -> row.addonId == TMDB_RAIL_ADDON_ID }
        .toList()
    val removedTmdbKeys = removedTmdbRows
        .asSequence()
        .flatMap { row -> sequenceOf(row.catalogId, homeCatalogGlobalKey(row)) }
        .toSet()
    val removedTmdbItemKeys = removedTmdbRows
        .asSequence()
        .flatMap { row -> row.items.asSequence() }
        .map { item -> "${item.apiType}:${item.id}" }
        .toSet()
    val retainedItemKeys = filteredFullRows
        .asSequence()
        .flatMap { row -> row.items.asSequence() }
        .map { item -> "${item.apiType}:${item.id}" }
        .toSet()
    val retainedCurrentTmdbItemKeys = filteredFullRows
        .asSequence()
        .filter { row -> row.addonId == TMDB_RAIL_ADDON_ID }
        .flatMap { row -> row.items.asSequence() }
        .map { item -> "${item.apiType}:${item.id}" }
        .toSet()
    return snapshot.copy(
        catalogRows = filteredDisplayRows,
        fullCatalogRows = filteredFullRows,
        heroItems = snapshot.heroItems.filter { item ->
            val key = "${item.apiType}:${item.id}"
            key in retainedItemKeys &&
                (key !in removedTmdbItemKeys || key in retainedCurrentTmdbItemKeys)
        },
        orderedGroupKeys = snapshot.orderedGroupKeys.filterNot { key -> key in removedTmdbKeys }
    )
}

internal fun filterRestoredHomeSnapshotKitsuRows(
    snapshot: com.nexio.tv.data.local.HomeCatalogSnapshotStore.Snapshot,
    kitsuPrefs: KitsuCatalogPreferences,
    kitsuSnapshot: com.nexio.tv.data.repository.KitsuDiscoverySnapshot,
    currentSyntheticKitsuGroups: List<PersistedSyntheticCatalogGroup> = emptyList()
): com.nexio.tv.data.local.HomeCatalogSnapshotStore.Snapshot {
    val currentKitsuCatalogIds = currentKitsuCatalogIds(
        kitsuPrefs = kitsuPrefs,
        kitsuSnapshot = kitsuSnapshot,
        currentSyntheticKitsuGroups = currentSyntheticKitsuGroups
    )

    fun isRetained(row: CatalogRow): Boolean {
        return row.addonId != KITSU_HOME_ADDON_ID || row.catalogId in currentKitsuCatalogIds
    }

    val filteredFullRows = snapshot.fullCatalogRows.filter(::isRetained)
    val filteredDisplayRows = snapshot.catalogRows.filter(::isRetained)
    if (
        filteredFullRows.size == snapshot.fullCatalogRows.size &&
        filteredDisplayRows.size == snapshot.catalogRows.size
    ) {
        return snapshot
    }

    val removedKitsuRows = (snapshot.fullCatalogRows.asSequence() + snapshot.catalogRows.asSequence())
        .filterNot(::isRetained)
        .filter { row -> row.addonId == KITSU_HOME_ADDON_ID }
        .toList()
    val removedKitsuKeys = removedKitsuRows
        .asSequence()
        .flatMap { row -> sequenceOf(row.catalogId, homeCatalogGlobalKey(row)) }
        .toSet()
    val removedKitsuItemKeys = removedKitsuRows
        .asSequence()
        .flatMap { row -> row.items.asSequence() }
        .map { item -> "${item.apiType}:${item.id}" }
        .toSet()
    val retainedItemKeys = filteredFullRows
        .asSequence()
        .flatMap { row -> row.items.asSequence() }
        .map { item -> "${item.apiType}:${item.id}" }
        .toSet()
    val retainedCurrentKitsuItemKeys = filteredFullRows
        .asSequence()
        .filter { row -> row.addonId == KITSU_HOME_ADDON_ID }
        .flatMap { row -> row.items.asSequence() }
        .map { item -> "${item.apiType}:${item.id}" }
        .toSet()
    return snapshot.copy(
        catalogRows = filteredDisplayRows,
        fullCatalogRows = filteredFullRows,
        heroItems = snapshot.heroItems.filter { item ->
            val key = "${item.apiType}:${item.id}"
            key in retainedItemKeys &&
                (key !in removedKitsuItemKeys || key in retainedCurrentKitsuItemKeys)
        },
        orderedGroupKeys = snapshot.orderedGroupKeys.filterNot { key -> key in removedKitsuKeys }
    )
}

private fun currentTmdbCatalogIds(
    tmdbPrefs: TmdbCatalogPreferences,
    tmdbSnapshot: com.nexio.tv.data.repository.TmdbDiscoverySnapshot,
    currentSyntheticTmdbGroups: List<PersistedSyntheticCatalogGroup>
): Set<String> {
    return buildSet {
        addAll(tmdbSnapshot.currentRowsFor(tmdbPrefs).filterValues { row -> row.items.isNotEmpty() }.keys)
        if (shouldPreserveExistingTmdbGroupsDuringRefresh(tmdbPrefs.sanitized(), tmdbSnapshot)) {
            currentSyntheticTmdbGroups.filterTmdbGroupsEnabledUnder(tmdbPrefs).forEach { group ->
                if (group.rows.any { row -> row.items.isNotEmpty() }) {
                    add(group.orderKey)
                    group.rows.forEach { row -> add(row.catalogId) }
                }
            }
        }
    }
}

private fun currentKitsuCatalogIds(
    kitsuPrefs: KitsuCatalogPreferences,
    kitsuSnapshot: com.nexio.tv.data.repository.KitsuDiscoverySnapshot,
    currentSyntheticKitsuGroups: List<PersistedSyntheticCatalogGroup>
): Set<String> {
    return buildSet {
        addAll(kitsuSnapshot.currentRowsFor(kitsuPrefs).filterValues { row -> row.items.isNotEmpty() }.keys)
        currentSyntheticKitsuGroups.filterKitsuGroupsEnabledUnder(kitsuPrefs).forEach { group ->
            if (group.rows.any { row -> row.items.isNotEmpty() }) {
                add(group.orderKey)
                group.rows.forEach { row -> add(row.catalogId) }
            }
        }
    }
}

private fun com.nexio.tv.data.local.HomeCatalogSnapshotStore.Snapshot.filterDisabledHomeCatalogRows(
    disabledHomeCatalogKeys: Set<String>,
    isAddonRowDisabled: (CatalogRow) -> Boolean
): com.nexio.tv.data.local.HomeCatalogSnapshotStore.Snapshot {
    // Pre-compute non-"custom" disabled slugs ONCE for the whole filter pass.
    // Without this, slugifySyntheticHomeCatalogKey(disabledKey) was recomputed for every row × every
    // disabled key, each call allocating 4-5 Strings (lowercase + replace(Regex) + trim + ifBlank).
    // ANR trace on PID 2076 (2026-05-10) caught the main thread burning CPU here under sustained
    // Modern Home soak — String.toLowerCase / CaseMapper at the top of the stack. CLAUDE.md hard
    // rule #5: memoize at every reference-fresh boundary; this is the canonical case.
    val disabledSlugs: Set<String> = if (disabledHomeCatalogKeys.isEmpty()) {
        emptySet()
    } else {
        val out = HashSet<String>(disabledHomeCatalogKeys.size)
        for (k in disabledHomeCatalogKeys) {
            val slug = slugifySyntheticHomeCatalogKey(k)
            if (slug != "custom") out += slug
        }
        out
    }

    fun isDisabled(row: CatalogRow): Boolean {
        return when (row.addonId) {
            TRAKT_RAIL_ADDON_ID,
            SIMKL_RAIL_ADDON_ID,
            MDBLIST_RAIL_ADDON_ID,
            TMDB_RAIL_ADDON_ID -> {
                if (isSyntheticHomeCatalogDisabled(row.catalogId, disabledHomeCatalogKeys)) return true
                if (isSyntheticHomeCatalogDisabled(homeCatalogGlobalKey(row), disabledHomeCatalogKeys)) return true
                if (disabledSlugs.isEmpty()) return false
                // Compute lowercase ONCE per row, not once per (row × disabled key).
                val rowCatalogIdLower = row.catalogId.lowercase()
                disabledSlugs.any { slug -> rowCatalogIdLower.contains(slug) }
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
    val kitsuExpectedOrderKeys = buildExpectedConfiguredKitsuOrderKeys(kitsuCatalogPreferences)
    val restoredSnapshot = filterRestoredHomeSnapshotKitsuRows(
        snapshot = filterRestoredHomeSnapshotTmdbRows(
            snapshot = snapshot,
            tmdbPrefs = tmdbCatalogPreferences,
            tmdbSnapshot = tmdbDiscoverySnapshot,
            currentSyntheticTmdbGroups = persistedTmdbSyntheticGroupsMatchingPreferences(tmdbCatalogPreferences)
        ),
        kitsuPrefs = kitsuCatalogPreferences,
        kitsuSnapshot = kitsuDiscoverySnapshot,
        currentSyntheticKitsuGroups = persistedKitsuSyntheticGroupsMatchingPreferences(kitsuCatalogPreferences)
    )
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
        tmdbSnapshot = tmdbDiscoverySnapshot,
        kitsuPrefs = kitsuCatalogPreferences,
        kitsuSnapshot = kitsuDiscoverySnapshot
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
        kitsuExpectedOrderKeys = kitsuExpectedOrderKeys,
        kitsuPrefs = kitsuCatalogPreferences,
        kitsuSnapshot = kitsuDiscoverySnapshot,
        tmdbExpectedOrderKeys = tmdbExpectedOrderKeys,
        tmdbPrefs = tmdbCatalogPreferences,
        tmdbSnapshot = tmdbDiscoverySnapshot
    )
    if (requireSourceCachesReady && !sourceCachesReady) {
        Log.d(
            HomeViewModel.TAG,
            "Persisted snapshot deferred reason=source_caches_not_ready requireSourceCachesReady=true " +
                "snapshotKeys=${restoredSnapshot.orderedGroupKeys.size} expectedKeys=${expectedConfiguredOrderKeys.size}"
        )
        pendingRestoredCatalogSnapshot = restoredSnapshot
        return false
    }
    val snapshotComplete = isConfiguredHomeSnapshotComplete(
        snapshotOrderedGroupKeys = restoredSnapshot.orderedGroupKeys,
        expectedConfiguredOrderKeys = publishableExpectedOrderKeys
    )
    if (publishableExpectedOrderKeys.isNotEmpty() && !snapshotComplete) {
        val missingKeys = publishableExpectedOrderKeys.filterNot { it in restoredSnapshot.orderedGroupKeys.toSet() }
        Log.d(
            HomeViewModel.TAG,
            "Persisted snapshot deferred reason=incomplete expected=${publishableExpectedOrderKeys.size} " +
                "actual=${restoredSnapshot.orderedGroupKeys.size} missing=${missingKeys.joinToString(limit = 12)}"
        )
        pendingRestoredCatalogSnapshot = restoredSnapshot
        return false
    }
    Log.d(
        HomeViewModel.TAG,
        "Persisted snapshot applied orderedKeys=${restoredSnapshot.orderedGroupKeys.size} expected=${publishableExpectedOrderKeys.size} " +
            "sourceCachesReady=$sourceCachesReady rows=${restoredSnapshot.catalogRows.size} fullRows=${restoredSnapshot.fullCatalogRows.size}"
    )
    inMemoryHomeSnapshot = restoredSnapshot
    pendingRestoredCatalogSnapshot = null
    hasPersistedCatalogSnapshot = true
    applyHomeSnapshotToUiPipeline(restoredSnapshot)
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
        ensureActive()
        if (homeSnapshotPersistGeneration != persistGeneration) return@launch
        if (!isCurrentHomeProfileGeneration(profileGeneration)) return@launch
        val latestSnapshot = pendingHomeSnapshotPersist ?: return@launch
        val posterToken = homeCatalogSnapshotStore.currentPosterProviderToken()
        ensureActive()
        integrationOwnershipService.syncRails(
            RailKeyFactory.homeCatalogNamespace(profileId),
            homeCatalogSnapshotStore.buildRailMemberships(
                snapshot = latestSnapshot,
                posterProviderToken = posterToken,
                profileId = profileId
            )
        )
        ensureActive()
        homeCatalogSnapshotStore.write(latestSnapshot, posterToken, profileId = profileId)
        runCatching {
            resolvedDisplaySnapshotStore.write(
                items = resolvedDisplaySurfaceRepository.snapshotNow(profileId).associateBy { it.itemKey },
                profileId = profileId,
            )
        }.onFailure { error ->
            android.util.Log.w(HomeViewModel.TAG, "Failed to flush typed cache alongside home snapshot", error)
        }
        ensureActive()
        // Use the in-memory snapshot directly. The atomic-rename file write is strongly
        // consistent, so re-reading + re-parsing 7.65 MB of JSON would only verify what
        // we already know is on disk and burn ~30 MB of transient allocation per persist
        // (16 MB UTF-16 char[] from file.readText() + Gson tree). With debounce at 5 s
        // that became death-spiral fuel.
        Log.d(
            HomeViewModel.TAG,
            "Persisted merged home snapshot write rows=${latestSnapshot.catalogRows.size} fullRows=${latestSnapshot.fullCatalogRows.size} " +
                "hero=${latestSnapshot.heroItems.size} orderedKeys=${latestSnapshot.orderedGroupKeys.size}"
        )
        withContext(Dispatchers.Main.immediate) {
            if (!isCurrentHomeProfileGeneration(profileGeneration)) return@withContext
            if (homeSnapshotPersistGeneration == persistGeneration) {
                pendingHomeSnapshotPersist = null
            }
            applyPersistedHomeSnapshotIfEligiblePipeline(
                snapshot = latestSnapshot,
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
    kitsuSnapshot: com.nexio.tv.data.repository.KitsuDiscoverySnapshot,
    kitsuPrefs: KitsuCatalogPreferences,
    persistedKitsuSyntheticGroups: List<PersistedSyntheticCatalogGroup>,
    disabledHomeCatalogKeys: Set<String>,
    hydratedHomeOverlaysByItemKey: Map<String, HydratedHomeOverlay>,
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
    signature = (signature * 31) + kitsuSnapshot.hashCode()
    signature = (signature * 31) + kitsuPrefs.hashCode()
    signature = (signature * 31) + persistedKitsuSyntheticGroups.hashCode()
    signature = (signature * 31) + disabledHomeCatalogKeys.hashCode()
    // Fold each overlay's displayHash (already content-derived) instead of the raw map's
    // hashCode. HydratedHomeOverlay's data-class hashCode includes updatedAtMs/staleAtMs/
    // expiresAtMs, which the hydration coordinator stamps fresh on every rebuild — without
    // this content-only fold the catalog signature would change on every cache-hit
    // re-hydration even when displayHash and fields are identical, defeating the
    // lastCatalogComputationSignature short-circuit one layer up.
    val overlayContentSignature = hydratedHomeOverlaysByItemKey
        .toSortedMap()
        .entries
        .joinToString(separator = "|") { (key, overlay) -> "$key=${overlay.displayHash}" }
    signature = (signature * 31) + overlayContentSignature.hashCode()
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
    val preserveKitsuRows: Boolean,
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

private fun shouldPreserveKitsuCachedRows(
    snapshot: com.nexio.tv.data.repository.KitsuDiscoverySnapshot,
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
    rowOrderKeyByGlobalKey: Map<String, String>,
    currentTmdbCatalogIds: Set<String> = emptySet(),
    currentKitsuCatalogIds: Set<String> = emptySet()
): List<CatalogRow> {
    fun canRetainCachedRow(row: CatalogRow): Boolean {
        if (!shouldPreserveCachedRow(row, preservationState)) return false
        if (row.addonId == TMDB_RAIL_ADDON_ID && row.catalogId !in currentTmdbCatalogIds) return false
        if (row.addonId == KITSU_HOME_ADDON_ID && row.catalogId !in currentKitsuCatalogIds) return false
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
        KITSU_HOME_ADDON_ID -> {
            val prefixedCatalogId = "kitsu_${row.catalogId}"
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
        KITSU_HOME_ADDON_ID -> preservationState.preserveKitsuRows
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
    val displayMetadata = nextUp.displayMetadata()
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
        name = "${displayMetadata.title ?: info.name} • $episodeSuffix",
        poster = displayMetadata.displayPoster,
        posterShape = PosterShape.LANDSCAPE,
        background = displayMetadata.displayBackdrop,
        logo = displayMetadata.displayLogo,
        description = info.episodeDescription ?: displayMetadata.description,
        releaseInfo = displayMetadata.releaseInfo ?: info.releaseInfo ?: info.released,
        imdbRating = info.imdbRating ?: displayMetadata.imdbRating,
        tomatoesRating = displayMetadata.tomatoesRating,
        genres = info.genres.ifEmpty { displayMetadata.genres },
        posterProviderTag = displayMetadata.posterProviderTag,
        artwork = displayMetadata.toArtworkBundleFromDisplayFields(),
        firstPaintStableIds = providerIdsFromContinueWatchingContentId(info.contentId)
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
                    watchProgressRepository.isWatched(
                        profileId = profileManager.activeProfileId.value,
                        contentId = itemId
                    )
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

/**
 * Guards against transient-empty overlay emissions without growing the map unboundedly.
 *
 * When a rail observer re-subscribes with a different itemKey set, the store can briefly
 * return an empty map before the new overlays arrive. Discarding previous overlays at that
 * point causes visible "pop" as the UI briefly loses all artwork. This function retains the
 * previous map only for that transient-empty window.
 *
 * Once a non-empty `next` arrives it represents the new authoritative view and REPLACES
 * `previous` entirely. Unioning the two maps would cause unbounded growth: each
 * rail-visibility change spawns a new observer with a different itemKey set, and merging
 * would accumulate every prior key indefinitely.
 *
 * Rules:
 * - `next` empty AND `previous` non-empty → return `previous` as-is (transient-empty guard)
 * - `next` non-empty → return `next` as-is (authoritative replacement)
 */
internal fun preserveStaleOverlays(
    previous: Map<String, HydratedHomeOverlay>,
    next: Map<String, HydratedHomeOverlay>
): Map<String, HydratedHomeOverlay> {
    // Transient empty re-emit (e.g., during observer re-subscribe) must not flush
    // hydrated overlays — keep `previous` until a non-empty emission arrives.
    // Once the new authoritative view lands, REPLACE entirely; do not union, or the
    // map grows unboundedly across observer re-subscriptions.
    if (next.isEmpty() && previous.isNotEmpty()) return previous
    return next
}
