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

internal fun HomeViewModel.observeTraktDiscoveryPipeline() {
    viewModelScope.launch {
        traktDiscoveryService.observeSnapshot().collectLatest { snapshot ->
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
            traktDiscoveryService.ensureFresh(force = true)
            scheduleUpdateCatalogRows()
        }
    }
}

internal fun HomeViewModel.observeMDBListDiscoveryPipeline() {
    viewModelScope.launch {
        mdbListDiscoveryService.observeSnapshot().collectLatest { snapshot ->
            mdbListDiscoverySnapshot = snapshot
            scheduleUpdateCatalogRows()
        }
    }
}

internal fun HomeViewModel.observeMDBListCatalogPreferencesPipeline() {
    viewModelScope.launch {
        mdbListSettingsDataStore.catalogPreferences.collectLatest { prefs ->
            if (prefs == mdbListCatalogPreferences) return@collectLatest
            mdbListCatalogPreferences = prefs
            mdbListDiscoveryService.ensureFresh(force = true)
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
    catalogOrder.clear()
    catalogsMap.clear()
    posterStatusReconcileJob?.cancel()
    reconcilePosterStatusObserversPipeline(emptyList())
    _fullCatalogRows.value = emptyList()
    truncatedRowCache.clear()
    hasRenderedFirstCatalog = false
    prefetchedExternalMetaIds.clear()
    externalMetaPrefetchInFlightIds.clear()
    externalMetaPrefetchJob?.cancel()
    pendingExternalMetaPrefetchItemId = null
    lastHeroEnrichmentSignature = null
    lastHeroEnrichedItems = emptyList()

    try {
        if (addons.isEmpty()) {
            catalogsLoadInProgress = false
            _uiState.update { it.copy(isLoading = false, error = "No addons installed") }
            return
        }

        rebuildCatalogOrder(addons)

        if (catalogOrder.isEmpty()) {
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
    val traktUpNextItems = continueWatchingItems
        .filterIsInstance<ContinueWatchingItem.NextUp>()
        .take(20)
        .map { nextUpToMetaPreview(it) }
    val syntheticTraktRows = buildSyntheticTraktRows(
        prefs = traktPrefs,
        upNextItems = traktUpNextItems,
        snapshot = traktSnapshot
    )
    val syntheticMDBListRows = buildSyntheticMDBListRows(
        prefs = mdbListPrefs,
        snapshot = mdbListSnapshot
    )
    val recommendationRefMap = traktSnapshot.recommendationRefsByStatusKey

    val (displayRows, baseHeroItems, baseGridItems, fullRowsFiltered) = withContext(Dispatchers.Default) {
        val rawRows = orderedKeys.mapNotNull { key -> catalogSnapshot[key] }
        val combinedRows = syntheticTraktRows + syntheticMDBListRows + rawRows
        val orderedRows = if (hideUnreleased) {
            val today = LocalDate.now()
            combinedRows.map { row ->
                if (row.addonId == TRAKT_RAIL_ADDON_ID) row else row.filterReleasedItems(today)
            }
        } else {
            combinedRows
        }
        val selectedHeroCatalogSet = heroCatalogKeys.toSet()
        val selectedHeroRows = if (selectedHeroCatalogSet.isNotEmpty()) {
            orderedRows.filter { row ->
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

        val fallbackHeroItemsWithArtwork = orderedRows
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

        val computedDisplayRows = orderedRows.map { row ->
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
            buildList {
                if (heroSectionEnabled && computedHeroItems.isNotEmpty()) {
                    add(GridItem.Hero(computedHeroItems))
                }
                computedDisplayRows.filter { it.items.isNotEmpty() }.forEach { row ->
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
        } else {
            currentGridItems
        }

        CatalogUpdateResult(computedDisplayRows, computedHeroItems, computedGridItems, orderedRows)
    }

    _fullCatalogRows.update { rows ->
        if (rows == fullRowsFiltered) rows else fullRowsFiltered
    }

    val nextGridItems = if (currentLayout == HomeLayout.GRID) {
        replaceGridHeroItemsPipeline(baseGridItems, baseHeroItems)
    } else {
        baseGridItems
    }

    _uiState.update { state ->
        state.copy(
            catalogRows = if (state.catalogRows == displayRows) state.catalogRows else displayRows,
            heroItems = if (state.heroItems == baseHeroItems) state.heroItems else baseHeroItems,
            gridItems = if (state.gridItems == nextGridItems) state.gridItems else nextGridItems,
            traktRecommendationRefs = if (state.traktRecommendationRefs == recommendationRefMap) {
                state.traktRecommendationRefs
            } else {
                recommendationRefMap
            },
            isLoading = false
        )
    }

    val tmdbSettings = currentTmdbSettings
    val shouldUseEnrichedHeroItems = tmdbSettings.enabled &&
        (tmdbSettings.useArtwork || tmdbSettings.useBasicInfo || tmdbSettings.useDetails)

    if (shouldUseEnrichedHeroItems && baseHeroItems.isNotEmpty()) {
        heroEnrichmentJob?.cancel()
        heroEnrichmentJob = viewModelScope.launch {
            val enrichmentSignature = heroEnrichmentSignaturePipeline(baseHeroItems, tmdbSettings)
            if (lastHeroEnrichmentSignature == enrichmentSignature) {
                val cached = lastHeroEnrichedItems
                _uiState.update { state ->
                    state.copy(
                        heroItems = if (state.heroItems == cached) state.heroItems else cached,
                        gridItems = if (currentLayout == HomeLayout.GRID) {
                            val enrichedGrid = replaceGridHeroItemsPipeline(state.gridItems, cached)
                            if (state.gridItems == enrichedGrid) state.gridItems else enrichedGrid
                        } else state.gridItems
                    )
                }
            } else {
                val enrichedItems = enrichHeroItemsPipeline(baseHeroItems, tmdbSettings)
                lastHeroEnrichmentSignature = enrichmentSignature
                lastHeroEnrichedItems = enrichedItems
                _uiState.update { state ->
                    state.copy(
                        heroItems = if (state.heroItems == enrichedItems) state.heroItems else enrichedItems,
                        gridItems = if (currentLayout == HomeLayout.GRID) {
                            val enrichedGrid = replaceGridHeroItemsPipeline(state.gridItems, enrichedItems)
                            if (state.gridItems == enrichedGrid) state.gridItems else enrichedGrid
                        } else state.gridItems
                    )
                }
            }
        }
    } else {
        lastHeroEnrichmentSignature = null
        lastHeroEnrichedItems = emptyList()
    }

    schedulePosterStatusReconcilePipeline(displayRows)
}

private fun buildSyntheticMDBListRows(
    prefs: MDBListCatalogPreferences,
    snapshot: com.nexio.tv.data.repository.MDBListDiscoverySnapshot
): List<CatalogRow> {
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
    return orderedKeys.flatMap { key ->
        groupedByKey[key].orEmpty().mapNotNull { custom -> custom.toCatalogRow() }
    }
}

private fun buildSyntheticTraktRows(
    prefs: TraktCatalogPreferences,
    upNextItems: List<MetaPreview>,
    snapshot: com.nexio.tv.data.repository.TraktDiscoverySnapshot
): List<CatalogRow> {
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

    val orderedBuiltIns = prefs.catalogOrder.mapNotNull { id -> builtInRows[id] }
    val customListRows = snapshot.customListCatalogs.mapNotNull { custom ->
        custom.toCatalogRow()
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
