package com.nexio.tv.data.repository

import android.util.Log
import com.nexio.tv.core.poster.PosterRatingsUrlResolver
import com.nexio.tv.core.network.NetworkResult
import com.nexio.tv.data.local.TraktDiscoverySnapshotStore
import com.nexio.tv.data.local.TraktCatalogIds
import com.nexio.tv.data.local.TraktSettingsDataStore
import com.nexio.tv.data.remote.api.TraktApi
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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
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

@Singleton
@OptIn(ExperimentalCoroutinesApi::class)
class TraktDiscoveryService @Inject constructor(
    private val traktApi: TraktApi,
    private val traktAuthService: TraktAuthService,
    private val metaRepository: MetaRepository,
    private val traktSettingsDataStore: TraktSettingsDataStore,
    private val posterRatingsUrlResolver: PosterRatingsUrlResolver,
    private val snapshotStore: TraktDiscoverySnapshotStore
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val rawSnapshotState = MutableStateFlow(TraktDiscoverySnapshot())
    private val snapshotState = MutableStateFlow(TraktDiscoverySnapshot())
    private val refreshMutex = Mutex()
    private var lastRefreshMs = 0L
    private var lastActivitiesFingerprint: String? = null

    private val minRefreshIntervalMs = 30_000L
    private val fallbackRefreshIntervalMs = 15 * 60_000L
    private val maxItemsPerRail = 20
    @Volatile
    private var activePosterProvider: PosterRatingsUrlResolver.ActiveProvider? = null

    init {
        snapshotStore.read()?.let { persisted ->
            rawSnapshotState.value = persisted
            snapshotState.value = persisted
            lastRefreshMs = persisted.updatedAtMs
        }
        scope.launch {
            combine(
                rawSnapshotState,
                traktSettingsDataStore.dismissedRecommendationKeys
            ) { snapshot, dismissedKeys ->
                if (dismissedKeys.isEmpty()) {
                    snapshot
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
                    snapshot.copy(
                        recommendationMovieItems = filteredMovieItems,
                        recommendationShowItems = filteredShowItems,
                        recommendationRefsByStatusKey = snapshot.recommendationRefsByStatusKey
                            .filterKeys { it in activeKeys }
                    )
                }
            }.collect { filtered ->
                if (filtered != snapshotState.value) {
                    snapshotState.value = filtered
                }
            }
        }
    }

    fun observeSnapshot(): Flow<TraktDiscoverySnapshot> {
        return snapshotState.onStart {
            scope.launch {
                runCatching { ensureFresh(force = false) }
                    .onFailure { error ->
                        Log.w("TraktDiscovery", "Failed to refresh Trakt discovery snapshot", error)
                    }
            }
        }
    }

    suspend fun ensureFresh(force: Boolean) {
        if (!traktAuthService.getCurrentAuthState().isAuthenticated) {
            rawSnapshotState.value = TraktDiscoverySnapshot()
            snapshotStore.clear()
            return
        }
        activePosterProvider = posterRatingsUrlResolver.getActiveProvider()

        val now = System.currentTimeMillis()
        if (!force && now - lastRefreshMs < minRefreshIntervalMs && rawSnapshotState.value.updatedAtMs > 0L) {
            return
        }

        refreshMutex.withLock {
            val lockedNow = System.currentTimeMillis()
            if (!force && lockedNow - lastRefreshMs < minRefreshIntervalMs && rawSnapshotState.value.updatedAtMs > 0L) {
                return
            }
            val snapshot = rawSnapshotState.value
            val snapshotAgeMs = if (snapshot.updatedAtMs > 0L) {
                (lockedNow - snapshot.updatedAtMs).coerceAtLeast(0L)
            } else {
                Long.MAX_VALUE
            }
            val fallbackRefreshDue = snapshotAgeMs >= fallbackRefreshIntervalMs

            if (!force && !hasActivitiesChanged() && !fallbackRefreshDue) {
                lastRefreshMs = lockedNow
                return
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

            rawSnapshotState.value = TraktDiscoverySnapshot(
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
            snapshotStore.write(rawSnapshotState.value)
            lastRefreshMs = System.currentTimeMillis()
        }
    }

    suspend fun dismissRecommendation(ref: TraktRecommendationRef) {
        runCatching {
            traktAuthService.executeAuthorizedWriteRequest { authHeader ->
                traktApi.hideRecommendation(
                    authorization = authHeader,
                    type = ref.type,
                    id = ref.pathId
                )
            }
        }.onFailure { error ->
            Log.w("TraktDiscoveryService", "Failed to hide recommendation remotely: ${error.message}")
        }
        traktSettingsDataStore.addDismissedRecommendationKey(ref.recommendationKey)
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
            trailerYtIds = trailerYtIds
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
