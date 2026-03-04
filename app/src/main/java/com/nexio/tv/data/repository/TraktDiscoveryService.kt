package com.nexio.tv.data.repository

import android.util.Log
import com.nexio.tv.core.network.NetworkResult
import com.nexio.tv.data.local.TraktSettingsDataStore
import com.nexio.tv.data.remote.api.TraktApi
import com.nexio.tv.data.remote.dto.trakt.TraktCalendarEpisodeItemDto
import com.nexio.tv.data.remote.dto.trakt.TraktIdsDto
import com.nexio.tv.data.remote.dto.trakt.TraktRecommendationItemDto
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
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.map
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

data class TraktDiscoverySnapshot(
    val calendarItems: List<MetaPreview> = emptyList(),
    val recommendationMovieItems: List<MetaPreview> = emptyList(),
    val recommendationShowItems: List<MetaPreview> = emptyList(),
    val recommendationRefsByStatusKey: Map<String, TraktRecommendationRef> = emptyMap(),
    val updatedAtMs: Long = 0L
)

@Singleton
@OptIn(ExperimentalCoroutinesApi::class)
class TraktDiscoveryService @Inject constructor(
    private val traktApi: TraktApi,
    private val traktAuthService: TraktAuthService,
    private val metaRepository: MetaRepository,
    private val traktSettingsDataStore: TraktSettingsDataStore
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val rawSnapshotState = MutableStateFlow(TraktDiscoverySnapshot())
    private val snapshotState = MutableStateFlow(TraktDiscoverySnapshot())
    private val refreshMutex = Mutex()
    private var lastRefreshMs = 0L
    private var lastActivitiesFingerprint: String? = null

    private val minRefreshIntervalMs = 30_000L
    private val maxItemsPerRail = 20

    init {
        scope.launch {
            // Keep snapshot filtered by dismissed recommendation keys.
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
        return snapshotState
            .onStart { ensureFresh(force = false) }
    }

    suspend fun ensureFresh(force: Boolean) {
        if (!traktAuthService.getCurrentAuthState().isAuthenticated) {
            rawSnapshotState.value = TraktDiscoverySnapshot()
            return
        }

        val now = System.currentTimeMillis()
        if (!force && now - lastRefreshMs < minRefreshIntervalMs && rawSnapshotState.value.updatedAtMs > 0L) {
            return
        }

        refreshMutex.withLock {
            val lockedNow = System.currentTimeMillis()
            if (!force && lockedNow - lastRefreshMs < minRefreshIntervalMs && rawSnapshotState.value.updatedAtMs > 0L) {
                return
            }
            if (!force && !hasActivitiesChanged()) {
                lastRefreshMs = lockedNow
                return
            }

        val movies = fetchRecommendations(type = "movies")
        val shows = fetchRecommendations(type = "shows")
            val calendar = fetchCalendarShows(days = 7)

            val refs = buildMap {
                (movies + shows).forEach { pair ->
                    val statusKey = recommendationStatusKey(pair.first.id, pair.first.apiType)
                    put(statusKey, pair.second)
                }
            }

            rawSnapshotState.value = TraktDiscoverySnapshot(
                calendarItems = calendar,
                recommendationMovieItems = movies.map { it.first },
                recommendationShowItems = shows.map { it.first },
                recommendationRefsByStatusKey = refs,
                updatedAtMs = System.currentTimeMillis()
            )
            lastRefreshMs = System.currentTimeMillis()
        }
    }

    suspend fun dismissRecommendation(ref: TraktRecommendationRef) {
        // Best effort remote hide + guaranteed local hide.
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

    suspend fun restoreRecommendation(ref: TraktRecommendationRef) {
        traktSettingsDataStore.clearDismissedRecommendationKey(ref.recommendationKey)
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
            .mapNotNull { dto -> mapRecommendationItem(dto, type) }
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

    private suspend fun mapRecommendationItem(
        dto: TraktRecommendationItemDto,
        type: String
    ): Pair<MetaPreview, TraktRecommendationRef>? {
        val movie = dto.movie
        val show = dto.show
        val ids = movie?.ids ?: show?.ids ?: return null
        val title = movie?.title ?: show?.title ?: return null
        val year = movie?.year ?: show?.year
        val normalizedType = if (movie != null) "movie" else "series"
        val contentId = normalizeContentId(ids, fallback = fallbackContentId(ids))
        if (contentId.isBlank()) return null

        val enriched = resolveMetaPreview(type = normalizedType, contentId = contentId)
        val preview = enriched ?: MetaPreview(
            id = contentId,
            type = if (normalizedType == "movie") ContentType.MOVIE else ContentType.SERIES,
            rawType = normalizedType,
            name = title,
            poster = null,
            posterShape = PosterShape.POSTER,
            background = null,
            logo = null,
            description = null,
            releaseInfo = year?.toString(),
            imdbRating = null,
            genres = emptyList()
        )

        val recType = if (normalizedType == "movie") "movies" else "shows"
        val pathId = ids.trakt?.toString()
            ?: ids.slug
            ?: ids.imdb
            ?: ids.tmdb?.toString()
            ?: return null
        val key = recommendationStatusKey(preview.id, preview.apiType)
        return preview to TraktRecommendationRef(
            recommendationKey = key,
            type = recType,
            pathId = pathId
        )
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

        return MetaPreview(
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
        )
    }

    private suspend fun resolveMetaPreview(type: String, contentId: String): MetaPreview? {
        val result = withTimeoutOrNull(2_000L) {
            metaRepository.getMetaFromAllAddons(type = type, id = contentId)
                .filter { it !is NetworkResult.Loading }
                .first()
        } ?: return null

        val meta = (result as? NetworkResult.Success)?.data ?: return null
        return meta.toMetaPreview()
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
