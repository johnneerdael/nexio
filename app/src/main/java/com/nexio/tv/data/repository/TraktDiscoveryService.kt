package com.nexio.tv.data.repository

import android.os.SystemClock
import android.util.Log
import com.nexio.tv.core.network.NetworkResult
import com.nexio.tv.core.poster.PosterRatingsUrlResolver
import com.nexio.tv.core.profile.ProfileManager
import com.nexio.tv.data.local.DebugSettingsDataStore
import com.nexio.tv.data.local.TraktDiscoverySnapshotStore
import com.nexio.tv.data.local.TraktCatalogIds
import com.nexio.tv.data.local.TraktCatalogPreferences
import com.nexio.tv.data.local.TraktSettingsDataStore
import com.nexio.tv.data.remote.api.TraktApi
import com.nexio.tv.data.repository.trakt.TraktDiscoveryMutationAdapter
import com.nexio.tv.data.remote.dto.trakt.TraktCalendarEpisodeItemDto
import com.nexio.tv.data.remote.dto.trakt.TraktIdsDto
import com.nexio.tv.data.remote.dto.trakt.TraktListItemDto
import com.nexio.tv.data.remote.dto.trakt.TraktMovieDto
import com.nexio.tv.data.remote.dto.trakt.TraktPopularListItemDto
import com.nexio.tv.data.remote.dto.trakt.TraktRecommendationItemDto
import com.nexio.tv.data.remote.dto.trakt.TraktShowDto
import com.nexio.tv.domain.model.ContentType
import com.nexio.tv.domain.model.Meta
import com.nexio.tv.domain.model.MetaPreview
import com.nexio.tv.domain.model.PosterShape
import com.nexio.tv.domain.repository.MetaRepository
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
import kotlinx.coroutines.withTimeoutOrNull
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
    val itemCount: Int
)

data class TraktCustomListCatalog(
    val key: String,
    val catalogId: String,
    val catalogName: String,
    val type: ContentType,
    val items: List<MetaPreview>
)

data class TraktDiscoverySnapshot(
    val calendarItems: List<MetaPreview> = emptyList(),
    val recommendationMovieItems: List<MetaPreview> = emptyList(),
    val recommendationShowItems: List<MetaPreview> = emptyList(),
    val trendingMovieItems: List<MetaPreview> = emptyList(),
    val trendingShowItems: List<MetaPreview> = emptyList(),
    val popularMovieItems: List<MetaPreview> = emptyList(),
    val popularShowItems: List<MetaPreview> = emptyList(),
    val customListCatalogs: List<TraktCustomListCatalog> = emptyList(),
    val popularLists: List<TraktPopularListOption> = emptyList(),
    val recommendationRefsByStatusKey: Map<String, TraktRecommendationRef> = emptyMap(),
    val updatedAtMs: Long = 0L
)

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
    if (TraktCatalogIds.TRENDING_MOVIES in prefs.enabledCatalogs && trendingMovieItems.isNotEmpty()) return true
    if (TraktCatalogIds.TRENDING_SHOWS in prefs.enabledCatalogs && trendingShowItems.isNotEmpty()) return true
    if (TraktCatalogIds.POPULAR_MOVIES in prefs.enabledCatalogs && popularMovieItems.isNotEmpty()) return true
    if (TraktCatalogIds.POPULAR_SHOWS in prefs.enabledCatalogs && popularShowItems.isNotEmpty()) return true
    if (TraktCatalogIds.RECOMMENDED_MOVIES in prefs.enabledCatalogs && recommendationMovieItems.isNotEmpty()) return true
    if (TraktCatalogIds.RECOMMENDED_SHOWS in prefs.enabledCatalogs && recommendationShowItems.isNotEmpty()) return true
    if (TraktCatalogIds.CALENDAR in prefs.enabledCatalogs && calendarItems.isNotEmpty()) return true
    if (prefs.selectedPopularListKeys.isEmpty()) return false
    return customListCatalogs.any { catalog ->
        catalog.key in prefs.selectedPopularListKeys && catalog.items.isNotEmpty()
    }
}

@Singleton
@OptIn(ExperimentalCoroutinesApi::class)
class TraktDiscoveryService @Inject constructor(
    private val traktApi: TraktApi,
    private val traktAuthService: TraktAuthService,
    private val metaRepository: MetaRepository,
    private val traktSettingsDataStore: TraktSettingsDataStore,
    private val posterRatingsUrlResolver: PosterRatingsUrlResolver,
    private val snapshotStore: TraktDiscoverySnapshotStore,
    private val debugSettingsDataStore: DebugSettingsDataStore,
    private val traktMutationOutboxCoordinator: TraktMutationOutboxCoordinator,
    private val profileManager: ProfileManager
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val rawProfileSnapshots = MutableStateFlow<Map<Int, TraktDiscoverySnapshot>>(emptyMap())
    private val profileSnapshots = MutableStateFlow<Map<Int, TraktDiscoverySnapshot>>(emptyMap())
    private val refreshMutex = Mutex()
    private val lastRefreshByProfile = mutableMapOf<Int, Long>()
    private var lastActivitiesFingerprint: String? = null

    private val minRefreshIntervalMs = 30_000L
    private val fallbackRefreshIntervalMs = 15 * 60_000L
    private val maxItemsPerRail = 20
    private val startupRefreshGateMs = 20_000L
    @Volatile
    private var activePosterProvider: PosterRatingsUrlResolver.ActiveProvider? = null
    @Volatile
    private var diskFirstHomeStartupEnabled: Boolean = false
    @Volatile
    private var startupRefreshGateUntilElapsedMs: Long = 0L
    @Volatile
    private var startupGateInitialized: Boolean = false

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
            val enabled = runCatching { debugSettingsDataStore.diskFirstHomeStartupEnabled.first() }.getOrDefault(false)
            applyStartupRefreshGate(enabled, "init")
            startupGateInitialized = true
            debugSettingsDataStore.diskFirstHomeStartupEnabled.collect { updated ->
                applyStartupRefreshGate(updated, "toggle_change")
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
                    val filteredMovieItems = snapshot.recommendationMovieItems.filterNot { item ->
                        recommendationStatusKey(item.id, item.apiType) in dismissedKeys
                    }
                    val filteredShowItems = snapshot.recommendationShowItems.filterNot { item ->
                        recommendationStatusKey(item.id, item.apiType) in dismissedKeys
                    }
                    val activeKeys = (filteredMovieItems + filteredShowItems)
                        .map { recommendationStatusKey(it.id, it.apiType) }
                        .toSet()
                    profileId to snapshot.copy(
                        recommendationMovieItems = filteredMovieItems,
                        recommendationShowItems = filteredShowItems,
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
                    snapshotStore.read(profileId = profileId)?.let { persisted ->
                        setRawProfileSnapshot(profileId, persisted)
                        setProfileSnapshot(profileId, persisted)
                        lastRefreshByProfile[profileId] = persisted.updatedAtMs
                    }
                    if (autoRefreshOnStart) {
                        scope.launch {
                            ensureStartupGateInitialized()
                            if (isStartupRefreshGated()) {
                                Log.d("TraktDiscovery", "Auto-refresh deferred by startup gate")
                                return@launch
                            }
                            runCatching { ensureFresh(force = false, profileId = profileId) }
                                .onFailure { error ->
                                    Log.w("TraktDiscovery", "Failed to refresh Trakt discovery snapshot", error)
                                }
                        }
                    }
                }
        }
    }

    /** Bypasses the startup refresh gate. Only call from explicit UI actions. */
    suspend fun priorityFetch() {
        startupRefreshGateUntilElapsedMs = 0L
        ensureFresh(force = true)
    }

    suspend fun ensureFresh(
        force: Boolean,
        profileId: Int = profileManager.activeProfileId.value
    ) = withContext(Dispatchers.IO) {
        ensureStartupGateInitialized()
        if (isStartupRefreshGated()) {
            Log.d("TraktDiscovery", "ensureFresh deferred by startup gate")
            return@withContext
        }
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

            val popularLists = fetchPopularLists()
            val selectedCustomCatalogs = fetchSelectedPopularListCatalogs(
                selectedKeys = prefs.selectedPopularListKeys,
                options = popularLists
            )

            val refs = buildMap {
                (recommendationMovies + recommendationShows).forEach { pair ->
                    val statusKey = recommendationStatusKey(pair.first.id, pair.first.apiType)
                    put(statusKey, pair.second)
                }
            }

            val refreshedSnapshot = TraktDiscoverySnapshot(
                calendarItems = calendar,
                recommendationMovieItems = recommendationMovies.map { it.first },
                recommendationShowItems = recommendationShows.map { it.first },
                trendingMovieItems = trendingMovies,
                trendingShowItems = trendingShows,
                popularMovieItems = popularMovies,
                popularShowItems = popularShows,
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
                TraktDiscoveryMutationAdapter.buildDismissRecommendationEnvelope(ref)
            )
        }.onFailure { error ->
            Log.w("TraktDiscoveryService", "Failed to enqueue recommendation dismissal: ${error.message}")
        }
    }

    private suspend fun ensureStartupGateInitialized() {
        if (startupGateInitialized) return
        val enabled = runCatching { debugSettingsDataStore.diskFirstHomeStartupEnabled.first() }.getOrDefault(false)
        applyStartupRefreshGate(enabled, "lazy_init")
        startupGateInitialized = true
    }

    private fun applyStartupRefreshGate(enabled: Boolean, reason: String) {
        diskFirstHomeStartupEnabled = enabled
        if (enabled) {
            startupRefreshGateUntilElapsedMs = SystemClock.elapsedRealtime() + startupRefreshGateMs
            Log.d("TraktDiscovery", "Startup refresh gate open (${startupRefreshGateMs}ms) reason=$reason")
        } else {
            startupRefreshGateUntilElapsedMs = 0L
            Log.d("TraktDiscovery", "Startup refresh gate disabled reason=$reason")
        }
    }

    private fun isStartupRefreshGated(): Boolean {
        if (!diskFirstHomeStartupEnabled) return false
        return SystemClock.elapsedRealtime() < startupRefreshGateUntilElapsedMs
    }

    private suspend fun hasActivitiesChanged(): Boolean {
        val response = traktAuthService.executeAuthorizedRequest { authHeader ->
            traktApi.getLastActivities(authHeader)
        } ?: return true
        if (!response.isSuccessful) return true

        val body = response.body() ?: return true
        val fingerprint = listOfNotNull(
            body.movies?.watchedAt,
            body.movies?.pausedAt,
            body.episodes?.watchedAt,
            body.episodes?.pausedAt
        ).joinToString("|")

        val changed = fingerprint != lastActivitiesFingerprint
        lastActivitiesFingerprint = fingerprint
        return changed
    }

    private suspend fun fetchRecommendations(type: String): List<Pair<MetaPreview, TraktRecommendationRef>> {
        val response = traktAuthService.executeAuthorizedRequest { authHeader ->
            traktApi.getRecommendations(
                authorization = authHeader,
                type = type,
                limit = maxItemsPerRail
            )
        } ?: return emptyList()
        if (!response.isSuccessful) return emptyList()

        return response.body().orEmpty()
            .mapNotNull { dto -> mapRecommendationItem(dto) }
            .take(maxItemsPerRail)
    }

    private suspend fun fetchCalendarShows(days: Int): List<MetaPreview> {
        val startDate = LocalDate.now().format(DateTimeFormatter.ISO_DATE)
        val response = traktAuthService.executeAuthorizedRequest { authHeader ->
            traktApi.getMyShowsCalendar(
                authorization = authHeader,
                startDate = startDate,
                days = days
            )
        } ?: return emptyList()
        if (!response.isSuccessful) return emptyList()

        return response.body().orEmpty()
            .mapNotNull { dto -> mapCalendarEpisodeItem(dto) }
            .take(maxItemsPerRail)
    }

    private suspend fun fetchTrendingMovies(): List<MetaPreview> {
        val response = traktAuthService.executeAuthorizedRequest { authHeader ->
            traktApi.getTrendingMovies(
                authorization = authHeader,
                limit = maxItemsPerRail
            )
        } ?: return emptyList()
        if (!response.isSuccessful) return emptyList()
        return response.body().orEmpty()
            .mapNotNull { it.movie }
            .mapNotNull { movie -> mapMovieDto(movie) }
            .take(maxItemsPerRail)
    }

    private suspend fun fetchTrendingShows(): List<MetaPreview> {
        val response = traktAuthService.executeAuthorizedRequest { authHeader ->
            traktApi.getTrendingShows(
                authorization = authHeader,
                limit = maxItemsPerRail
            )
        } ?: return emptyList()
        if (!response.isSuccessful) return emptyList()
        return response.body().orEmpty()
            .mapNotNull { it.show }
            .mapNotNull { show -> mapShowDto(show) }
            .take(maxItemsPerRail)
    }

    private suspend fun fetchPopularMovies(): List<MetaPreview> {
        val response = traktAuthService.executeAuthorizedRequest { authHeader ->
            traktApi.getPopularMovies(
                authorization = authHeader,
                limit = maxItemsPerRail
            )
        } ?: return emptyList()
        if (!response.isSuccessful) return emptyList()
        return response.body().orEmpty()
            .mapNotNull { movie -> mapMovieDto(movie) }
            .take(maxItemsPerRail)
    }

    private suspend fun fetchPopularShows(): List<MetaPreview> {
        val response = traktAuthService.executeAuthorizedRequest { authHeader ->
            traktApi.getPopularShows(
                authorization = authHeader,
                limit = maxItemsPerRail
            )
        } ?: return emptyList()
        if (!response.isSuccessful) return emptyList()
        return response.body().orEmpty()
            .mapNotNull { show -> mapShowDto(show) }
            .take(maxItemsPerRail)
    }

    private suspend fun fetchPopularLists(): List<TraktPopularListOption> {
        val response = traktAuthService.executeAuthorizedRequest { authHeader ->
            traktApi.getPopularLists(
                authorization = authHeader,
                page = 1,
                limit = 30
            )
        } ?: return emptyList()
        if (!response.isSuccessful) return emptyList()
        return response.body().orEmpty()
            .mapNotNull { dto -> mapPopularListOption(dto) }
    }

    private suspend fun fetchSelectedPopularListCatalogs(
        selectedKeys: Set<String>,
        options: List<TraktPopularListOption>
    ): List<TraktCustomListCatalog> {
        if (selectedKeys.isEmpty()) return emptyList()

        val byKey = options.associateBy { it.key }
        return selectedKeys.flatMap { key ->
            val option = byKey[key] ?: parseListKeyFallback(key)
            if (option != null) {
                fetchPopularListCatalog(option)
            } else {
                emptyList()
            }
        }
    }

    private suspend fun fetchPopularListCatalog(option: TraktPopularListOption): List<TraktCustomListCatalog> {
        val movieItems = traktAuthService.executeAuthorizedRequest { authHeader ->
            traktApi.getUserListItems(
                authorization = authHeader,
                id = option.userId,
                listId = option.listId,
                type = "movies"
            )
        }?.takeIf { it.isSuccessful }?.body().orEmpty()

        val showItems = traktAuthService.executeAuthorizedRequest { authHeader ->
            traktApi.getUserListItems(
                authorization = authHeader,
                id = option.userId,
                listId = option.listId,
                type = "shows"
            )
        }?.takeIf { it.isSuccessful }?.body().orEmpty()

        val movies = movieItems.mapNotNull { mapListMovieItem(it) }.take(maxItemsPerRail)
        val shows = showItems.mapNotNull { mapListShowItem(it) }.take(maxItemsPerRail)

        val rows = mutableListOf<TraktCustomListCatalog>()
        if (movies.isNotEmpty()) {
            rows += TraktCustomListCatalog(
                key = option.key,
                catalogId = "${option.catalogIdBase}_movies",
                catalogName = "${option.title} (Movies)",
                type = ContentType.MOVIE,
                items = movies
            )
        }
        if (shows.isNotEmpty()) {
            rows += TraktCustomListCatalog(
                key = option.key,
                catalogId = "${option.catalogIdBase}_shows",
                catalogName = "${option.title} (Shows)",
                type = ContentType.SERIES,
                items = shows
            )
        }
        return rows
    }

    private suspend fun mapRecommendationItem(
        dto: TraktRecommendationItemDto
    ): Pair<MetaPreview, TraktRecommendationRef>? {
        val movie = dto.movie
        val show = dto.show
        return if (movie != null) {
            val preview = mapMovieDto(movie) ?: return null
            val pathId = movie.ids?.trakt?.toString()
                ?: movie.ids?.slug
                ?: movie.ids?.imdb
                ?: movie.ids?.tmdb?.toString()
                ?: return null
            val key = recommendationStatusKey(preview.id, preview.apiType)
            preview to TraktRecommendationRef(
                recommendationKey = key,
                type = "movies",
                pathId = pathId
            )
        } else if (show != null) {
            val preview = mapShowDto(show) ?: return null
            val pathId = show.ids?.trakt?.toString()
                ?: show.ids?.slug
                ?: show.ids?.imdb
                ?: show.ids?.tmdb?.toString()
                ?: return null
            val key = recommendationStatusKey(preview.id, preview.apiType)
            preview to TraktRecommendationRef(
                recommendationKey = key,
                type = "shows",
                pathId = pathId
            )
        } else {
            null
        }
    }

    private suspend fun mapCalendarEpisodeItem(dto: TraktCalendarEpisodeItemDto): MetaPreview? {
        val show = dto.show ?: return null
        val episode = dto.episode
        val ids = show.ids ?: return null
        val contentId = normalizeContentId(ids, fallback = fallbackContentId(ids))
        if (contentId.isBlank()) return null

        val enriched = resolveMetaPreview(type = "series", contentId = contentId)
        if (enriched != null) return enriched

        val title = buildString {
            append(show.title ?: contentId)
            if (episode?.season != null && episode.number != null) {
                append("  S")
                append(episode.season)
                append("E")
                append(episode.number)
            }
        }

        return posterRatingsUrlResolver.apply(
            MetaPreview(
            id = contentId,
            type = ContentType.SERIES,
            rawType = "series",
            name = title,
            poster = null,
            posterShape = PosterShape.LANDSCAPE,
            background = null,
            logo = null,
            description = episode?.title,
            releaseInfo = dto.firstAired,
            imdbRating = null,
            genres = emptyList()
        ),
            activePosterProvider
        )
    }

    private suspend fun mapMovieDto(movie: TraktMovieDto): MetaPreview? {
        val ids = movie.ids ?: return null
        val contentId = normalizeContentId(ids, fallback = fallbackContentId(ids))
        if (contentId.isBlank()) return null
        val enriched = resolveMetaPreview(type = "movie", contentId = contentId)
        return enriched ?: posterRatingsUrlResolver.apply(
            MetaPreview(
            id = contentId,
            type = ContentType.MOVIE,
            rawType = "movie",
            name = movie.title ?: contentId,
            poster = null,
            posterShape = PosterShape.POSTER,
            background = null,
            logo = null,
            description = null,
            releaseInfo = movie.year?.toString(),
            imdbRating = null,
            genres = emptyList()
        ),
            activePosterProvider
        )
    }

    private suspend fun mapShowDto(show: TraktShowDto): MetaPreview? {
        val ids = show.ids ?: return null
        val contentId = normalizeContentId(ids, fallback = fallbackContentId(ids))
        if (contentId.isBlank()) return null
        val enriched = resolveMetaPreview(type = "series", contentId = contentId)
        return enriched ?: posterRatingsUrlResolver.apply(
            MetaPreview(
            id = contentId,
            type = ContentType.SERIES,
            rawType = "series",
            name = show.title ?: contentId,
            poster = null,
            posterShape = PosterShape.POSTER,
            background = null,
            logo = null,
            description = null,
            releaseInfo = show.year?.toString(),
            imdbRating = null,
            genres = emptyList()
        ),
            activePosterProvider
        )
    }

    private suspend fun mapListMovieItem(item: TraktListItemDto): MetaPreview? {
        return item.movie?.let { mapMovieDto(it) }
    }

    private suspend fun mapListShowItem(item: TraktListItemDto): MetaPreview? {
        return item.show?.let { mapShowDto(it) }
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
            itemCount = list.itemCount ?: 0
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
            itemCount = 0
        )
    }

    private fun slugify(value: String): String {
        return value.lowercase()
            .replace(Regex("[^a-z0-9]+"), "_")
            .trim('_')
            .ifBlank { "custom" }
    }

    private suspend fun resolveMetaPreview(type: String, contentId: String): MetaPreview? {
        val result = withTimeoutOrNull(2_000L) {
            metaRepository.getMetaFromAllAddons(type = type, id = contentId)
                .filter { it !is NetworkResult.Loading }
                .first()
        } ?: return null

        val meta = (result as? NetworkResult.Success)?.data ?: return null
        return posterRatingsUrlResolver.apply(meta.toMetaPreview(), activePosterProvider)
    }

    private fun Meta.toMetaPreview(): MetaPreview {
        return MetaPreview(
            id = id,
            type = type,
            rawType = rawType,
            name = name,
            poster = poster,
            posterShape = posterShape,
            background = background,
            logo = logo,
            description = description,
            releaseInfo = releaseInfo,
            imdbRating = imdbRating,
            genres = genres,
            trailerYtIds = trailerYtIds,
            language = language
        )
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
}
