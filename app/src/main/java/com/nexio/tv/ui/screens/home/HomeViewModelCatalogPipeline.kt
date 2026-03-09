package com.nexio.tv.ui.screens.home

import android.util.Log
import com.nexio.tv.data.local.MDBListCatalogPreferences
import androidx.lifecycle.viewModelScope
import com.nexio.tv.core.network.NetworkResult
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
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.withPermit
import com.nexio.tv.core.util.filterReleasedItems
import kotlinx.coroutines.withContext
import java.time.LocalDate

private data class CatalogUpdateResult(
    val displayRows: List<CatalogRow>,
    val heroItems: List<com.nexio.tv.domain.model.MetaPreview>,
    val gridItems: List<GridItem>,
    val fullRows: List<CatalogRow>
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

internal fun HomeViewModel.restorePersistedCatalogSnapshotPipeline() {
    val snapshot = homeCatalogSnapshotStore.read() ?: return
    if (snapshot.catalogRows.isEmpty() && snapshot.fullCatalogRows.isEmpty() && snapshot.heroItems.isEmpty()) {
        return
    }

    hasPersistedCatalogSnapshot = true
    startupRefreshPending = true
    restoredCatalogSnapshotActive = true
    applyHomeSnapshotToUiPipeline(snapshot)
}

internal fun HomeViewModel.observeTraktDiscoveryPipeline() {
    viewModelScope.launch {
        traktDiscoveryService.observeSnapshot().collectLatest { snapshot ->
            traktDiscoveryObserved = true
            traktDiscoverySnapshot = snapshot
            scheduleUpdateCatalogRows()
        }
    }
}

internal fun HomeViewModel.observeTraktCatalogPreferencesPipeline() {
    viewModelScope.launch {
        traktSettingsDataStore.catalogPreferences.collectLatest { prefs ->
            if (prefs == traktCatalogPreferences) return@collectLatest
            traktCatalogPreferences = prefs
            if (shouldRefreshTraktDiscoveryForState(prefs, traktDiscoverySnapshot)) {
                traktDiscoveryService.ensureFresh(force = false)
            }
            scheduleUpdateCatalogRows()
        }
    }
}

internal fun HomeViewModel.observeMDBListDiscoveryPipeline() {
    viewModelScope.launch {
        mdbListDiscoveryService.observeSnapshot().collectLatest { snapshot ->
            mdbListDiscoveryObserved = true
            mdbListDiscoverySnapshot = snapshot
            Log.d(
                HomeViewModel.TAG,
                "MDBList snapshot personal=${snapshot.personalLists.size} top=${snapshot.topLists.size} custom=${snapshot.customListCatalogs.size}"
            )
            scheduleUpdateCatalogRows()
        }
    }
}

internal fun HomeViewModel.observeMDBListSettingsPipeline() {
    viewModelScope.launch {
        mdbListSettingsDataStore.settings
            .distinctUntilChanged()
            .collectLatest { settings ->
                if (settings.enabled && settings.apiKey.isNotBlank() &&
                    shouldRefreshMDBListDiscoveryForState(mdbListCatalogPreferences, mdbListDiscoverySnapshot)
                ) {
                    runCatching { mdbListDiscoveryService.ensureFresh(force = false) }
                        .onFailure { error ->
                            Log.w(HomeViewModel.TAG, "Failed to refresh MDBList discovery after settings change", error)
                        }
                } else if (!settings.enabled || settings.apiKey.isBlank()) {
                    mdbListDiscoverySnapshot = com.nexio.tv.data.repository.MDBListDiscoverySnapshot()
                    scheduleUpdateCatalogRows()
                }
            }
    }
}

internal fun HomeViewModel.observeMDBListCatalogPreferencesPipeline() {
    viewModelScope.launch {
        mdbListSettingsDataStore.catalogPreferences.collectLatest { prefs ->
            if (prefs == mdbListCatalogPreferences) return@collectLatest
            mdbListCatalogPreferences = prefs
            if (shouldRefreshMDBListDiscoveryForState(prefs, mdbListDiscoverySnapshot)) {
                mdbListDiscoveryService.ensureFresh(force = false)
            }
            scheduleUpdateCatalogRows()
        }
    }
}

internal fun HomeViewModel.dismissTraktRecommendationPipeline(
    ref: com.nexio.tv.data.repository.TraktRecommendationRef
) {
    viewModelScope.launch {
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
            if (addonsCache.isNotEmpty()) {
                loadAllCatalogsPipeline(addonsCache)
            } else {
                scheduleUpdateCatalogRows()
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
                loadAllCatalogsPipeline(addons)
            }
    }
}

internal suspend fun HomeViewModel.loadAllCatalogsPipeline(
    addons: List<Addon>,
    forceReload: Boolean = false
) {
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

    try {
        val hasRestoredContent = _uiState.value.catalogRows.any { it.items.isNotEmpty() } ||
            _uiState.value.heroItems.isNotEmpty()
        val activeRefreshInProgress =
            catalogsLoadInProgress ||
                traktDiscoveryRefreshInProgress ||
                mdbListDiscoveryRefreshInProgress
        val refreshInProgress = startupRefreshPending || activeRefreshInProgress
        val shouldPreserveCachedHome =
            hasPersistedCatalogSnapshot && (restoredCatalogSnapshotActive || hasRestoredContent || refreshInProgress)

        if (addons.isEmpty()) {
            if (shouldPreserveCachedHome) {
                catalogsLoadInProgress = false
                _uiState.update { it.copy(isLoading = true, error = null, installedAddonsCount = 0) }
                return
            }
            catalogOrder.clear()
            catalogsMap.clear()
            reconcilePosterStatusObserversPipeline(emptyList())
            _fullCatalogRows.value = emptyList()
            homeCatalogSnapshotStore.clear()
            truncatedRowCache.clear()
            hasRenderedFirstCatalog = false
            prefetchedExternalMetaIds.clear()
            externalMetaPrefetchInFlightIds.clear()
            lastHeroEnrichmentSignature = null
            lastHeroEnrichedItems = emptyList()
            catalogsLoadInProgress = false
            _uiState.update { it.copy(isLoading = false, error = "No addons installed") }
            return
        }

        rebuildCatalogOrder(addons)

        if (catalogOrder.isEmpty()) {
            if (shouldPreserveCachedHome) {
                catalogsLoadInProgress = false
                _uiState.update { it.copy(isLoading = true, error = null, installedAddonsCount = addons.size) }
                return
            }
            hasPersistedCatalogSnapshot = false
            startupRefreshPending = false
            homeCatalogSnapshotStore.clear()
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
        pendingCatalogLoads = catalogsToLoad.size
        catalogsToLoad.forEach { (addon, catalog) ->
            loadCatalogPipeline(addon, catalog, generation)
        }
    } catch (e: Exception) {
        catalogsLoadInProgress = false
        _uiState.update { it.copy(isLoading = false, error = e.message) }
    }
}

internal fun HomeViewModel.loadCatalogPipeline(
    addon: Addon,
    catalog: CatalogDescriptor,
    generation: Long
) {
    val loadJob = viewModelScope.launch {
        var hasCountedCompletion = false
        catalogLoadSemaphore.withPermit {
            if (generation != catalogLoadGeneration) return@withPermit
            val supportsSkip = catalog.supportsExtra("skip")
            val skipStep = catalog.skipStep()
            Log.d(
                HomeViewModel.TAG,
                "Loading home catalog addonId=${addon.id} addonName=${addon.name} type=${catalog.apiType} catalogId=${catalog.id} catalogName=${catalog.name} supportsSkip=$supportsSkip skipStep=$skipStep"
            )
            catalogRepository.getCatalog(
                addonBaseUrl = addon.baseUrl,
                addonId = addon.id,
                addonName = addon.displayName,
                catalogId = catalog.id,
                catalogName = catalog.name,
                type = catalog.apiType,
                skip = 0,
                skipStep = skipStep,
                supportsSkip = supportsSkip
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
        val addon = addonsCache.find { it.id == addonId } ?: return@launch

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
    val orderedKeys = catalogOrder.toList()
    val catalogSnapshot = catalogsMap.toMap()
    val heroCatalogKeys = currentHeroCatalogKeys
    val currentLayout = _uiState.value.homeLayout
    val currentGridItems = _uiState.value.gridItems
    val continueWatchingItems = _uiState.value.continueWatchingItems
    val heroSectionEnabled = _uiState.value.heroSectionEnabled
    val hideUnreleased = _uiState.value.hideUnreleasedContent
    val traktSnapshot = traktDiscoverySnapshot
    val traktPrefs = traktCatalogPreferences
    val mdbListSnapshot = mdbListDiscoverySnapshot
    val mdbListPrefs = mdbListCatalogPreferences
    val currentVisibleFullRows = _fullCatalogRows.value
    val startupHydrationPending = !installedAddonsObserved || !traktDiscoveryObserved || !mdbListDiscoveryObserved
    val traktUpNextItems = continueWatchingItems
        .filterIsInstance<ContinueWatchingItem.NextUp>()
        .take(20)
        .map { nextUpToMetaPreview(it) }
    val syntheticTraktGroups = buildSyntheticTraktRows(
        prefs = traktPrefs,
        upNextItems = traktUpNextItems,
        snapshot = traktSnapshot
    )
    val syntheticMDBListGroups = buildSyntheticMDBListRows(
        prefs = mdbListPrefs,
        snapshot = mdbListSnapshot
    )
    val recommendationRefMap = traktSnapshot.recommendationRefsByStatusKey

    val (displayRows, baseHeroItems, _, fullRowsFiltered) = withContext(Dispatchers.Default) {
        val rawRowsByKey = orderedKeys
            .mapNotNull { key ->
                catalogSnapshot[key]?.let { row ->
                    homeCatalogGlobalKey(row) to row
                }
            }
            .toMap(linkedMapOf())

        val syntheticGroups = syntheticTraktGroups + syntheticMDBListGroups
        val syntheticRowsByKey = linkedMapOf<String, List<CatalogRow>>().apply {
            syntheticGroups.forEach { group -> put(group.orderKey, group.rows) }
        }
        val rowOrderKeyByGlobalKey = linkedMapOf<String, String>().apply {
            rawRowsByKey.keys.forEach { globalKey -> put(globalKey, globalKey) }
            syntheticGroups.forEach { group ->
                group.rows.forEach { row ->
                    put(homeCatalogGlobalKey(row), group.orderKey)
                }
            }
        }

        val defaultOrderKeys = buildList {
            addAll(syntheticGroups.map { it.orderKey })
            addAll(rawRowsByKey.keys)
        }.distinct()

        val savedOrderKeys = homeCatalogOrderKeys
            .asSequence()
            .mapNotNull { rawKey -> resolveHomeOrderedKey(rawKey, defaultOrderKeys.toSet()) }
            .distinct()
            .toList()

        val savedOrderSet = savedOrderKeys.toSet()
        val effectiveOrderKeys = savedOrderKeys + defaultOrderKeys.filterNot { it in savedOrderSet }
        Log.d(
            HomeViewModel.TAG,
            "Catalog order reconciliation saved=${savedOrderKeys.size} default=${defaultOrderKeys.size} effective=${effectiveOrderKeys.size}"
        )
        val combinedRows = buildList {
            effectiveOrderKeys.forEach { key ->
                syntheticRowsByKey[key]?.let { addAll(it) } ?: rawRowsByKey[key]?.let { add(it) }
            }
        }
        val liveOrderedRows = if (hideUnreleased) {
            val today = LocalDate.now()
            combinedRows.map { row ->
                if (row.addonId == TRAKT_RAIL_ADDON_ID) row else row.filterReleasedItems(today)
            }
        } else {
            combinedRows
        }

        val preservationState = CachedHomePreservationState(
            preserveAddonRows = hasPersistedCatalogSnapshot &&
                (restoredCatalogSnapshotActive || startupHydrationPending || startupRefreshPending || catalogsLoadInProgress),
            preserveTraktRows = shouldPreserveTraktCachedRows(
                snapshot = traktSnapshot,
                refreshInProgress = startupHydrationPending || startupRefreshPending || traktDiscoveryRefreshInProgress
            ),
            preserveMDBListRows = shouldPreserveMDBListCachedRows(
                prefs = mdbListPrefs,
                snapshot = mdbListSnapshot,
                refreshInProgress = startupHydrationPending || startupRefreshPending || mdbListDiscoveryRefreshInProgress
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
            .take(7)
            .toList()
        val fallbackHeroItemsFromSelectedCatalogs = selectedHeroRows
            .asSequence()
            .flatMap { row -> row.items.asSequence() }
            .take(7)
            .toList()

        val fallbackHeroItemsWithArtwork = effectiveOrderedRows
            .asSequence()
            .flatMap { it.items.asSequence() }
            .filter { it.hasHeroArtwork() }
            .take(7)
            .toList()

        val computedHeroItems = when {
            heroItemsFromSelectedCatalogs.isNotEmpty() -> heroItemsFromSelectedCatalogs
            fallbackHeroItemsFromSelectedCatalogs.isNotEmpty() -> fallbackHeroItemsFromSelectedCatalogs
            fallbackHeroItemsWithArtwork.isNotEmpty() -> fallbackHeroItemsWithArtwork
            else -> emptyList()
        }

        val computedDisplayRows = effectiveOrderedRows.map { row ->
            val shouldKeepFullRowInModern = currentLayout == HomeLayout.MODERN && row.supportsSkip
            if (row.items.size > 25 && !shouldKeepFullRowInModern) {
                val key = "${row.addonId}_${row.apiType}_${row.catalogId}"
                val cachedEntry = truncatedRowCache[key]
                if (cachedEntry != null && cachedEntry.sourceRow === row) {
                    cachedEntry.truncatedRow
                } else {
                    val truncatedRow = row.copy(items = row.items.take(25))
                    truncatedRowCache[key] = HomeViewModel.TruncatedRowCacheEntry(
                        sourceRow = row,
                        truncatedRow = truncatedRow
                    )
                    truncatedRow
                }
            } else {
                val key = "${row.addonId}_${row.apiType}_${row.catalogId}"
                truncatedRowCache.remove(key)
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

        CatalogUpdateResult(computedDisplayRows, computedHeroItems, computedGridItems, effectiveOrderedRows)
    }

    val currentState = _uiState.value
    val hasCurrentRenderedContent = currentState.catalogRows.any { it.items.isNotEmpty() } ||
        currentState.heroItems.isNotEmpty()
    val activeRefreshInProgress =
        catalogsLoadInProgress ||
            traktDiscoveryRefreshInProgress ||
            mdbListDiscoveryRefreshInProgress
    val refreshInProgress = startupRefreshPending || activeRefreshInProgress
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
        hasPersistedCatalogSnapshot = persistAndApplyHomeSnapshotPipeline(
            com.nexio.tv.data.local.HomeCatalogSnapshotStore.Snapshot(
                catalogRows = displayRows,
                fullCatalogRows = fullRowsFiltered,
                heroItems = baseHeroItems
            )
        )
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

    if (shouldUseEnrichedHeroItems && baseHeroItems.isNotEmpty()) {
        heroEnrichmentJob?.cancel()
        heroEnrichmentJob = viewModelScope.launch {
            val enrichmentSignature = heroEnrichmentSignaturePipeline(baseHeroItems, tmdbSettings)
            if (lastHeroEnrichmentSignature == enrichmentSignature) {
                val cached = lastHeroEnrichedItems
                updatePersistedHomeSnapshotPipeline { snapshot ->
                    snapshot.copy(heroItems = cached)
                }
            } else {
                val enrichedItems = enrichHeroItemsPipeline(baseHeroItems, tmdbSettings)
                lastHeroEnrichmentSignature = enrichmentSignature
                lastHeroEnrichedItems = enrichedItems
                updatePersistedHomeSnapshotPipeline { snapshot ->
                    snapshot.copy(heroItems = enrichedItems)
                }
            }
        }
    } else {
        lastHeroEnrichmentSignature = null
        lastHeroEnrichedItems = emptyList()
    }

    schedulePosterStatusReconcilePipeline(displayRows)
}

internal fun HomeViewModel.applyHomeSnapshotToUiPipeline(
    snapshot: com.nexio.tv.data.local.HomeCatalogSnapshotStore.Snapshot
) {
    _fullCatalogRows.value = snapshot.fullCatalogRows
    _uiState.update { state ->
        val snapshotGridItems = if (state.homeLayout == HomeLayout.GRID) {
            buildGridItemsFromRowsPipeline(
                rows = snapshot.catalogRows,
                heroItems = snapshot.heroItems,
                heroSectionEnabled = state.heroSectionEnabled
            )
        } else {
            state.gridItems
        }

        state.copy(
            catalogRows = snapshot.catalogRows,
            heroItems = snapshot.heroItems,
            gridItems = if (state.gridItems == snapshotGridItems) state.gridItems else snapshotGridItems,
            isLoading = false,
            error = null
        )
    }
}

internal suspend fun HomeViewModel.persistAndApplyHomeSnapshotPipeline(
    snapshot: com.nexio.tv.data.local.HomeCatalogSnapshotStore.Snapshot
): Boolean {
    val persistedSnapshot = withContext(Dispatchers.IO) {
        homeCatalogSnapshotStore.write(snapshot)
        homeCatalogSnapshotStore.read()
    } ?: return false

    applyHomeSnapshotToUiPipeline(persistedSnapshot)
    return true
}

internal suspend fun HomeViewModel.updatePersistedHomeSnapshotPipeline(
    transform: (
        com.nexio.tv.data.local.HomeCatalogSnapshotStore.Snapshot
    ) -> com.nexio.tv.data.local.HomeCatalogSnapshotStore.Snapshot
): Boolean {
    val currentSnapshot = withContext(Dispatchers.IO) {
        homeCatalogSnapshotStore.read()
    } ?: return false

    return persistAndApplyHomeSnapshotPipeline(transform(currentSnapshot))
}

private data class CachedHomePreservationState(
    val preserveAddonRows: Boolean,
    val preserveTraktRows: Boolean,
    val preserveMDBListRows: Boolean,
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
    prefs: MDBListCatalogPreferences,
    snapshot: com.nexio.tv.data.repository.MDBListDiscoverySnapshot,
    refreshInProgress: Boolean
): Boolean {
    return refreshInProgress || shouldRefreshMDBListDiscoveryForState(prefs, snapshot)
}

private fun mergeCachedRowsWithLiveRows(
    cachedRows: List<CatalogRow>,
    liveRows: List<CatalogRow>,
    preservationState: CachedHomePreservationState,
    orderedGroupKeys: List<String>,
    rowOrderKeyByGlobalKey: Map<String, String>
): List<CatalogRow> {
    val mergedRowsInRetentionOrder = when {
        cachedRows.isEmpty() -> liveRows
        liveRows.isEmpty() -> cachedRows.filter { row -> shouldPreserveCachedRow(row, preservationState) }
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
                    shouldPreserveCachedRow(cachedRow, preservationState) -> cachedRow
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
        MDBLIST_RAIL_ADDON_ID -> {
            val prefixedCatalogId = "mdblist_${row.catalogId}"
            when {
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
        MDBLIST_RAIL_ADDON_ID -> preservationState.preserveMDBListRows
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
    // Popular list metadata should only gate refresh when custom Trakt popular lists are enabled.
    if (prefs.selectedPopularListKeys.isEmpty()) {
        return snapshot.updatedAtMs <= 0L
    }

    if (snapshot.popularLists.isEmpty()) {
        return true
    }

    val customKeys = snapshot.customListCatalogs.map { it.key }.toSet()
    return prefs.selectedPopularListKeys.any { it !in customKeys }
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

    val orderedBuiltIns = prefs.catalogOrder.mapNotNull { id ->
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

private fun nextUpToMetaPreview(nextUp: ContinueWatchingItem.NextUp): MetaPreview {
    val info = nextUp.info
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
        name = "${info.name} • $episodeSuffix",
        poster = info.poster ?: info.thumbnail,
        posterShape = PosterShape.LANDSCAPE,
        background = info.backdrop ?: info.thumbnail,
        logo = info.logo,
        description = info.episodeDescription,
        releaseInfo = info.releaseInfo ?: info.released,
        imdbRating = info.imdbRating,
        genres = info.genres
    )
}

internal fun HomeViewModel.schedulePosterStatusReconcilePipeline(rows: List<CatalogRow>) {
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
