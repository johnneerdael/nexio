package com.nexio.tv.data.repository

import android.os.SystemClock
import android.util.Log
import com.nexio.tv.core.poster.PosterRatingsUrlResolver
import com.nexio.tv.core.profile.ProfileManager
import com.nexio.tv.data.integration.railpreview.TraktRailPreviewMapper
import com.nexio.tv.data.integration.trakt.TraktIntegrationProvider
import com.nexio.tv.data.local.DebugSettingsDataStore
import com.nexio.tv.data.local.TraktDiscoverySnapshotStore
import com.nexio.tv.data.local.TraktCatalogIds
import com.nexio.tv.data.local.TraktCatalogPreferences
import com.nexio.tv.data.local.TraktSettingsDataStore
import com.nexio.tv.data.repository.trakt.TraktDiscoveryMutationAdapter
import com.nexio.tv.data.remote.dto.trakt.TraktCalendarEpisodeItemDto
import com.nexio.tv.data.remote.dto.trakt.TraktIdsDto
import com.nexio.tv.data.remote.dto.trakt.TraktListItemDto
import com.nexio.tv.data.remote.dto.trakt.TraktListSummaryDto
import com.nexio.tv.data.remote.dto.trakt.TraktMovieDto
import com.nexio.tv.data.remote.dto.trakt.TraktPopularListItemDto
import com.nexio.tv.data.remote.dto.trakt.TraktRecommendationItemDto
import com.nexio.tv.data.remote.dto.trakt.TraktShowDto
import com.nexio.tv.data.remote.dto.trakt.TraktTrendingMovieItemDto
import com.nexio.tv.data.remote.dto.trakt.TraktTrendingShowItemDto
import com.nexio.tv.domain.model.ContentType
import com.nexio.tv.domain.model.MetaPreview
import com.nexio.tv.domain.model.PosterShape
import com.nexio.tv.domain.model.RailItemPreview
import com.nexio.tv.domain.model.toLegacyRailItemPreviews
import com.nexio.tv.domain.model.toMetaPreview
import com.nexio.tv.data.trakt.outbox.TraktMutationOutboxCoordinator
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import javax.inject.Inject
import javax.inject.Singleton

data class TraktRecommendationRef(
    val recommendationKey: String,
    val type: String,
    val pathId: String
)

data class TraktPopularListOption(
    val key: String,
    val userId: String,
    val listId: String,
    val catalogIdBase: String,
    val title: String,
    val itemCount: Int,
    val source: TraktListSource = TraktListSource.POPULAR,
    val alternateKeys: List<String> = emptyList()
)

enum class TraktListSource {
    PERSONAL,
    POPULAR,
    SEARCH
}

data class TraktCustomListCatalog(
    val key: String,
    val catalogId: String,
    val catalogName: String,
    val type: ContentType,
    val itemRecords: List<RailItemPreview> = emptyList()
) {
    constructor(
        key: String,
        catalogId: String,
        catalogName: String,
        type: ContentType,
        items: List<MetaPreview>,
        fromLegacyItems: Boolean = true
    ) : this(
        key = key,
        catalogId = catalogId,
        catalogName = catalogName,
        type = type,
        itemRecords = items.toLegacyRailItemPreviews(railId = catalogId)
    )

    val items get() = itemRecords.map { it.toMetaPreview() }
}

fun legacyTraktCustomListCatalog(
    key: String,
    catalogId: String,
    catalogName: String,
    type: ContentType,
    items: List<MetaPreview>
): TraktCustomListCatalog = TraktCustomListCatalog(
    key = key,
    catalogId = catalogId,
    catalogName = catalogName,
    type = type,
    itemRecords = items.toLegacyRailItemPreviews(railId = catalogId)
)

data class TraktDiscoverySnapshot(
    val calendarItemRecords: List<RailItemPreview> = emptyList(),
    val recommendationMovieItemRecords: List<RailItemPreview> = emptyList(),
    val recommendationShowItemRecords: List<RailItemPreview> = emptyList(),
    val trendingMovieItemRecords: List<RailItemPreview> = emptyList(),
    val trendingShowItemRecords: List<RailItemPreview> = emptyList(),
    val popularMovieItemRecords: List<RailItemPreview> = emptyList(),
    val popularShowItemRecords: List<RailItemPreview> = emptyList(),
    val customListCatalogs: List<TraktCustomListCatalog> = emptyList(),
    val popularLists: List<TraktPopularListOption> = emptyList(),
    val recommendationRefsByStatusKey: Map<String, TraktRecommendationRef> = emptyMap(),
    val updatedAtMs: Long = 0L
) {
    constructor(
        calendarItems: List<MetaPreview> = emptyList(),
        recommendationMovieItems: List<MetaPreview> = emptyList(),
        recommendationShowItems: List<MetaPreview> = emptyList(),
        trendingMovieItems: List<MetaPreview> = emptyList(),
        trendingShowItems: List<MetaPreview> = emptyList(),
        popularMovieItems: List<MetaPreview> = emptyList(),
        popularShowItems: List<MetaPreview> = emptyList(),
        customListCatalogs: List<TraktCustomListCatalog> = emptyList(),
        popularLists: List<TraktPopularListOption> = emptyList(),
        recommendationRefsByStatusKey: Map<String, TraktRecommendationRef> = emptyMap(),
        updatedAtMs: Long = 0L,
        fromLegacyItems: Boolean = true
    ) : this(
        calendarItemRecords = calendarItems.toLegacyRailItemPreviews(railId = TraktCatalogIds.CALENDAR),
        recommendationMovieItemRecords = recommendationMovieItems.toLegacyRailItemPreviews(railId = TraktCatalogIds.RECOMMENDED_MOVIES),
        recommendationShowItemRecords = recommendationShowItems.toLegacyRailItemPreviews(railId = TraktCatalogIds.RECOMMENDED_SHOWS),
        trendingMovieItemRecords = trendingMovieItems.toLegacyRailItemPreviews(railId = TraktCatalogIds.TRENDING_MOVIES),
        trendingShowItemRecords = trendingShowItems.toLegacyRailItemPreviews(railId = TraktCatalogIds.TRENDING_SHOWS),
        popularMovieItemRecords = popularMovieItems.toLegacyRailItemPreviews(railId = TraktCatalogIds.POPULAR_MOVIES),
        popularShowItemRecords = popularShowItems.toLegacyRailItemPreviews(railId = TraktCatalogIds.POPULAR_SHOWS),
        customListCatalogs = customListCatalogs,
        popularLists = popularLists,
        recommendationRefsByStatusKey = recommendationRefsByStatusKey,
        updatedAtMs = updatedAtMs
    )

    val calendarItems get() = calendarItemRecords.map { it.toMetaPreview() }
    val recommendationMovieItems get() = recommendationMovieItemRecords.map { it.toMetaPreview() }
    val recommendationShowItems get() = recommendationShowItemRecords.map { it.toMetaPreview() }
    val trendingMovieItems get() = trendingMovieItemRecords.map { it.toMetaPreview() }
    val trendingShowItems get() = trendingShowItemRecords.map { it.toMetaPreview() }
    val popularMovieItems get() = popularMovieItemRecords.map { it.toMetaPreview() }
    val popularShowItems get() = popularShowItemRecords.map { it.toMetaPreview() }
}

internal fun shouldPreserveLastNonEmptyTraktDiscoverySnapshot(
    previousSnapshot: TraktDiscoverySnapshot,
    refreshedSnapshot: TraktDiscoverySnapshot,
    prefs: TraktCatalogPreferences
): Boolean {
    if (!prefs.hasExpectedDiscoveryBackedTraktRails()) return false
    if (!previousSnapshot.hasConfiguredDiscoveryContent(prefs)) return false
    return !refreshedSnapshot.hasConfiguredDiscoveryContent(prefs)
}

private fun TraktCatalogPreferences.hasExpectedDiscoveryBackedTraktRails(): Boolean {
    return enabledCatalogs.any { it != TraktCatalogIds.UP_NEXT } || selectedPopularListKeys.isNotEmpty()
}

private fun TraktDiscoverySnapshot.hasConfiguredDiscoveryContent(
    prefs: TraktCatalogPreferences
): Boolean {
    if (TraktCatalogIds.TRENDING_MOVIES in prefs.enabledCatalogs && trendingMovieItemRecords.isNotEmpty()) return true
    if (TraktCatalogIds.TRENDING_SHOWS in prefs.enabledCatalogs && trendingShowItemRecords.isNotEmpty()) return true
    if (TraktCatalogIds.POPULAR_MOVIES in prefs.enabledCatalogs && popularMovieItemRecords.isNotEmpty()) return true
    if (TraktCatalogIds.POPULAR_SHOWS in prefs.enabledCatalogs && popularShowItemRecords.isNotEmpty()) return true
    if (TraktCatalogIds.RECOMMENDED_MOVIES in prefs.enabledCatalogs && recommendationMovieItemRecords.isNotEmpty()) return true
    if (TraktCatalogIds.RECOMMENDED_SHOWS in prefs.enabledCatalogs && recommendationShowItemRecords.isNotEmpty()) return true
    if (TraktCatalogIds.CALENDAR in prefs.enabledCatalogs && calendarItemRecords.isNotEmpty()) return true
    if (prefs.selectedPopularListKeys.isEmpty()) return false
    return customListCatalogs.any { catalog ->
        catalog.key in prefs.selectedPopularListKeys && catalog.itemRecords.isNotEmpty()
    }
}

@Singleton
@OptIn(ExperimentalCoroutinesApi::class)
class TraktDiscoveryService @Inject constructor(
    private val traktAuthService: TraktRepositoryAuthGateway,
    private val traktIntegrationProvider: TraktIntegrationProvider,
    private val traktSettingsDataStore: TraktSettingsDataStore,
    private val posterRatingsUrlResolver: PosterRatingsUrlResolver,
    private val snapshotStore: TraktDiscoverySnapshotStore,
    private val debugSettingsDataStore: DebugSettingsDataStore,
    private val traktMutationOutboxCoordinator: TraktMutationOutboxCoordinator,
    private val profileManager: ProfileManager,
    private val traktProgressService: TraktProgressService
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val rawProfileSnapshots = MutableStateFlow<Map<Int, TraktDiscoverySnapshot>>(emptyMap())
    private val profileSnapshots = MutableStateFlow<Map<Int, TraktDiscoverySnapshot>>(emptyMap())
    private val refreshMutex = Mutex()
    private val lastRefreshByProfile = mutableMapOf<Int, Long>()
    private var lastActivitiesFingerprint: String? = null
    private val railPreviewMapper = TraktRailPreviewMapper()

    private val minRefreshIntervalMs = 30_000L
    private val fallbackRefreshIntervalMs = 6L * 60 * 60 * 1_000L
    private val maxItemsPerRail = 20
    @Volatile
    private var activePosterProvider: PosterRatingsUrlResolver.ActiveProvider? = null

    private fun snapshotForProfile(profileId: Int): TraktDiscoverySnapshot =
        profileSnapshots.value[profileId] ?: TraktDiscoverySnapshot()

    private fun rawSnapshotForProfile(profileId: Int): TraktDiscoverySnapshot =
        rawProfileSnapshots.value[profileId] ?: TraktDiscoverySnapshot()

    private fun setProfileSnapshot(profileId: Int, snapshot: TraktDiscoverySnapshot) {
        profileSnapshots.value = profileSnapshots.value + (profileId to snapshot)
    }

    private fun setRawProfileSnapshot(profileId: Int, snapshot: TraktDiscoverySnapshot) {
        rawProfileSnapshots.value = rawProfileSnapshots.value + (profileId to snapshot)
    }

    init {
        scope.launch {
            val profileId = profileManager.activeProfileId.value
            snapshotStore.read(profileId = profileId)?.let { persisted ->
                setRawProfileSnapshot(profileId, persisted)
                setProfileSnapshot(profileId, persisted)
                lastRefreshByProfile[profileId] = persisted.updatedAtMs
            }
        }
        scope.launch {
            combine(
                rawProfileSnapshots,
                traktSettingsDataStore.dismissedRecommendationKeys
            ) { snapshots, dismissedKeys ->
                val profileId = profileManager.activeProfileId.value
                val snapshot = snapshots[profileId] ?: TraktDiscoverySnapshot()
                if (dismissedKeys.isEmpty()) {
                    profileId to snapshot
                } else {
                    val filteredMovieItems = snapshot.recommendationMovieItemRecords.filterNot { item ->
                        recommendationStatusKey(item.toMetaPreview().id, item.itemType.toApiString()) in dismissedKeys
                    }
                    val filteredShowItems = snapshot.recommendationShowItemRecords.filterNot { item ->
                        recommendationStatusKey(item.toMetaPreview().id, item.itemType.toApiString()) in dismissedKeys
                    }
                    val activeKeys = (filteredMovieItems + filteredShowItems)
                        .map { recommendationStatusKey(it.toMetaPreview().id, it.itemType.toApiString()) }
                        .toSet()
                    profileId to snapshot.copy(
                        recommendationMovieItemRecords = filteredMovieItems,
                        recommendationShowItemRecords = filteredShowItems,
                        recommendationRefsByStatusKey = snapshot.recommendationRefsByStatusKey
                            .filterKeys { it in activeKeys }
                    )
                }
            }.collect { (profileId, filtered) ->
                if (filtered != snapshotForProfile(profileId)) {
                    setProfileSnapshot(profileId, filtered)
                }
            }
        }
    }

    fun observeSnapshot(autoRefreshOnStart: Boolean = true): Flow<TraktDiscoverySnapshot> {
        return profileManager.activeProfileId.flatMapLatest { profileId ->
            profileSnapshots
                .map { snapshots -> snapshots[profileId] ?: TraktDiscoverySnapshot() }
                .onStart {
                    var hadPersistedSnapshot = false
                    snapshotStore.read(profileId = profileId)?.let { persisted ->
                        hadPersistedSnapshot = true
                        setRawProfileSnapshot(profileId, persisted)
                        setProfileSnapshot(profileId, persisted)
                        lastRefreshByProfile[profileId] = persisted.updatedAtMs
                    }
                    if (autoRefreshOnStart && !hadPersistedSnapshot) {
                        scope.launch {
                            runCatching { ensureFresh(force = false, profileId = profileId) }
                                .onFailure { error ->
                                    Log.w("TraktDiscovery", "Failed to refresh Trakt discovery snapshot", error)
                                }
                        }
                    }
                }
        }
    }

    suspend fun priorityFetch() {
        ensureFresh(force = true)
    }

    suspend fun ensureFresh(
        force: Boolean,
        profileId: Int = profileManager.activeProfileId.value
    ) = withContext(Dispatchers.IO) {
        if (!traktAuthService.getCurrentAuthState().isAuthenticated) {
            setRawProfileSnapshot(profileId, TraktDiscoverySnapshot())
            setProfileSnapshot(profileId, TraktDiscoverySnapshot())
            snapshotStore.clear(profileId = profileId)
            return@withContext
        }
        activePosterProvider = posterRatingsUrlResolver.getActiveProvider()

        val now = System.currentTimeMillis()
        val lastRefreshMs = lastRefreshByProfile[profileId] ?: 0L
        if (now - lastRefreshMs < minRefreshIntervalMs && rawSnapshotForProfile(profileId).updatedAtMs > 0L) {
            return@withContext
        }

        refreshMutex.withLock {
            val lockedNow = System.currentTimeMillis()
            val lockedLastRefreshMs = lastRefreshByProfile[profileId] ?: 0L
            val snapshot = rawSnapshotForProfile(profileId)
            if (lockedNow - lockedLastRefreshMs < minRefreshIntervalMs && snapshot.updatedAtMs > 0L) {
                return@withLock
            }
            val snapshotAgeMs = if (snapshot.updatedAtMs > 0L) {
                (lockedNow - snapshot.updatedAtMs).coerceAtLeast(0L)
            } else {
                Long.MAX_VALUE
            }
            val fallbackRefreshDue = snapshotAgeMs >= fallbackRefreshIntervalMs

            if (!force && !hasActivitiesChanged() && !fallbackRefreshDue) {
                lastRefreshByProfile[profileId] = lockedNow
                return@withLock
            }

            val prefs = traktSettingsDataStore.catalogPreferences.first()

            val calendar = if (TraktCatalogIds.CALENDAR in prefs.enabledCatalogs) {
                fetchCalendarShows(days = 7)
            } else {
                emptyList()
            }

            val recommendationMovies = if (TraktCatalogIds.RECOMMENDED_MOVIES in prefs.enabledCatalogs) {
                fetchRecommendations(type = "movies")
            } else {
                emptyList()
            }
            val recommendationShows = if (TraktCatalogIds.RECOMMENDED_SHOWS in prefs.enabledCatalogs) {
                fetchRecommendations(type = "shows")
            } else {
                emptyList()
            }

            val trendingMovies = if (TraktCatalogIds.TRENDING_MOVIES in prefs.enabledCatalogs) {
                fetchTrendingMovies()
            } else {
                emptyList()
            }
            val trendingShows = if (TraktCatalogIds.TRENDING_SHOWS in prefs.enabledCatalogs) {
                fetchTrendingShows()
            } else {
                emptyList()
            }
            val popularMovies = if (TraktCatalogIds.POPULAR_MOVIES in prefs.enabledCatalogs) {
                fetchPopularMovies()
            } else {
                emptyList()
            }
            val popularShows = if (TraktCatalogIds.POPULAR_SHOWS in prefs.enabledCatalogs) {
                fetchPopularShows()
            } else {
                emptyList()
            }

            val popularLists = mergeTraktListOptions(
                personal = fetchPersonalListOptions(),
                popular = fetchPopularLists()
            )
            val selectedCustomCatalogs = fetchSelectedPopularListCatalogs(
                selectedKeys = prefs.selectedPopularListKeys,
                options = popularLists
            )

            val refs = buildMap {
                (recommendationMovies + recommendationShows).forEach { pair ->
                    val previewMeta = pair.first.toMetaPreview()
                    val statusKey = recommendationStatusKey(previewMeta.id, previewMeta.apiType)
                    put(statusKey, pair.second)
                }
            }

            val refreshedSnapshot = TraktDiscoverySnapshot(
                calendarItemRecords = calendar,
                recommendationMovieItemRecords = recommendationMovies.map { it.first },
                recommendationShowItemRecords = recommendationShows.map { it.first },
                trendingMovieItemRecords = trendingMovies,
                trendingShowItemRecords = trendingShows,
                popularMovieItemRecords = popularMovies,
                popularShowItemRecords = popularShows,
                customListCatalogs = selectedCustomCatalogs,
                popularLists = popularLists,
                recommendationRefsByStatusKey = refs,
                updatedAtMs = System.currentTimeMillis()
            )
            val snapshotToPersist = if (
                shouldPreserveLastNonEmptyTraktDiscoverySnapshot(
                    previousSnapshot = snapshot,
                    refreshedSnapshot = refreshedSnapshot,
                    prefs = prefs
                )
            ) {
                Log.d("TraktDiscovery", "Preserving previous non-empty snapshot after empty refresh")
                snapshot
            } else {
                refreshedSnapshot
            }
            setRawProfileSnapshot(profileId, snapshotToPersist)
            setProfileSnapshot(profileId, snapshotToPersist)
            snapshotStore.write(snapshotToPersist, profileId = profileId)
            lastRefreshByProfile[profileId] = System.currentTimeMillis()
        }
    }

    suspend fun dismissRecommendation(ref: TraktRecommendationRef) = withContext(Dispatchers.IO) {
        runCatching {
            traktMutationOutboxCoordinator.enqueueAndDrain(
                TraktDiscoveryMutationAdapter.buildDismissRecommendationEnvelope(
                    ref = ref,
                    profileId = traktIntegrationProvider.currentTraktProfileId()
                )
            )
        }.onFailure { error ->
            Log.w("TraktDiscoveryService", "Failed to enqueue recommendation dismissal: ${error.message}")
        }
    }

    private suspend fun hasActivitiesChanged(): Boolean {
        val body = traktProgressService.getRecentActivities(maxAgeMs = 10_000L)
            ?: return true
        val fingerprint = listOfNotNull(
            body.all,
            body.movies?.watchedAt,
            body.movies?.pausedAt,
            body.episodes?.watchedAt,
            body.episodes?.pausedAt,
            body.lists?.updatedAt,
            body.watchlist?.updatedAt
        ).joinToString("|")

        val changed = fingerprint != lastActivitiesFingerprint
        lastActivitiesFingerprint = fingerprint
        return changed
    }

    private suspend fun fetchRecommendations(type: String): List<Pair<RailItemPreview, TraktRecommendationRef>> {
        val generatedAtMs = System.currentTimeMillis()
        val railId = if (type == "movies") {
            TraktCatalogIds.RECOMMENDED_MOVIES
        } else {
            TraktCatalogIds.RECOMMENDED_SHOWS
        }
        return traktIntegrationProvider.fetchRecommendations(
            type = type,
            limit = maxItemsPerRail
        ).orEmpty()
            .mapIndexedNotNull { index, dto ->
                mapRecommendationItem(
                    dto = dto,
                    railId = railId,
                    position = index,
                    generatedAtMs = generatedAtMs
                )
            }
            .take(maxItemsPerRail)
    }

    private suspend fun fetchCalendarShows(days: Int): List<RailItemPreview> {
        val startDate = LocalDate.now().format(DateTimeFormatter.ISO_DATE)
        val generatedAtMs = System.currentTimeMillis()
        return traktIntegrationProvider.fetchCalendarShows(
            startDate = startDate,
            days = days
        ).orEmpty()
            .mapIndexedNotNull { index, dto ->
                mapCalendarEpisodeItem(
                    dto = dto,
                    railId = TraktCatalogIds.CALENDAR,
                    position = index,
                    generatedAtMs = generatedAtMs
                )
            }
            .take(maxItemsPerRail)
    }

    private suspend fun fetchTrendingMovies(): List<RailItemPreview> {
        val generatedAtMs = System.currentTimeMillis()
        return mapTrendingMovieRailPreviews(
            railId = TraktCatalogIds.TRENDING_MOVIES,
            items = traktIntegrationProvider.fetchTrendingMovies(limit = maxItemsPerRail).orEmpty(),
            generatedAtMs = generatedAtMs
        )
            .take(maxItemsPerRail)
    }

    private suspend fun fetchTrendingShows(): List<RailItemPreview> {
        val generatedAtMs = System.currentTimeMillis()
        return mapTrendingShowRailPreviews(
            railId = TraktCatalogIds.TRENDING_SHOWS,
            items = traktIntegrationProvider.fetchTrendingShows(limit = maxItemsPerRail).orEmpty(),
            generatedAtMs = generatedAtMs
        )
            .take(maxItemsPerRail)
    }

    private fun mapTrendingMovieRailPreviews(
        railId: String,
        items: List<TraktTrendingMovieItemDto>,
        generatedAtMs: Long
    ): List<RailItemPreview> {
        return items.mapIndexedNotNull { index, item ->
            railPreviewMapper.mapTrendingMovie(
                railId = railId,
                item = item,
                position = index,
                generatedAtMs = generatedAtMs
            )
        }
    }

    private fun mapTrendingShowRailPreviews(
        railId: String,
        items: List<TraktTrendingShowItemDto>,
        generatedAtMs: Long
    ): List<RailItemPreview> {
        return items.mapIndexedNotNull { index, item ->
            railPreviewMapper.mapTrendingShow(
                railId = railId,
                item = item,
                position = index,
                generatedAtMs = generatedAtMs
            )
        }
    }

    private suspend fun fetchPopularMovies(): List<RailItemPreview> {
        val generatedAtMs = System.currentTimeMillis()
        return traktIntegrationProvider.fetchPopularMovies(limit = maxItemsPerRail).orEmpty()
            .mapIndexedNotNull { index, movie ->
                mapMovieDto(
                    movie = movie,
                    railId = TraktCatalogIds.POPULAR_MOVIES,
                    position = index,
                    generatedAtMs = generatedAtMs
                )
            }
            .take(maxItemsPerRail)
    }

    private suspend fun fetchPopularShows(): List<RailItemPreview> {
        val generatedAtMs = System.currentTimeMillis()
        return traktIntegrationProvider.fetchPopularShows(limit = maxItemsPerRail).orEmpty()
            .mapIndexedNotNull { index, show ->
                mapShowDto(
                    show = show,
                    railId = TraktCatalogIds.POPULAR_SHOWS,
                    position = index,
                    generatedAtMs = generatedAtMs
                )
            }
            .take(maxItemsPerRail)
    }

    private suspend fun fetchPopularLists(): List<TraktPopularListOption> {
        return traktIntegrationProvider.fetchPopularLists(
            page = 1,
            limit = 30
        ).orEmpty()
            .mapNotNull { dto -> mapPopularListOption(dto) }
    }

    private suspend fun fetchPersonalListOptions(): List<TraktPopularListOption> {
        val authState = traktAuthService.getCurrentAuthState()
        val ownerKey = authState.userSlug?.takeIf { it.isNotBlank() }
            ?: authState.username?.takeIf { it.isNotBlank() }
            ?: ME_PATH
        return traktIntegrationProvider.fetchUserLists(ME_PATH).orEmpty()
            .filter { it.type.equals("personal", ignoreCase = true) }
            .mapNotNull { dto -> mapPersonalListOption(dto, ownerKey) }
    }

    private fun mergeTraktListOptions(
        personal: List<TraktPopularListOption>,
        popular: List<TraktPopularListOption>
    ): List<TraktPopularListOption> {
        return (personal + popular)
            .distinctBy { it.key }
            .sortedWith(
                compareBy<TraktPopularListOption> { it.source != TraktListSource.PERSONAL }
                    .thenBy { it.title.lowercase() }
            )
    }

    private suspend fun fetchSelectedPopularListCatalogs(
        selectedKeys: Set<String>,
        options: List<TraktPopularListOption>
    ): List<TraktCustomListCatalog> {
        if (selectedKeys.isEmpty()) return emptyList()

        val byKey = options.flatMap { option ->
            (listOf(option.key) + option.alternateKeys).map { key -> key to option }
        }.toMap()
        return selectedKeys.flatMap { key ->
            val option = byKey[key] ?: parseListKeyFallback(key)
            if (option != null) {
                fetchPopularListCatalog(option, selectedKey = key)
            } else {
                emptyList()
            }
        }
    }

    private suspend fun fetchPopularListCatalog(
        option: TraktPopularListOption,
        selectedKey: String = option.key
    ): List<TraktCustomListCatalog> {
        val movieItems = traktIntegrationProvider.fetchUserListItems(
            id = option.userId,
            listId = option.listId,
            type = "movies"
        ).orEmpty()

        val showItems = traktIntegrationProvider.fetchUserListItems(
            id = option.userId,
            listId = option.listId,
            type = "shows"
        ).orEmpty()
        val seasonItems = traktIntegrationProvider.fetchUserListItems(
            id = option.userId,
            listId = option.listId,
            type = "seasons"
        ).orEmpty()
        val episodeItems = traktIntegrationProvider.fetchUserListItems(
            id = option.userId,
            listId = option.listId,
            type = "episodes"
        ).orEmpty()

        val generatedAtMs = System.currentTimeMillis()
        val movieRailId = "${option.catalogIdBase}_movies"
        val showRailId = "${option.catalogIdBase}_shows"
        val movies = movieItems.mapIndexedNotNull { index, item ->
            mapListMovieItem(
                item = item,
                railId = movieRailId,
                position = index,
                generatedAtMs = generatedAtMs
            )
        }.take(maxItemsPerRail)
        val shows = (showItems + seasonItems + episodeItems)
            .mapIndexedNotNull { index, item ->
                mapListShowItem(
                    item = item,
                    railId = showRailId,
                    position = index,
                    generatedAtMs = generatedAtMs
                )
            }
            .distinctBy { it.sourceItemId }
            .take(maxItemsPerRail)

        val rows = mutableListOf<TraktCustomListCatalog>()
        if (movies.isNotEmpty()) {
            rows += TraktCustomListCatalog(
                key = selectedKey,
                catalogId = movieRailId,
                catalogName = "${option.title} (Movies)",
                type = ContentType.MOVIE,
                itemRecords = movies
            )
        }
        if (shows.isNotEmpty()) {
            rows += TraktCustomListCatalog(
                key = selectedKey,
                catalogId = showRailId,
                catalogName = "${option.title} (Shows)",
                type = ContentType.SERIES,
                itemRecords = shows
            )
        }
        return rows
    }

    private fun mapRecommendationItem(
        dto: TraktRecommendationItemDto,
        railId: String,
        position: Int,
        generatedAtMs: Long
    ): Pair<RailItemPreview, TraktRecommendationRef>? {
        val movie = dto.movie
        val show = dto.show
        return if (movie != null) {
            val preview = mapMovieDto(
                movie = movie,
                railId = railId,
                position = position,
                generatedAtMs = generatedAtMs
            ) ?: return null
            val pathId = movie.ids?.trakt?.toString()
                ?: movie.ids?.slug
                ?: movie.ids?.imdb
                ?: movie.ids?.tmdb?.toString()
                ?: return null
            val previewMeta = preview.toMetaPreview()
            val key = recommendationStatusKey(previewMeta.id, previewMeta.apiType)
            preview to TraktRecommendationRef(
                recommendationKey = key,
                type = "movies",
                pathId = pathId
            )
        } else if (show != null) {
            val preview = mapShowDto(
                show = show,
                railId = railId,
                position = position,
                generatedAtMs = generatedAtMs
            ) ?: return null
            val pathId = show.ids?.trakt?.toString()
                ?: show.ids?.slug
                ?: show.ids?.imdb
                ?: show.ids?.tmdb?.toString()
                ?: return null
            val previewMeta = preview.toMetaPreview()
            val key = recommendationStatusKey(previewMeta.id, previewMeta.apiType)
            preview to TraktRecommendationRef(
                recommendationKey = key,
                type = "shows",
                pathId = pathId
            )
        } else {
            null
        }
    }

    private fun mapCalendarEpisodeItem(
        dto: TraktCalendarEpisodeItemDto,
        railId: String,
        position: Int,
        generatedAtMs: Long
    ): RailItemPreview? {
        return railPreviewMapper.mapCalendarEpisode(
            railId = railId,
            item = dto,
            position = position,
            generatedAtMs = generatedAtMs
        )
    }

    private fun mapMovieDto(
        movie: TraktMovieDto,
        railId: String,
        position: Int,
        generatedAtMs: Long
    ): RailItemPreview? {
        return railPreviewMapper.mapMovie(
            railId = railId,
            movie = movie,
            position = position,
            generatedAtMs = generatedAtMs
        )
    }

    private fun mapShowDto(
        show: TraktShowDto,
        railId: String,
        position: Int,
        generatedAtMs: Long
    ): RailItemPreview? {
        return railPreviewMapper.mapShow(
            railId = railId,
            show = show,
            position = position,
            generatedAtMs = generatedAtMs
        )
    }

    private fun mapListMovieItem(
        item: TraktListItemDto,
        railId: String,
        position: Int,
        generatedAtMs: Long
    ): RailItemPreview? {
        return item.movie?.let {
            mapMovieDto(
                movie = it,
                railId = railId,
                position = position,
                generatedAtMs = generatedAtMs
            )
        }
    }

    private fun mapListShowItem(
        item: TraktListItemDto,
        railId: String,
        position: Int,
        generatedAtMs: Long
    ): RailItemPreview? {
        return item.show?.let {
            mapShowDto(
                show = it,
                railId = railId,
                position = position,
                generatedAtMs = generatedAtMs
            )
        }
    }

    private fun mapPopularListOption(dto: TraktPopularListItemDto): TraktPopularListOption? {
        val list = dto.list ?: return null
        val userId = dto.user?.ids?.slug
            ?: dto.user?.username
            ?: list.user?.ids?.slug
            ?: list.user?.username
            ?: return null
        val listId = list.ids?.slug
            ?: list.ids?.trakt?.toString()
            ?: return null
        val key = "$userId/$listId"
        return TraktPopularListOption(
            key = key,
            userId = userId,
            listId = listId,
            catalogIdBase = "trakt_list_${slugify(key)}",
            title = list.name ?: key,
            itemCount = list.itemCount ?: 0,
            source = TraktListSource.POPULAR
        )
    }

    private fun mapPersonalListOption(dto: TraktListSummaryDto, ownerKey: String): TraktPopularListOption? {
        val listId = dto.ids?.slug
            ?: dto.ids?.trakt?.toString()
            ?: return null
        val keyOwner = ownerKey.ifBlank { ME_PATH }
        val key = "$keyOwner/$listId"
        val meAlias = "$ME_PATH/$listId"
        return TraktPopularListOption(
            key = key,
            userId = ME_PATH,
            listId = listId,
            catalogIdBase = "trakt_list_${slugify(key)}",
            title = dto.name?.takeIf { it.isNotBlank() } ?: listId,
            itemCount = dto.itemCount ?: 0,
            source = TraktListSource.PERSONAL,
            alternateKeys = if (key == meAlias) emptyList() else listOf(meAlias)
        )
    }

    private fun parseListKeyFallback(key: String): TraktPopularListOption? {
        val parts = key.split('/')
        if (parts.size < 2) return null
        val userId = parts[0].trim()
        val listId = parts.drop(1).joinToString("/").trim()
        if (userId.isBlank() || listId.isBlank()) return null
        return TraktPopularListOption(
            key = "$userId/$listId",
            userId = userId,
            listId = listId,
            catalogIdBase = "trakt_list_${slugify("$userId/$listId")}",
            title = "$userId / $listId",
            itemCount = 0,
            source = if (userId == ME_PATH) TraktListSource.PERSONAL else TraktListSource.POPULAR
        )
    }

    private fun slugify(value: String): String {
        return value.lowercase()
            .replace(Regex("[^a-z0-9]+"), "_")
            .trim('_')
            .ifBlank { "custom" }
    }

    private fun fallbackContentId(ids: TraktIdsDto): String {
        return ids.imdb
            ?: ids.tmdb?.let { "tmdb:$it" }
            ?: ids.trakt?.let { "trakt:$it" }
            ?: ids.slug.orEmpty()
    }

    private fun recommendationStatusKey(itemId: String, itemType: String): String {
        return "${itemType.lowercase()}|$itemId"
    }

    private companion object {
        const val ME_PATH = "me"
    }
}
