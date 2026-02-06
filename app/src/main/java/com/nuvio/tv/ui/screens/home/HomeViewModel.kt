package com.nuvio.tv.ui.screens.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.nuvio.tv.core.network.NetworkResult
import com.nuvio.tv.data.local.LayoutPreferenceDataStore
import com.nuvio.tv.domain.model.Addon
import com.nuvio.tv.domain.model.CatalogDescriptor
import com.nuvio.tv.domain.model.CatalogRow
import com.nuvio.tv.domain.model.WatchProgress
import com.nuvio.tv.domain.repository.AddonRepository
import com.nuvio.tv.domain.repository.CatalogRepository
import com.nuvio.tv.domain.repository.WatchProgressRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class HomeViewModel @Inject constructor(
    private val addonRepository: AddonRepository,
    private val catalogRepository: CatalogRepository,
    private val watchProgressRepository: WatchProgressRepository,
    private val layoutPreferenceDataStore: LayoutPreferenceDataStore
) : ViewModel() {

    private val _uiState = MutableStateFlow(HomeUiState())
    val uiState: StateFlow<HomeUiState> = _uiState.asStateFlow()

    private val _focusState = MutableStateFlow(HomeScreenFocusState())
    val focusState: StateFlow<HomeScreenFocusState> = _focusState.asStateFlow()

    private val _gridFocusState = MutableStateFlow(HomeScreenFocusState())
    val gridFocusState: StateFlow<HomeScreenFocusState> = _gridFocusState.asStateFlow()

    private val catalogsMap = linkedMapOf<String, CatalogRow>()
    private val catalogOrder = mutableListOf<String>()
    private var addonsCache: List<Addon> = emptyList()
    private var currentHeroCatalogKey: String? = null

    init {
        loadLayoutPreference()
        loadHeroCatalogPreference()
        loadContinueWatching()
        observeInstalledAddons()
    }

    private fun loadLayoutPreference() {
        viewModelScope.launch {
            layoutPreferenceDataStore.selectedLayout.collectLatest { layout ->
                _uiState.update { it.copy(homeLayout = layout) }
            }
        }
    }

    private fun loadHeroCatalogPreference() {
        viewModelScope.launch {
            layoutPreferenceDataStore.heroCatalogSelection.collectLatest { key ->
                currentHeroCatalogKey = key
                _uiState.update { it.copy(heroCatalogKey = key) }
                updateCatalogRows()
            }
        }
    }

    fun onEvent(event: HomeEvent) {
        when (event) {
            is HomeEvent.OnItemClick -> navigateToDetail(event.itemId, event.itemType)
            is HomeEvent.OnLoadMoreCatalog -> loadMoreCatalogItems(event.catalogId, event.addonId, event.type)
            HomeEvent.OnRetry -> viewModelScope.launch { loadAllCatalogs(addonsCache) }
        }
    }

    private fun loadContinueWatching() {
        viewModelScope.launch {
            watchProgressRepository.continueWatching.collectLatest { items ->
                _uiState.update { it.copy(continueWatchingItems = items) }
            }
        }
    }

    private fun observeInstalledAddons() {
        viewModelScope.launch {
            addonRepository.getInstalledAddons()
                .distinctUntilChanged { old, new ->
                    old.map { it.id } == new.map { it.id }
                }
                .collectLatest { addons ->
                    addonsCache = addons
                    loadAllCatalogs(addons)
                }
        }
    }

    private suspend fun loadAllCatalogs(addons: List<Addon>) {
        _uiState.update { it.copy(isLoading = true, error = null, installedAddonsCount = addons.size) }
        catalogOrder.clear()
        catalogsMap.clear()

        try {
            if (addons.isEmpty()) {
                _uiState.update { it.copy(isLoading = false, error = "No addons installed") }
                return
            }

            // Build catalog order based on addon manifest order
            addons.forEach { addon ->
                addon.catalogs
                    .filterNot { it.isSearchOnlyCatalog() }
                    .forEach { catalog ->
                    val key = catalogKey(
                        addonId = addon.id,
                        type = catalog.type.toApiString(),
                        catalogId = catalog.id
                    )
                    if (key !in catalogOrder) {
                        catalogOrder.add(key)
                    }
                    }
            }

            if (catalogOrder.isEmpty()) {
                _uiState.update { it.copy(isLoading = false, error = "No catalog addons installed") }
                return
            }

            // Load catalogs
            addons.forEach { addon ->
                addon.catalogs
                    .filterNot { it.isSearchOnlyCatalog() }
                    .forEach { catalog ->
                        loadCatalog(addon, catalog)
                    }
            }
        } catch (e: Exception) {
            _uiState.update { it.copy(isLoading = false, error = e.message) }
        }
    }

    private fun loadCatalog(addon: Addon, catalog: CatalogDescriptor) {
        viewModelScope.launch {
            catalogRepository.getCatalog(
                addonBaseUrl = addon.baseUrl,
                addonId = addon.id,
                addonName = addon.name,
                catalogId = catalog.id,
                catalogName = catalog.name,
                type = catalog.type.toApiString(),
                skip = 0
            ).collect { result ->
                when (result) {
                    is NetworkResult.Success -> {
                        val key = catalogKey(
                            addonId = addon.id,
                            type = catalog.type.toApiString(),
                            catalogId = catalog.id
                        )
                        catalogsMap[key] = result.data
                        updateCatalogRows()
                    }
                    is NetworkResult.Error -> {
                        // Log error but don't fail entire screen
                    }
                    NetworkResult.Loading -> { /* Handled by individual row */ }
                }
            }
        }
    }

    private fun loadMoreCatalogItems(catalogId: String, addonId: String, type: String) {
        val key = catalogKey(addonId = addonId, type = type, catalogId = catalogId)
        val currentRow = catalogsMap[key] ?: return

        if (currentRow.isLoading || !currentRow.hasMore) return

        catalogsMap[key] = currentRow.copy(isLoading = true)
        updateCatalogRows()

        viewModelScope.launch {
            val addon = addonsCache.find { it.id == addonId } ?: return@launch

            val nextSkip = (currentRow.currentPage + 1) * 100
            catalogRepository.getCatalog(
                addonBaseUrl = addon.baseUrl,
                addonId = addon.id,
                addonName = addon.name,
                catalogId = catalogId,
                catalogName = currentRow.catalogName,
                type = currentRow.type.toApiString(),
                skip = nextSkip
            ).collect { result ->
                when (result) {
                    is NetworkResult.Success -> {
                        val mergedItems = currentRow.items + result.data.items
                        catalogsMap[key] = result.data.copy(items = mergedItems)
                        updateCatalogRows()
                    }
                    is NetworkResult.Error -> {
                        catalogsMap[key] = currentRow.copy(isLoading = false)
                        updateCatalogRows()
                    }
                    NetworkResult.Loading -> { }
                }
            }
        }
    }

    private fun updateCatalogRows() {
        _uiState.update { state ->
            // Preserve addon manifest order
            val orderedRows = catalogOrder.mapNotNull { key -> catalogsMap[key] }

            // Derive hero items from selected catalog or first catalog with backgrounds
            val heroSourceRow = if (currentHeroCatalogKey != null) {
                catalogsMap[currentHeroCatalogKey]
            } else {
                orderedRows.firstOrNull { row -> row.items.any { it.background != null } }
            }
            val heroItems = heroSourceRow?.items
                ?.filter { it.background != null || it.poster != null }
                ?.take(7)
                ?: orderedRows.flatMap { it.items }.take(7)

            // Build flattened grid items
            val gridItems = buildList {
                if (heroItems.isNotEmpty()) {
                    add(GridItem.Hero(heroItems))
                }
                orderedRows.forEach { row ->
                    add(
                        GridItem.SectionDivider(
                            catalogName = row.catalogName,
                            catalogId = row.catalogId,
                            addonBaseUrl = row.addonBaseUrl,
                            addonId = row.addonId,
                            type = row.type.toApiString()
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
                                type = row.type.toApiString()
                            )
                        )
                    }
                }
            }

            state.copy(
                catalogRows = orderedRows,
                heroItems = heroItems,
                gridItems = gridItems,
                isLoading = false
            )
        }
    }

    private fun navigateToDetail(itemId: String, itemType: String) {
        _uiState.update { it.copy(selectedItemId = itemId) }
    }

    private fun catalogKey(addonId: String, type: String, catalogId: String): String {
        return "${addonId}_${type}_${catalogId}"
    }

    private fun CatalogDescriptor.isSearchOnlyCatalog(): Boolean {
        return extra.any { extra -> extra.name == "search" && extra.isRequired }
    }

    /**
     * Saves the current focus and scroll state for restoration when returning to this screen.
     */
    fun saveFocusState(
        verticalScrollIndex: Int,
        verticalScrollOffset: Int,
        focusedRowIndex: Int,
        focusedItemIndex: Int,
        catalogRowScrollStates: Map<String, Int>
    ) {
        _focusState.value = HomeScreenFocusState(
            verticalScrollIndex = verticalScrollIndex,
            verticalScrollOffset = verticalScrollOffset,
            focusedRowIndex = focusedRowIndex,
            focusedItemIndex = focusedItemIndex,
            catalogRowScrollStates = catalogRowScrollStates,
            hasSavedFocus = true
        )
    }

    /**
     * Clears the saved focus state.
     */
    fun clearFocusState() {
        _focusState.value = HomeScreenFocusState()
    }

    /**
     * Saves the grid layout focus and scroll state.
     */
    fun saveGridFocusState(
        verticalScrollIndex: Int,
        verticalScrollOffset: Int,
        focusedRowIndex: Int = 0,
        focusedItemIndex: Int = 0
    ) {
        _gridFocusState.value = HomeScreenFocusState(
            verticalScrollIndex = verticalScrollIndex,
            verticalScrollOffset = verticalScrollOffset,
            focusedRowIndex = focusedRowIndex,
            focusedItemIndex = focusedItemIndex
        )
    }
}
