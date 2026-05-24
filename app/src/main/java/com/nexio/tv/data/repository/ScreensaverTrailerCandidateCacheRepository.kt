package com.nexio.tv.data.repository

import android.content.Context
import com.google.gson.Gson
import com.google.gson.stream.JsonReader
import com.google.gson.stream.JsonWriter
import com.nexio.tv.core.media.ClipSite
import com.nexio.tv.core.media.Confidence
import com.nexio.tv.core.media.ContentIdentity
import com.nexio.tv.core.media.MediaClipCandidate
import com.nexio.tv.core.media.MediaClipPlaybackRef
import com.nexio.tv.core.media.MediaClipScope
import com.nexio.tv.core.media.MediaClipSource
import com.nexio.tv.core.media.MediaClipStore
import com.nexio.tv.core.media.MediaClipType
import com.nexio.tv.core.metadata.router.resolver.TrailerPlaybackRef
import com.nexio.tv.data.integration.trailer.TrailerTmdbProvider
import com.nexio.tv.data.integration.trailer.TrailerTmdbVideoProvider
import com.nexio.tv.data.trailer.rankedTmdbTrailerYoutubeIds
import com.nexio.tv.domain.model.ContentType
import com.nexio.tv.domain.model.ResolvedDisplayItem
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.BufferedReader
import java.io.BufferedWriter
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.nio.file.Files
import java.nio.file.StandardCopyOption.ATOMIC_MOVE
import java.nio.file.StandardCopyOption.REPLACE_EXISTING
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

private const val DEFAULT_STATE_FILE = "screensaver-trailer-candidate-cache-v1/state.json"
internal const val SCREENSAVER_TRAILER_CANDIDATE_TTL_MS: Long = 48L * 60L * 60L * 1000L

enum class ScreensaverTrailerCandidateCacheStatus {
    HIT,
    REFRESHED,
    STALE_FALLBACK,
    FAILED_EMPTY
}

data class ScreensaverTrailerCandidateCacheResult(
    val status: ScreensaverTrailerCandidateCacheStatus,
    val refreshedAtMs: Long?,
    val itemCount: Int,
    val storedCandidateCount: Int,
    val youtubeIds: List<String>,
    val playbackRefs: List<TrailerPlaybackRef>,
    val extractedVideoUrl: String? = null,
    val extractedAudioUrl: String? = null
)

@Singleton
class ScreensaverTrailerCandidateCacheRepository internal constructor(
    @ApplicationContext private val context: Context,
    private val trailerTmdbProvider: TrailerTmdbVideoProvider,
    private val mediaClipStore: MediaClipStore,
    private val clock: () -> Long,
    private val stateFileName: String = DEFAULT_STATE_FILE,
    @Suppress("UNUSED_PARAMETER") private val testOnlyConstructor: Boolean
) {
    @Inject
    constructor(
        @ApplicationContext context: Context,
        trailerTmdbProvider: TrailerTmdbProvider,
        mediaClipStore: MediaClipStore
    ) : this(
        context = context,
        trailerTmdbProvider = trailerTmdbProvider,
        mediaClipStore = mediaClipStore,
        clock = { System.currentTimeMillis() },
        testOnlyConstructor = false
    )

    private val gson = Gson()
    private val mutex = Mutex()
    private val stateFile: File
        get() = File(context.filesDir, stateFileName)

    suspend fun ensureFreshTmdbTrendingTrailerCandidates(
        profileId: Int,
        items: List<ResolvedDisplayItem>,
        ttlMs: Long = SCREENSAVER_TRAILER_CANDIDATE_TTL_MS
    ): ScreensaverTrailerCandidateCacheResult = withContext(Dispatchers.IO) {
        mutex.withLock {
            val now = clock()
            val existing = readState()
            val profileState = existing.profiles[profileId.toString()]
            if (profileState != null && now - profileState.refreshedAtMs < ttlMs) {
                return@withLock ScreensaverTrailerCandidateCacheResult(
                    status = ScreensaverTrailerCandidateCacheStatus.HIT,
                    refreshedAtMs = profileState.refreshedAtMs,
                    itemCount = items.size,
                    storedCandidateCount = 0,
                    youtubeIds = emptyList(),
                    playbackRefs = emptyList()
                )
            }

            val apiKey = trailerTmdbProvider.getTmdbApiKey()
            if (apiKey.isNullOrBlank()) {
                return@withLock failedResult(profileState, items.size)
            }

            val candidates = mutableListOf<MediaClipCandidate>()
            val youtubeIds = mutableListOf<String>()
            for (i in items.indices) {
                val item = items[i]
                val tmdbId = item.stableIds.tmdb?.toIntOrNull() ?: continue
                val videos = try {
                    if (item.itemType == ContentType.MOVIE) {
                        trailerTmdbProvider.fetchMovieVideos(tmdbId, "en-US", apiKey)
                    } else {
                        trailerTmdbProvider.fetchTvVideos(tmdbId, "en-US", apiKey)
                    }
                } catch (_: Exception) {
                    return@withLock failedResult(profileState, items.size)
                }
                val rankedIds = rankedTmdbTrailerYoutubeIds(videos)
                if (rankedIds.isEmpty()) continue
                youtubeIds += rankedIds
                for (rank in rankedIds.indices) {
                    candidates += item.toMediaClipCandidate(
                        tmdbId = tmdbId,
                        youtubeId = rankedIds[rank],
                        rank = rank,
                        nowMs = now
                    )
                }
            }

            val stored = mediaClipStore.storeCandidates(
                candidates = candidates,
                freshTtlMs = ttlMs,
                staleTtlMs = ttlMs
            )
            writeState(existing.withProfile(profileId, now, items.size, stored))
            val distinctIds = youtubeIds.distinct()
            ScreensaverTrailerCandidateCacheResult(
                status = ScreensaverTrailerCandidateCacheStatus.REFRESHED,
                refreshedAtMs = now,
                itemCount = items.size,
                storedCandidateCount = stored,
                youtubeIds = distinctIds,
                playbackRefs = distinctIds.map(TrailerPlaybackRef::YouTubeId)
            )
        }
    }

    internal suspend fun testOnlyMarkFresh(profileId: Int, refreshedAtMs: Long) {
        mutex.withLock {
            writeState(readState().withProfile(profileId, refreshedAtMs, itemCount = 0, storedCandidateCount = 0))
        }
    }

    private fun failedResult(
        profileState: ProfileState?,
        itemCount: Int
    ): ScreensaverTrailerCandidateCacheResult =
        ScreensaverTrailerCandidateCacheResult(
            status = if (profileState != null) {
                ScreensaverTrailerCandidateCacheStatus.STALE_FALLBACK
            } else {
                ScreensaverTrailerCandidateCacheStatus.FAILED_EMPTY
            },
            refreshedAtMs = profileState?.refreshedAtMs,
            itemCount = itemCount,
            storedCandidateCount = 0,
            youtubeIds = emptyList(),
            playbackRefs = emptyList()
        )

    private fun ResolvedDisplayItem.toMediaClipCandidate(
        tmdbId: Int,
        youtubeId: String,
        rank: Int,
        nowMs: Long
    ): MediaClipCandidate {
        val identity = ContentIdentity(
            contentId = "tmdb:$tmdbId",
            itemType = itemType.toApiString(),
            stableIds = stableIds.copy(tmdb = tmdbId.toString())
        )
        return MediaClipCandidate(
            clipId = "screensaver:tmdb:${itemType.toApiString()}:$tmdbId:$rank:$youtubeId",
            contentId = identity,
            provider = "TMDB",
            source = MediaClipSource.PROVIDER,
            scope = MediaClipScope.Title(identity),
            clipType = MediaClipType.TRAILER,
            title = display.title,
            language = "en",
            site = ClipSite.YOUTUBE,
            externalVideoId = youtubeId,
            playbackRef = MediaClipPlaybackRef.YouTubeId(youtubeId),
            confidence = if (rank == 0) Confidence.HIGH else Confidence.MEDIUM,
            sourceTrace = listOf("screensaver.trailer_candidate_cache"),
            fetchedAtMs = nowMs
        )
    }

    private fun readState(): StoreState {
        val file = stateFile
        if (!file.exists()) return StoreState()
        return runCatching {
            FileInputStream(file).use { fis ->
                BufferedReader(InputStreamReader(fis, Charsets.UTF_8)).use { br ->
                    JsonReader(br).use { reader ->
                        gson.fromJson(reader, StoreState::class.java) ?: StoreState()
                    }
                }
            }
        }.getOrElse { StoreState() }
    }

    private suspend fun writeState(state: StoreState) = withContext(Dispatchers.IO) {
        val file = stateFile
        file.parentFile?.mkdirs()
        val temp = File(file.parentFile, "${file.name}.tmp")
        FileOutputStream(temp).use { fos ->
            BufferedWriter(OutputStreamWriter(fos, Charsets.UTF_8)).use { bw ->
                JsonWriter(bw).use { writer ->
                    gson.toJson(state, StoreState::class.java, writer)
                }
            }
        }
        Files.move(temp.toPath(), file.toPath(), ATOMIC_MOVE, REPLACE_EXISTING)
    }

    private data class StoreState(
        val profiles: Map<String, ProfileState> = emptyMap()
    ) {
        fun withProfile(
            profileId: Int,
            refreshedAtMs: Long,
            itemCount: Int,
            storedCandidateCount: Int
        ): StoreState =
            copy(
                profiles = profiles + (
                    profileId.toString() to ProfileState(
                        refreshedAtMs = refreshedAtMs,
                        itemCount = itemCount,
                        storedCandidateCount = storedCandidateCount
                    )
                )
            )
    }

    private data class ProfileState(
        val refreshedAtMs: Long = 0L,
        val itemCount: Int = 0,
        val storedCandidateCount: Int = 0
    )
}
