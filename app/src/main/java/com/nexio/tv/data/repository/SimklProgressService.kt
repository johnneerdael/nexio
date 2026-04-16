package com.nexio.tv.data.repository

import android.util.Log
import com.google.gson.Gson
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.nexio.tv.data.local.SimklAuthDataStore
import com.nexio.tv.data.local.SimklProgressSyncState
import com.nexio.tv.data.local.SimklProgressSyncStateStore
import com.nexio.tv.data.remote.SimklRequestGate
import com.nexio.tv.domain.model.TrackingProvider
import com.nexio.tv.domain.model.WatchProgress
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.time.Instant
import javax.inject.Inject
import javax.inject.Named
import javax.inject.Singleton

@Singleton
class SimklProgressService @Inject constructor(
    @Named("simkl") private val okHttpClient: OkHttpClient,
    private val simklAuthDataStore: SimklAuthDataStore,
    private val syncStateStore: SimklProgressSyncStateStore,
    private val requestGate: SimklRequestGate
) {
    private class SimklProgressRuntimeState(
        persisted: SimklProgressSyncState = SimklProgressSyncState()
    ) {
        val remoteProgress = MutableStateFlow<List<WatchProgress>>(emptyList())
        val nextUp = MutableStateFlow<List<TrackingNextUpEntry>>(emptyList())
        val episodeProgress = MutableStateFlow<Map<String, Map<Pair<Int, Int>, WatchProgress>>>(emptyMap())
        val watchedMovies = MutableStateFlow<Set<String>>(emptySet())
        val playbackIdsByKey = MutableStateFlow<Map<String, Long>>(emptyMap())
        val loaded = MutableStateFlow(false)
        var lastAllActivityAt: String? = persisted.lastAllActivityAt
        var lastPlaybackActivityAt: String? = persisted.lastPlaybackActivityAt
        var lastRemovedFromListActivityAt: String? = persisted.lastRemovedFromListActivityAt

        fun clear() {
            remoteProgress.value = emptyList()
            nextUp.value = emptyList()
            episodeProgress.value = emptyMap()
            watchedMovies.value = emptySet()
            playbackIdsByKey.value = emptyMap()
            loaded.value = false
            lastAllActivityAt = null
            lastPlaybackActivityAt = null
            lastRemovedFromListActivityAt = null
        }
    }

    private class SimklProgressRuntimeRegistry(
        private val syncStateStore: SimklProgressSyncStateStore
    ) {
        private val states = mutableMapOf<Int, SimklProgressRuntimeState>()

        fun stateFor(session: TrackingRuntimeSession): SimklProgressRuntimeState {
            require(session.provider == TrackingProvider.SIMKL) {
                "SimklProgressRuntimeRegistry only accepts SIMKL sessions"
            }
            return states.getOrPut(session.profileId) {
                SimklProgressRuntimeState(readPersistedState(session.profileId))
            }
        }

        private fun readPersistedState(profileId: Int): SimklProgressSyncState {
            val currentProfileId = runCatching { syncStateStore.currentProfileId() }
                .getOrDefault(profileId)
                .takeIf { it in 1..4 }
                ?: 1
            if (profileId == currentProfileId) {
                return syncStateStore.read()
            }
            return syncStateStore.read(profileId)
        }
    }

    private val gson = Gson()
    private val debounceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var refreshDebounceJob: Job? = null
    private val refreshDebounceMutex = Mutex()
    private val runtimeRegistry = SimklProgressRuntimeRegistry(syncStateStore)
    private val remoteProgress get() = runtimeState().remoteProgress
    private val nextUp get() = runtimeState().nextUp
    private val episodeProgress get() = runtimeState().episodeProgress
    private val watchedMovies get() = runtimeState().watchedMovies
    private val playbackIdsByKey get() = runtimeState().playbackIdsByKey
    private val loaded get() = runtimeState().loaded

    private fun currentProfileId(): Int {
        return syncStateStore.currentProfileId().takeIf { it in 1..4 } ?: 1
    }

    private fun runtimeState(profileId: Int = currentProfileId()): SimklProgressRuntimeState {
        return runtimeRegistry.stateFor(
            TrackingRuntimeSession(
                provider = TrackingProvider.SIMKL,
                profileId = profileId
            )
        )
    }

    fun observeAllProgress(): Flow<List<WatchProgress>> = remoteProgress
    fun observeRemoteSnapshotLoaded(): Flow<Boolean> = loaded
    fun observeContinueWatchingNextUp(): Flow<List<TrackingNextUpEntry>> = nextUp
    fun observeSyntheticContinueWatchingNextUp(): Flow<List<TrackingNextUpEntry>> = nextUp
    fun observeEpisodeProgress(contentId: String): Flow<Map<Pair<Int, Int>, WatchProgress>> =
        episodeProgress.map { it[contentId].orEmpty() }

    fun observeMovieWatched(contentId: String): Flow<Boolean> =
        watchedMovies.map { contentId in it }

    fun applyOptimisticProgress(progress: WatchProgress) {
        remoteProgress.update { current ->
            (current.filterNot { it.videoId == progress.videoId } + progress)
                .sortedByDescending { it.lastWatched }
        }
    }

    fun applyOptimisticRemoval(contentId: String, season: Int?, episode: Int?) {
        remoteProgress.update { current ->
            current.filterNot { progress ->
                progress.contentId == contentId &&
                    (season == null || progress.season == season) &&
                    (episode == null || progress.episode == episode)
            }
        }
        if (season != null && episode != null) {
            episodeProgress.update { current ->
                val updated = current.toMutableMap()
                val existing = updated[contentId].orEmpty().toMutableMap()
                existing.remove(season to episode)
                updated[contentId] = existing
                updated
            }
        } else {
            episodeProgress.update { current ->
                current - contentId
            }
            watchedMovies.update { it - contentId }
        }
    }

    fun clearOptimistic() = Unit
    fun invalidateLocalizedMetadata() = Unit

    /**
     * Debounced refresh - collapses rapid successive calls (e.g. batch season marks)
     * into a single API refresh cycle after a 3-second quiet window. Safe for mutation
     * reconcile paths where multiple adapters may settle in quick succession.
     */
    suspend fun refreshNow() {
        val profileId = currentProfileId()
        refreshDebounceMutex.withLock {
            refreshDebounceJob?.cancel()
            refreshDebounceJob = debounceScope.launch {
                delay(3_000L)
                executeRefresh(profileId)
            }
        }
    }

    /**
     * Immediate refresh - bypasses debounce. Use for critical paths where the caller
     * needs fresh state right away (app startup, foreground resume, user-initiated refresh).
     */
    suspend fun refreshNowImmediate() {
        val profileId = currentProfileId()
        refreshDebounceMutex.withLock {
            refreshDebounceJob?.cancel()
        }
        executeRefresh(profileId)
    }

    private suspend fun executeRefresh(profileId: Int = currentProfileId()) {
        val runtime = runtimeState(profileId)
        val auth = runCatching { simklAuthDataStore.stateForProfile(profileId).first() }
            .getOrElse { simklAuthDataStore.state.first() }
        val accessToken = auth.accessToken?.trim().orEmpty()
        if (accessToken.isBlank()) {
            runtime.clear()
            syncStateStore.clear(profileId)
            return
        }

        val activities = fetchJsonObject(
            url = "https://api.simkl.com/sync/activities",
            method = "POST",
            accessToken = accessToken
        )

        val playbackActivity = maxOfNotNull(
            activities?.getAsJsonObject("tv_shows")?.stringValue("playback"),
            activities?.getAsJsonObject("anime")?.stringValue("playback"),
            activities?.getAsJsonObject("movies")?.stringValue("playback")
        )
        val allActivity = activities?.stringValue("all")
        val removedFromListActivity = maxOfNotNull(
            activities?.getAsJsonObject("tv_shows")?.stringValue("removed_from_list"),
            activities?.getAsJsonObject("anime")?.stringValue("removed_from_list"),
            activities?.getAsJsonObject("movies")?.stringValue("removed_from_list")
        )

        if (!runtime.loaded.value || playbackActivity != runtime.lastPlaybackActivityAt) {
            val playbackDateFrom = runtime.lastPlaybackActivityAt
            val moviePlaybacks = fetchJsonArrayAsObjects(
                url = buildPlaybackUrl("movies", playbackDateFrom),
                accessToken = accessToken
            )
            val episodePlaybacks = fetchJsonArrayAsObjects(
                url = buildPlaybackUrl("episodes", playbackDateFrom),
                accessToken = accessToken
            )
            val playbackDelta = (moviePlaybacks.mapNotNull(::mapMoviePlayback) + episodePlaybacks.mapNotNull(::mapEpisodePlayback))
                .sortedByDescending { it.lastWatched }
            runtime.remoteProgress.value = if (playbackDateFrom == null) {
                playbackDelta
            } else {
                mergePlaybackDelta(runtime.remoteProgress.value, playbackDelta)
            }
            val playbackIdDelta = buildMap {
                moviePlaybacks.forEach { item ->
                    val movie = item.getAsJsonObject("movie") ?: return@forEach
                    val ids = movie.getAsJsonObject("ids") ?: return@forEach
                    val contentId = ids.toCanonicalContentId() ?: return@forEach
                    val playbackId = item.numberValue("id")?.toLong() ?: return@forEach
                    put(contentId, playbackId)
                }
                episodePlaybacks.forEach { item ->
                    val show = item.getAsJsonObject("show") ?: return@forEach
                    val ids = show.getAsJsonObject("ids") ?: return@forEach
                    val contentId = ids.toCanonicalContentId() ?: return@forEach
                    val episode = item.getAsJsonObject("episode") ?: return@forEach
                    val seasonNumber = episode.numberValue("tvdb_season")?.toInt()
                        ?: episode.numberValue("season")?.toInt()
                        ?: return@forEach
                    val episodeNumber = episode.numberValue("tvdb_number")?.toInt()
                        ?: episode.numberValue("episode")?.toInt()
                        ?: return@forEach
                    val playbackId = item.numberValue("id")?.toLong() ?: return@forEach
                    put("$contentId:$seasonNumber:$episodeNumber", playbackId)
                }
            }
            runtime.playbackIdsByKey.value = if (playbackDateFrom == null) {
                playbackIdDelta
            } else {
                runtime.playbackIdsByKey.value + playbackIdDelta
            }
            runtime.lastPlaybackActivityAt = playbackActivity
        }

        if (!runtime.loaded.value ||
            allActivity != runtime.lastAllActivityAt ||
            removedFromListActivity != runtime.lastRemovedFromListActivityAt
        ) {
            val allDateFrom = if (removedFromListActivity != runtime.lastRemovedFromListActivityAt) {
                null
            } else {
                runtime.lastAllActivityAt
            }
            val moviesRoot = fetchJsonObject(
                url = buildAllItemsUrl("movies", allDateFrom, "full"),
                accessToken = accessToken
            )
            val showsRoot = fetchJsonObject(
                url = buildAllItemsUrl("shows", allDateFrom, "full"),
                accessToken = accessToken
            )
            val animeRoot = fetchJsonObject(
                url = buildAllItemsUrl("anime", allDateFrom, "full_anime_seasons"),
                accessToken = accessToken
            )
            runtime.watchedMovies.value = if (allDateFrom == null) {
                buildWatchedMovies(moviesRoot)
            } else {
                mergeWatchedMovies(runtime.watchedMovies.value, moviesRoot)
            }
            runtime.nextUp.value = if (allDateFrom == null) {
                buildNextUp(showsRoot, animeRoot)
            } else {
                mergeNextUp(runtime.nextUp.value, showsRoot, animeRoot)
            }
            runtime.episodeProgress.value = if (allDateFrom == null) {
                buildEpisodeProgressMap(showsRoot, animeRoot)
            } else {
                mergeEpisodeProgress(runtime.episodeProgress.value, showsRoot, animeRoot)
            }
            runtime.lastAllActivityAt = allActivity
            runtime.lastRemovedFromListActivityAt = removedFromListActivity
        }

        runtime.loaded.value = true
        syncStateStore.write(
            SimklProgressSyncState(
                lastAllActivityAt = runtime.lastAllActivityAt,
                lastPlaybackActivityAt = runtime.lastPlaybackActivityAt,
                lastRemovedFromListActivityAt = runtime.lastRemovedFromListActivityAt
            ),
            profileId = profileId
        )
    }

    suspend fun rollbackQueuedHistoryRemove(
        contentId: String,
        season: Int?,
        episode: Int?,
        removeShow: Boolean
    ) {
        refreshNow()
    }

    suspend fun resolvePlaybackDeleteIdsForOutbox(
        contentId: String,
        season: Int?,
        episode: Int?
    ): List<Long> {
        val map = playbackIdsByKey.value
        return if (season != null && episode != null) {
            listOfNotNull(map["$contentId:$season:$episode"])
        } else {
            map.filterKeys { it == contentId || it.startsWith("$contentId:") }.values.toList()
        }
    }

    suspend fun rollbackQueuedPlaybackDelete(
        contentId: String,
        season: Int?,
        episode: Int?,
        clearShow: Boolean
    ) {
        refreshNow()
    }

    private fun buildEpisodeProgressMap(
        showsRoot: JsonObject?,
        animeRoot: JsonObject?
    ): Map<String, Map<Pair<Int, Int>, WatchProgress>> {
        val result = linkedMapOf<String, MutableMap<Pair<Int, Int>, WatchProgress>>()
        fun appendFromArray(array: JsonArray?) {
            array?.forEach { element ->
                val item = element.asJsonObject
                val show = item.getAsJsonObject("show") ?: return@forEach
                val ids = show.getAsJsonObject("ids") ?: return@forEach
                val contentId = ids.toCanonicalContentId() ?: return@forEach
                val name = show.stringValue("title") ?: contentId
                val runtimeMs = ((show.numberValue("runtime")?.toLong() ?: 45L) * 60_000L).coerceAtLeast(1L)
                val watchedAtMs = parseIsoMillis(item.stringValue("last_watched_at"))
                val seasons = item.getAsJsonArray("seasons") ?: return@forEach
                val perShow = result.getOrPut(contentId) { linkedMapOf() }
                seasons.forEach { seasonElement ->
                    val seasonObj = seasonElement.asJsonObject
                    seasonObj.getAsJsonArray("episodes")?.forEach { epElement ->
                        val epObj = epElement.asJsonObject
                        val (seasonNumber, episodeNumber) = resolveEpisodeNumbers(
                            episode = epObj,
                            fallbackSeasonNumber = seasonObj.numberValue("number")?.toInt() ?: 1
                        ) ?: return@forEach
                        val videoId = "$contentId:$seasonNumber:$episodeNumber"
                        perShow[seasonNumber to episodeNumber] = WatchProgress(
                            contentId = contentId,
                            contentType = "series",
                            name = name,
                            poster = null,
                            backdrop = null,
                            logo = null,
                            videoId = videoId,
                            season = seasonNumber,
                            episode = episodeNumber,
                            episodeTitle = null,
                            position = runtimeMs,
                            duration = runtimeMs,
                            lastWatched = watchedAtMs,
                            progressPercent = 100f,
                            source = "simkl_history"
                        )
                    }
                }
            }
        }
        appendFromArray(showsRoot?.getAsJsonArray("shows"))
        appendFromArray(animeRoot?.getAsJsonArray("anime"))
        return result
    }

    private fun buildWatchedMovies(
        moviesRoot: JsonObject?
    ): Set<String> = buildSet {
        moviesRoot?.getAsJsonArray("movies")
            ?.forEach { element ->
                val item = element.asJsonObject
                if (item.stringValue("status") == "completed") {
                    item.getAsJsonObject("movie")?.getAsJsonObject("ids")?.toCanonicalContentId()?.let(::add)
                }
            }
    }

    private fun buildNextUp(
        showsRoot: JsonObject?,
        animeRoot: JsonObject?
    ): List<TrackingNextUpEntry> {
        return buildList {
            showsRoot?.getAsJsonArray("shows")
                ?.mapNotNull { mapNextUpEntry(it.asJsonObject, "series") }
                ?.let(::addAll)
            animeRoot?.getAsJsonArray("anime")
                ?.mapNotNull { mapNextUpEntry(it.asJsonObject, "anime") }
                ?.let(::addAll)
        }.sortedByDescending { it.activityAtMs }
    }

    private fun mergeWatchedMovies(
        current: Set<String>,
        moviesRoot: JsonObject?
    ): Set<String> {
        val updated = current.toMutableSet()
        moviesRoot?.getAsJsonArray("movies")?.forEach { element ->
            val item = element.asJsonObject
            val contentId = item.getAsJsonObject("movie")?.getAsJsonObject("ids")?.toCanonicalContentId() ?: return@forEach
            if (item.stringValue("status") == "completed") updated.add(contentId) else updated.remove(contentId)
        }
        return updated
    }

    private fun mergeNextUp(
        current: List<TrackingNextUpEntry>,
        showsRoot: JsonObject?,
        animeRoot: JsonObject?
    ): List<TrackingNextUpEntry> {
        val updated = current.associateBy { it.contentId }.toMutableMap()
        fun mergeArray(array: JsonArray?, rawType: String) {
            array?.forEach { element ->
                val item = element.asJsonObject
                val show = item.getAsJsonObject("show") ?: return@forEach
                val ids = show.getAsJsonObject("ids") ?: return@forEach
                val contentId = ids.toCanonicalContentId() ?: return@forEach
                val next = mapNextUpEntry(item, rawType)
                if (next != null) updated[contentId] = next else updated.remove(contentId)
            }
        }
        mergeArray(showsRoot?.getAsJsonArray("shows"), "series")
        mergeArray(animeRoot?.getAsJsonArray("anime"), "anime")
        return updated.values.sortedByDescending { it.activityAtMs }
    }

    private fun mergeEpisodeProgress(
        current: Map<String, Map<Pair<Int, Int>, WatchProgress>>,
        showsRoot: JsonObject?,
        animeRoot: JsonObject?
    ): Map<String, Map<Pair<Int, Int>, WatchProgress>> {
        val updated = current.toMutableMap()
        fun mergeArray(array: JsonArray?) {
            array?.forEach { element ->
                val item = element.asJsonObject
                val show = item.getAsJsonObject("show") ?: return@forEach
                val ids = show.getAsJsonObject("ids") ?: return@forEach
                val contentId = ids.toCanonicalContentId() ?: return@forEach
                val perItem = buildEpisodeProgressMap(
                    JsonObject().apply { add("shows", JsonArray().apply { add(item) }) },
                    null
                )[contentId]
                if (perItem == null || perItem.isEmpty()) updated.remove(contentId) else updated[contentId] = perItem
            }
        }
        mergeArray(showsRoot?.getAsJsonArray("shows"))
        mergeArray(animeRoot?.getAsJsonArray("anime"))
        return updated
    }

    private fun mergePlaybackDelta(
        current: List<WatchProgress>,
        delta: List<WatchProgress>
    ): List<WatchProgress> {
        val updated = current.associateBy { it.videoId }.toMutableMap()
        delta.forEach { updated[it.videoId] = it }
        return updated.values.sortedByDescending { it.lastWatched }
    }

    private fun mapNextUpEntry(item: JsonObject, rawType: String): TrackingNextUpEntry? {
        val nextToWatch = item.stringValue("next_to_watch") ?: return null
        val show = item.getAsJsonObject("show") ?: return null
        val ids = show.getAsJsonObject("ids") ?: return null
        val contentId = ids.toCanonicalContentId() ?: return null
        val parsed = parseNextToWatch(nextToWatch) ?: return null
        val name = show.stringValue("title") ?: contentId
        val lastWatchedMs = parseIsoMillis(item.stringValue("last_watched_at"))
        return TrackingNextUpEntry(
            contentId = contentId,
            contentType = "series",
            name = name,
            season = parsed.first,
            episode = parsed.second,
            episodeTitle = null,
            videoId = "$contentId:${parsed.first}:${parsed.second}",
            firstAired = null,
            firstAiredMs = 0L,
            activityAtMs = lastWatchedMs,
            poster = null,
            backdrop = null,
            logo = null
        )
    }

    private fun mapMoviePlayback(item: JsonObject): WatchProgress? {
        val movie = item.getAsJsonObject("movie") ?: return null
        val ids = movie.getAsJsonObject("ids") ?: return null
        val contentId = ids.toCanonicalContentId() ?: return null
        val title = movie.stringValue("title") ?: contentId
        val runtimeMs = ((movie.numberValue("runtime")?.toLong() ?: 90L) * 60_000L).coerceAtLeast(1L)
        val percent = item.numberValue("progress")?.toFloat() ?: return null
        return WatchProgress(
            contentId = contentId,
            contentType = "movie",
            name = title,
            poster = null,
            backdrop = null,
            logo = null,
            videoId = contentId,
            season = null,
            episode = null,
            episodeTitle = null,
            position = ((runtimeMs * (percent / 100f))).toLong(),
            duration = runtimeMs,
            lastWatched = parseIsoMillis(item.stringValue("paused_at")),
            progressPercent = percent,
            source = "simkl_playback"
        )
    }

    private fun mapEpisodePlayback(item: JsonObject): WatchProgress? {
        val show = item.getAsJsonObject("show") ?: return null
        val episode = item.getAsJsonObject("episode") ?: return null
        val ids = show.getAsJsonObject("ids") ?: return null
        val contentId = ids.toCanonicalContentId() ?: return null
        val title = show.stringValue("title") ?: contentId
        val seasonNumber = episode.numberValue("tvdb_season")?.toInt()
            ?: episode.numberValue("season")?.toInt()
            ?: return null
        val episodeNumber = episode.numberValue("tvdb_number")?.toInt()
            ?: episode.numberValue("episode")?.toInt()
            ?: return null
        val runtimeMs = ((show.numberValue("runtime")?.toLong() ?: 45L) * 60_000L).coerceAtLeast(1L)
        val percent = item.numberValue("progress")?.toFloat() ?: return null
        return WatchProgress(
            contentId = contentId,
            contentType = "series",
            name = title,
            poster = null,
            backdrop = null,
            logo = null,
            videoId = "$contentId:$seasonNumber:$episodeNumber",
            season = seasonNumber,
            episode = episodeNumber,
            episodeTitle = episode.stringValue("title"),
            position = ((runtimeMs * (percent / 100f))).toLong(),
            duration = runtimeMs,
            lastWatched = parseIsoMillis(item.stringValue("paused_at")),
            progressPercent = percent,
            source = "simkl_playback"
        )
    }

    private suspend fun fetchJsonObject(
        url: String,
        accessToken: String,
        method: String = "GET"
    ): JsonObject? {
        val text = executeRequest(url = url, accessToken = accessToken, method = method) ?: return null
        return runCatching { gson.fromJson(text, JsonObject::class.java) }
            .onFailure { Log.w("SimklProgress", "Failed to parse object for $url", it) }
            .getOrNull()
    }

    private suspend fun fetchJsonArray(
        url: String,
        accessToken: String
    ): JsonArray? {
        val text = executeRequest(url = url, accessToken = accessToken) ?: return null
        return runCatching { gson.fromJson(text, JsonArray::class.java) }
            .onFailure { Log.w("SimklProgress", "Failed to parse array for $url", it) }
            .getOrNull()
    }

    private suspend fun fetchJsonArrayAsObjects(
        url: String,
        accessToken: String
    ): List<JsonObject> {
        val array = fetchJsonArray(url = url, accessToken = accessToken) ?: return emptyList()
        return array.mapNotNull { element ->
            runCatching { element.asJsonObject }.getOrNull()
        }
    }

    private suspend fun executeRequest(
        url: String,
        accessToken: String,
        method: String = "GET"
    ): String? = withContext(Dispatchers.IO) {
        val builder = Request.Builder()
            .url(url.toHttpUrlOrNull() ?: return@withContext null)
            .header("Authorization", "Bearer $accessToken")
        val request = if (method == "POST") {
            builder.post(ByteArray(0).toRequestBody(null)).build()
        } else {
            builder.get().build()
        }
        requestGate.acquire {
            runCatching {
                okHttpClient.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) {
                        Log.w("SimklProgress", "Request failed ${response.code} ${request.url}")
                        return@use null
                    }
                    response.body?.string()
                }
            }.getOrElse {
                Log.w("SimklProgress", "Request error ${request.url}", it)
                null
            }
        }
    }

    private fun JsonObject.toCanonicalContentId(): String? {
        stringValue("imdb")?.let { return it }
        stringValue("tmdb")?.let { return "tmdb:$it" }
        numberValue("tmdb")?.toString()?.let { return "tmdb:$it" }
        stringValue("simkl")?.let { return "simkl:$it" }
        stringValue("simkl_id")?.let { return "simkl:$it" }
        numberValue("simkl")?.toString()?.let { return "simkl:$it" }
        numberValue("simkl_id")?.toString()?.let { return "simkl:$it" }
        return null
    }

    private fun JsonObject.stringValue(key: String): String? =
        runCatching { get(key) }
            .getOrNull()
            ?.takeIf { !it.isJsonNull }
            ?.asString
            ?.trim()
            ?.takeIf { it.isNotBlank() }

    private fun JsonObject.numberValue(key: String): Number? =
        runCatching { get(key) }
            .getOrNull()
            ?.takeIf { !it.isJsonNull }
            ?.asNumber

    private fun parseIsoMillis(value: String?): Long {
        if (value.isNullOrBlank()) return System.currentTimeMillis()
        return runCatching { Instant.parse(value).toEpochMilli() }
            .getOrElse { System.currentTimeMillis() }
    }

    private fun parseNextToWatch(value: String): Pair<Int, Int>? {
        val trimmed = value.trim()
        Regex("""S(\d+)E(\d+)""", RegexOption.IGNORE_CASE).find(trimmed)?.let { match ->
            return match.groupValues[1].toIntOrNull()?.let { season ->
                match.groupValues[2].toIntOrNull()?.let { episode -> season to episode }
            }
        }
        Regex("""E(\d+)""", RegexOption.IGNORE_CASE).find(trimmed)?.let { match ->
            return match.groupValues[1].toIntOrNull()?.let { 1 to it }
        }
        return null
    }

    private fun resolveEpisodeNumbers(
        episode: JsonObject,
        fallbackSeasonNumber: Int
    ): Pair<Int, Int>? {
        val tvdb = episode.getAsJsonObject("tvdb")
        val seasonNumber = tvdb?.numberValue("season")?.toInt()
            ?: episode.numberValue("tvdb_season")?.toInt()
            ?: episode.numberValue("season")?.toInt()
            ?: fallbackSeasonNumber
        val episodeNumber = tvdb?.numberValue("episode")?.toInt()
            ?: episode.numberValue("tvdb_number")?.toInt()
            ?: episode.numberValue("episode")?.toInt()
            ?: episode.numberValue("number")?.toInt()
            ?: return null
        return seasonNumber to episodeNumber
    }

    private fun buildPlaybackUrl(type: String, dateFrom: String?): String {
        val base = "https://api.simkl.com/sync/playback/$type"
        return if (dateFrom.isNullOrBlank()) base else "$base?date_from=$dateFrom"
    }

    private fun buildAllItemsUrl(type: String, dateFrom: String?, extended: String): String {
        val base = "https://api.simkl.com/sync/all-items/$type/?extended=$extended"
        return if (dateFrom.isNullOrBlank()) base else "$base&date_from=$dateFrom"
    }
}

private fun maxOfNotNull(vararg values: String?): String? =
    values.filterNotNull().maxByOrNull { it }
