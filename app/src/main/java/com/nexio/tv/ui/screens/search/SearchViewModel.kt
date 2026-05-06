package com.nexio.tv.ui.screens.search

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.nexio.tv.core.image.SearchSuggestionPosterModel
import com.nexio.tv.core.image.SearchSuggestionPosterRegistry
import com.nexio.tv.core.network.NetworkResult
import com.nexio.tv.core.tmdb.ImdbPosterLookupService
import com.nexio.tv.data.remote.api.ImdbSuggestion
import com.nexio.tv.data.local.DebugSettingsDataStore
import com.nexio.tv.data.local.LayoutPreferenceDataStore
import com.nexio.tv.data.local.PlayerSettingsDataStore
import com.nexio.tv.data.local.DEFAULT_MAX_RECENT_SEARCHES
import com.nexio.tv.data.local.SearchHistoryDataStore
import com.nexio.tv.data.local.TmdbCatalogSettingsDataStore
import com.nexio.tv.data.repository.ImdbTitleSearchRepository
import com.nexio.tv.data.repository.TmdbDiscoveryService
import com.nexio.tv.domain.model.Addon
import com.nexio.tv.domain.model.CatalogDescriptor
import com.nexio.tv.domain.model.CatalogRow
import com.nexio.tv.domain.model.ContentType
import com.nexio.tv.domain.model.skipStep
import com.nexio.tv.domain.model.supportsExtra
import com.nexio.tv.domain.repository.AddonRepository
import com.nexio.tv.domain.repository.CatalogRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class SearchViewModel @Inject constructor(
    private val addonRepository: AddonRepository,
    private val catalogRepository: CatalogRepository,
    private val layoutPreferenceDataStore: LayoutPreferenceDataStore,
    private val playerSettingsDataStore: PlayerSettingsDataStore,
    private val searchHistoryDataStore: SearchHistoryDataStore,
    private val imdbTitleSearchRepository: ImdbTitleSearchRepository,
    private val imdbPosterLookupService: ImdbPosterLookupService,
    private val debugSettingsDataStore: DebugSettingsDataStore,
    private val tmdbDiscoveryService: TmdbDiscoveryService,
    private val tmdbCatalogSettingsDataStore: TmdbCatalogSettingsDataStore,
    private val searchSuggestionPosterRegistry: SearchSuggestionPosterRegistry = SearchSuggestionPosterRegistry()
) : ViewModel() {

    private val _uiState = MutableStateFlow(SearchUiState())
    val uiState: StateFlow<SearchUiState> = _uiState.asStateFlow()

    private val catalogsMap = linkedMapOf<String, CatalogRow>()
    private val catalogOrder = mutableListOf<String>()

    private var activeSearchJobs: List<Job> = emptyList()
    private var searchJob: Job? = null
    private var discoverJob: Job? = null
    private var catalogRowsUpdateJob: Job? = null
    private var suggestionJob: Job? = null
    private var posterEnrichmentJob: Job? = null
    private var hasRenderedFirstCatalog = false
    private var pendingCatalogResponses = 0
    private var revealBatchAfterNextDiscoverFetch = false

    private companion object {
        const val DISCOVER_INITIAL_LIMIT = 100
        const val DISCOVER_SHOW_MORE_BATCH = 50
        const val SUGGESTION_DEBOUNCE_MS = 150L
        const val MAX_SUGGESTIONS = 8
        val SEARCH_ALLOWED_TYPES: Set<ContentType> = setOf(ContentType.MOVIE, ContentType.SERIES)
    }

    init {
        viewModelScope.launch {
            playerSettingsDataStore.playerSettings
                .map { it.deterministicAutoplayEnabled }
                .distinctUntilChanged()
                .collectLatest { enabled ->
                    _uiState.update { state ->
                        if (state.deterministicAutoplayEnabled == enabled) state
                        else state.copy(deterministicAutoplayEnabled = enabled)
                    }
                }
        }
        viewModelScope.launch {
            layoutPreferenceDataStore.searchDiscoverEnabled.collectLatest { enabled ->
                _uiState.update { it.copy(discoverEnabled = enabled) }
                if (enabled) {
                    loadDiscoverCatalogs()
                } else {
                    discoverJob?.cancel()
                    _uiState.update {
                        it.copy(
                            discoverLoading = false,
                            discoverLoadingMore = false,
                            discoverResults = emptyList(),
                            pendingDiscoverResults = emptyList()
                        )
                    }
                }
            }
        }
        // Combine all layout preference flows into a single collector to reduce coroutine overhead
        viewModelScope.launch {
            combine(
                layoutPreferenceDataStore.posterCardWidthDp,
                layoutPreferenceDataStore.posterLabelsEnabled,
                layoutPreferenceDataStore.catalogAddonNameEnabled,
                layoutPreferenceDataStore.posterCardHeightDp,
                layoutPreferenceDataStore.posterCardCornerRadiusDp
            ) { widthDp, labelsEnabled, addonNameEnabled, heightDp, cornerRadiusDp ->
                LayoutPrefs(widthDp, labelsEnabled, addonNameEnabled, heightDp, cornerRadiusDp)
            }.collectLatest { prefs ->
                _uiState.update {
                    it.copy(
                        posterCardWidthDp = prefs.widthDp,
                        posterLabelsEnabled = prefs.labelsEnabled,
                        catalogAddonNameEnabled = prefs.addonNameEnabled,
                        posterCardHeightDp = prefs.heightDp,
                        posterCardCornerRadiusDp = prefs.cornerRadiusDp
                    )
                }
            }
        }
        viewModelScope.launch {
            layoutPreferenceDataStore.catalogTypeSuffixEnabled.collectLatest { enabled ->
                _uiState.update { it.copy(catalogTypeSuffixEnabled = enabled) }
            }
        }
        viewModelScope.launch {
            searchHistoryDataStore.recentSearches.collectLatest { recentSearches ->
                _uiState.update {
                    it.copy(recentSearches = recentSearches.take(DEFAULT_MAX_RECENT_SEARCHES))
                }
            }
        }
        viewModelScope.launch {
            debugSettingsDataStore.searchPosterPreviewEnabled
                .distinctUntilChanged()
                .collectLatest { enabled ->
                    _uiState.update { it.copy(searchPosterPreviewEnabled = enabled) }
                    if (!enabled) {
                        posterEnrichmentJob?.cancel()
                        _uiState.update { it.copy(imdbSuggestionPosters = retainRegisteredPosters(emptyMap())) }
                    } else {
                        enrichPosters(_uiState.value.imdbSuggestions)
                    }
                }
        }
    }

    private fun enrichPosters(suggestions: List<ImdbSuggestion>) {
        posterEnrichmentJob?.cancel()
        if (suggestions.isEmpty() || !_uiState.value.searchPosterPreviewEnabled) return
        val targets = suggestions.take(MAX_SUGGESTIONS)
        posterEnrichmentJob = viewModelScope.launch {
            targets.forEach { suggestion ->
                launch {
                    val url = try {
                        imdbPosterLookupService.lookupPosterUrl(
                            tconst = suggestion.tconst,
                            titleType = suggestion.titleType
                        )
                    } catch (e: CancellationException) {
                        throw e
                    } catch (_: Exception) {
                        null
                    } ?: return@launch
                    val model = searchSuggestionPosterRegistry.register(
                        tconst = suggestion.tconst,
                        rawUrl = url
                    ) ?: return@launch
                    val stillRelevant = _uiState.value.imdbSuggestions
                        .any { it.tconst == suggestion.tconst }
                    if (!stillRelevant) return@launch
                    _uiState.update { state ->
                        if (state.imdbSuggestionPosters[suggestion.tconst] == model) state
                        else {
                            state.copy(
                                imdbSuggestionPosters = retainRegisteredPosters(
                                    state.imdbSuggestionPosters + (suggestion.tconst to model)
                                )
                            )
                        }
                    }
                }
            }
        }
    }

    private fun retainRegisteredPosters(
        posters: Map<String, SearchSuggestionPosterModel>
    ): Map<String, SearchSuggestionPosterModel> {
        searchSuggestionPosterRegistry.retainOnly(posters.values.toSet())
        return posters
    }

    private data class LayoutPrefs(
        val widthDp: Int,
        val labelsEnabled: Boolean,
        val addonNameEnabled: Boolean,
        val heightDp: Int,
        val cornerRadiusDp: Int
    )

    fun onEvent(event: SearchEvent) {
        when (event) {
            is SearchEvent.QueryChanged -> onQueryChanged(event.query)
            SearchEvent.SubmitSearch -> submitSearch()
            SearchEvent.ClearRecentSearches -> clearRecentSearches()
            is SearchEvent.LoadMoreCatalog -> loadMoreCatalogItems(
                catalogId = event.catalogId,
                addonId = event.addonId,
                type = event.type
            )
            is SearchEvent.SelectDiscoverType -> selectDiscoverType(event.type)
            is SearchEvent.SelectDiscoverCatalog -> selectDiscoverCatalog(event.catalogKey)
            is SearchEvent.SelectDiscoverGenre -> selectDiscoverGenre(event.genre)
            SearchEvent.LoadNextDiscoverResults -> loadNextDiscoverResults()
            SearchEvent.Retry -> performSearch(uiState.value.submittedQuery.ifBlank { uiState.value.query })
        }
    }

    private fun onQueryChanged(query: String) {
        _uiState.update {
            val trimmedInput = query.trim()
            val submitted = it.submittedQuery.trim()
            it.copy(
                query = query,
                error = null,
                isSearching = false,
                catalogRows = if (trimmedInput == submitted) it.catalogRows else emptyList()
            )
        }

        // Search is explicit on submit only; stop any in-flight requests while editing.
        searchJob?.cancel()
        searchJob = null
        activeSearchJobs.forEach { it.cancel() }
        activeSearchJobs = emptyList()
        catalogRowsUpdateJob?.cancel()
        catalogRowsUpdateJob = null

        fetchSuggestions(query.trim())
    }

    private fun fetchSuggestions(query: String) {
        suggestionJob?.cancel()

        if (query.length < 2) {
            _uiState.update {
                it.copy(
                    suggestions = emptyList(),
                    imdbSuggestions = emptyList(),
                    imdbSuggestionPosters = retainRegisteredPosters(emptyMap())
                )
            }
            posterEnrichmentJob?.cancel()
            return
        }

        // Don't show suggestions if the query already matches the submitted search
        if (query == _uiState.value.submittedQuery.trim() && _uiState.value.catalogRows.isNotEmpty()) {
            _uiState.update {
                it.copy(
                    suggestions = emptyList(),
                    imdbSuggestions = emptyList(),
                    imdbSuggestionPosters = retainRegisteredPosters(emptyMap())
                )
            }
            posterEnrichmentJob?.cancel()
            return
        }

        suggestionJob = viewModelScope.launch {
            kotlinx.coroutines.delay(SUGGESTION_DEBOUNCE_MS)

            val imdbResults: List<ImdbSuggestion> = try {
                imdbTitleSearchRepository.search(query)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                emptyList()
            }
            if (_uiState.value.query.trim() != query) return@launch
            if (imdbResults.isNotEmpty()) {
                val names = imdbResults.asSequence()
                    .map { it.primaryTitle }
                    .distinct()
                    .take(MAX_SUGGESTIONS)
                    .toList()
                val existingPosters = _uiState.value.imdbSuggestionPosters
                val activeTconsts = imdbResults.map { it.tconst }.toSet()
                val prunedPosters = existingPosters.filterKeys { it in activeTconsts }
                _uiState.update {
                    it.copy(
                        suggestions = names,
                        imdbSuggestions = imdbResults,
                        imdbSuggestionPosters = retainRegisteredPosters(prunedPosters)
                    )
                }
                enrichPosters(imdbResults)
                return@launch
            }

            posterEnrichmentJob?.cancel()
            _uiState.update {
                it.copy(imdbSuggestions = emptyList(), imdbSuggestionPosters = retainRegisteredPosters(emptyMap()))
            }

            val addons = try {
                addonRepository.getInstalledAddons().first()
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                return@launch
            }

            val searchTargets = buildSearchTargets(addons)
            if (searchTargets.isEmpty()) {
                _uiState.update { it.copy(suggestions = emptyList()) }
                return@launch
            }

            val collectedNames = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()
            val queryLower = query.lowercase()
            val suggestionJobs = searchTargets.map { (addon, catalog) ->
                launch {
                    try {
                        catalogRepository.getCatalog(
                            addonBaseUrl = addon.baseUrl,
                            addonId = addon.id,
                            addonName = addon.displayName,
                            catalogId = catalog.id,
                            catalogName = catalog.name,
                            type = catalog.apiType,
                            skip = 0,
                            skipStep = 100,
                            extraArgs = mapOf("search" to query),
                            supportsSkip = false
                        ).collect { result ->
                            if (result is NetworkResult.Success && _uiState.value.query.trim() == query) {
                                var added = false
                                result.data.items.forEach { item ->
                                    if (item.type !in SEARCH_ALLOWED_TYPES) return@forEach
                                    if (collectedNames.add(item.name)) added = true
                                }
                                // Push updated suggestions immediately as each addon responds
                                if (added) {
                                    val sorted = collectedNames
                                        .sortedWith(
                                            compareByDescending<String> { it.lowercase().startsWith(queryLower) }
                                                .thenBy { it.lowercase() }
                                        )
                                        .take(MAX_SUGGESTIONS)
                                    _uiState.update { it.copy(suggestions = sorted) }
                                }
                            }
                        }
                    } catch (e: CancellationException) {
                        throw e
                    } catch (_: Exception) {
                        // Ignore per-catalog errors for suggestions
                    }
                }
            }

            suggestionJobs.joinAll()
        }
    }

    private fun submitSearch() {
        performSearch(_uiState.value.query)
    }

    private fun clearRecentSearches() {
        viewModelScope.launch {
            searchHistoryDataStore.clearRecentSearches()
        }
    }

    private fun performSearch(rawQuery: String) {
        val query = rawQuery.trim()
        suggestionJob?.cancel()
        posterEnrichmentJob?.cancel()
        _uiState.update {
            it.copy(
                submittedQuery = query,
                query = rawQuery,
                suggestions = emptyList(),
                imdbSuggestions = emptyList(),
                imdbSuggestionPosters = retainRegisteredPosters(emptyMap())
            )
        }

        if (query.length >= 2) {
            viewModelScope.launch {
                searchHistoryDataStore.saveRecentSearch(query, DEFAULT_MAX_RECENT_SEARCHES)
            }
        }

        // Cancel any in-flight work from the previous query.
        searchJob?.cancel()
        searchJob = null
        activeSearchJobs.forEach { it.cancel() }
        activeSearchJobs = emptyList()
        catalogRowsUpdateJob?.cancel()

        catalogsMap.clear()
        catalogOrder.clear()
        hasRenderedFirstCatalog = false
        pendingCatalogResponses = 0

        if (query.length < 2) {
            _uiState.update {
                it.copy(
                    isSearching = false,
                    error = null,
                    catalogRows = emptyList()
                )
            }
            if (_uiState.value.discoverEnabled && !_uiState.value.discoverInitialized) {
                viewModelScope.launch {
                    loadDiscoverCatalogs()
                }
            }
            return
        }

        searchJob = viewModelScope.launch {
            _uiState.update { it.copy(isSearching = true, error = null, catalogRows = emptyList()) }

            val tmdbJob = launch {
                val tmdbRows = runCatching {
                    val preferences = tmdbCatalogSettingsDataStore.catalogPreferences.first()
                    tmdbDiscoveryService.search(query, preferences)
                }.getOrElse { error ->
                    if (error is CancellationException) throw error
                    emptyList()
                }

                if (!isCurrentSubmittedSearch(query)) {
                    return@launch
                }

                if (tmdbRows.isNotEmpty()) {
                    publishTmdbRows(tmdbRows)
                }
            }

            val addons = try {
                addonRepository.getInstalledAddons().first()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                tmdbJob.join()
                if (isCurrentSubmittedSearch(query)) {
                    _uiState.update {
                        it.copy(
                            isSearching = false,
                            error = if (catalogsMap.isEmpty()) e.message ?: "Failed to load addons" else null
                        )
                    }
                }
                return@launch
            }

            if (!isCurrentSubmittedSearch(query)) {
                return@launch
            }

            _uiState.update { it.copy(installedAddons = addons) }

            val searchTargets = buildSearchTargets(addons)

            if (searchTargets.isEmpty()) {
                tmdbJob.join()
                if (catalogsMap.isEmpty()) {
                    _uiState.update {
                        it.copy(
                            isSearching = false,
                            error = "No searchable catalogs found in installed addons",
                            catalogRows = emptyList()
                        )
                    }
                } else {
                    _uiState.update {
                        it.copy(
                            isSearching = false,
                            error = null
                        )
                    }
                }
                return@launch
            }

            // Preserve addon manifest order.
            searchTargets.forEach { (addon, catalog) ->
                val key = catalogKey(addonId = addon.id, type = catalog.apiType, catalogId = catalog.id)
                if (key !in catalogOrder) {
                    catalogOrder.add(key)
                }
            }

            val jobs = searchTargets.map { (addon, catalog) ->
                launch {
                    loadCatalog(addon, catalog, query)
                }
            }
            pendingCatalogResponses = jobs.size
            activeSearchJobs = jobs

            // Wait for all jobs to complete so we can stop showing the global loading state.
            try {
                (jobs + tmdbJob).joinAll()
            } finally {
                if (isCurrentSubmittedSearch(query)) {
                    _uiState.update { it.copy(isSearching = false) }
                }
            }
        }
    }

    private fun publishTmdbRows(rows: List<CatalogRow>) {
        rows.forEachIndexed { index, row ->
            val key = catalogKey(addonId = row.addonId, type = row.apiType, catalogId = row.catalogId)
            catalogsMap[key] = row
            if (key !in catalogOrder) {
                catalogOrder.add(index.coerceAtMost(catalogOrder.size), key)
            }
        }
        hasRenderedFirstCatalog = true
        updateCatalogRowsNow()
    }

    private suspend fun loadCatalog(addon: Addon, catalog: CatalogDescriptor, query: String) {
        val supportsSkip = catalog.supportsExtra("skip")
        val skipStep = catalog.skipStep()
        catalogRepository.getCatalog(
            addonBaseUrl = addon.baseUrl,
            addonId = addon.id,
            addonName = addon.displayName,
            catalogId = catalog.id,
            catalogName = catalog.name,
            type = catalog.apiType,
            skip = 0,
            skipStep = skipStep,
            extraArgs = mapOf("search" to query),
            supportsSkip = supportsSkip
        ).collect { result ->
            when (result) {
                is NetworkResult.Success -> {
                    if (!isCurrentSubmittedSearch(query)) return@collect
                    val key = catalogKey(
                        addonId = addon.id,
                        type = catalog.apiType,
                        catalogId = catalog.id
                    )
                    val filteredItems = result.data.items.filter { it.type in SEARCH_ALLOWED_TYPES }
                    catalogsMap[key] = result.data.copy(items = filteredItems)
                    pendingCatalogResponses = (pendingCatalogResponses - 1).coerceAtLeast(0)
                    scheduleCatalogRowsUpdate(query)
                }
                is NetworkResult.Error -> {
                    if (!isCurrentSubmittedSearch(query)) return@collect
                    pendingCatalogResponses = (pendingCatalogResponses - 1).coerceAtLeast(0)
                    // Ignore per-catalog errors unless we have nothing to show.
                    if (catalogsMap.isEmpty()) {
                        _uiState.update { it.copy(error = result.message) }
                    }
                    scheduleCatalogRowsUpdate(query)
                }
                NetworkResult.Loading -> {
                    // No-op; screen shows global loading when empty.
                }
            }
        }
    }

    private fun loadMoreCatalogItems(catalogId: String, addonId: String, type: String) {
        val key = catalogKey(addonId = addonId, type = type, catalogId = catalogId)
        val currentRow = catalogsMap[key] ?: return

        if (currentRow.isLoading || !currentRow.hasMore) return

        catalogsMap[key] = currentRow.copy(isLoading = true)
        scheduleCatalogRowsUpdate()

        val query = uiState.value.submittedQuery.trim()
        if (query.isBlank()) {
            catalogsMap[key] = currentRow.copy(isLoading = false)
            scheduleCatalogRowsUpdate()
            return
        }

        viewModelScope.launch {
            val addon = uiState.value.installedAddons.find { it.id == addonId } ?: run {
                catalogsMap[key] = currentRow.copy(isLoading = false)
                scheduleCatalogRowsUpdate()
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
                extraArgs = mapOf("search" to query),
                supportsSkip = currentRow.supportsSkip
            ).collect { result ->
                when (result) {
                    is NetworkResult.Success -> {
                        val existingIds = currentRow.items.asSequence()
                            .map { "${it.apiType}:${it.id}" }
                            .toHashSet()
                        val newUniqueItems = result.data.items.filter { item ->
                            item.type in SEARCH_ALLOWED_TYPES &&
                                "${item.apiType}:${item.id}" !in existingIds
                        }
                        val mergedItems = currentRow.items + newUniqueItems
                        val hasMore = if (newUniqueItems.isEmpty()) false else result.data.hasMore
                        catalogsMap[key] = result.data.copy(items = mergedItems, hasMore = hasMore)
                        scheduleCatalogRowsUpdate()
                    }
                    is NetworkResult.Error -> {
                        catalogsMap[key] = currentRow.copy(isLoading = false)
                        scheduleCatalogRowsUpdate()
                    }
                    NetworkResult.Loading -> Unit
                }
            }
        }
    }

    private fun scheduleCatalogRowsUpdate(query: String? = null) {
        catalogRowsUpdateJob?.cancel()
        catalogRowsUpdateJob = viewModelScope.launch {
            if (query != null && !isCurrentSubmittedSearch(query)) return@launch
            if (!hasRenderedFirstCatalog && catalogsMap.isNotEmpty()) {
                hasRenderedFirstCatalog = true
                updateCatalogRowsNow()
                return@launch
            }
            val debounceMs = when {
                pendingCatalogResponses > 5 -> 220L
                pendingCatalogResponses > 0 -> 140L
                else -> 90L
            }
            kotlinx.coroutines.delay(debounceMs)
            if (query != null && !isCurrentSubmittedSearch(query)) return@launch
            updateCatalogRowsNow()
        }
    }

    private fun isCurrentSubmittedSearch(query: String): Boolean {
        val state = uiState.value
        return state.submittedQuery.trim() == query && state.query.trim() == query
    }

    private fun updateCatalogRowsNow() {
        _uiState.update { state ->
            val orderedRows = catalogOrder.mapNotNull { key -> catalogsMap[key] }
            val filteredRows = orderedRows
            state.copy(
                catalogRows = filteredRows
            )
        }
    }

    private suspend fun loadDiscoverCatalogs() {
        if (!_uiState.value.discoverEnabled) return
        _uiState.update { it.copy(discoverLoading = true) }
        val addons = try {
            addonRepository.getInstalledAddons().first()
        } catch (_: Exception) {
            _uiState.update { it.copy(discoverInitialized = true, discoverLoading = false) }
            return
        }

        val discoverCatalogs = addons.flatMap { addon ->
            addon.catalogs
                .filter { catalog ->
                    !(catalog.supportsExtra("search") &&
                        catalog.extra.any { it.name.equals("search", ignoreCase = true) && it.isRequired })
                }
                .map { catalog ->
                    val genres = catalog.extra
                        .firstOrNull { it.name.equals("genre", ignoreCase = true) }
                        ?.options
                        .orEmpty()
                    DiscoverCatalog(
                        key = "${addon.id}_${catalog.apiType}_${catalog.id}",
                        addonId = addon.id,
                        addonName = addon.displayName,
                        addonBaseUrl = addon.baseUrl,
                        catalogId = catalog.id,
                        catalogName = catalog.name,
                        type = catalog.apiType,
                        genres = genres,
                        supportsSkip = catalog.supportsExtra("skip"),
                        skipStep = catalog.skipStep()
                    )
                }
        }

        val availableTypes = discoverCatalogs.map { it.type }.distinct()
        val currentType = _uiState.value.selectedDiscoverType
        val selectedType = if (currentType in availableTypes) currentType else availableTypes.firstOrNull() ?: "movie"
        val selectedCatalog = pickDiscoverCatalog(
            catalogs = discoverCatalogs,
            selectedType = selectedType,
            preferredKey = _uiState.value.selectedDiscoverCatalogKey
        )
        val selectedGenre: String? = null

        _uiState.update {
            it.copy(
                installedAddons = addons,
                discoverCatalogs = discoverCatalogs,
                selectedDiscoverType = selectedType,
                selectedDiscoverCatalogKey = selectedCatalog?.key,
                selectedDiscoverGenre = selectedGenre,
                discoverInitialized = true,
                discoverLoading = false,
                discoverResults = emptyList(),
                pendingDiscoverResults = emptyList(),
                discoverHasMore = true,
                discoverPage = 1
            )
        }
        fetchDiscoverContent(reset = true)
    }

    private fun selectDiscoverType(type: String) {
        val catalogs = _uiState.value.discoverCatalogs
        val selectedCatalog = pickDiscoverCatalog(
            catalogs = catalogs,
            selectedType = type,
            preferredKey = _uiState.value.selectedDiscoverCatalogKey
        )
        val selectedGenre: String? = null
        _uiState.update {
            it.copy(
                selectedDiscoverType = type,
                selectedDiscoverCatalogKey = selectedCatalog?.key,
                selectedDiscoverGenre = selectedGenre,
                discoverResults = emptyList(),
                pendingDiscoverResults = emptyList(),
                discoverPage = 1,
                discoverHasMore = true
            )
        }
        fetchDiscoverContent(reset = true)
    }

    private fun selectDiscoverCatalog(catalogKey: String) {
        val catalog = _uiState.value.discoverCatalogs.firstOrNull { it.key == catalogKey } ?: return
        _uiState.update {
            it.copy(
                selectedDiscoverCatalogKey = catalog.key,
                selectedDiscoverType = catalog.type,
                selectedDiscoverGenre = null,
                discoverResults = emptyList(),
                pendingDiscoverResults = emptyList(),
                discoverPage = 1,
                discoverHasMore = true
            )
        }
        fetchDiscoverContent(reset = true)
    }

    private fun selectDiscoverGenre(genre: String?) {
        _uiState.update {
            it.copy(
                selectedDiscoverGenre = genre,
                discoverResults = emptyList(),
                pendingDiscoverResults = emptyList(),
                discoverPage = 1,
                discoverHasMore = true
            )
        }
        fetchDiscoverContent(reset = true)
    }

    private fun loadNextDiscoverResults() {
        if (_uiState.value.pendingDiscoverResults.isNotEmpty()) {
            showMoreDiscoverResults()
        } else {
            revealBatchAfterNextDiscoverFetch = true
            loadMoreDiscoverResults()
        }
    }

    private fun showMoreDiscoverResults() {
        val pending = _uiState.value.pendingDiscoverResults
        if (pending.isEmpty()) return
        val nextBatch = pending.take(DISCOVER_SHOW_MORE_BATCH)
        val remaining = pending.drop(DISCOVER_SHOW_MORE_BATCH)
        _uiState.update {
            it.copy(
                discoverResults = it.discoverResults + nextBatch,
                pendingDiscoverResults = remaining
            )
        }
    }

    private fun loadMoreDiscoverResults() {
        val state = _uiState.value
        if (state.query.trim().isNotEmpty()) return
        if (!state.discoverHasMore || state.discoverLoadingMore || state.pendingDiscoverResults.isNotEmpty()) return
        fetchDiscoverContent(reset = false)
    }

    private fun fetchDiscoverContent(reset: Boolean) {
        discoverJob?.cancel()
        discoverJob = viewModelScope.launch {
            val state = _uiState.value
            if (state.query.trim().isNotEmpty()) return@launch
            val selectedCatalog = state.discoverCatalogs.firstOrNull { it.key == state.selectedDiscoverCatalogKey }
                ?: return@launch

            if (reset) {
                revealBatchAfterNextDiscoverFetch = false
                _uiState.update {
                    it.copy(
                        discoverLoading = true,
                        discoverResults = emptyList(),
                        pendingDiscoverResults = emptyList(),
                        discoverPage = 1,
                        discoverHasMore = true
                    )
                }
            } else {
                _uiState.update { it.copy(discoverLoadingMore = true) }
            }

            val currentPage = if (reset) 1 else state.discoverPage + 1
            val skip = if (currentPage <= 1) 0 else (currentPage - 1) * selectedCatalog.skipStep
            val visibleCountBeforeRequest = state.discoverResults.size
            val extraArgs = buildMap<String, String> {
                state.selectedDiscoverGenre?.takeIf { it.isNotBlank() }?.let { put("genre", it) }
            }

            catalogRepository.getCatalog(
                addonBaseUrl = selectedCatalog.addonBaseUrl,
                addonId = selectedCatalog.addonId,
                addonName = selectedCatalog.addonName,
                catalogId = selectedCatalog.catalogId,
                catalogName = selectedCatalog.catalogName,
                type = selectedCatalog.type,
                skip = skip,
                skipStep = selectedCatalog.skipStep,
                extraArgs = extraArgs,
                supportsSkip = selectedCatalog.supportsSkip
            ).collect { result ->
                when (result) {
                    is NetworkResult.Success -> {
                        val incoming = result.data.items
                        val existing = if (reset) {
                            emptyList()
                        } else {
                            _uiState.value.discoverResults + _uiState.value.pendingDiscoverResults
                        }
                        val existingKeys = existing.asSequence()
                            .map { "${it.apiType}:${it.id}" }
                            .toSet()
                        val hasNewUniqueIncoming = incoming.any { item ->
                            "${item.apiType}:${item.id}" !in existingKeys
                        }
                        val merged = if (reset) incoming else (existing + incoming)
                        val rawDeduped = merged.distinctBy { "${it.apiType}:${it.id}" }
                        val deduped = rawDeduped
                        val shouldRevealBatch = !reset && revealBatchAfterNextDiscoverFetch
                        val visibleLimit = if (reset) {
                            DISCOVER_INITIAL_LIMIT
                        } else if (shouldRevealBatch) {
                            (visibleCountBeforeRequest + DISCOVER_SHOW_MORE_BATCH)
                                .coerceAtLeast(DISCOVER_INITIAL_LIMIT)
                        } else {
                            visibleCountBeforeRequest.coerceAtLeast(DISCOVER_INITIAL_LIMIT)
                        }
                        val visible = deduped.take(visibleLimit)
                        val pending = deduped.drop(visibleLimit)
                        val shouldStopPagination = !reset && !hasNewUniqueIncoming
                        _uiState.update {
                            it.copy(
                                discoverLoading = false,
                                discoverLoadingMore = false,
                                discoverResults = visible,
                                pendingDiscoverResults = pending,
                                discoverHasMore = if (shouldStopPagination) false else result.data.hasMore,
                                discoverPage = if (shouldStopPagination) it.discoverPage else currentPage
                            )
                        }
                        revealBatchAfterNextDiscoverFetch = false
                    }
                    is NetworkResult.Error -> {
                        revealBatchAfterNextDiscoverFetch = false
                        _uiState.update {
                            it.copy(
                                discoverLoading = false,
                                discoverLoadingMore = false,
                                discoverHasMore = false
                            )
                        }
                    }
                    NetworkResult.Loading -> Unit
                }
            }
        }
    }

    private fun pickDiscoverCatalog(
        catalogs: List<DiscoverCatalog>,
        selectedType: String,
        preferredKey: String?
    ): DiscoverCatalog? {
        val filtered = catalogs.filter { it.type == selectedType }
        return filtered.firstOrNull { it.key == preferredKey } ?: filtered.firstOrNull()
    }

    private fun buildSearchTargets(addons: List<Addon>): List<Pair<Addon, CatalogDescriptor>> {
        val allSearchTargets = addons.flatMap { addon ->
            addon.catalogs
                .filter { catalog ->
                    catalog.type in SEARCH_ALLOWED_TYPES && catalog.supportsExtra("search")
                }
                .map { catalog -> addon to catalog }
        }

        return allSearchTargets
    }

    private fun catalogKey(addonId: String, type: String, catalogId: String): String {
        return "${addonId}_${type}_${catalogId}"
    }
}
