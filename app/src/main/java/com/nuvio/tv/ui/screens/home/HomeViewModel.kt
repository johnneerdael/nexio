package com.nuvio.tv.ui.screens.home

import android.util.Log
import androidx.compose.runtime.mutableStateMapOf
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.nuvio.tv.core.network.NetworkResult
import com.nuvio.tv.core.tmdb.TmdbMetadataService
import com.nuvio.tv.core.tmdb.TmdbService
import com.nuvio.tv.data.local.LayoutPreferenceDataStore
import com.nuvio.tv.data.local.TraktSettingsDataStore
import com.nuvio.tv.data.local.TmdbSettingsDataStore
import com.nuvio.tv.data.trailer.TrailerService
import com.nuvio.tv.domain.model.Addon
import com.nuvio.tv.domain.model.CatalogDescriptor
import com.nuvio.tv.domain.model.CatalogRow
import com.nuvio.tv.domain.model.HomeLayout
import com.nuvio.tv.domain.model.Meta
import com.nuvio.tv.domain.model.MetaPreview
import com.nuvio.tv.domain.model.TmdbSettings
import com.nuvio.tv.domain.model.Video
import com.nuvio.tv.domain.model.WatchProgress
import com.nuvio.tv.domain.repository.AddonRepository
import com.nuvio.tv.domain.repository.CatalogRepository
import com.nuvio.tv.domain.repository.MetaRepository
import com.nuvio.tv.domain.repository.WatchProgressRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import javax.inject.Inject

@HiltViewModel
class HomeViewModel @Inject constructor(
    private val addonRepository: AddonRepository,
    private val catalogRepository: CatalogRepository,
    private val watchProgressRepository: WatchProgressRepository,
    private val metaRepository: MetaRepository,
    private val layoutPreferenceDataStore: LayoutPreferenceDataStore,
    private val traktSettingsDataStore: TraktSettingsDataStore,
    private val tmdbSettingsDataStore: TmdbSettingsDataStore,
    private val tmdbService: TmdbService,
    private val tmdbMetadataService: TmdbMetadataService,
    private val trailerService: TrailerService
) : ViewModel() {
    companion object {
        private const val TAG = "HomeViewModel"
        private const val MAX_RECENT_PROGRESS_ITEMS = 300
        private const val MAX_NEXT_UP_LOOKUPS = 24
        private const val MAX_NEXT_UP_CONCURRENCY = 4
    }

    private val _uiState = MutableStateFlow(HomeUiState())
    val uiState: StateFlow<HomeUiState> = _uiState.asStateFlow()

    private val _focusState = MutableStateFlow(HomeScreenFocusState())
    val focusState: StateFlow<HomeScreenFocusState> = _focusState.asStateFlow()

    private val _gridFocusState = MutableStateFlow(HomeScreenFocusState())
    val gridFocusState: StateFlow<HomeScreenFocusState> = _gridFocusState.asStateFlow()

    private val _loadingCatalogs = MutableStateFlow<Set<String>>(emptySet())
    val loadingCatalogs: StateFlow<Set<String>> = _loadingCatalogs.asStateFlow()

    private val catalogsMap = linkedMapOf<String, CatalogRow>()
    private val catalogOrder = mutableListOf<String>()
    private var addonsCache: List<Addon> = emptyList()
    private var homeCatalogOrderKeys: List<String> = emptyList()
    private var disabledHomeCatalogKeys: Set<String> = emptySet()
    private var currentHeroCatalogKeys: List<String> = emptyList()
    private var catalogUpdateJob: Job? = null
    private var hasRenderedFirstCatalog = false
    private val catalogLoadSemaphore = Semaphore(6)
    private var pendingCatalogLoads = 0
    private val truncatedItemsCache = mutableMapOf<String, List<MetaPreview>>()
    private val trailerPreviewLoadingIds = mutableSetOf<String>()
    private val trailerPreviewNegativeCache = mutableSetOf<String>()
    private val trailerPreviewUrlsState = mutableStateMapOf<String, String>()
    private var activeTrailerPreviewItemId: String? = null
    private var trailerPreviewRequestVersion: Long = 0L
    private var currentTmdbSettings: TmdbSettings = TmdbSettings()
    private var lastHeroEnrichmentSignature: String? = null
    private var lastHeroEnrichedItems: List<MetaPreview> = emptyList()
    private val dismissedNextUpKeys = MutableStateFlow<Set<String>>(emptySet())
    val trailerPreviewUrls: Map<String, String>
        get() = trailerPreviewUrlsState

    init {
        observeLayoutPreferences()
        loadHomeCatalogOrderPreference()
        loadDisabledHomeCatalogPreference()
        observeTmdbSettings()
        loadContinueWatching()
        observeInstalledAddons()
    }

    private fun observeLayoutPreferences() {
        val coreLayoutPrefsFlow = combine(
            combine(
                layoutPreferenceDataStore.selectedLayout,
                layoutPreferenceDataStore.heroCatalogSelections,
                layoutPreferenceDataStore.heroSectionEnabled,
                layoutPreferenceDataStore.posterLabelsEnabled,
                layoutPreferenceDataStore.catalogAddonNameEnabled
            ) { layout, heroCatalogKeys, heroSectionEnabled, posterLabelsEnabled, catalogAddonNameEnabled ->
                CoreLayoutPrefs(
                    layout = layout,
                    heroCatalogKeys = heroCatalogKeys,
                    heroSectionEnabled = heroSectionEnabled,
                    posterLabelsEnabled = posterLabelsEnabled,
                    catalogAddonNameEnabled = catalogAddonNameEnabled,
                    catalogTypeSuffixEnabled = true
                )
            },
            layoutPreferenceDataStore.catalogTypeSuffixEnabled
        ) { corePrefs, catalogTypeSuffixEnabled ->
            corePrefs.copy(catalogTypeSuffixEnabled = catalogTypeSuffixEnabled)
        }

        val focusedBackdropPrefsFlow = combine(
            layoutPreferenceDataStore.focusedPosterBackdropExpandEnabled,
            layoutPreferenceDataStore.focusedPosterBackdropExpandDelaySeconds,
            layoutPreferenceDataStore.focusedPosterBackdropTrailerEnabled,
            layoutPreferenceDataStore.focusedPosterBackdropTrailerMuted
        ) { expandEnabled, expandDelaySeconds, trailerEnabled, trailerMuted ->
            FocusedBackdropPrefs(
                expandEnabled = expandEnabled,
                expandDelaySeconds = expandDelaySeconds,
                trailerEnabled = trailerEnabled,
                trailerMuted = trailerMuted
            )
        }

        viewModelScope.launch {
            combine(
                coreLayoutPrefsFlow,
                focusedBackdropPrefsFlow,
                layoutPreferenceDataStore.posterCardWidthDp,
                layoutPreferenceDataStore.posterCardHeightDp,
                layoutPreferenceDataStore.posterCardCornerRadiusDp
            ) { corePrefs, focusedBackdropPrefs, posterCardWidthDp, posterCardHeightDp, posterCardCornerRadiusDp ->
                LayoutUiPrefs(
                    layout = corePrefs.layout,
                    heroCatalogKeys = corePrefs.heroCatalogKeys,
                    heroSectionEnabled = corePrefs.heroSectionEnabled,
                    posterLabelsEnabled = corePrefs.posterLabelsEnabled,
                    catalogAddonNameEnabled = corePrefs.catalogAddonNameEnabled,
                    catalogTypeSuffixEnabled = corePrefs.catalogTypeSuffixEnabled,
                    focusedBackdropExpandEnabled = focusedBackdropPrefs.expandEnabled,
                    focusedBackdropExpandDelaySeconds = focusedBackdropPrefs.expandDelaySeconds,
                    focusedBackdropTrailerEnabled = focusedBackdropPrefs.trailerEnabled,
                    focusedBackdropTrailerMuted = focusedBackdropPrefs.trailerMuted,
                    posterCardWidthDp = posterCardWidthDp,
                    posterCardHeightDp = posterCardHeightDp,
                    posterCardCornerRadiusDp = posterCardCornerRadiusDp
                )
            }.collectLatest { prefs ->
                val previousState = _uiState.value
                val shouldRefreshCatalogPresentation =
                    currentHeroCatalogKeys != prefs.heroCatalogKeys ||
                        previousState.heroSectionEnabled != prefs.heroSectionEnabled ||
                        previousState.homeLayout != prefs.layout
                currentHeroCatalogKeys = prefs.heroCatalogKeys
                _uiState.update {
                    it.copy(
                        homeLayout = prefs.layout,
                        heroCatalogKeys = prefs.heroCatalogKeys,
                        heroSectionEnabled = prefs.heroSectionEnabled,
                        posterLabelsEnabled = prefs.posterLabelsEnabled,
                        catalogAddonNameEnabled = prefs.catalogAddonNameEnabled,
                        catalogTypeSuffixEnabled = prefs.catalogTypeSuffixEnabled,
                        focusedPosterBackdropExpandEnabled = prefs.focusedBackdropExpandEnabled,
                        focusedPosterBackdropExpandDelaySeconds = prefs.focusedBackdropExpandDelaySeconds,
                        focusedPosterBackdropTrailerEnabled = prefs.focusedBackdropTrailerEnabled,
                        focusedPosterBackdropTrailerMuted = prefs.focusedBackdropTrailerMuted,
                        posterCardWidthDp = prefs.posterCardWidthDp,
                        posterCardHeightDp = prefs.posterCardHeightDp,
                        posterCardCornerRadiusDp = prefs.posterCardCornerRadiusDp
                    )
                }
                if (shouldRefreshCatalogPresentation) {
                    scheduleUpdateCatalogRows()
                }
            }
        }
    }

    private data class CoreLayoutPrefs(
        val layout: HomeLayout,
        val heroCatalogKeys: List<String>,
        val heroSectionEnabled: Boolean,
        val posterLabelsEnabled: Boolean,
        val catalogAddonNameEnabled: Boolean,
        val catalogTypeSuffixEnabled: Boolean
    )

    private data class FocusedBackdropPrefs(
        val expandEnabled: Boolean,
        val expandDelaySeconds: Int,
        val trailerEnabled: Boolean,
        val trailerMuted: Boolean
    )

    private data class LayoutUiPrefs(
        val layout: HomeLayout,
        val heroCatalogKeys: List<String>,
        val heroSectionEnabled: Boolean,
        val posterLabelsEnabled: Boolean,
        val catalogAddonNameEnabled: Boolean,
        val catalogTypeSuffixEnabled: Boolean,
        val focusedBackdropExpandEnabled: Boolean,
        val focusedBackdropExpandDelaySeconds: Int,
        val focusedBackdropTrailerEnabled: Boolean,
        val focusedBackdropTrailerMuted: Boolean,
        val posterCardWidthDp: Int,
        val posterCardHeightDp: Int,
        val posterCardCornerRadiusDp: Int
    )

    fun requestTrailerPreview(item: MetaPreview) {
        val itemId = item.id
        if (activeTrailerPreviewItemId != itemId) {
            activeTrailerPreviewItemId = itemId
            trailerPreviewRequestVersion++
        }

        if (trailerPreviewNegativeCache.contains(itemId)) return
        if (trailerPreviewUrlsState.containsKey(itemId)) return
        if (!trailerPreviewLoadingIds.add(itemId)) return

        val requestVersion = trailerPreviewRequestVersion

        viewModelScope.launch {
            val trailerUrl = trailerService.getTrailerUrl(
                title = item.name,
                year = extractYear(item.releaseInfo),
                tmdbId = null,
                type = item.apiType
            )

            val isLatestFocusedItem =
                activeTrailerPreviewItemId == itemId && trailerPreviewRequestVersion == requestVersion
            if (!isLatestFocusedItem) {
                trailerPreviewLoadingIds.remove(itemId)
                return@launch
            }

            if (trailerUrl.isNullOrBlank()) {
                trailerPreviewNegativeCache.add(itemId)
            } else {
                if (trailerPreviewUrlsState[itemId] != trailerUrl) {
                    trailerPreviewUrlsState[itemId] = trailerUrl
                }
            }

            trailerPreviewLoadingIds.remove(itemId)
        }
    }

    fun onItemFocus(item: MetaPreview) {
        viewModelScope.launch {
            val prefetchEnabled = layoutPreferenceDataStore.preferExternalMetaAddonDetail.first()
            if (prefetchEnabled) {
                // Fetch meta from external addons
                metaRepository.getMetaFromAllAddons(item.apiType, item.id)
                    .first { it is NetworkResult.Success || it is NetworkResult.Error }
                    .let { result ->
                        if (result is NetworkResult.Success) {
                            // Update the item in catalog rows
                            updateCatalogItemWithMeta(item.id, result.data)
                        }
                    }
            }
        }
    }

    private fun updateCatalogItemWithMeta(itemId: String, meta: Meta) {
        _uiState.update { state ->
            val updatedRows = state.catalogRows.map { row ->
                val updatedItems = row.items.map { item ->
                    if (item.id == itemId) {
                        item.copy(
                            background = meta.background ?: item.background,
                            logo = meta.logo ?: item.logo,
                            description = meta.description ?: item.description,
                            releaseInfo = meta.releaseInfo ?: item.releaseInfo,
                            imdbRating = meta.imdbRating ?: item.imdbRating,
                            genres = if (meta.genres.isNotEmpty()) meta.genres else item.genres
                        )
                    } else item
                }
                if (updatedItems != row.items) {
                    row.copy(items = updatedItems)
                } else row
            }
            state.copy(catalogRows = updatedRows)
        }
    }

    private fun loadHomeCatalogOrderPreference() {
        viewModelScope.launch {
            layoutPreferenceDataStore.homeCatalogOrderKeys.collectLatest { keys ->
                homeCatalogOrderKeys = keys
                rebuildCatalogOrder(addonsCache)
                scheduleUpdateCatalogRows()
            }
        }
    }

    private fun loadDisabledHomeCatalogPreference() {
        viewModelScope.launch {
            layoutPreferenceDataStore.disabledHomeCatalogKeys.collectLatest { keys ->
                disabledHomeCatalogKeys = keys.toSet()
                rebuildCatalogOrder(addonsCache)
                if (addonsCache.isNotEmpty()) {
                    loadAllCatalogs(addonsCache)
                } else {
                    scheduleUpdateCatalogRows()
                }
            }
        }
    }

    private fun observeTmdbSettings() {
        viewModelScope.launch {
            tmdbSettingsDataStore.settings
                .distinctUntilChanged()
                .collectLatest { settings ->
                    currentTmdbSettings = settings
                    scheduleUpdateCatalogRows()
                }
        }
    }

    fun onEvent(event: HomeEvent) {
        when (event) {
            is HomeEvent.OnItemClick -> navigateToDetail(event.itemId, event.itemType)
            is HomeEvent.OnLoadMoreCatalog -> loadMoreCatalogItems(event.catalogId, event.addonId, event.type)
            is HomeEvent.OnRemoveContinueWatching ->
                removeContinueWatching(
                    contentId = event.contentId,
                    season = event.season,
                    episode = event.episode,
                    isNextUp = event.isNextUp
                )
            HomeEvent.OnRetry -> viewModelScope.launch { loadAllCatalogs(addonsCache) }
        }
    }

    private fun loadContinueWatching() {
        viewModelScope.launch {
            combine(
                watchProgressRepository.allProgress,
                traktSettingsDataStore.continueWatchingDaysCap,
                dismissedNextUpKeys
            ) { items, daysCap, dismissedNextUp ->
                Triple(items, daysCap, dismissedNextUp)
            }.collectLatest { (items, daysCap, dismissedNextUp) ->
                val windowMs = daysCap.toLong() * 24L * 60L * 60L * 1000L
                val cutoffMs = System.currentTimeMillis() - windowMs
                val recentItems = items
                    .asSequence()
                    .filter { it.lastWatched >= cutoffMs }
                    .sortedByDescending { it.lastWatched }
                    .take(MAX_RECENT_PROGRESS_ITEMS)
                    .toList()

                Log.d("HomeViewModel", "allProgress emitted=${items.size} recentWindow=${recentItems.size}")

                val inProgressOnly = deduplicateInProgress(
                    recentItems.filter { it.isInProgress() }
                ).map { ContinueWatchingItem.InProgress(it) }

                Log.d("HomeViewModel", "inProgressOnly: ${inProgressOnly.size} items after filter+dedup")

                // Optimistic immediate render: show in-progress entries instantly.
                _uiState.update { it.copy(continueWatchingItems = inProgressOnly) }

                // Then enrich Next Up in background with bounded concurrency.
                enrichContinueWatchingProgressively(
                    allProgress = recentItems,
                    inProgressItems = inProgressOnly,
                    dismissedNextUp = dismissedNextUp
                )
            }
        }
    }

    private fun deduplicateInProgress(items: List<WatchProgress>): List<WatchProgress> {
        val (series, nonSeries) = items.partition { isSeriesType(it.contentType) }
        val latestPerShow = series
            .sortedByDescending { it.lastWatched }
            .distinctBy { it.contentId }
        return (nonSeries + latestPerShow).sortedByDescending { it.lastWatched }
    }

    private suspend fun enrichContinueWatchingProgressively(
        allProgress: List<WatchProgress>,
        inProgressItems: List<ContinueWatchingItem.InProgress>,
        dismissedNextUp: Set<String>
    ) = coroutineScope {
        val inProgressIds = inProgressItems.map { it.progress.contentId }.toSet()

        val latestCompletedBySeries = allProgress
            .filter { progress ->
                isSeriesType(progress.contentType) &&
                    progress.season != null &&
                    progress.episode != null &&
                    progress.season != 0 &&
                    progress.isCompleted()
            }
            .groupBy { it.contentId }
            .mapNotNull { (_, items) -> items.maxByOrNull { it.lastWatched } }
            .filter { it.contentId !in inProgressIds }
            .filter { progress -> nextUpDismissKey(progress.contentId) !in dismissedNextUp }
            .sortedByDescending { it.lastWatched }
            .take(MAX_NEXT_UP_LOOKUPS)

        if (latestCompletedBySeries.isEmpty()) {
            return@coroutineScope
        }

        val lookupSemaphore = Semaphore(MAX_NEXT_UP_CONCURRENCY)
        val mergeMutex = Mutex()
        val nextUpByContent = linkedMapOf<String, ContinueWatchingItem.NextUp>()

        latestCompletedBySeries.forEach { progress ->
            launch(Dispatchers.IO) {
                lookupSemaphore.withPermit {
                    val nextUp = buildNextUpItem(progress) ?: return@withPermit
                    mergeMutex.withLock {
                        nextUpByContent[progress.contentId] = nextUp
                        _uiState.update {
                            it.copy(
                                continueWatchingItems = mergeContinueWatchingItems(
                                    inProgressItems = inProgressItems,
                                    nextUpItems = nextUpByContent.values.toList()
                                )
                            )
                        }
                    }
                }
            }
        }
    }

    private fun mergeContinueWatchingItems(
        inProgressItems: List<ContinueWatchingItem.InProgress>,
        nextUpItems: List<ContinueWatchingItem.NextUp>
    ): List<ContinueWatchingItem> {
        val combined = mutableListOf<Pair<Long, ContinueWatchingItem>>()
        inProgressItems.forEach { combined.add(it.progress.lastWatched to it) }
        nextUpItems.forEach { combined.add(it.info.lastWatched to it) }

        return combined
            .sortedByDescending { it.first }
            .map { it.second }
    }

    private suspend fun buildNextUpItem(progress: WatchProgress): ContinueWatchingItem.NextUp? {
        val nextEpisode = findNextEpisode(progress) ?: return null
        val meta = nextEpisode.first
        val video = nextEpisode.second
        val info = NextUpInfo(
            contentId = progress.contentId,
            contentType = progress.contentType,
            name = meta.name,
            poster = meta.poster,
            backdrop = meta.background,
            logo = meta.logo,
            videoId = video.id,
            season = video.season ?: return null,
            episode = video.episode ?: return null,
            episodeTitle = video.title,
            thumbnail = video.thumbnail,
            lastWatched = progress.lastWatched
        )
        return ContinueWatchingItem.NextUp(info)
    }

    private suspend fun findNextEpisode(progress: WatchProgress): Pair<Meta, Video>? {
        if (!isSeriesType(progress.contentType)) return null

        val idCandidates = buildList {
            add(progress.contentId)
            if (progress.contentId.startsWith("tmdb:")) add(progress.contentId.substringAfter(':'))
            if (progress.contentId.startsWith("trakt:")) add(progress.contentId.substringAfter(':'))
        }.distinct()

        val meta = run {
            var resolved: Meta? = null
            val typeCandidates = listOf(progress.contentType, "series", "tv").distinct()
            for (type in typeCandidates) {
                for (candidateId in idCandidates) {
                    val result = withTimeoutOrNull(2500) {
                        metaRepository.getMetaFromAllAddons(
                            type = type,
                            id = candidateId
                        ).first { it !is NetworkResult.Loading }
                    } ?: continue
                    resolved = (result as? NetworkResult.Success)?.data
                    if (resolved != null) break
                }
                if (resolved != null) break
            }
            resolved
        } ?: return null

        val episodes = meta.videos
            .filter { it.season != null && it.episode != null && it.season != 0 }
            .sortedWith(compareBy<Video> { it.season }.thenBy { it.episode })

        val currentIndex = episodes.indexOfFirst {
            it.season == progress.season && it.episode == progress.episode
        }

        if (currentIndex == -1 || currentIndex + 1 >= episodes.size) return null

        return meta to episodes[currentIndex + 1]
    }

    private fun isSeriesType(type: String?): Boolean {
        return type == "series" || type == "tv"
    }

    private fun removeContinueWatching(
        contentId: String,
        season: Int? = null,
        episode: Int? = null,
        isNextUp: Boolean = false
    ) {
        if (isNextUp) {
            val dismissKey = nextUpDismissKey(contentId)
            dismissedNextUpKeys.update { it + dismissKey }
            _uiState.update { state ->
                state.copy(
                    continueWatchingItems = state.continueWatchingItems.filterNot { item ->
                        when (item) {
                            is ContinueWatchingItem.NextUp ->
                                nextUpDismissKey(item.info.contentId) == dismissKey
                            is ContinueWatchingItem.InProgress -> false
                        }
                    }
                )
            }
            return
        }

        viewModelScope.launch {
            Log.d(
                TAG,
                "removeContinueWatching requested contentId=$contentId season=$season episode=$episode; removing all progress for content"
            )
            watchProgressRepository.removeProgress(contentId = contentId, season = null, episode = null)
        }
    }

    private fun nextUpDismissKey(contentId: String): String = contentId

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
        truncatedItemsCache.clear()
        hasRenderedFirstCatalog = false
        trailerPreviewLoadingIds.clear()
        trailerPreviewNegativeCache.clear()
        trailerPreviewUrlsState.clear()
        activeTrailerPreviewItemId = null
        trailerPreviewRequestVersion = 0L
        lastHeroEnrichmentSignature = null
        lastHeroEnrichedItems = emptyList()

        try {
            if (addons.isEmpty()) {
                _uiState.update { it.copy(isLoading = false, error = "No addons installed") }
                return
            }

            rebuildCatalogOrder(addons)

            if (catalogOrder.isEmpty()) {
                _uiState.update { it.copy(isLoading = false, error = "No catalog addons installed") }
                return
            }

            // Load catalogs
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
                loadCatalog(addon, catalog)
            }
        } catch (e: Exception) {
            _uiState.update { it.copy(isLoading = false, error = e.message) }
        }
    }

    private fun loadCatalog(addon: Addon, catalog: CatalogDescriptor) {
        viewModelScope.launch {
            catalogLoadSemaphore.withPermit {
                val supportsSkip = catalog.extra.any { it.name == "skip" }
                Log.d(
                    TAG,
                    "Loading home catalog addonId=${addon.id} addonName=${addon.name} type=${catalog.apiType} catalogId=${catalog.id} catalogName=${catalog.name} supportsSkip=$supportsSkip"
                )
                catalogRepository.getCatalog(
                    addonBaseUrl = addon.baseUrl,
                    addonId = addon.id,
                    addonName = addon.displayName,
                    catalogId = catalog.id,
                    catalogName = catalog.name,
                    type = catalog.apiType,
                    skip = 0,
                    supportsSkip = supportsSkip
                ).collect { result ->
                    when (result) {
                        is NetworkResult.Success -> {
                            val key = catalogKey(
                                addonId = addon.id,
                                type = catalog.apiType,
                                catalogId = catalog.id
                            )
                            catalogsMap[key] = result.data
                            pendingCatalogLoads = (pendingCatalogLoads - 1).coerceAtLeast(0)
                            Log.d(
                                TAG,
                                "Home catalog loaded addonId=${addon.id} type=${catalog.apiType} catalogId=${catalog.id} items=${result.data.items.size} pending=$pendingCatalogLoads"
                            )
                            scheduleUpdateCatalogRows()
                        }
                        is NetworkResult.Error -> {
                            pendingCatalogLoads = (pendingCatalogLoads - 1).coerceAtLeast(0)
                            Log.w(
                                TAG,
                                "Home catalog failed addonId=${addon.id} type=${catalog.apiType} catalogId=${catalog.id} code=${result.code} message=${result.message}"
                            )
                            scheduleUpdateCatalogRows()
                        }
                        NetworkResult.Loading -> { /* Handled by individual row */ }
                    }
                }
            }
        }
    }

    private fun loadMoreCatalogItems(catalogId: String, addonId: String, type: String) {
        val key = catalogKey(addonId = addonId, type = type, catalogId = catalogId)
        val currentRow = catalogsMap[key] ?: return

        if (currentRow.isLoading || !currentRow.hasMore) return
        if (key in _loadingCatalogs.value) return

        // Mark loading via lightweight separate flow — avoids full state cascade
        catalogsMap[key] = currentRow.copy(isLoading = true)
        _loadingCatalogs.update { it + key }

        viewModelScope.launch {
            val addon = addonsCache.find { it.id == addonId } ?: return@launch

            // Use actual loaded item count for skip, not fixed 100-page size
            val nextSkip = currentRow.items.size
            catalogRepository.getCatalog(
                addonBaseUrl = addon.baseUrl,
                addonId = addon.id,
                addonName = addon.displayName,
                catalogId = catalogId,
                catalogName = currentRow.catalogName,
                type = currentRow.apiType,
                skip = nextSkip,
                supportsSkip = currentRow.supportsSkip
            ).collect { result ->
                when (result) {
                    is NetworkResult.Success -> {
                        val mergedItems = currentRow.items + result.data.items
                        catalogsMap[key] = result.data.copy(items = mergedItems)
                        _loadingCatalogs.update { it - key }
                        updateCatalogRows()
                    }
                    is NetworkResult.Error -> {
                        catalogsMap[key] = currentRow.copy(isLoading = false)
                        _loadingCatalogs.update { it - key }
                        updateCatalogRows()
                    }
                    NetworkResult.Loading -> { }
                }
            }
        }
    }

    private fun scheduleUpdateCatalogRows() {
        catalogUpdateJob?.cancel()
        catalogUpdateJob = viewModelScope.launch {
            // Render immediately for the first catalog arrival, debounce subsequent updates
            if (!hasRenderedFirstCatalog && catalogsMap.isNotEmpty()) {
                hasRenderedFirstCatalog = true
                updateCatalogRows()
                return@launch
            }
            val debounceMs = when {
                pendingCatalogLoads > 5 -> 300L
                pendingCatalogLoads > 0 -> 150L
                else -> 100L
            }
            delay(debounceMs)
            updateCatalogRows()
        }
    }

    private suspend fun updateCatalogRows() {
        // Snapshot mutable state before entering background context
        val orderedKeys = catalogOrder.toList()
        val catalogSnapshot = catalogsMap.toMap()
        val heroCatalogKeys = currentHeroCatalogKeys
        val currentLayout = _uiState.value.homeLayout
        val currentGridItems = _uiState.value.gridItems
        val heroSectionEnabled = _uiState.value.heroSectionEnabled

        val (displayRows, baseHeroItems, baseGridItems) = withContext(Dispatchers.Default) {
            val orderedRows = orderedKeys.mapNotNull { key -> catalogSnapshot[key] }
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
                else -> orderedRows.flatMap { it.items }.take(7)
            }

            val computedDisplayRows = orderedRows.map { row ->
                if (row.items.size > 25) {
                    val key = "${row.addonId}_${row.apiType}_${row.catalogId}"
                    val cached = truncatedItemsCache[key]
                    if (cached != null && cached.size == 25 && row.items.take(25) == cached) {
                        row.copy(items = cached)
                    } else {
                        val truncated = row.items.take(25)
                        truncatedItemsCache[key] = truncated
                        row.copy(items = truncated)
                    }
                } else {
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

            Triple(computedDisplayRows, computedHeroItems, computedGridItems)
        }

        // Full (untruncated) rows for CatalogSeeAllScreen
        val fullRows = orderedKeys.mapNotNull { key -> catalogSnapshot[key] }

        val tmdbSettings = currentTmdbSettings
        val shouldUseEnrichedHeroItems = tmdbSettings.enabled &&
            (tmdbSettings.useArtwork || tmdbSettings.useBasicInfo || tmdbSettings.useDetails)

        val resolvedHeroItems = if (shouldUseEnrichedHeroItems) {
            val enrichmentSignature = heroEnrichmentSignature(baseHeroItems, tmdbSettings)
            if (lastHeroEnrichmentSignature == enrichmentSignature) {
                lastHeroEnrichedItems
            } else {
                val enrichedItems = enrichHeroItems(baseHeroItems, tmdbSettings)
                lastHeroEnrichmentSignature = enrichmentSignature
                lastHeroEnrichedItems = enrichedItems
                enrichedItems
            }
        } else {
            lastHeroEnrichmentSignature = null
            lastHeroEnrichedItems = emptyList()
            baseHeroItems
        }

        val nextGridItems = if (currentLayout == HomeLayout.GRID) {
            replaceGridHeroItems(baseGridItems, resolvedHeroItems)
        } else {
            baseGridItems
        }

        // Preserve references when values are structurally equal to reduce unnecessary recomposition.
        _uiState.update { state ->
            state.copy(
                catalogRows = if (state.catalogRows == displayRows) state.catalogRows else displayRows,
                fullCatalogRows = if (state.fullCatalogRows == fullRows) state.fullCatalogRows else fullRows,
                heroItems = if (state.heroItems == resolvedHeroItems) state.heroItems else resolvedHeroItems,
                gridItems = if (state.gridItems == nextGridItems) state.gridItems else nextGridItems,
                isLoading = false
            )
        }
    }

    private fun navigateToDetail(itemId: String, itemType: String) {
        _uiState.update { it.copy(selectedItemId = itemId) }
    }

    private suspend fun enrichHeroItems(
        items: List<MetaPreview>,
        settings: TmdbSettings
    ): List<MetaPreview> {
        if (items.isEmpty()) return items

        // Enrich all hero items in parallel
        return coroutineScope {
            items.map { item ->
                async(Dispatchers.IO) {
                    try {
                        val tmdbId = tmdbService.ensureTmdbId(item.id, item.apiType) ?: return@async item
                        val enrichment = tmdbMetadataService.fetchEnrichment(
                            tmdbId = tmdbId,
                            contentType = item.type,
                            language = settings.language
                        ) ?: return@async item

                        var enriched = item

                        if (settings.useArtwork) {
                            enriched = enriched.copy(
                                background = enrichment.backdrop ?: enriched.background,
                                logo = enrichment.logo ?: enriched.logo,
                                poster = enrichment.poster ?: enriched.poster
                            )
                        }

                        if (settings.useBasicInfo) {
                            enriched = enriched.copy(
                                name = enrichment.localizedTitle ?: enriched.name,
                                description = enrichment.description ?: enriched.description,
                                genres = if (enrichment.genres.isNotEmpty()) enrichment.genres else enriched.genres,
                                imdbRating = enrichment.rating?.toFloat() ?: enriched.imdbRating
                            )
                        }

                        if (settings.useDetails) {
                            enriched = enriched.copy(
                                releaseInfo = enrichment.releaseInfo ?: enriched.releaseInfo
                            )
                        }

                        enriched
                    } catch (e: Exception) {
                        Log.w(TAG, "Hero enrichment failed for ${item.id}: ${e.message}")
                        item
                    }
                }
            }.awaitAll()
        }
    }

    private fun replaceGridHeroItems(
        gridItems: List<GridItem>,
        heroItems: List<MetaPreview>
    ): List<GridItem> {
        if (gridItems.isEmpty()) return gridItems
        return gridItems.map { item ->
            if (item is GridItem.Hero) {
                item.copy(items = heroItems)
            } else {
                item
            }
        }
    }

    private fun heroEnrichmentSignature(items: List<MetaPreview>, settings: TmdbSettings): String {
        val itemSignature = items.joinToString(separator = "|") { item ->
            "${item.id}:${item.apiType}:${item.name}:${item.background}:${item.logo}:${item.poster}"
        }
        return buildString {
            append(settings.enabled)
            append(':')
            append(settings.language)
            append(':')
            append(settings.useArtwork)
            append(':')
            append(settings.useBasicInfo)
            append(':')
            append(settings.useDetails)
            append("::")
            append(itemSignature)
        }
    }

    private fun catalogKey(addonId: String, type: String, catalogId: String): String {
        return "${addonId}_${type}_${catalogId}"
    }

    private fun rebuildCatalogOrder(addons: List<Addon>) {
        val defaultOrder = buildDefaultCatalogOrder(addons)
        val availableSet = defaultOrder.toSet()

        val savedValid = homeCatalogOrderKeys
            .asSequence()
            .filter { it in availableSet }
            .distinct()
            .toList()

        val savedSet = savedValid.toSet()
        val mergedOrder = savedValid + defaultOrder.filterNot { it in savedSet }

        catalogOrder.clear()
        catalogOrder.addAll(mergedOrder)
    }

    private fun buildDefaultCatalogOrder(addons: List<Addon>): List<String> {
        val orderedKeys = mutableListOf<String>()
        addons.forEach { addon ->
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
                .forEach { catalog ->
                    val key = catalogKey(
                        addonId = addon.id,
                        type = catalog.apiType,
                        catalogId = catalog.id
                    )
                    if (key !in orderedKeys) {
                        orderedKeys.add(key)
                    }
                }
        }
        return orderedKeys
    }

    private fun isCatalogDisabled(
        addonBaseUrl: String,
        addonId: String,
        type: String,
        catalogId: String,
        catalogName: String
    ): Boolean {
        if (disableCatalogKey(addonBaseUrl, type, catalogId, catalogName) in disabledHomeCatalogKeys) {
            return true
        }
        // Backward compatibility with previously stored keys.
        return catalogKey(addonId, type, catalogId) in disabledHomeCatalogKeys
    }

    private fun disableCatalogKey(
        addonBaseUrl: String,
        type: String,
        catalogId: String,
        catalogName: String
    ): String {
        return "${addonBaseUrl}_${type}_${catalogId}_${catalogName}"
    }

    private fun CatalogDescriptor.isSearchOnlyCatalog(): Boolean {
        return extra.any { extra -> extra.name == "search" && extra.isRequired }
    }

    private fun MetaPreview.hasHeroArtwork(): Boolean {
        return !background.isNullOrBlank() || !poster.isNullOrBlank()
    }

    private fun extractYear(releaseInfo: String?): String? {
        if (releaseInfo.isNullOrBlank()) return null
        return Regex("\\b(19|20)\\d{2}\\b").find(releaseInfo)?.value
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
