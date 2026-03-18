package com.nexio.tv.ui.screens.home

import android.content.Context
import android.os.SystemClock
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.nexio.tv.core.locale.AppLocaleResolver
import com.nexio.tv.core.tmdb.TmdbEnrichment
import com.nexio.tv.core.tmdb.TmdbMetadataService
import com.nexio.tv.core.tmdb.TmdbService
import com.nexio.tv.core.sync.AccountSyncRefreshNotifier
import com.nexio.tv.data.local.DebugSettingsDataStore
import com.nexio.tv.data.local.HomeCatalogSnapshotStore
import com.nexio.tv.data.local.LayoutPreferenceDataStore
import com.nexio.tv.data.local.MDBListCatalogPreferences
import com.nexio.tv.data.local.MDBListDiscoverySnapshotStore
import com.nexio.tv.data.local.MDBListSettingsDataStore
import com.nexio.tv.data.local.MetadataDiskCacheStore
import com.nexio.tv.data.local.PersistedSyntheticCatalogGroup
import com.nexio.tv.data.local.SyntheticHomeCatalogStore
import com.nexio.tv.data.local.TmdbSettingsDataStore
import com.nexio.tv.data.local.TraktCatalogPreferences
import com.nexio.tv.data.local.TraktDiscoverySnapshotStore
import com.nexio.tv.data.local.TraktSettingsDataStore
import com.nexio.tv.data.repository.ContinueWatchingSnapshotService
import com.nexio.tv.data.repository.MDBListRepository
import com.nexio.tv.data.repository.MDBListDiscoveryService
import com.nexio.tv.data.repository.TraktDiscoveryService
import com.nexio.tv.data.repository.TraktScrobbleService
import com.nexio.tv.domain.model.Addon
import com.nexio.tv.domain.model.CatalogDescriptor
import com.nexio.tv.domain.model.CatalogRow
import com.nexio.tv.domain.model.LibraryEntryInput
import com.nexio.tv.domain.model.Meta
import com.nexio.tv.domain.model.MetaPreview
import com.nexio.tv.domain.model.TmdbSettings
import com.nexio.tv.domain.repository.AddonRepository
import com.nexio.tv.domain.repository.CatalogRepository
import com.nexio.tv.domain.repository.LibraryRepository
import com.nexio.tv.domain.repository.MetaRepository
import com.nexio.tv.domain.repository.WatchProgressRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.Mutex
import java.util.Collections
import javax.inject.Inject

@OptIn(kotlinx.coroutines.FlowPreview::class)
@HiltViewModel
class HomeViewModel @Inject constructor(
    internal val addonRepository: AddonRepository,
    internal val catalogRepository: CatalogRepository,
    internal val watchProgressRepository: WatchProgressRepository,
    internal val libraryRepository: LibraryRepository,
    internal val metaRepository: MetaRepository,
    internal val layoutPreferenceDataStore: LayoutPreferenceDataStore,
    internal val tmdbSettingsDataStore: TmdbSettingsDataStore,
    internal val traktSettingsDataStore: TraktSettingsDataStore,
    internal val mdbListSettingsDataStore: MDBListSettingsDataStore,
    internal val traktDiscoverySnapshotStore: TraktDiscoverySnapshotStore,
    internal val mdbListDiscoverySnapshotStore: MDBListDiscoverySnapshotStore,
    internal val continueWatchingSnapshotService: ContinueWatchingSnapshotService,
    internal val traktScrobbleService: TraktScrobbleService,
    internal val traktDiscoveryService: TraktDiscoveryService,
    internal val mdbListDiscoveryService: MDBListDiscoveryService,
    internal val mdbListRepository: MDBListRepository,
    internal val tmdbService: TmdbService,
    internal val tmdbMetadataService: TmdbMetadataService,
    internal val accountSyncRefreshNotifier: AccountSyncRefreshNotifier,
    internal val homeCatalogSnapshotStore: HomeCatalogSnapshotStore,
    internal val homeCatalogRefreshCoordinator: HomeCatalogRefreshCoordinator,
    internal val debugSettingsDataStore: DebugSettingsDataStore,
    internal val metadataDiskCacheStore: MetadataDiskCacheStore,
    internal val syntheticHomeCatalogStore: SyntheticHomeCatalogStore,
    @ApplicationContext internal val appContext: Context
) : ViewModel() {
    companion object {
        internal const val TAG = "HomeViewModel"
        private const val CONTINUE_WATCHING_WINDOW_MS = 30L * 24 * 60 * 60 * 1000
        private const val MAX_RECENT_PROGRESS_ITEMS = 300
        private const val MAX_NEXT_UP_LOOKUPS = 24
        private const val MAX_NEXT_UP_CONCURRENCY = 4
        private const val STARTUP_CATALOG_LOAD_CONCURRENCY = 1
        private const val MAX_CATALOG_LOAD_CONCURRENCY = 4
        internal const val STARTUP_NETWORK_DEFERRAL_WINDOW_MS = 20_000L
        internal const val HOME_SNAPSHOT_PERSIST_DEBOUNCE_MS = 750L
        internal const val FOCUS_ENRICHMENT_BATCH_WINDOW_MS = 75L
        internal const val EXTERNAL_META_PREFETCH_FOCUS_DEBOUNCE_MS = 220L
        internal const val EXTERNAL_META_PREFETCH_ADJACENT_DEBOUNCE_MS = 120L
        internal const val MAX_POSTER_STATUS_OBSERVERS = 24
    }

    internal val _uiState = MutableStateFlow(HomeUiState())
    val uiState: StateFlow<HomeUiState> = _uiState.asStateFlow()
    internal val _fullCatalogRows = MutableStateFlow<List<CatalogRow>>(emptyList())
    val fullCatalogRows: StateFlow<List<CatalogRow>> = _fullCatalogRows.asStateFlow()

    private val _focusState = MutableStateFlow(HomeScreenFocusState())
    val focusState: StateFlow<HomeScreenFocusState> = _focusState.asStateFlow()

    private val _gridFocusState = MutableStateFlow(HomeScreenFocusState())
    val gridFocusState: StateFlow<HomeScreenFocusState> = _gridFocusState.asStateFlow()

    internal val _loadingCatalogs = MutableStateFlow<Set<String>>(emptySet())
    val loadingCatalogs: StateFlow<Set<String>> = _loadingCatalogs.asStateFlow()
    internal val _enrichingItemId = MutableStateFlow<String?>(null)
    val enrichingItemId: StateFlow<String?> = _enrichingItemId.asStateFlow()
    internal fun setEnrichingItemId(id: String?) { _enrichingItemId.value = id }

    internal val catalogsMap = linkedMapOf<String, CatalogRow>()
    internal val catalogOrder = mutableListOf<String>()
    internal var addonsCache: List<Addon> = emptyList()
    internal var homeCatalogOrderKeys: List<String> = emptyList()
    internal var disabledHomeCatalogKeys: Set<String> = emptySet()
    internal var currentHeroCatalogKeys: List<String> = emptyList()
    internal var catalogUpdateJob: Job? = null
    internal var hasRenderedFirstCatalog = false
    internal val startupCatalogLoadSemaphore = Semaphore(STARTUP_CATALOG_LOAD_CONCURRENCY)
    internal val catalogLoadSemaphore = Semaphore(MAX_CATALOG_LOAD_CONCURRENCY)
    internal var pendingCatalogLoads = 0
    internal val activeCatalogLoadJobs = mutableSetOf<Job>()
    internal var activeCatalogLoadSignature: String? = null
    internal var catalogLoadGeneration: Long = 0L
    internal var catalogsLoadInProgress: Boolean = false
    internal var lastCatalogComputationSignature: String? = null
    internal var lastCatalogOrderDiagnosticsSignature: String? = null
    internal data class TruncatedRowCacheEntry(
        val sourceRow: CatalogRow,
        val truncatedRow: CatalogRow
    )
    internal val truncatedRowCache = mutableMapOf<String, TruncatedRowCacheEntry>()
    internal var inMemoryHomeSnapshot: HomeCatalogSnapshotStore.Snapshot? = null
    internal var pendingRestoredCatalogSnapshot: HomeCatalogSnapshotStore.Snapshot? = null
    internal var homeSnapshotPersistJob: Job? = null
    internal var pendingHomeSnapshotPersist: HomeCatalogSnapshotStore.Snapshot? = null
    internal var homeSnapshotPersistGeneration: Long = 0L
    internal val pendingTmdbEnrichmentByItemId = linkedMapOf<String, TmdbEnrichment>()
    internal val pendingMetaEnrichmentByItemId = linkedMapOf<String, Meta>()
    internal val pendingTomatoesEnrichmentByItemId = linkedMapOf<String, Double>()
    internal val syntheticTomatoesOverridesByItemId = linkedMapOf<String, Double>()
    internal var metadataEnrichmentFlushJob: Job? = null
    internal var currentTmdbSettings: TmdbSettings = TmdbSettings()
    internal var traktDiscoverySnapshot: com.nexio.tv.data.repository.TraktDiscoverySnapshot =
        com.nexio.tv.data.repository.TraktDiscoverySnapshot()
    internal var persistedTraktDiscoverySnapshot: com.nexio.tv.data.repository.TraktDiscoverySnapshot =
        com.nexio.tv.data.repository.TraktDiscoverySnapshot()
    internal var traktCatalogPreferences: TraktCatalogPreferences = TraktCatalogPreferences()
    internal var mdbListDiscoverySnapshot: com.nexio.tv.data.repository.MDBListDiscoverySnapshot =
        com.nexio.tv.data.repository.MDBListDiscoverySnapshot()
    internal var persistedMDBListDiscoverySnapshot: com.nexio.tv.data.repository.MDBListDiscoverySnapshot =
        com.nexio.tv.data.repository.MDBListDiscoverySnapshot()
    internal var mdbListCatalogPreferences: MDBListCatalogPreferences = MDBListCatalogPreferences()
    internal var persistedTraktSyntheticGroups: List<PersistedSyntheticCatalogGroup> = emptyList()
    internal var persistedMDBListSyntheticGroups: List<PersistedSyntheticCatalogGroup> = emptyList()
    internal var heroEnrichmentJob: Job? = null
    internal var lastHeroEnrichmentSignature: String? = null
    internal var lastHeroEnrichedItems: List<MetaPreview> = emptyList()
    internal val prefetchedExternalMetaIds = Collections.synchronizedSet(mutableSetOf<String>())
    internal val externalMetaPrefetchInFlightIds = Collections.synchronizedSet(mutableSetOf<String>())
    internal val prefetchedTomatoesIds = Collections.synchronizedSet(mutableSetOf<String>())
    internal val tomatoesEnrichmentInFlightIds = Collections.synchronizedSet(mutableSetOf<String>())
    internal var pendingFocusedItemForEnrichment: MetaPreview? = null
    internal var externalMetaPrefetchJob: Job? = null
    internal var pendingExternalMetaPrefetchItemId: String? = null
    internal var adjacentItemPrefetchJob: Job? = null
    internal var pendingAdjacentPrefetchItemId: String? = null
    internal val prefetchedTmdbIds = Collections.synchronizedSet(mutableSetOf<String>())
    internal var tmdbEnrichFocusJob: Job? = null
    internal var pendingTmdbEnrichItemId: String? = null
    internal val posterLibraryObserverJobs = mutableMapOf<String, Job>()
    internal val movieWatchedObserverJobs = mutableMapOf<String, Job>()
    internal var activePosterListPickerInput: LibraryEntryInput? = null
    @Volatile
    internal var externalMetaPrefetchEnabled: Boolean = false
    @Volatile
    internal var restoredCatalogSnapshotActive: Boolean = false
    @Volatile
    internal var hasPersistedCatalogSnapshot: Boolean = false
    @Volatile
    internal var startupRefreshPending: Boolean = false
    @Volatile
    internal var traktDiscoveryRefreshInProgress: Boolean = false
    @Volatile
    internal var mdbListDiscoveryRefreshInProgress: Boolean = false
    @Volatile
    internal var installedAddonsObserved: Boolean = false
    @Volatile
    internal var traktDiscoveryObserved: Boolean = false
    @Volatile
    internal var mdbListDiscoveryObserved: Boolean = false
    @Volatile
    internal var lastForegroundRefreshMs: Long = 0L
    @Volatile
    internal var startupPerfTelemetryEnabled: Boolean = false
    @Volatile
    internal var diskFirstHomeStartupEnabled: Boolean = false
    @Volatile
    internal var startupWindowOpenUntilMs: Long = 0L
    @Volatile
    internal var isStartupDeferredRefreshAllowed: Boolean = true
    @Volatile
    internal var startupDeferralCompleted: Boolean = false
    internal var startupDeferralWindowJob: Job? = null
    internal var deferredStartupRefreshJob: Job? = null
    internal var pendingSerializedHomeRefreshReason: String? = null
    internal val syntheticCatalogStoreMutex = Mutex()
    internal val catalogRowsComputationMutex = Mutex()
    @Volatile
    internal var syntheticSnapshotBatchActive: Boolean = false

    init {
        observeStartupPerfTelemetry()
        observeDiskFirstHomeStartupToggle()
        observeLocaleChangesForMetadata()
        restorePersistedDiscoverySnapshots()
        restorePersistedSyntheticCatalogRows()
        restorePersistedCatalogSnapshot()
        observeLayoutPreferences()
        observeExternalMetaPrefetchPreference()
        loadHomeCatalogOrderPreference()
        loadDisabledHomeCatalogPreference()
        observeLibraryState()
        observeTmdbSettings()
        observeMDBListSettings()
        observeTraktCatalogPreferences()
        observeTraktDiscovery()
        observeMDBListCatalogPreferences()
        observeMDBListDiscovery()
        observeAccountSyncRefresh()
        loadContinueWatching()
        observeInstalledAddons()
    }

    private fun observeStartupPerfTelemetry() {
        viewModelScope.launch {
            debugSettingsDataStore.startupPerfTelemetryEnabled.collectLatest { enabled ->
                startupPerfTelemetryEnabled = enabled
            }
        }
    }

    private fun observeDiskFirstHomeStartupToggle() {
        viewModelScope.launch {
            debugSettingsDataStore.diskFirstHomeStartupEnabled.collectLatest { enabled ->
                diskFirstHomeStartupEnabled = enabled
                if (!enabled) {
                    isStartupDeferredRefreshAllowed = true
                    startupDeferralCompleted = false
                    startupWindowOpenUntilMs = 0L
                    startupDeferralWindowJob?.cancel()
                    return@collectLatest
                }
                startupDeferralCompleted = false
                openStartupDeferralWindowIfNeeded("toggle_enabled")
            }
        }
    }

    private fun observeLocaleChangesForMetadata() {
        viewModelScope.launch {
            AppLocaleResolver.observeStoredLocaleTag(appContext)
                .drop(1)
                .collectLatest {
                    metadataDiskCacheStore.bumpLanguageEpoch()
                    val removedImageUrls = metadataDiskCacheStore.removeEntriesFromStaleEpochs()
                    homeCatalogRefreshCoordinator.evictCachedImageUrls(removedImageUrls)
                    metaRepository.clearCache()
                    tmdbMetadataService.clearCache()
                    homeCatalogSnapshotStore.clear()
                    syntheticHomeCatalogStore.clear()
                    inMemoryHomeSnapshot = null
                    pendingRestoredCatalogSnapshot = null
                    pendingHomeSnapshotPersist = null
                    persistedTraktSyntheticGroups = emptyList()
                    persistedMDBListSyntheticGroups = emptyList()
                    watchProgressRepository.invalidateLocalizedMetadata()
                    continueWatchingSnapshotService.invalidateLocalizedMetadata()
                    logStartupPerf("metadata_language_epoch_bumped")
                }
        }
    }

    private fun observeLayoutPreferences() = observeLayoutPreferencesPipeline()

    private fun observeExternalMetaPrefetchPreference() = observeExternalMetaPrefetchPreferencePipeline()

    fun onItemFocus(item: MetaPreview) = onItemFocusPipeline(item)
    fun preloadAdjacentItem(item: MetaPreview) = preloadAdjacentItemPipeline(item)

    private fun loadHomeCatalogOrderPreference() = loadHomeCatalogOrderPreferencePipeline()

    private fun loadDisabledHomeCatalogPreference() = loadDisabledHomeCatalogPreferencePipeline()

    private fun observeTmdbSettings() = observeTmdbSettingsPipeline()

    private fun observeMDBListSettings() = observeMDBListSettingsPipeline()

    private fun observeTraktCatalogPreferences() = observeTraktCatalogPreferencesPipeline()

    private fun observeTraktDiscovery() = observeTraktDiscoveryPipeline()

    private fun observeMDBListCatalogPreferences() = observeMDBListCatalogPreferencesPipeline()

    private fun observeMDBListDiscovery() = observeMDBListDiscoveryPipeline()

    private fun observeAccountSyncRefresh() = observeAccountSyncRefreshPipeline()

    fun onForeground() = onForegroundPipeline()

    private fun restorePersistedSyntheticCatalogRows() = restorePersistedSyntheticCatalogRowsPipeline()

    private fun restorePersistedDiscoverySnapshots() = restorePersistedDiscoverySnapshotsPipeline()

    private fun restorePersistedCatalogSnapshot() = restorePersistedCatalogSnapshotPipeline()

    fun onEvent(event: HomeEvent) {
        when (event) {
            is HomeEvent.OnItemClick -> navigateToDetail(event.itemId, event.itemType)
            is HomeEvent.OnLoadMoreCatalog -> loadMoreCatalogItems(event.catalogId, event.addonId, event.type)
            is HomeEvent.OnRemoveContinueWatching -> removeContinueWatching(
                contentId = event.contentId,
                season = event.season,
                episode = event.episode,
                isNextUp = event.isNextUp
            )
            HomeEvent.OnRetry -> viewModelScope.launch { loadAllCatalogs(addonsCache, forceReload = true) }
        }
    }

    private fun loadContinueWatching() = loadContinueWatchingPipeline()

    private fun removeContinueWatching(
        contentId: String,
        season: Int? = null,
        episode: Int? = null,
        isNextUp: Boolean = false
    ) = removeContinueWatchingPipeline(
        contentId = contentId,
        season = season,
        episode = episode,
        isNextUp = isNextUp
    )

    fun markContinueWatchingAsWatched(item: ContinueWatchingItem) =
        markContinueWatchingAsWatchedPipeline(item)

    fun checkInContinueWatching(item: ContinueWatchingItem) =
        checkInContinueWatchingPipeline(item)

    fun dismissTraktRecommendation(ref: com.nexio.tv.data.repository.TraktRecommendationRef) =
        dismissTraktRecommendationPipeline(ref)

    fun openContinueWatchingListPicker(item: ContinueWatchingItem) =
        openContinueWatchingListPickerPipeline(item)

    private fun observeInstalledAddons() = observeInstalledAddonsPipeline()

    private suspend fun loadAllCatalogs(addons: List<Addon>, forceReload: Boolean = false) =
        loadAllCatalogsPipeline(addons, forceReload)

    private fun loadCatalog(addon: Addon, catalog: CatalogDescriptor, generation: Long) =
        loadCatalogPipeline(addon, catalog, generation)

    private fun loadMoreCatalogItems(catalogId: String, addonId: String, type: String) =
        loadMoreCatalogItemsPipeline(catalogId, addonId, type)

    internal fun openStartupDeferralWindowIfNeeded(reason: String) {
        if (!diskFirstHomeStartupEnabled) return
        if (startupDeferralCompleted) return
        val now = SystemClock.elapsedRealtime()
        if (startupWindowOpenUntilMs > now) return
        startupWindowOpenUntilMs = now + STARTUP_NETWORK_DEFERRAL_WINDOW_MS
        isStartupDeferredRefreshAllowed = false
        logStartupPerf("disk_first_startup_window_open", "reason=$reason")
        startupDeferralWindowJob?.cancel()
        startupDeferralWindowJob = viewModelScope.launch {
            delay(STARTUP_NETWORK_DEFERRAL_WINDOW_MS)
            isStartupDeferredRefreshAllowed = true
            startupDeferralCompleted = true
            logStartupPerf("disk_first_startup_window_closed")
            runDeferredStartupRefreshIfNeeded("window_closed")
        }
    }

    internal fun shouldDeferStartupNetworkWork(): Boolean {
        if (!diskFirstHomeStartupEnabled) return false
        return !isStartupDeferredRefreshAllowed && SystemClock.elapsedRealtime() < startupWindowOpenUntilMs
    }

    internal fun effectiveCatalogLoadConcurrency(): Int {
        return if (shouldDeferStartupNetworkWork()) STARTUP_CATALOG_LOAD_CONCURRENCY else MAX_CATALOG_LOAD_CONCURRENCY
    }

    internal fun runDeferredStartupRefreshIfNeeded(reason: String) {
        if (!diskFirstHomeStartupEnabled || shouldDeferStartupNetworkWork()) return
        runSerializedHomeRefreshIfNeeded(reason)
    }

    internal fun runSerializedHomeRefreshIfNeeded(reason: String) {
        if (shouldDeferStartupNetworkWork()) return
        if (deferredStartupRefreshJob?.isActive == true) {
            Log.d(TAG, "Serialized home refresh already running; queueing reason=$reason")
            pendingSerializedHomeRefreshReason = reason
            return
        }
        deferredStartupRefreshJob = viewModelScope.launch {
            var nextReason: String? = reason
            while (nextReason != null) {
                val currentReason = nextReason
                pendingSerializedHomeRefreshReason = null
                startupRefreshPending = true
                Log.d(TAG, "Serialized home refresh start reason=$currentReason")
                logStartupPerf("catalog_refresh_start", "reason=$currentReason")
                runSerializedPostStartupRefresh()
                logStartupPerf("catalog_refresh_end", "reason=$currentReason")
                Log.d(TAG, "Serialized home refresh end reason=$currentReason")
                nextReason = pendingSerializedHomeRefreshReason
            }
            runDeferredFocusedItemEnrichmentIfReady()
        }
    }

    internal fun shouldSuppressIncrementalHomeSnapshotPublish(): Boolean {
        if (!diskFirstHomeStartupEnabled) return false
        if (!hasPersistedCatalogSnapshot) return false
        val hasVisibleContent = _uiState.value.catalogRows.any { it.items.isNotEmpty() } ||
            _uiState.value.heroItems.isNotEmpty()
        if (!hasVisibleContent) return false
        return restoredCatalogSnapshotActive ||
            startupRefreshPending ||
            syntheticSnapshotBatchActive ||
            deferredStartupRefreshJob?.isActive == true
    }

    internal fun logStartupPerf(event: String, details: String? = null) {
        if (!startupPerfTelemetryEnabled) return
        val suffix = details?.let { " $it" }.orEmpty()
        Log.i("StartupPerf", "t=${SystemClock.elapsedRealtime()}ms event=$event$suffix")
    }

    internal fun scheduleUpdateCatalogRows() {
        if (shouldSuppressIncrementalHomeSnapshotPublish()) {
            return
        }
        catalogUpdateJob?.cancel()
        catalogUpdateJob = viewModelScope.launch {
            val debounceMs = when {
                // First render: use minimal debounce to show content ASAP while still
                // batching near-simultaneous arrivals.
                !hasRenderedFirstCatalog && catalogsMap.isNotEmpty() -> {
                    hasRenderedFirstCatalog = true
                    50L
                }
                pendingCatalogLoads > 8 -> 200L
                pendingCatalogLoads > 3 -> 150L
                pendingCatalogLoads > 0 -> 100L
                else -> 50L
            }
            delay(debounceMs)
            updateCatalogRows()
        }
    }

    private suspend fun updateCatalogRows() = updateCatalogRowsPipeline()
    private suspend fun runSerializedPostStartupRefresh() = runSerializedPostStartupRefreshPipeline()

    internal var posterStatusReconcileJob: Job? = null

    private fun schedulePosterStatusReconcile(rows: List<CatalogRow>) =
        schedulePosterStatusReconcilePipeline(rows)

    private fun reconcilePosterStatusObservers(rows: List<CatalogRow>) =
        reconcilePosterStatusObserversPipeline(rows)

    private fun navigateToDetail(itemId: String, itemType: String) {
        _uiState.update { it.copy(selectedItemId = itemId) }
    }

    private suspend fun enrichHeroItems(
        items: List<MetaPreview>,
        settings: TmdbSettings
    ): List<MetaPreview> = enrichHeroItemsPipeline(items, settings)

    private fun replaceGridHeroItems(
        gridItems: List<GridItem>,
        heroItems: List<MetaPreview>
    ): List<GridItem> = replaceGridHeroItemsPipeline(gridItems, heroItems)

    private fun heroEnrichmentSignature(items: List<MetaPreview>, settings: TmdbSettings): String =
        heroEnrichmentSignaturePipeline(items, settings)

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
        val nextState = HomeScreenFocusState(
            verticalScrollIndex = verticalScrollIndex,
            verticalScrollOffset = verticalScrollOffset,
            focusedRowIndex = focusedRowIndex,
            focusedItemIndex = focusedItemIndex,
            catalogRowScrollStates = catalogRowScrollStates,
            hasSavedFocus = true
        )
        if (_focusState.value == nextState) return
        _focusState.value = nextState
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

    override fun onCleared() {
        posterStatusReconcileJob?.cancel()
        startupDeferralWindowJob?.cancel()
        deferredStartupRefreshJob?.cancel()
        metadataEnrichmentFlushJob?.cancel()
        homeSnapshotPersistJob?.cancel()
        pendingTmdbEnrichmentByItemId.clear()
        pendingMetaEnrichmentByItemId.clear()
        pendingHomeSnapshotPersist = null
        cancelInFlightCatalogLoads()
        posterLibraryObserverJobs.values.forEach { it.cancel() }
        movieWatchedObserverJobs.values.forEach { it.cancel() }
        posterLibraryObserverJobs.clear()
        movieWatchedObserverJobs.clear()
        super.onCleared()
    }
}
