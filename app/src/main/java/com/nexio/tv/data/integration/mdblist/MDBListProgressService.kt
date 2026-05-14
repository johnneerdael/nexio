package com.nexio.tv.data.integration.mdblist

import android.util.Log
import com.nexio.tv.core.profile.ProfileManager
import com.nexio.tv.data.remote.api.MDBListApi
import com.nexio.tv.data.remote.dto.mdblist.MDBListPlaybackResponseDto
import com.nexio.tv.data.remote.dto.mdblist.MDBListSyncIdsDto
import com.nexio.tv.data.remote.dto.mdblist.MDBListWatchedResponseDto
import com.nexio.tv.data.repository.MDBListSettingsReader
import com.nexio.tv.domain.model.WatchProgress
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import java.time.Instant
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
@OptIn(ExperimentalCoroutinesApi::class)
class MDBListProgressService @Inject constructor(
    private val api: MDBListApi,
    private val settingsReader: MDBListSettingsReader,
    private val profileManager: ProfileManager,
) {
    private class RuntimeState {
        val allProgress = MutableStateFlow<List<WatchProgress>>(emptyList())
        val loaded = MutableStateFlow(false)

        fun clear() {
            allProgress.value = emptyList()
            loaded.value = false
        }
    }

    private val states = mutableMapOf<Int, RuntimeState>()

    private fun activeProfileId(): Int = profileManager.activeProfileId.value.takeIf { it in 1..4 } ?: 1

    private fun stateFor(profileId: Int = activeProfileId()): RuntimeState =
        synchronized(states) {
            states.getOrPut(profileId) { RuntimeState() }
        }

    fun observeAllProgress(): Flow<List<WatchProgress>> =
        profileManager.activeProfileId.flatMapLatest { profileId -> stateFor(profileId).allProgress }

    fun observeRemoteSnapshotLoaded(): Flow<Boolean> =
        profileManager.activeProfileId.flatMapLatest { profileId -> stateFor(profileId).loaded }

    fun observeEpisodeProgress(contentId: String): Flow<Map<Pair<Int, Int>, WatchProgress>> =
        profileManager.activeProfileId.flatMapLatest { profileId ->
            stateFor(profileId).allProgress.map { rows ->
                rows.asSequence()
                    .filter { it.contentId == contentId && it.season != null && it.episode != null }
                    .associateBy { it.season!! to it.episode!! }
            }
        }

    fun observeMovieWatched(contentId: String): Flow<Boolean> =
        profileManager.activeProfileId.flatMapLatest { profileId ->
            stateFor(profileId).allProgress.map { rows ->
                rows.any { progress ->
                    progress.contentId == contentId &&
                        progress.season == null &&
                        progress.episode == null &&
                        progress.isCompleted()
                }
            }
        }

    suspend fun refreshNowImmediate(profileId: Int? = null) {
        val profileId = profileId?.takeIf { it in 1..4 } ?: activeProfileId()
        val runtime = stateFor(profileId)
        val settings = settingsReader.settingsForProfile(profileId).first()
        val apiKey = settings.apiKey.trim()
        if (!settings.enabled || apiKey.isBlank()) {
            runtime.clear()
            return
        }

        val playback = runCatching { api.getPlayback(apiKey = apiKey) }
            .onFailure { Log.w(TAG, "Failed to load MDBList playback: ${it.message}") }
            .getOrNull()
        val watched = runCatching { api.getWatched(apiKey = apiKey, limit = 1000, offset = 0) }
            .onFailure { Log.w(TAG, "Failed to load MDBList watched: ${it.message}") }
            .getOrNull()

        val playbackRows = playback?.body()?.toPlaybackProgress().orEmpty()
        val watchedRows = watched?.body()?.toWatchedProgress().orEmpty()
        runtime.allProgress.value = (playbackRows + watchedRows).sortedByDescending { it.lastWatched }
        runtime.loaded.value = playback?.isSuccessful == true || watched?.isSuccessful == true
    }

    companion object {
        private const val TAG = "MDBListProgress"
    }
}

private fun MDBListPlaybackResponseDto.toPlaybackProgress(): List<WatchProgress> {
    val out = ArrayList<WatchProgress>()
    movies.orEmpty().mapNotNullTo(out) { row ->
        val movie = row.movie ?: return@mapNotNullTo null
        val id = movie.ids.bestContentId() ?: return@mapNotNullTo null
        WatchProgress(
            contentId = id,
            contentType = "movie",
            name = movie.title.orEmpty(),
            poster = null,
            backdrop = null,
            logo = null,
            videoId = id,
            season = null,
            episode = null,
            episodeTitle = null,
            position = 0L,
            duration = 0L,
            lastWatched = row.pausedAt.toEpochMs(),
            progressPercent = row.progress?.toFloat(),
            source = WatchProgress.SOURCE_MDBLIST_PLAYBACK,
        )
    }
    episodes.orEmpty().mapNotNullTo(out) { row ->
        val episode = row.episode ?: return@mapNotNullTo null
        val show = episode.show ?: row.show ?: return@mapNotNullTo null
        val id = show.ids.bestContentId() ?: return@mapNotNullTo null
        val season = episode.season ?: return@mapNotNullTo null
        val number = episode.number ?: return@mapNotNullTo null
        WatchProgress(
            contentId = id,
            contentType = "series",
            name = show.title.orEmpty(),
            poster = null,
            backdrop = null,
            logo = null,
            videoId = "$id:$season:$number",
            season = season,
            episode = number,
            episodeTitle = episode.name,
            position = 0L,
            duration = 0L,
            lastWatched = row.pausedAt.toEpochMs(),
            progressPercent = row.progress?.toFloat(),
            source = WatchProgress.SOURCE_MDBLIST_PLAYBACK,
        )
    }
    return out
}

private fun MDBListWatchedResponseDto.toWatchedProgress(): List<WatchProgress> {
    val out = ArrayList<WatchProgress>()
    movies.orEmpty().mapNotNullTo(out) { row ->
        val movie = row.movie ?: return@mapNotNullTo null
        val id = movie.ids.bestContentId() ?: return@mapNotNullTo null
        WatchProgress(
            contentId = id,
            contentType = "movie",
            name = movie.title.orEmpty(),
            poster = null,
            backdrop = null,
            logo = null,
            videoId = id,
            season = null,
            episode = null,
            episodeTitle = null,
            position = 1L,
            duration = 1L,
            lastWatched = row.lastWatchedAt.toEpochMs(),
            progressPercent = 100f,
            source = WatchProgress.SOURCE_MDBLIST_HISTORY,
        )
    }
    episodes.orEmpty().mapNotNullTo(out) { row ->
        val episode = row.episode ?: return@mapNotNullTo null
        val show = episode.show ?: return@mapNotNullTo null
        val id = show.ids.bestContentId() ?: return@mapNotNullTo null
        val season = episode.season ?: return@mapNotNullTo null
        val number = episode.number ?: return@mapNotNullTo null
        WatchProgress(
            contentId = id,
            contentType = "series",
            name = show.title.orEmpty(),
            poster = null,
            backdrop = null,
            logo = null,
            videoId = "$id:$season:$number",
            season = season,
            episode = number,
            episodeTitle = episode.name,
            position = 1L,
            duration = 1L,
            lastWatched = row.lastWatchedAt.toEpochMs(),
            progressPercent = 100f,
            source = WatchProgress.SOURCE_MDBLIST_HISTORY,
        )
    }
    return out
}

private fun MDBListSyncIdsDto?.bestContentId(): String? {
    val ids = this ?: return null
    return ids.imdb?.takeIf { it.isNotBlank() }
        ?: ids.tmdb?.let { "tmdb:$it" }
        ?: ids.tvdb?.let { "tvdb:$it" }
        ?: ids.trakt?.let { "trakt:$it" }
        ?: ids.kitsu?.let { "kitsu:$it" }
        ?: ids.mdblist?.takeIf { it.isNotBlank() }?.let { "mdblist:$it" }
}

private fun String?.toEpochMs(): Long {
    val value = this?.takeIf { it.isNotBlank() } ?: return System.currentTimeMillis()
    return runCatching { Instant.parse(value).toEpochMilli() }.getOrDefault(System.currentTimeMillis())
}
