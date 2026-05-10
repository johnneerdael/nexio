package com.nexio.tv.ui.screens.home

import android.content.Context
import android.os.SystemClock
import android.util.Log
import androidx.compose.runtime.mutableStateMapOf
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.nexio.tv.core.artwork.PremiumArtworkInvalidationNotifier
import com.nexio.tv.core.integration.ActiveProfileSession
import com.nexio.tv.core.integration.ActiveRailTracker
import com.nexio.tv.core.integration.IntegrationHydrationCoordinator
import com.nexio.tv.core.integration.IntegrationOwnershipService
import com.nexio.tv.core.locale.AppLocaleResolver
import com.nexio.tv.core.integration.IntegrationPlaybackGate
import com.nexio.tv.core.integration.NoOpIntegrationHydrationCoordinator
import com.nexio.tv.core.integration.RailKeyFactory
import com.nexio.tv.core.metadata.router.MetadataRouterFacade
import com.nexio.tv.core.profile.ProfileBoundary
import com.nexio.tv.core.profile.ProfileManager
import com.nexio.tv.core.profile.ProfileModeRouter
import com.nexio.tv.core.trace.TraceMetadataEvents
import com.nexio.tv.core.tvdb.ProviderLocalizedMetadataResolver
import com.nexio.tv.core.tvdb.TvMetadataEnrichment
import com.nexio.tv.core.sync.AccountSyncRefreshNotifier
import com.nexio.tv.data.local.DebugSettingsDataStore
import com.nexio.tv.data.local.HydratedHomeOverlayStore
import com.nexio.tv.data.local.HomeCatalogSnapshotStore
import com.nexio.tv.data.local.KitsuCatalogPreferences
import com.nexio.tv.data.local.KitsuCatalogSettingsDataStore
import com.nexio.tv.data.local.LayoutPreferenceDataStore
import com.nexio.tv.data.local.MDBListCatalogPreferences
import com.nexio.tv.data.local.MDBListDiscoverySnapshotStore
import com.nexio.tv.data.local.MDBListSettingsDataStore
import com.nexio.tv.data.local.MetadataDiskCacheStore
import com.nexio.tv.data.local.PlayerSettingsDataStore
import com.nexio.tv.data.local.PersistedSyntheticCatalogGroup
import com.nexio.tv.data.local.SimklCatalogPreferences
import com.nexio.tv.data.local.SimklDiscoverySnapshotStore
import com.nexio.tv.data.local.SimklSettingsDataStore
import com.nexio.tv.data.local.SyntheticHomeCatalogStore
import com.nexio.tv.data.local.TrailerSettingsDataStore
import com.nexio.tv.data.local.TmdbCatalogPreferences
import com.nexio.tv.data.local.TmdbCatalogSettingsDataStore
import com.nexio.tv.data.local.TmdbSettingsDataStore
import com.nexio.tv.data.local.TraktCatalogPreferences
import com.nexio.tv.data.local.TraktDiscoverySnapshotStore
import com.nexio.tv.data.local.TraktSettingsDataStore
import com.nexio.tv.data.repository.CatalogInventoryRepository
import com.nexio.tv.data.repository.ContinueWatchingSnapshotService
import com.nexio.tv.data.repository.KitsuDiscoveryService
import com.nexio.tv.data.repository.TrackingProviderStateService
import com.nexio.tv.data.repository.MDBListRepository
import com.nexio.tv.data.repository.ResolvedDisplaySurfaceRepository
import com.nexio.tv.data.repository.SimklDiscoveryService
import com.nexio.tv.data.repository.MDBListDiscoveryService
import com.nexio.tv.data.repository.TmdbDiscoveryService
import com.nexio.tv.data.repository.TrackingScrobbleService
import com.nexio.tv.data.repository.TraktDiscoveryService
import com.nexio.tv.domain.model.Addon
import com.nexio.tv.domain.model.CatalogDescriptor
import com.nexio.tv.domain.model.CatalogRow
import com.nexio.tv.domain.model.HydratedHomeOverlay
import com.nexio.tv.domain.model.LibraryEntryInput
import com.nexio.tv.domain.model.MetaPreview
import com.nexio.tv.domain.model.homeDisplayItemKey
import com.nexio.tv.domain.model.RailHydrationState
import com.nexio.tv.domain.model.TmdbSettings
import com.nexio.tv.domain.repository.AddonRepository
import com.nexio.tv.domain.repository.CatalogRepository
import com.nexio.tv.domain.repository.LibraryRepository
import com.nexio.tv.domain.repository.MetaRepository
import com.nexio.tv.domain.repository.WatchProgressRepository
import com.nexio.tv.ui.screens.home.order.HomeRailOrderStore
import com.nexio.tv.ui.screensaver.PlaybackIdleGateState
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.mapNotNull
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
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
    internal val tmdbCatalogSettingsDataStore: TmdbCatalogSettingsDataStore,
    internal val kitsuCatalogSettingsDataStore: KitsuCatalogSettingsDataStore,
    internal val traktSettingsDataStore: TraktSettingsDataStore,
    internal val mdbListSettingsDataStore: MDBListSettingsDataStore,
    internal val simklSettingsDataStore: SimklSettingsDataStore,
    internal val playerSettingsDataStore: PlayerSettingsDataStore,
    internal val traktDiscoverySnapshotStore: TraktDiscoverySnapshotStore,
    internal val simklDiscoverySnapshotStore: SimklDiscoverySnapshotStore,
    internal val mdbListDiscoverySnapshotStore: MDBListDiscoverySnapshotStore,
    internal val continueWatchingSnapshotService: ContinueWatchingSnapshotService,
    internal val trackingScrobbleService: TrackingScrobbleService,
    internal val traktDiscoveryService: TraktDiscoveryService,
    internal val simklDiscoveryService: SimklDiscoveryService,
    internal val mdbListDiscoveryService: MDBListDiscoveryService,
    internal val tmdbDiscoveryService: TmdbDiscoveryService,
    internal val kitsuDiscoveryService: KitsuDiscoveryService,
    internal val mdbListRepository: MDBListRepository,
    internal val metadataRouterFacade: MetadataRouterFacade,
    internal val providerLocalizedMetadataResolver: ProviderLocalizedMetadataResolver,
    internal val trailerSettingsDataStore: TrailerSettingsDataStore,
    internal val accountSyncRefreshNotifier: AccountSyncRefreshNotifier,
    internal val catalogPriorityHydrationNotifier: com.nexio.tv.core.sync.CatalogPriorityHydrationNotifier,
    internal val homeCatalogSnapshotStore: HomeCatalogSnapshotStore,
    internal val homeCatalogRefreshCoordinator: HomeCatalogRefreshCoordinator,
    internal val debugSettingsDataStore: DebugSettingsDataStore,
    internal val metadataDiskCacheStore: MetadataDiskCacheStore,
    internal val syntheticHomeCatalogStore: SyntheticHomeCatalogStore,
    internal val homeRailOrderStore: HomeRailOrderStore,
    internal val profileManager: ProfileManager,
    internal val profileModeRouter: ProfileModeRouter,
    internal val profileBoundary: ProfileBoundary,
    internal val homeProfileSessionCoordinator: HomeProfileSessionCoordinator,
    internal val trackingProviderStateService: TrackingProviderStateService,
    internal val playbackIdleGateState: PlaybackIdleGateState,
    internal val resolvedDisplaySurfaceRepository: ResolvedDisplaySurfaceRepository,
    internal val catalogInventoryRepository: CatalogInventoryRepository,
    internal val projectionCache: ResolvedDisplayProjectionCache,
    internal val integrationPlaybackGate: IntegrationPlaybackGate = IntegrationPlaybackGate(),
    internal val activeRailTracker: ActiveRailTracker = ActiveRailTracker(),
    internal val integrationHydrationCoordinator: IntegrationHydrationCoordinator = NoOpIntegrationHydrationCoordinator,
    internal val integrationOwnershipService: IntegrationOwnershipService,
    internal val homeRailHydrationExecutor: HomeRailHydrationExecutor = NoOpHomeRailHydrationExecutor,
    internal val hydratedHomeOverlayStore: HydratedHomeOverlayStore,
    internal val homeHydrationCoordinator: HomeHydrationCoordinator,
    internal val traceEvents: TraceMetadataEvents,
    internal val premiumArtworkInvalidationNotifier: PremiumArtworkInvalidationNotifier = PremiumArtworkInvalidationNotifier(),
    internal val animeSeasonProjectionResolver: com.nexio.tv.core.anime.projection.AnimeSeasonProjectionResolver,
    internal val catalogRowMemo: CatalogRowMemo,
    @ApplicationContext internal val appContext: Context
) : ViewModel() {
    companion object {
        internal const val TAG = "HomeViewModel"
        private const val CONTINUE_WATCHING_WINDOW_MS = 30L * 24 * 60 * 60 * 1000
        private const val MAX_RECENT_PROGRESS_ITEMS = 300
        private const val MAX_NEXT_UP_LOOKUPS = 24
        private const val MAX_NEXT_UP_CONCURRENCY = 4
        internal const val CONTINUE_WATCHING_ENRICHMENT_CONCURRENCY = 2
        private const val MAX_CATALOG_LOAD_CONCURRENCY = 4
        internal const val HOME_SNAPSHOT_PERSIST_DEBOUNCE_MS = 5000L
        internal const val FOCUS_ENRICHMENT_BATCH_WINDOW_MS = 75L
        internal const val EXTERNAL_META_PREFETCH_FOCUS_DEBOUNCE_MS = 220L
        internal const val EXTERNAL_META_PREFETCH_ADJACENT_DEBOUNCE_MS = 120L
        internal const val MAX_POSTER_STATUS_OBSERVERS = 24
        private val PROFILE_SWITCH_DISK_SNAPSHOT_ALLOWED_REFRESH_REASONS = setOf(
            "account_sync",
            "foreground",
            "manual_retry",
            "priority_hydration",
            "dismiss_trakt_recommendation"
        )
        private val PROFILE_SWITCH_DISK_SNAPSHOT_BLOCKED_REFRESH_REASONS = setOf(
            "trakt_discovery",
            "trakt_pref_change",
            "simkl_discovery",
            "simkl_pref_change",
            "mdblist_discovery",
            "mdblist_pref_change",
            "mdblist_settings_change",
            "mdblist_settings_disabled",
            "tmdb_discovery",
            "tmdb_pref_change",
            "window_closed",
            "observe_disabled_home_catalogs",
            "observe_installed_addons"
        )
    }

    internal val _uiState = MutableStateFlow(HomeUiState())
    val uiState: StateFlow<HomeUiState> = _uiState.asStateFlow()
    internal val _fullCatalogRows = MutableStateFlow<List<CatalogRow>>(emptyList())
    val fullCatalogRows: StateFlow<List<CatalogRow>> = _fullCatalogRows.asStateFlow()
    // Post-truncation/layout-adjusted catalog rows used for rendering. Held outside
    // [HomeUiState] (Plan B small Task 26) to avoid Compose SlotTable retention pinning
    // both legacy CatalogRow instances and resolved Plan B rails simultaneously. UI consumes
    // this StateFlow directly; do not re-introduce a catalogRows field on HomeUiState.
    internal val _displayCatalogRows = MutableStateFlow<List<CatalogRow>>(emptyList())
    val displayCatalogRows: StateFlow<List<CatalogRow>> = _displayCatalogRows.asStateFlow()

    /**
     * Single-rail observation for `CatalogSeeAllScreen`. Delegates to
     * [CatalogInventoryRepository.observeRail] so the SeeAll screen does NOT
     * subscribe to the full inventory StateFlow — recomposes only when the
     * specific rail's content changes.
     *
     * Spec: docs/superpowers/specs/2026-05-10-catalog-inventory-repository-design.md
     */
    fun observeCatalogRail(key: String): Flow<CatalogRow?> =
        catalogInventoryRepository.observeRail(key)
    // Hero items held outside [HomeUiState] (mirrors small Task 26 catalogRows pattern) so
    // Compose's SnapshotStateRecord history does not pin prior MetaPreview lists alongside
    // the current set. UI consumes this StateFlow directly; do not re-introduce a
    // heroItems field on HomeUiState.
    internal val _displayHeroItems = MutableStateFlow<List<MetaPreview>>(emptyList())
    val displayHeroItems: StateFlow<List<MetaPreview>> = _displayHeroItems.asStateFlow()
    // Resolved hero projection (Plan B Task 9). Held outside [HomeUiState] for the
    // same reason as [_displayHeroItems] and [_displayCatalogRows] — Compose's
    // SnapshotStateRecord chain pins prior versions of every observed list field
    // (CLAUDE.md hard rule #2). Composable consumers collect this StateFlow
    // directly. Derived from observeHomeSurface(profileId) joined with
    // [_displayHeroItems] by itemKey; per-item projections are memoized via
    // [ResolvedDisplayProjectionCache.projectHero] and the outer list is
    // memoized via [ResolvedDisplayProjectionCache.internHeroList].
    internal val _resolvedHeroItems = MutableStateFlow<List<HeroDisplayItem>>(emptyList())
    val resolvedHeroItems: StateFlow<List<HeroDisplayItem>> = _resolvedHeroItems.asStateFlow()
    internal val hydratedHomeOverlaysByItemKey = MutableStateFlow<Map<String, HydratedHomeOverlay>>(emptyMap())

    // Not a feedback loop: the consumer at observeResolvedRailRows() writes only
    // resolvedRailRows back into _uiState; _displayCatalogRows is the catalogRows source
    // here and is written from the catalog pipeline only. Any future refactor that has
    // the consumer mutate _displayCatalogRows will re-introduce the loop.
    @OptIn(ExperimentalCoroutinesApi::class)
    private val resolvedRailRowsFlow: Flow<List<ResolvedRailRow>> =
        profileManager.activeProfileSession
            .map { it.profileId }
            .distinctUntilChanged()
            .flatMapLatest { profileId ->
                // flatMapLatest cancels the previous profile's surface flow on switch;
                // projectionCache is process-wide and gets rebound to the new profile's
                // active set on the next emission via retainOnly/retainOnlyRails. Stale
                // entries from the previous profile are evicted then. itemKey is
                // surface-scoped (homeDisplayItemKey of apiType+id), so cross-profile
                // collisions only matter if two profiles surface the same content —
                // in which case the cached projection is correct anyway.
                resolvedDisplaySurfaceRepository.observeHomeSurface(profileId)
            }
            .combine(_displayCatalogRows) { resolvedItems, catalogRows ->
                val byItemKey = resolvedItems.associateBy { it.itemKey }
                val activeItemKeys = mutableSetOf<String>()
                val activeCatalogIds = mutableSetOf<String>()
                val rails = catalogRows.map { row ->
                    val items = row.items.mapNotNull { meta ->
                        val itemKey = homeDisplayItemKey(meta.apiType, meta.id)
                        byItemKey[itemKey]?.let { resolved ->
                            activeItemKeys += itemKey
                            projectionCache.projectItem(resolved)
                        }
                    }
                    activeCatalogIds += row.catalogId
                    projectionCache.projectRail(row.catalogId, row.catalogName, items)
                }
                projectionCache.retainOnly(activeItemKeys)
                projectionCache.retainOnlyRails(activeCatalogIds)
                // Stabilise the OUTER list reference so observeResolvedRailRows's
                // `===` guard can short-circuit when content is unchanged. Without
                // this, every emission allocates a fresh `rails` list (even when
                // every element is reference-identical via projectRail), defeating
                // the guard and pushing identical content into _uiState — which in
                // turn defeats Compose stability skipping in the home tree.
                projectionCache.internRailsList(rails)
            }

    // Plan B Task 9 — resolved-hero counterpart to [resolvedRailRowsFlow]. Joins
    // the home surface's resolved items with the existing hero MetaPreview list
    // (already populated by the legacy hero pipeline at [_displayHeroItems]) by
    // itemKey, projects via [HeroDisplayItem.from] (memoized through
    // projectionCache.projectHero), and stabilises the outer list reference via
    // internHeroList. Profile switches are handled via flatMapLatest on the
    // active profile session — same pattern as resolvedRailRowsFlow; the cache
    // is rebound to the new profile's active item-key set on the next emission.
    @OptIn(ExperimentalCoroutinesApi::class)
    private val resolvedHeroItemsFlow: Flow<List<HeroDisplayItem>> =
        profileManager.activeProfileSession
            .map { it.profileId }
            .distinctUntilChanged()
            .flatMapLatest { profileId ->
                resolvedDisplaySurfaceRepository.observeHomeSurface(profileId)
            }
            .combine(_displayHeroItems) { resolvedItems, heroMetas ->
                // Hero list is small (~7 items). Avoid allocating a 1900-entry
                // associateBy() Map per emission of observeHomeSurface — that
                // alone added ~9 MB/sec of HashMap churn on top of the rails
                // flow doing the same thing. Build a tiny lookup keyed only by
                // the keys we actually need (max ~7 entries), then linear-scan
                // resolvedItems just for those.
                if (heroMetas.isEmpty()) {
                    projectionCache.retainOnlyHeroItems(emptySet())
                    return@combine projectionCache.internHeroList(emptyList())
                }
                val wantedKeys = HashSet<String>(heroMetas.size)
                for (i in heroMetas.indices) {
                    val meta = heroMetas[i]
                    wantedKeys += homeDisplayItemKey(meta.apiType, meta.id)
                }
                val resolvedByWantedKey = HashMap<String, com.nexio.tv.domain.model.ResolvedDisplayItem>(heroMetas.size)
                for (i in resolvedItems.indices) {
                    val item = resolvedItems[i]
                    if (item.itemKey in wantedKeys) {
                        resolvedByWantedKey[item.itemKey] = item
                        if (resolvedByWantedKey.size == wantedKeys.size) break
                    }
                }
                val activeKeys = HashSet<String>(heroMetas.size)
                val out = ArrayList<HeroDisplayItem>(heroMetas.size)
                for (i in heroMetas.indices) {
                    val meta = heroMetas[i]
                    val itemKey = homeDisplayItemKey(meta.apiType, meta.id)
                    val resolved = resolvedByWantedKey[itemKey] ?: continue
                    activeKeys += itemKey
                    out += projectionCache.projectHero(resolved)
                }
                projectionCache.retainOnlyHeroItems(activeKeys)
                projectionCache.internHeroList(out)
            }

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
    internal var hydratedHomeOverlayObserverJob: Job? = null
    internal var hydratedHomeOverlayObserverSignature: String? = null
    internal var hasRenderedFirstCatalog = false
    internal val catalogLoadSemaphore = Semaphore(MAX_CATALOG_LOAD_CONCURRENCY)
    internal var pendingCatalogLoads = 0
    internal val activeCatalogLoadJobs = mutableSetOf<Job>()
    internal var activeCatalogLoadSignature: String? = null
    internal var catalogLoadGeneration: Long = 0L
    internal var catalogsLoadInProgress: Boolean = false
    internal var lastCatalogComputationSignature: String? = null
    internal var lastCatalogOrderDiagnosticsSignature: String? = null
    internal val migrationAttempted: MutableSet<Int> = mutableSetOf()
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
    internal val pendingProviderEnrichmentByItemId = linkedMapOf<String, TvMetadataEnrichment>()
    internal val pendingTomatoesEnrichmentByItemId = linkedMapOf<String, Double>()
    internal val syntheticTomatoesOverridesByItemId = linkedMapOf<String, Double>()
    internal var metadataEnrichmentFlushJob: Job? = null
    internal var currentTmdbSettings: TmdbSettings = TmdbSettings()
    internal var traktDiscoverySnapshot: com.nexio.tv.data.repository.TraktDiscoverySnapshot =
        com.nexio.tv.data.repository.TraktDiscoverySnapshot()
    internal var persistedTraktDiscoverySnapshot: com.nexio.tv.data.repository.TraktDiscoverySnapshot =
        com.nexio.tv.data.repository.TraktDiscoverySnapshot()
    internal var traktCatalogPreferences: TraktCatalogPreferences = TraktCatalogPreferences()
    internal var activeProfileTraktAuthenticated: Boolean = false
    internal var activeProfileSimklAuthenticated: Boolean = false
    internal var simklDiscoverySnapshot: com.nexio.tv.data.repository.SimklDiscoverySnapshot =
        com.nexio.tv.data.repository.SimklDiscoverySnapshot()
    internal var persistedSimklDiscoverySnapshot: com.nexio.tv.data.repository.SimklDiscoverySnapshot =
        com.nexio.tv.data.repository.SimklDiscoverySnapshot()
    internal var simklCatalogPreferences: SimklCatalogPreferences = SimklCatalogPreferences()
    internal var mdbListDiscoverySnapshot: com.nexio.tv.data.repository.MDBListDiscoverySnapshot =
        com.nexio.tv.data.repository.MDBListDiscoverySnapshot()
    internal var persistedMDBListDiscoverySnapshot: com.nexio.tv.data.repository.MDBListDiscoverySnapshot =
        com.nexio.tv.data.repository.MDBListDiscoverySnapshot()
    internal var mdbListCatalogPreferences: MDBListCatalogPreferences = MDBListCatalogPreferences()
    internal var tmdbDiscoverySnapshot: com.nexio.tv.data.repository.TmdbDiscoverySnapshot =
        com.nexio.tv.data.repository.TmdbDiscoverySnapshot()
    internal var kitsuDiscoverySnapshot: com.nexio.tv.data.repository.KitsuDiscoverySnapshot =
        com.nexio.tv.data.repository.KitsuDiscoverySnapshot()
    internal var tmdbCatalogPreferences: TmdbCatalogPreferences =
        TmdbCatalogPreferences(enabledCatalogs = emptySet(), catalogOrder = emptyList())
    internal var kitsuCatalogPreferences: KitsuCatalogPreferences =
        KitsuCatalogPreferences(enabledCatalogs = emptySet(), catalogOrder = emptyList())
    internal var persistedTraktSyntheticGroups: List<PersistedSyntheticCatalogGroup> = emptyList()
    internal var persistedSimklSyntheticGroups: List<PersistedSyntheticCatalogGroup> = emptyList()
    internal var persistedMDBListSyntheticGroups: List<PersistedSyntheticCatalogGroup> = emptyList()
    internal var persistedKitsuSyntheticGroups: List<PersistedSyntheticCatalogGroup> = emptyList()
    internal var persistedTmdbSyntheticGroups: List<PersistedSyntheticCatalogGroup> = emptyList()
    internal var persistedTmdbSyntheticIncludeAdult: Boolean? = null
    internal var persistedTmdbSyntheticHideUnreleasedDigital: Boolean? = null
    internal var heroEnrichmentJob: Job? = null
    internal var continueWatchingEnrichmentJob: Job? = null
    internal var continueWatchingSnapshotVersion: Long = 0L
    internal var lastHeroEnrichmentSignature: String? = null
    internal var lastHeroEnrichedItems: List<MetaPreview> = emptyList()
    internal val trailerPreviewLoadingIds = mutableStateMapOf<String, Boolean>()
    internal val trailerPreviewNegativeCache = mutableStateMapOf<String, Boolean>()
    internal val trailerPreviewUrlsState = mutableStateMapOf<String, String>()
    internal val trailerPreviewAudioUrlsState = mutableStateMapOf<String, String>()
    internal val trailerPreviewUserAgentsState = mutableStateMapOf<String, String>()
    internal val trailerPreviewExternalUrlsState = mutableStateMapOf<String, String>()
    internal val trailerMetadataAvailableState = mutableStateMapOf<String, Boolean>()
    internal val trailerSelectedFallbackYtIdsState = mutableStateMapOf<String, String>()
    internal val trailerMetadataAvailabilityInFlightKeys = Collections.synchronizedSet(mutableSetOf<String>())
    internal val trailerMetadataAvailabilityJobs = Collections.synchronizedSet(mutableSetOf<Job>())
    internal var activeTrailerPreviewItemId: String? = null
    internal var trailerPreviewRequestVersion: Long = 0L
    internal var trailerPreviewJob: Job? = null
    internal var lastHomeTrailerSurfaceTraceSummary: HomeTrailerSurfaceTraceSummary? = null
    internal val trailerMetadataAvailabilitySemaphore = Semaphore(4)
    internal val prefetchedExternalMetaIds = Collections.synchronizedSet(mutableSetOf<String>())
    internal val prefetchedTomatoesIds = Collections.synchronizedSet(mutableSetOf<String>())
    internal val tomatoesEnrichmentInFlightIds = Collections.synchronizedSet(mutableSetOf<String>())
    internal val visibleHomeHydrationInFlightItemKeys = Collections.synchronizedSet(mutableSetOf<String>())
    internal var pendingFocusedItemForEnrichment: MetaPreview? = null
    internal var adjacentItemPrefetchJob: Job? = null
    internal var pendingAdjacentPrefetchItemId: String? = null
    internal val prefetchedTmdbIds = Collections.synchronizedSet(mutableSetOf<String>())
    internal var tmdbEnrichFocusJob: Job? = null
    internal var pendingTmdbEnrichItemId: String? = null
    /** Tracks per-item hydration state so RAIL_PREVIEW items are routed through MetadataRouter at
     *  most once per ViewModel instance. Seeded with [RailHydrationState.PREVIEW_ONLY] via
     *  [withDefault]. Updated to [RailHydrationState.HYDRATING] when a focus hydration coroutine
     *  is launched and to [RailHydrationState.CANONICAL_READY] or
     *  [RailHydrationState.HYDRATION_FAILED_USING_PREVIEW] when it completes. */
    internal val focusedItemHydrationStates: MutableMap<String, RailHydrationState> =
        mutableMapOf<String, RailHydrationState>().withDefault { RailHydrationState.PREVIEW_ONLY }
    internal val posterLibraryObserverJobs = mutableMapOf<String, Job>()
    internal val movieWatchedObserverJobs = mutableMapOf<String, Job>()
    internal var activePosterListPickerInput: LibraryEntryInput? = null
    @Volatile
    internal var restoredCatalogSnapshotActive: Boolean = false
    @Volatile
    internal var hasPersistedCatalogSnapshot: Boolean = false
    @Volatile
    internal var startupRefreshPending: Boolean = false
    @Volatile
    internal var traktDiscoveryRefreshInProgress: Boolean = false
    @Volatile
    internal var simklDiscoveryRefreshInProgress: Boolean = false
    @Volatile
    internal var mdbListDiscoveryRefreshInProgress: Boolean = false
    @Volatile
    internal var kitsuDiscoveryRefreshInProgress: Boolean = false
    @Volatile
    internal var tmdbDiscoveryRefreshInProgress: Boolean = false
    @Volatile
    internal var installedAddonsObserved: Boolean = false
    @Volatile
    internal var traktDiscoveryObserved: Boolean = false
    @Volatile
    internal var simklDiscoveryObserved: Boolean = false
    @Volatile
    internal var mdbListDiscoveryObserved: Boolean = false
    @Volatile
    internal var kitsuDiscoveryObserved: Boolean = false
    @Volatile
    internal var tmdbDiscoveryObserved: Boolean = false
    @Volatile
    internal var kitsuCatalogPreferencesObserved: Boolean = false
    @Volatile
    internal var tmdbCatalogPreferencesObserved: Boolean = false
    @Volatile
    internal var tmdbCredentialRefreshPending: Boolean = false
    @Volatile
    internal var lastForegroundRefreshMs: Long = 0L
    @Volatile
    internal var startupPerfTelemetryEnabled: Boolean = false
    internal var deferredStartupRefreshJob: Job? = null
    internal var pendingSerializedHomeRefreshReason: String? = null
    internal val syntheticCatalogStoreMutex = Mutex()
    internal val catalogRowsComputationMutex = Mutex()
    @Volatile
    internal var syntheticSnapshotBatchActive: Boolean = false
    @Volatile
    internal var profileSwitchDiskHydrationActive: Boolean = false
    @Volatile
    internal var suppressProfileSwitchRefreshUntilMs: Long = 0L
    @Volatile
    internal var profileSwitchDiskSnapshotActive: Boolean = false
    @Volatile
    internal var profileSwitchDiskSnapshotGeneration: Long = 0L
    @Volatile
    internal var homeProfileGeneration: Long = 0L
    internal val modernCarouselRowBuildCache = ModernCarouselRowBuildCache()
    internal val activeHomeProfileSession: StateFlow<HomeProfileSession> =
        homeProfileSessionCoordinator.start(viewModelScope, ::advanceHomeProfileGeneration)
    internal var activeHomeProfileSessionSnapshot: HomeProfileSession = activeHomeProfileSession.value

    val trailerPreviewUrls: Map<String, String>
        get() = trailerPreviewUrlsState
    val trailerPreviewAudioUrls: Map<String, String>
        get() = trailerPreviewAudioUrlsState
    val trailerPreviewUserAgents: Map<String, String>
        get() = trailerPreviewUserAgentsState
    val trailerPreviewExternalUrls: Map<String, String>
        get() = trailerPreviewExternalUrlsState
    val trailerPreviewLoadingItemIds: Set<String>
        get() = trailerPreviewLoadingIds.keys.toSet()
    val trailerPreviewNegativeCacheIds: Set<String>
        get() = trailerPreviewNegativeCache.keys.toSet()
    val trailerMetadataAvailableKeys: Set<String>
        get() = trailerMetadataAvailableState
            .filterValues { it }
            .keys
            .toSet()
    val trailerSelectedFallbackYtIds: Map<String, String>
        get() = trailerSelectedFallbackYtIdsState

    init {
        emitInitialHomeProfileSessionStarted()
        observeStartupPerfTelemetry()
        observePlaybackWorkGate()
        observeLocaleChangesForMetadata()
        observeProfileSwitches()
        observeTrackingProviderState()
        restorePersistedDiscoverySnapshots()
        restorePersistedSyntheticCatalogRows()
        restorePersistedCatalogSnapshot()
        observeLayoutPreferences()
        observeModernHomePresentation()
        observeTrailerAutoplaySettings()
        observePlayerSettings()
        observeExternalMetaPrefetchPreference()
        loadHomeCatalogOrderPreference()
        loadDisabledHomeCatalogPreference()
        observeActiveHomeRails()
        observeLibraryState()
        observeTmdbSettings()
        observeMDBListSettings()
        observeTraktCatalogPreferences()
        observeTraktDiscovery()
        observeSimklCatalogPreferences()
        observeSimklDiscovery()
        observeMDBListCatalogPreferences()
        observeMDBListDiscovery()
        observeKitsuCatalogPreferences()
        observeKitsuDiscovery()
        observeTmdbCatalogPreferences()
        observeTmdbDiscovery()
        observeAccountSyncRefresh()
        observePriorityHydration()
        observePremiumArtworkInvalidations()
        loadContinueWatching()
        observeInstalledAddons()
        observeResolvedRailRows()
        observeResolvedHeroItems()
    }

    private fun observeResolvedRailRows() {
        resolvedRailRowsFlow
            .onEach { rails ->
                _uiState.update { state ->
                    if (state.resolvedRailRows === rails) state else state.copy(resolvedRailRows = rails)
                }
            }
            .launchIn(viewModelScope)
    }

    // Plan B Task 9 — collects [resolvedHeroItemsFlow] into [_resolvedHeroItems].
    // The flow already memoizes the outer list via internHeroList, so this `===`
    // guard short-circuits on unchanged content and avoids waking up StateFlow
    // collectors. Mirrors observeResolvedRailRows() but writes to a dedicated
    // StateFlow rather than HomeUiState (CLAUDE.md hard rule #2).
    private fun observeResolvedHeroItems() {
        resolvedHeroItemsFlow
            .onEach { items ->
                if (_resolvedHeroItems.value !== items) {
                    _resolvedHeroItems.value = items
                }
            }
            .launchIn(viewModelScope)
    }

    private fun observePremiumArtworkInvalidations() {
        viewModelScope.launch {
            premiumArtworkInvalidationNotifier.events.collectLatest {
                invalidateHydratedHomeOverlayScope()
            }
        }
    }

    private fun observeActiveHomeRails() {
        viewModelScope.launch {
            _displayCatalogRows
                .map { rows ->
                    val profileId = profileManager.activeProfileId.value
                    rows.map { row ->
                        RailKeyFactory.homeCatalog(profileId, row.catalogId)
                    }.toSet()
                }
                .distinctUntilChanged()
                .collectLatest { activeRails ->
                    activeRailTracker.replaceActiveRails(activeRails)
                    withContext(Dispatchers.IO) {
                        val plannedRails = integrationHydrationCoordinator.hydrateNextBatch(
                            limit = activeRails.size.coerceAtLeast(1)
                        )
                        homeRailHydrationExecutor.hydrate(plannedRails)
                    }
                }
        }
    }

    private fun observeStartupPerfTelemetry() {
        viewModelScope.launch {
            debugSettingsDataStore.startupPerfTelemetryEnabled.collectLatest { enabled ->
                startupPerfTelemetryEnabled = enabled
            }
        }
    }

    private fun observeLocaleChangesForMetadata() {
        viewModelScope.launch {
            AppLocaleResolver.observeStoredLocaleTag(appContext)
                .drop(1)
                .collectLatest {
                    metaRepository.clearCache()
                    val profileId = profileManager.activeProfileId.value
                    integrationOwnershipService.syncRails(
                        RailKeyFactory.homeCatalogNamespace(profileId),
                        emptyList()
                    )
                    homeCatalogSnapshotStore.clear(profileId = profileId)
                    syntheticHomeCatalogStore.clear(profileId = profileId)
                    inMemoryHomeSnapshot = null
                    pendingRestoredCatalogSnapshot = null
                    pendingHomeSnapshotPersist = null
                    invalidateHydratedHomeOverlayScope()
                    persistedTraktSyntheticGroups = emptyList()
                    persistedSimklSyntheticGroups = emptyList()
                    persistedMDBListSyntheticGroups = emptyList()
                    clearPersistedTmdbSyntheticGroups()
                    watchProgressRepository.invalidateLocalizedMetadata()
                    continueWatchingSnapshotService.invalidateLocalizedMetadata()
                    logStartupPerf("metadata_language_changed")
                }
        }
    }

    private fun observeLayoutPreferences() = observeLayoutPreferencesPipeline()

    private fun observeModernHomePresentation() = observeModernHomePresentationPipeline()

    private fun observeTrailerAutoplaySettings() {
        viewModelScope.launch {
            trailerSettingsDataStore.settings.collectLatest { settings ->
                _uiState.update { state ->
                    state.copy(
                        homeTrailerAutoplayEnabled = settings.enabled,
                        homeTrailerAutoplayDelaySeconds = settings.delaySeconds
                    )
                }
            }
        }
    }

    private fun observePlayerSettings() {
        viewModelScope.launch {
            playerSettingsDataStore.playerSettings.collectLatest { settings ->
                _uiState.update { state ->
                    if (state.deterministicAutoplayEnabled == settings.deterministicAutoplayEnabled) {
                        state
                    } else {
                        state.copy(deterministicAutoplayEnabled = settings.deterministicAutoplayEnabled)
                    }
                }
            }
        }
    }

    private fun emitInitialHomeProfileSessionStarted() {
        val session = activeHomeProfileSessionSnapshot
        traceEvents.emitHomeProfileSessionStarted(
            profileId = session.profileId,
            sessionId = session.sessionId,
            generation = session.generation
        )
    }

    private fun observeTrackingProviderState() {
        viewModelScope.launch {
            trackingProviderStateService.state.collectLatest { state ->
                val authChanged = activeProfileTraktAuthenticated != state.traktAuthenticated ||
                    activeProfileSimklAuthenticated != state.simklAuthenticated
                activeProfileTraktAuthenticated = state.traktAuthenticated
                activeProfileSimklAuthenticated = state.simklAuthenticated
                if (authChanged) {
                    if (!state.traktAuthenticated) {
                        clearTraktHomeState("tracking_auth_changed")
                    }
                    scheduleUpdateCatalogRows()
                }
            }
        }
    }

    private fun observeProfileSwitches() {
        viewModelScope.launch {
            activeHomeProfileSession
                .drop(1)
                .mapNotNull { session ->
                    val previousSession = activeHomeProfileSessionSnapshot
                    val shouldResetHomeState = shouldResetProfileScopedHomeState(
                        previousSession = previousSession,
                        nextSession = session
                    )
                    if (shouldResetHomeState) {
                        traceEvents.emitHomeProfileSessionCancelled(
                            profileId = previousSession.profileId,
                            sessionId = previousSession.sessionId,
                            reason = "profile_session_replaced"
                        )
                    }
                    activeHomeProfileSessionSnapshot = session
                    if (shouldResetHomeState) {
                        traceEvents.emitHomeProfileSessionStarted(
                            profileId = session.profileId,
                            sessionId = session.sessionId,
                            generation = session.generation
                        )
                    }
                    session.takeIf { shouldResetHomeState }
                }
                .collectLatest { session ->
                    profileSwitchDiskHydrationActive = true
                    suppressProfileSwitchRefreshUntilMs = SystemClock.elapsedRealtime() + 5_000L
                    resetProfileScopedHomeState("home_session:${session.profileId}")
                    try {
                        continueWatchingSnapshotService.reloadPersistedSnapshotForActiveProfile(clearWhenMissing = true)
                        val hasDiskCacheState = loadActiveProfileDiskBackedHomeState(
                            reason = "home_session:${session.profileId}",
                            expectedGeneration = session.generation
                        )
                        if (isCurrentHomeProfileGeneration(session.generation)) {
                            if (hasDiskCacheState) {
                                activateProfileSwitchDiskSnapshotMode(session.generation)
                            } else {
                                clearProfileSwitchDiskSnapshotMode("profile_switch_no_disk_state")
                            }
                            reloadDiskCachedAddonCatalogsForActiveProfileSwitch(allowNetworkRefresh = !hasDiskCacheState)
                        }
                    } finally {
                        if (isCurrentHomeProfileGeneration(session.generation)) {
                            profileSwitchDiskHydrationActive = false
                            pendingSerializedHomeRefreshReason = null
                            startupRefreshPending = false
                        }
                    }
                }
        }
    }

    private fun observeExternalMetaPrefetchPreference() = observeExternalMetaPrefetchPreferencePipeline()

    fun onItemFocus(item: MetaPreview) = onItemFocusPipeline(item)
    fun preloadAdjacentItem(item: MetaPreview) = preloadAdjacentItemPipeline(item)
    fun requestTrailerPreview(item: MetaPreview) = requestTrailerPreviewPipeline(item)
    fun requestTrailerPreview(
        itemId: String,
        title: String,
        releaseInfo: String?,
        apiType: String,
        fallbackYtId: String? = null
    ) = requestTrailerPreviewPipeline(
        itemId = itemId,
        title = title,
        releaseInfo = releaseInfo,
        apiType = apiType,
        fallbackYtId = fallbackYtId
    )
    fun retryTrailerPreview(
        itemId: String,
        title: String,
        releaseInfo: String?,
        apiType: String,
        fallbackYtId: String? = null
    ) {
        trailerPreviewNegativeCache.remove(itemId)
        requestTrailerPreviewPipeline(
            itemId = itemId,
            title = title,
            releaseInfo = releaseInfo,
            apiType = apiType,
            fallbackYtId = fallbackYtId,
            forceRefresh = true
        )
    }

    private fun loadHomeCatalogOrderPreference() = loadHomeCatalogOrderPreferencePipeline()

    private fun loadDisabledHomeCatalogPreference() = loadDisabledHomeCatalogPreferencePipeline()

    private fun observeTmdbSettings() = observeTmdbSettingsPipeline()

    private fun observeMDBListSettings() = observeMDBListSettingsPipeline()

    private fun observeTraktCatalogPreferences() = observeTraktCatalogPreferencesPipeline()

    private fun observeTraktDiscovery() = observeTraktDiscoveryPipeline()

    private fun observeSimklCatalogPreferences() = observeSimklCatalogPreferencesPipeline()

    private fun observeSimklDiscovery() = observeSimklDiscoveryPipeline()

    private fun observeMDBListCatalogPreferences() = observeMDBListCatalogPreferencesPipeline()

    private fun observeMDBListDiscovery() = observeMDBListDiscoveryPipeline()

    private fun observeKitsuCatalogPreferences() = observeKitsuCatalogPreferencesPipeline()

    private fun observeKitsuDiscovery() = observeKitsuDiscoveryPipeline()

    private fun observeTmdbCatalogPreferences() = observeTmdbCatalogPreferencesPipeline()

    private fun observeTmdbDiscovery() = observeTmdbDiscoveryPipeline()

    private fun observeAccountSyncRefresh() = observeAccountSyncRefreshPipeline()

    private fun observePriorityHydration() = observePriorityHydrationPipeline()

    fun onForeground() = onForegroundPipeline()

    private suspend fun reloadDiskCachedAddonCatalogsForActiveProfileSwitch(allowNetworkRefresh: Boolean) =
        reloadDiskCachedAddonCatalogsForActiveProfileSwitchPipeline(allowNetworkRefresh = allowNetworkRefresh)

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
            HomeEvent.OnRetry -> viewModelScope.launch {
                clearProfileSwitchDiskSnapshotMode("manual_retry")
                loadAllCatalogs(addonsCache, forceReload = true)
            }
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

    fun toggleContinueWatchingLibrary(item: ContinueWatchingItem) =
        toggleContinueWatchingLibraryPipeline(item)

    private fun observeInstalledAddons() = observeInstalledAddonsPipeline()

    private suspend fun loadAllCatalogs(addons: List<Addon>, forceReload: Boolean = false) =
        loadAllCatalogsPipeline(addons, forceReload)

    private fun loadCatalog(addon: Addon, catalog: CatalogDescriptor, generation: Long) =
        loadCatalogPipeline(addon, catalog, generation)

    private fun loadMoreCatalogItems(catalogId: String, addonId: String, type: String) =
        loadMoreCatalogItemsPipeline(catalogId, addonId, type)

    internal fun effectiveCatalogLoadConcurrency(): Int {
        return MAX_CATALOG_LOAD_CONCURRENCY
    }

    internal fun runDeferredStartupRefreshIfNeeded(reason: String) {
        if (shouldSuppressProfileSwitchRefresh(reason)) return
        if (shouldBlockProfileSwitchDiskSnapshotRefresh(reason)) return
        if (!isNonPlaybackHomeWorkAllowed()) return
        runSerializedHomeRefreshIfNeeded(reason)
    }

    internal fun runSerializedHomeRefreshIfNeeded(reason: String) {
        if (shouldSuppressProfileSwitchRefresh(reason)) return
        if (shouldBlockProfileSwitchDiskSnapshotRefresh(reason)) return
        if (!isNonPlaybackHomeWorkAllowed()) {
            startupRefreshPending = false
            pendingSerializedHomeRefreshReason = null
            Log.d(TAG, "Skipping serialized home refresh during active playback reason=$reason")
            return
        }
        if (deferredStartupRefreshJob?.isActive == true) {
            Log.d(TAG, "Serialized home refresh already running; queueing reason=$reason")
            pendingSerializedHomeRefreshReason = reason
            return
        }
        val capturedGeneration = homeProfileGeneration
        val capturedProfileSession = profileManager.activeProfileSession.value
        deferredStartupRefreshJob = viewModelScope.launch {
            var nextReason: String? = reason
            while (
                nextReason != null &&
                isCurrentHomeProfileGeneration(capturedGeneration) &&
                profileManager.activeProfileSession.value == capturedProfileSession
            ) {
                val currentReason = nextReason
                if (!isNonPlaybackHomeWorkAllowed()) {
                    startupRefreshPending = false
                    pendingSerializedHomeRefreshReason = null
                    Log.d(TAG, "Stopping serialized home refresh during active playback reason=$currentReason")
                    return@launch
                }
                pendingSerializedHomeRefreshReason = null
                startupRefreshPending = true
                Log.d(TAG, "Serialized home refresh start reason=$currentReason")
                logStartupPerf("catalog_refresh_start", "reason=$currentReason")
                runSerializedPostStartupRefresh(
                    expectedGeneration = capturedGeneration,
                    expectedProfileSession = capturedProfileSession,
                    reason = currentReason
                )
                logStartupPerf("catalog_refresh_end", "reason=$currentReason")
                Log.d(TAG, "Serialized home refresh end reason=$currentReason")
                nextReason = if (
                    isCurrentHomeProfileGeneration(capturedGeneration) &&
                    profileManager.activeProfileSession.value == capturedProfileSession
                ) {
                    pendingSerializedHomeRefreshReason
                } else {
                    null
                }
            }
            if (
                isCurrentHomeProfileGeneration(capturedGeneration) &&
                profileManager.activeProfileSession.value == capturedProfileSession
            ) {
                runDeferredFocusedItemEnrichmentIfReady()
            }
        }
    }

    internal fun advanceHomeProfileGeneration(): Long {
        clearProfileSwitchDiskSnapshotMode("profile_generation_advance")
        homeProfileGeneration += 1L
        return homeProfileGeneration
    }

    internal fun isCurrentHomeSession(session: HomeProfileSession): Boolean {
        return activeHomeProfileSession.value.sessionId == session.sessionId &&
            isCurrentHomeProfileGeneration(session.generation)
    }

    internal fun isCurrentHomeProfileGeneration(generation: Long): Boolean {
        return homeProfileGeneration == generation
    }

    internal fun activateProfileSwitchDiskSnapshotMode(generation: Long) {
        profileSwitchDiskSnapshotActive = true
        profileSwitchDiskSnapshotGeneration = generation
        Log.d(TAG, "Profile switch disk snapshot mode active generation=$generation")
    }

    internal fun clearProfileSwitchDiskSnapshotMode(reason: String) {
        if (!profileSwitchDiskSnapshotActive) return
        Log.d(TAG, "Clearing profile switch disk snapshot mode reason=$reason")
        profileSwitchDiskSnapshotActive = false
        profileSwitchDiskSnapshotGeneration = 0L
    }

    internal fun isProfileSwitchDiskSnapshotModeActive(): Boolean {
        return profileSwitchDiskSnapshotActive &&
            profileSwitchDiskSnapshotGeneration == homeProfileGeneration
    }

    internal fun shouldBlockProfileSwitchDiskSnapshotRefresh(reason: String): Boolean {
        if (!isProfileSwitchDiskSnapshotModeActive()) return false
        if (reason in PROFILE_SWITCH_DISK_SNAPSHOT_ALLOWED_REFRESH_REASONS) {
            clearProfileSwitchDiskSnapshotMode("explicit_refresh:$reason")
            return false
        }
        val blocked = reason in PROFILE_SWITCH_DISK_SNAPSHOT_BLOCKED_REFRESH_REASONS ||
            reason !in PROFILE_SWITCH_DISK_SNAPSHOT_ALLOWED_REFRESH_REASONS
        if (blocked) {
            Log.d(TAG, "Blocking home refresh during profile switch disk snapshot mode reason=$reason")
        }
        return blocked
    }

    internal fun shouldSuppressProfileSwitchRefresh(reason: String): Boolean {
        if (reason == "account_sync") return false
        val active = profileSwitchDiskHydrationActive ||
            SystemClock.elapsedRealtime() < suppressProfileSwitchRefreshUntilMs
        if (active) {
            Log.d(TAG, "Suppressing home refresh during profile switch reason=$reason")
        }
        return active
    }

    internal fun shouldSuppressIncrementalHomeSnapshotPublish(): Boolean {
        return false
    }

    internal fun logStartupPerf(event: String, details: String? = null) {
        if (!startupPerfTelemetryEnabled) return
        val suffix = details?.let { " $it" }.orEmpty()
        Log.i("StartupPerf", "t=${SystemClock.elapsedRealtime()}ms event=$event$suffix")
    }

    internal fun scheduleUpdateCatalogRows(
        profileSessionForUpdate: ActiveProfileSession = profileManager.activeProfileSession.value
    ) {
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
                // Steady state (nothing actively loading): the pipeline can be invoked
                // by incidental triggers (focus moves, recomposition wake-ups, observer
                // re-emits) at high rate. Coalesce to 200 ms so the heavy
                // updateCatalogRowsPipeline runs at most ~5/sec instead of ~20/sec
                // when no actual new catalog content is in flight.
                else -> 200L
            }
            delay(debounceMs)
            updateCatalogRows(profileSessionForUpdate)
        }
    }

    internal suspend fun flushCatalogRowsForFirstPaint(
        profileSessionForUpdate: ActiveProfileSession = profileManager.activeProfileSession.value
    ) {
        if (shouldSuppressIncrementalHomeSnapshotPublish()) {
            return
        }
        catalogUpdateJob?.cancel()
        hasRenderedFirstCatalog = true
        updateCatalogRows(profileSessionForUpdate)
    }

    private suspend fun updateCatalogRows(profileSessionForUpdate: ActiveProfileSession) =
        updateCatalogRowsPipeline(profileSessionForUpdate)
    private suspend fun runSerializedPostStartupRefresh(
        expectedGeneration: Long,
        expectedProfileSession: ActiveProfileSession,
        reason: String
    ) = runSerializedPostStartupRefreshPipeline(expectedGeneration, expectedProfileSession, reason)

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
        deferredStartupRefreshJob?.cancel()
        metadataEnrichmentFlushJob?.cancel()
        trailerPreviewJob?.cancel()
        val trailerAvailabilityJobs = synchronized(trailerMetadataAvailabilityJobs) {
            trailerMetadataAvailabilityJobs.toList().also { trailerMetadataAvailabilityJobs.clear() }
        }
        trailerAvailabilityJobs.forEach { it.cancel() }
        homeSnapshotPersistJob?.cancel()
        hydratedHomeOverlayObserverJob?.cancel()
        pendingProviderEnrichmentByItemId.clear()
        pendingHomeSnapshotPersist = null
        cancelInFlightCatalogLoads()
        posterLibraryObserverJobs.values.forEach { it.cancel() }
        movieWatchedObserverJobs.values.forEach { it.cancel() }
        posterLibraryObserverJobs.clear()
        movieWatchedObserverJobs.clear()
        super.onCleared()
    }
}

internal fun shouldResetProfileScopedHomeState(
    previousSession: HomeProfileSession,
    nextSession: HomeProfileSession
): Boolean {
    return previousSession.profileSessionKey != nextSession.profileSessionKey
}
