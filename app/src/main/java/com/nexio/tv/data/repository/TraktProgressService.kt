package com.nexio.tv.data.repository

import android.util.Log
import com.nexio.tv.BuildConfig
import com.nexio.tv.core.integration.IntegrationCallResult
import com.nexio.tv.core.metadata.router.MetadataDepth
import com.nexio.tv.core.metadata.router.MetadataRequest
import com.nexio.tv.core.metadata.router.MetadataRouterFacade
import com.nexio.tv.core.metadata.router.MetadataSourceContext
import com.nexio.tv.core.tvdb.TvMetadataRequest
import com.nexio.tv.data.integration.trakt.TraktIntegrationProvider
import com.nexio.tv.data.integration.trakt.TraktWatchedKind
import com.nexio.tv.data.remote.dto.trakt.TraktEpisodeDto
import com.nexio.tv.data.remote.dto.trakt.TraktLastActivitiesResponseDto
import com.nexio.tv.data.remote.dto.trakt.TraktEpisodeSummaryDto
import com.nexio.tv.data.remote.dto.trakt.TraktHistoryEpisodeAddDto
import com.nexio.tv.data.remote.dto.trakt.TraktHistoryAddRequestDto
import com.nexio.tv.data.remote.dto.trakt.TraktHistoryAddResponseDto
import com.nexio.tv.data.remote.dto.trakt.TraktHistorySeasonAddDto
import com.nexio.tv.data.remote.dto.trakt.TraktHistoryShowAddDto
import com.nexio.tv.data.remote.dto.trakt.TraktHistoryMovieAddDto
import com.nexio.tv.data.remote.dto.trakt.TraktHistoryEpisodeRemoveDto
import com.nexio.tv.data.remote.dto.trakt.TraktHistoryRemoveRequestDto
import com.nexio.tv.data.remote.dto.trakt.TraktHistorySeasonRemoveDto
import com.nexio.tv.data.remote.dto.trakt.TraktHistoryShowRemoveDto
import com.nexio.tv.data.remote.dto.trakt.TraktHiddenItemDto
import com.nexio.tv.data.remote.dto.trakt.TraktMovieDto
import com.nexio.tv.data.remote.dto.trakt.TraktIdsDto
import com.nexio.tv.data.remote.dto.trakt.TraktPlaybackItemDto
import com.nexio.tv.data.remote.dto.trakt.TraktShowSeasonProgressDto
import com.nexio.tv.data.remote.dto.trakt.TraktUserEpisodeHistoryItemDto
import com.nexio.tv.data.remote.dto.trakt.TraktWatchedShowItemDto
import com.nexio.tv.data.repository.trakt.TraktProgressMutationExecutor
import com.nexio.tv.core.anime.AnimeIdMappingService
import com.nexio.tv.core.anime.AnimeIdSource
import com.nexio.tv.core.anime.AnimeStremioId
import com.nexio.tv.core.anime.ContentMediaKind
import com.nexio.tv.domain.model.TrackingProvider
import com.nexio.tv.domain.model.WatchProgress
import com.nexio.tv.domain.model.ContentType
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import retrofit2.Response
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.abs
import javax.inject.Inject
import javax.inject.Singleton
import androidx.annotation.VisibleForTesting

internal fun shouldPreferEpisodeHistoryEntry(
    existing: WatchProgress,
    candidate: WatchProgress
): Boolean {
    if (candidate.lastWatched != existing.lastWatched) {
        return candidate.lastWatched > existing.lastWatched
    }
    val existingSeason = existing.season ?: -1
    val candidateSeason = candidate.season ?: -1
    if (candidateSeason != existingSeason) {
        return candidateSeason > existingSeason
    }
    val existingEpisode = existing.episode ?: -1
    val candidateEpisode = candidate.episode ?: -1
    return candidateEpisode > existingEpisode
}

@Singleton
@OptIn(ExperimentalCoroutinesApi::class)
class TraktProgressService @Inject constructor(
    private val traktIntegrationProvider: TraktIntegrationProvider,
    private val traktProgressMutationExecutor: TraktProgressMutationExecutor,
    private val metadataRouterFacade: MetadataRouterFacade,
    private val animeIdMappingService: AnimeIdMappingService = AnimeIdMappingService { com.nexio.tv.core.anime.AnimeIdMapAsset(schemaVersion = 0) }
) {
    data class NextUpEntry(
        val contentId: String,
        val contentType: String = "series",
        val name: String,
        val season: Int,
        val episode: Int,
        val episodeTitle: String?,
        val videoId: String,
        val firstAired: String?,
        val firstAiredMs: Long,
        val activityAtMs: Long,
        val poster: String? = null,
        val backdrop: String? = null,
        val logo: String? = null,
        val traktShowId: Int? = null,
        val traktEpisodeId: Int? = null
    )

    companion object {
        private const val TAG = "TraktProgressSvc"
    }

    private fun trace(message: String) {
        if (BuildConfig.DEBUG) {
            Log.d(TAG, message)
        }
    }

    data class TraktCachedStats(
        val moviesWatched: Int = 0,
        val showsWatched: Int = 0,
        val episodesWatched: Int = 0,
        val totalWatchedHours: Int = 0
    )

    internal data class TimedCache<T>(
        val value: T,
        val updatedAtMs: Long
    )

    internal data class EpisodeProgressCacheEntry(
        val progress: Map<Pair<Int, Int>, WatchProgress>,
        val updatedAtMs: Long,
        val activityVersion: Long,
        val hasCompletedSnapshot: Boolean
    )

    private data class EpisodeProgressFetchResult(
        val progress: Map<Pair<Int, Int>, WatchProgress>,
        val hasCompletedSnapshot: Boolean
    )

    internal data class OptimisticProgressEntry(
        val progress: WatchProgress,
        val expiresAtMs: Long
    )

    internal data class EpisodeMetadata(
        val title: String?,
        val thumbnail: String?
    )

    internal data class WatchedShowIndexEntry(
        val canonicalContentId: String,
        val aliasContentIds: Set<String>,
        val name: String,
        val lastWatchedAtMs: Long,
        val resetAtMs: Long?,
        val traktShowId: Int?,
        val watchedEpisodes: Set<Pair<Int, Int>>
    )

    internal data class HiddenProgressSnapshot(
        val hiddenShowIds: Set<String> = emptySet(),
        val hiddenSeasonKeys: Set<String> = emptySet(),
        val droppedShowIds: Set<String> = emptySet()
    )

    internal data class ShowNextUpCacheEntry(
        val entry: NextUpEntry?,
        val updatedAtMs: Long,
        val activityVersion: Long,
        val hasCompletedSnapshot: Boolean
    )

    private data class DerivedNextUpCandidate(
        val entry: NextUpEntry,
        val weakDerivation: Boolean
    )

    internal data class CachedNextUpValidation(
        val result: TraktNextUpValidationResult,
        val updatedAtMs: Long,
        val ttlMs: Long
    ) {
        fun freshness(): TraktNextUpValidationCacheEntry {
            return TraktNextUpValidationCacheEntry(
                updatedAtMs = updatedAtMs,
                ttlMs = ttlMs
            )
        }
    }

    private sealed interface ShowNextUpFetchResult {
        data class Success(val entry: NextUpEntry?, val hasCompletedSnapshot: Boolean) : ShowNextUpFetchResult
        data object Failure : ShowNextUpFetchResult
    }

    internal data class ContentMetadata(
        val name: String?,
        val poster: String?,
        val backdrop: String?,
        val logo: String?,
        val episodes: Map<Pair<Int, Int>, EpisodeMetadata>
    )

    private data class TraktProgressRuntimeState(
        val remoteProgress: MutableStateFlow<List<WatchProgress>> = MutableStateFlow(emptyList()),
        val myShowsNextUp: MutableStateFlow<List<NextUpEntry>> = MutableStateFlow(emptyList()),
        val myShowsNextUpAll: MutableStateFlow<List<NextUpEntry>> = MutableStateFlow(emptyList()),
        val optimisticProgress: MutableStateFlow<Map<String, OptimisticProgressEntry>> = MutableStateFlow(emptyMap()),
        val metadataState: MutableStateFlow<Map<String, ContentMetadata>> = MutableStateFlow(emptyMap()),
        val hiddenProgressState: MutableStateFlow<HiddenProgressSnapshot> = MutableStateFlow(HiddenProgressSnapshot()),
        val episodeProgressState: MutableStateFlow<Map<String, EpisodeProgressCacheEntry>> = MutableStateFlow(emptyMap()),
        val showNextUpState: MutableStateFlow<Map<String, ShowNextUpCacheEntry>> = MutableStateFlow(emptyMap()),
        val nextUpValidationCache: MutableMap<String, CachedNextUpValidation> = mutableMapOf(),
        val nextUpValidationBypassKeys: MutableSet<String> = mutableSetOf(),
        val hasLoadedRemoteProgress: MutableStateFlow<Boolean> = MutableStateFlow(false),
        val inFlightMetadataKeys: MutableSet<String> = mutableSetOf(),
        val inFlightEpisodeProgressKeys: MutableSet<String> = mutableSetOf(),
        val inFlightShowNextUpKeys: MutableSet<String> = mutableSetOf(),
        val episodeProgressLastAttemptAtMs: MutableMap<String, Long> = mutableMapOf(),
        val showNextUpLastAttemptAtMs: MutableMap<String, Long> = mutableMapOf(),
        var cachedMoviesPlayback: TimedCache<List<TraktPlaybackItemDto>>? = null,
        var cachedEpisodesPlayback: TimedCache<List<TraktPlaybackItemDto>>? = null,
        var cachedUserStats: TimedCache<TraktCachedStats>? = null,
        var forceRefreshUntilMs: Long = 0L,
        var hiddenProgressUpdatedAtMs: Long = 0L,
        var hiddenProgressLastAttemptAtMs: Long = 0L,
        var hasLoadedHiddenProgress: Boolean = false,
        var hiddenProgressStale: Boolean = true,
        var lastFastSyncRequestMs: Long = 0L,
        var lastKnownActivityFingerprint: String? = null,
        var lastKnownMoviesWatchedAt: String? = null,
        var lastKnownEpisodeActivityFingerprint: String? = null,
        var lastKnownWatchedShowsFingerprint: String? = null,
        var lastKnownHiddenProgressFingerprint: String? = null,
        var lastManualRefreshSignalMs: Long = 0L,
        val episodeProgressActivityVersion: AtomicLong = AtomicLong(0L),
        val showNextUpActivityVersion: AtomicLong = AtomicLong(0L),
        val episodeInfoCache: MutableMap<String, ResolvedEpisodeInfo> = mutableMapOf(),
        var lastEventDrivenRefreshMs: Long = 0L,
        var eventDrivenRefreshFiringCount: Int = 0,
    ) {
        fun clear() {
            remoteProgress.value = emptyList()
            myShowsNextUp.value = emptyList()
            myShowsNextUpAll.value = emptyList()
            optimisticProgress.value = emptyMap()
            metadataState.value = emptyMap()
            hiddenProgressState.value = HiddenProgressSnapshot()
            episodeProgressState.value = emptyMap()
            showNextUpState.value = emptyMap()
            nextUpValidationCache.clear()
            nextUpValidationBypassKeys.clear()
            hasLoadedRemoteProgress.value = false
            inFlightMetadataKeys.clear()
            inFlightEpisodeProgressKeys.clear()
            inFlightShowNextUpKeys.clear()
            episodeProgressLastAttemptAtMs.clear()
            showNextUpLastAttemptAtMs.clear()
            cachedMoviesPlayback = null
            cachedEpisodesPlayback = null
            cachedUserStats = null
            forceRefreshUntilMs = 0L
            hiddenProgressUpdatedAtMs = 0L
            hiddenProgressLastAttemptAtMs = 0L
            hasLoadedHiddenProgress = false
            hiddenProgressStale = true
            lastFastSyncRequestMs = 0L
            lastKnownActivityFingerprint = null
            lastKnownMoviesWatchedAt = null
            lastKnownEpisodeActivityFingerprint = null
            lastKnownWatchedShowsFingerprint = null
            lastKnownHiddenProgressFingerprint = null
            lastManualRefreshSignalMs = 0L
            episodeProgressActivityVersion.set(0L)
            showNextUpActivityVersion.set(0L)
            episodeInfoCache.clear()
            lastEventDrivenRefreshMs = 0L
            eventDrivenRefreshFiringCount = 0
        }
    }

    private class TraktProgressRuntimeRegistry {
        private val states = java.util.concurrent.ConcurrentHashMap<Int, TraktProgressRuntimeState>()

        fun stateFor(session: TrackingRuntimeSession): TraktProgressRuntimeState {
            require(session.provider == com.nexio.tv.domain.model.TrackingProvider.TRAKT) {
                "TraktProgressRuntimeRegistry only accepts TRAKT sessions"
            }
            return states.computeIfAbsent(session.profileId) { TraktProgressRuntimeState() }
        }

        fun peek(profileId: Int): TraktProgressRuntimeState? = states[profileId]

        fun clearProfile(profileId: Int) {
            states[profileId]?.clear()
        }
    }

    private val exceptionHandler = CoroutineExceptionHandler { _, throwable ->
        Log.e(TAG, "Uncaught exception in TraktProgressService scope", throwable)
    }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO + exceptionHandler)
    private val refreshSignals = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    private val episodeInfoCache get() = runtimeState().episodeInfoCache
    private val runtimeRegistry = TraktProgressRuntimeRegistry()
    private val remoteProgress get() = runtimeState().remoteProgress
    private val myShowsNextUp get() = runtimeState().myShowsNextUp
    private val myShowsNextUpAll get() = runtimeState().myShowsNextUpAll
    private val optimisticProgress get() = runtimeState().optimisticProgress
    private val metadataState get() = runtimeState().metadataState
    private val hiddenProgressState get() = runtimeState().hiddenProgressState
    private val episodeProgressState get() = runtimeState().episodeProgressState
    private val showNextUpState get() = runtimeState().showNextUpState
    private val nextUpValidationCache get() = runtimeState().nextUpValidationCache
    private val nextUpValidationBypassKeys get() = runtimeState().nextUpValidationBypassKeys
    private val hasLoadedRemoteProgress get() = runtimeState().hasLoadedRemoteProgress
    private val cacheMutex = Mutex()
    private val metadataMutex = Mutex()
    private val hiddenProgressMutex = Mutex()
    private val episodeProgressMutex = Mutex()
    private val showNextUpMutex = Mutex()
    private val inFlightMetadataKeys get() = runtimeState().inFlightMetadataKeys
    private val inFlightEpisodeProgressKeys get() = runtimeState().inFlightEpisodeProgressKeys
    private val inFlightShowNextUpKeys get() = runtimeState().inFlightShowNextUpKeys
    private val episodeProgressLastAttemptAtMs get() = runtimeState().episodeProgressLastAttemptAtMs
    private val showNextUpLastAttemptAtMs get() = runtimeState().showNextUpLastAttemptAtMs
    private var cachedMoviesPlayback: TimedCache<List<TraktPlaybackItemDto>>?
        get() = runtimeState().cachedMoviesPlayback
        set(value) { runtimeState().cachedMoviesPlayback = value }
    private var cachedEpisodesPlayback: TimedCache<List<TraktPlaybackItemDto>>?
        get() = runtimeState().cachedEpisodesPlayback
        set(value) { runtimeState().cachedEpisodesPlayback = value }
    private var cachedUserStats: TimedCache<TraktCachedStats>?
        get() = runtimeState().cachedUserStats
        set(value) { runtimeState().cachedUserStats = value }
    private var forceRefreshUntilMs: Long
        get() = runtimeState().forceRefreshUntilMs
        set(value) { runtimeState().forceRefreshUntilMs = value }
    private var hiddenProgressUpdatedAtMs: Long
        get() = runtimeState().hiddenProgressUpdatedAtMs
        set(value) { runtimeState().hiddenProgressUpdatedAtMs = value }
    private var hiddenProgressLastAttemptAtMs: Long
        get() = runtimeState().hiddenProgressLastAttemptAtMs
        set(value) { runtimeState().hiddenProgressLastAttemptAtMs = value }
    private var hasLoadedHiddenProgress: Boolean
        get() = runtimeState().hasLoadedHiddenProgress
        set(value) { runtimeState().hasLoadedHiddenProgress = value }
    private var hiddenProgressStale: Boolean
        get() = runtimeState().hiddenProgressStale
        set(value) { runtimeState().hiddenProgressStale = value }
    private var lastFastSyncRequestMs: Long
        get() = runtimeState().lastFastSyncRequestMs
        set(value) { runtimeState().lastFastSyncRequestMs = value }
    private var lastKnownActivityFingerprint: String?
        get() = runtimeState().lastKnownActivityFingerprint
        set(value) { runtimeState().lastKnownActivityFingerprint = value }
    private var lastKnownMoviesWatchedAt: String?
        get() = runtimeState().lastKnownMoviesWatchedAt
        set(value) { runtimeState().lastKnownMoviesWatchedAt = value }
    private var lastKnownEpisodeActivityFingerprint: String?
        get() = runtimeState().lastKnownEpisodeActivityFingerprint
        set(value) { runtimeState().lastKnownEpisodeActivityFingerprint = value }
    private var lastKnownWatchedShowsFingerprint: String?
        get() = runtimeState().lastKnownWatchedShowsFingerprint
        set(value) { runtimeState().lastKnownWatchedShowsFingerprint = value }
    private var lastKnownHiddenProgressFingerprint: String?
        get() = runtimeState().lastKnownHiddenProgressFingerprint
        set(value) { runtimeState().lastKnownHiddenProgressFingerprint = value }
    private var lastManualRefreshSignalMs: Long
        get() = runtimeState().lastManualRefreshSignalMs
        set(value) { runtimeState().lastManualRefreshSignalMs = value }
    private val episodeProgressActivityVersion get() = runtimeState().episodeProgressActivityVersion
    private val showNextUpActivityVersion get() = runtimeState().showNextUpActivityVersion

    private fun runtimeState(): TraktProgressRuntimeState {
        return runtimeRegistry.stateFor(
            TrackingRuntimeSession(
                provider = com.nexio.tv.domain.model.TrackingProvider.TRAKT,
                profileId = traktIntegrationProvider.currentTraktProfileId()
            )
        )
    }

    /**
     * Returns a recent `/sync/last_activities` response. Deduplication is handled by the
     * runtime cache (CacheFirst, 5-min TTL) inside [traktIntegrationProvider.getLastActivities].
     *
     * [maxAgeMs] == 0L is the forced-refresh signal: invalidate the runtime cache first so
     * the next read goes to the wire. Any other value is advisory and ignored — the runtime
     * cache TTL is fixed at 5 minutes. The `hasActivityChanged()` polling path relies on
     * the 0L contract to pick up new Trakt activity within its own polling cadence.
     */
    suspend fun getRecentActivities(maxAgeMs: Long = 10_000L): TraktLastActivitiesResponseDto? {
        if (maxAgeMs == 0L) {
            traktIntegrationProvider.invalidateLastActivities()
        }
        return when (val result = traktIntegrationProvider.getLastActivities()) {
            is IntegrationCallResult.Success -> result.value
            else -> null
        }
    }

    private val playbackCacheTtlMs = 30_000L
    private val userStatsCacheTtlMs = Long.MAX_VALUE
    private val hiddenProgressCacheTtlMs = 10 * 60_000L
    private val hiddenProgressFetchThrottleMs = 15_000L
    private val episodeProgressCacheTtlMs = 5 * 60_000L
    private val episodeProgressFetchThrottleMs = 15_000L
    private val optimisticTtlMs = 3 * 60_000L
    private val maxRecentEpisodeHistoryEntries = 300
    private val metadataHydrationLimit = 110
    private val metadataFetchSemaphore = Semaphore(5)
    private val nextUpValidationSemaphore = Semaphore(2)
    private val fastSyncThrottleMs = 15_000L
    private val manualRefreshSignalThrottleMs = 3_000L
    private val eventDrivenRefreshThrottleMs = 30_000L
    private val nextUpValidationVisibleCandidateLimit = 30
    private val nextUpValidationBudget = 30
    private val nextUpValidationPositiveTtlMs = 10 * 60_000L
    private val nextUpValidationNegativeTtlMs = 5 * 60_000L
    private var lastEventDrivenRefreshMs: Long
        get() = runtimeState().lastEventDrivenRefreshMs
        set(value) { runtimeState().lastEventDrivenRefreshMs = value }

    init {
        scope.launch {
            refreshSignals.collectLatest {
                try {
                    refreshRemoteSnapshot()
                } catch (e: Exception) {
                    Log.w(TAG, "Failed to refresh remote snapshot", e)
                }
            }
        }
    }

    suspend fun refreshNow() {
        val now = System.currentTimeMillis()
        forceRefreshUntilMs = now + 30_000L
        if (now - lastManualRefreshSignalMs < manualRefreshSignalThrottleMs) {
            trace("refreshNow: suppressed duplicate signal (${now - lastManualRefreshSignalMs}ms since last)")
            return
        }
        lastManualRefreshSignalMs = now
        trace("refreshNow: emitting signal, force window active for 30s")
        refreshSignals.emit(Unit)
    }

    /**
     * Immediate refresh - bypasses the throttle window. Use for critical paths where the caller
     * needs fresh state right away (app startup, foreground resume, user-initiated refresh).
     */
    suspend fun refreshNowImmediate() {
        val now = System.currentTimeMillis()
        forceRefreshUntilMs = now + 30_000L
        lastManualRefreshSignalMs = now
        trace("refreshNowImmediate: emitting immediate signal, force window active for 30s")
        refreshSignals.emit(Unit)
    }

    /**
     * Event-driven refresh for navigation triggers (Home screen focus, Library focus, app resume).
     * Uses a 30-second throttle to prevent rapid re-fetching during normal navigation.
     * For mutation-driven refreshes, use [refreshNow] which has a shorter throttle.
     */
    suspend fun requestEventDrivenRefresh() {
        val now = System.currentTimeMillis()
        if (now - lastEventDrivenRefreshMs < eventDrivenRefreshThrottleMs) {
            trace("requestEventDrivenRefresh: suppressed (${now - lastEventDrivenRefreshMs}ms since last)")
            return
        }
        lastEventDrivenRefreshMs = now
        runtimeState().eventDrivenRefreshFiringCount += 1
        trace("requestEventDrivenRefresh: emitting signal")
        refreshSignals.emit(Unit)
    }

    @VisibleForTesting
    internal fun testOnlyEventDrivenRefreshFiringCount(profileId: Int): Int =
        runtimeRegistry.peek(profileId)?.eventDrivenRefreshFiringCount ?: 0

    suspend fun getCachedStats(forceRefresh: Boolean = false): TraktCachedStats? {
        val now = System.currentTimeMillis()
        cacheMutex.withLock {
            val cached = cachedUserStats
            if (!forceRefresh && cached != null && now - cached.updatedAtMs <= userStatsCacheTtlMs) {
                return cached.value
            }
        }

        val body = when (val result = traktIntegrationProvider.getUserStats(id = "me")) {
            is IntegrationCallResult.Success -> result.value
            else -> return null
        }

        val totalMinutes = (body.movies?.minutes ?: 0) + (body.episodes?.minutes ?: 0)
        val stats = TraktCachedStats(
            moviesWatched = body.movies?.watched ?: 0,
            showsWatched = body.shows?.watched ?: 0,
            episodesWatched = body.episodes?.watched ?: 0,
            totalWatchedHours = totalMinutes / 60
        )

        cacheMutex.withLock {
            cachedUserStats = TimedCache(value = stats, updatedAtMs = now)
        }
        return stats
    }

    fun applyOptimisticProgress(progress: WatchProgress) {
        val now = System.currentTimeMillis()
        val derivedPercent = when {
            progress.progressPercent != null -> progress.progressPercent
            progress.duration > 0L -> ((progress.position.toFloat() / progress.duration.toFloat()) * 100f)
            else -> null
        }?.coerceIn(0f, 100f)

        val optimistic = progress.copy(
            progressPercent = derivedPercent,
            source = WatchProgress.SOURCE_TRAKT_PLAYBACK,
            lastWatched = now
        )

        optimisticProgress.update { current ->
            current.toMutableMap().apply {
                this[progressKey(optimistic)] = OptimisticProgressEntry(
                    progress = optimistic,
                    expiresAtMs = now + optimisticTtlMs
                )
            }
        }
        requestFastSync()
    }

    fun applyOptimisticRemoval(contentId: String, season: Int?, episode: Int?) {
        val contentKeyPrefix = contentId.trim()
        optimisticProgress.update { current ->
            current.filterKeys { key ->
                if (season != null && episode != null) {
                    key != "${contentKeyPrefix}_s${season}e${episode}"
                } else {
                    key != contentKeyPrefix && !key.startsWith("${contentKeyPrefix}_s")
                }
            }
        }
        if (season != null && episode != null) {
            val canonicalKey = canonicalLookupKey(contentKeyPrefix)
            episodeProgressState.update { current ->
                val updated = current.toMutableMap()
                val existing = updated[canonicalKey] ?: return@update current
                updated[canonicalKey] = existing.copy(
                    progress = existing.progress - (season to episode)
                )
                updated
            }
        } else {
            val canonicalKey = canonicalLookupKey(contentKeyPrefix)
            episodeProgressState.update { current ->
                current.toMutableMap().apply {
                    remove(contentKeyPrefix)
                    remove(canonicalKey)
                }
            }
        }
        requestFastSync()
    }

    fun clearOptimistic() {
        optimisticProgress.value = emptyMap()
    }

    fun invalidateLocalizedMetadata() {
        metadataState.value = emptyMap()
        scope.launch {
            metadataMutex.withLock {
                inFlightMetadataKeys.clear()
            }
            hydrateMetadata(
                progressList = remoteProgress.value +
                    myShowsNextUpAll.value.map(::nextUpEntryToWatchProgress)
            )
        }
    }

    fun observeAllProgress(): Flow<List<WatchProgress>> {
        return combine(
            remoteProgress,
            optimisticProgress,
            metadataState,
            hasLoadedRemoteProgress
        ) { remote, optimistic, metadata, loaded ->
            val now = System.currentTimeMillis()
            val validOptimistic = optimistic
                .filterValues { it.expiresAtMs > now }
                .mapValues { it.value.progress }

            // Avoid emitting a transient empty state before first remote fetch completes.
            if (!loaded && remote.isEmpty() && validOptimistic.isEmpty()) {
                return@combine null
            }

            val mergedByKey = linkedMapOf<String, WatchProgress>()
            remote.forEach { mergedByKey[progressKey(it)] = it }
            validOptimistic.forEach { (key, value) -> mergedByKey[key] = value }
            mergedByKey.values
                .map { enrichWithMetadata(it, metadata) }
                .sortedByDescending { it.lastWatched }
        }
            .filterNotNull()
            .distinctUntilChanged()
    }

    fun observeRemoteSnapshotLoaded(): Flow<Boolean> {
        return hasLoadedRemoteProgress
    }

    fun observeContinueWatchingNextUp(): Flow<List<NextUpEntry>> {
        return combine(
            myShowsNextUp,
            metadataState
        ) { nextUp, metadata ->
            nextUp.map { entry ->
                val contentMetadata = metadata[entry.contentId]
                entry.copy(
                    name = contentMetadata?.name?.takeIf { it.isNotBlank() } ?: entry.name,
                    poster = entry.poster ?: contentMetadata?.poster,
                    backdrop = entry.backdrop ?: contentMetadata?.backdrop,
                    logo = entry.logo ?: contentMetadata?.logo,
                    episodeTitle = entry.episodeTitle
                        ?: contentMetadata?.episodes?.get(entry.season to entry.episode)?.title
                )
            }
        }.distinctUntilChanged()
    }

    fun observeSyntheticContinueWatchingNextUp(): Flow<List<NextUpEntry>> {
        return combine(
            myShowsNextUpAll,
            metadataState
        ) { nextUp, metadata ->
            nextUp.map { entry ->
                val contentMetadata = metadata[entry.contentId]
                entry.copy(
                    name = contentMetadata?.name?.takeIf { it.isNotBlank() } ?: entry.name,
                    poster = entry.poster ?: contentMetadata?.poster,
                    backdrop = entry.backdrop ?: contentMetadata?.backdrop,
                    logo = entry.logo ?: contentMetadata?.logo,
                    episodeTitle = entry.episodeTitle
                        ?: contentMetadata?.episodes?.get(entry.season to entry.episode)?.title
                )
            }
        }.distinctUntilChanged()
    }

    fun observeEpisodeProgress(contentId: String): Flow<Map<Pair<Int, Int>, WatchProgress>> {
        val rawKey = contentId.trim()
        val cacheKey = canonicalLookupKey(contentId)
        return flow {
            val watchedShows = getWatchedShowsSnapshot(forceRefresh = false)
            val watchedShowEntry = watchedShows[rawKey]
                ?: watchedShows[cacheKey]
                ?: watchedShows.values.firstOrNull { entry ->
                    entry.aliasContentIds.any { alias ->
                        alias == rawKey || alias == cacheKey
                    }
                }
            val baseFromSnapshot: Map<Pair<Int, Int>, WatchProgress> = watchedShowEntry?.watchedEpisodes
                ?.associateWith { (season, episode) ->
                    synthesizeWatchedProgress(
                        contentId = watchedShowEntry.canonicalContentId,
                        season = season,
                        episode = episode,
                        lastWatchedAtMs = watchedShowEntry.lastWatchedAtMs,
                        traktShowId = watchedShowEntry.traktShowId
                    )
                }
                ?: emptyMap()
            emit(baseFromSnapshot)
            emitAll(
                episodeProgressState
                    .map { state -> state[cacheKey]?.progress ?: emptyMap() }
                    .map { lazyEntries -> baseFromSnapshot + lazyEntries }
            )
        }
            .onStart {
                scope.launch {
                    ensureEpisodeProgressSnapshot(contentId = cacheKey, forceRefresh = false)
                }
            }
            .distinctUntilChanged()
    }

    private fun synthesizeWatchedProgress(
        contentId: String,
        season: Int,
        episode: Int,
        lastWatchedAtMs: Long,
        traktShowId: Int?
    ): WatchProgress {
        return WatchProgress(
            contentId = contentId,
            contentType = "series",
            name = contentId,
            poster = null,
            backdrop = null,
            logo = null,
            videoId = "$contentId:$season:$episode",
            season = season,
            episode = episode,
            episodeTitle = null,
            position = 1L,
            duration = 1L,
            lastWatched = lastWatchedAtMs,
            progressPercent = 100f,
            source = WatchProgress.SOURCE_TRAKT_HISTORY,
            traktShowId = traktShowId,
            traktEpisodeId = null
        )
    }

    fun observeMovieWatched(contentId: String): Flow<Boolean> {
        val rawKey = contentId.trim()
        val canonicalKey = canonicalLookupKey(rawKey)
        return flow {
            val watchedMovies = getWatchedMoviesSnapshot(forceRefresh = false)
            emit(watchedMovies.contains(rawKey) || watchedMovies.contains(canonicalKey))
        }
            .combine(optimisticProgress) { snapshotWatched, optimistic ->
                val optimisticEntry = optimistic[rawKey]?.progress
                    ?: optimistic[canonicalKey]?.progress
                when {
                    optimisticEntry?.isCompleted() == true -> true
                    optimisticEntry?.isInProgress() == true -> false
                    else -> snapshotWatched
                }
            }
            .distinctUntilChanged()
    }

    suspend fun markAsWatched(
        progress: WatchProgress,
        title: String?,
        year: Int?
    ) {
        val body = buildHistoryAddRequest(progress, title, year)
            ?: throw IllegalStateException("Insufficient Trakt IDs to mark watched")

        val response = traktProgressMutationExecutor.addHistory(body)
            ?: throw IllegalStateException("Trakt request failed")

        val responseBody = response.body()
        if (!response.isSuccessful || hasHistoryAddNotFound(responseBody)) {
            throw IllegalStateException("Failed to mark watched on Trakt (${response.code()})")
        }
        if (!hasSuccessfulHistoryAdd(responseBody)) {
            trace("markAsWatched: Trakt accepted request with no new history rows (code=${response.code()})")
        }

        if (progress.contentType.equals("movie", ignoreCase = true)) {
            traktIntegrationProvider.invalidateWatchedSnapshot(TraktWatchedKind.MOVIES)
        } else if (
            progress.contentType.equals("series", ignoreCase = true) ||
            progress.contentType.equals("tv", ignoreCase = true)
        ) {
            invalidateEpisodeProgressCache(progress.contentId)
            invalidateShowNextUpCache(progress.contentId)
            traktIntegrationProvider.invalidateWatchedSnapshot(TraktWatchedKind.SHOWS)
        }
        refreshNow()
    }

    internal fun buildHistoryAddRequestForOutbox(
        progress: WatchProgress,
        title: String?,
        year: Int?
    ): TraktHistoryAddRequestDto? {
        return buildHistoryAddRequest(progress, title, year)
    }

    internal fun hasHistoryAddNotFoundForOutbox(
        body: TraktHistoryAddResponseDto?
    ): Boolean {
        return hasHistoryAddNotFound(body)
    }

    internal suspend fun reconcileQueuedHistoryAddSuccess(progress: WatchProgress) {
        if (progress.contentType.equals("movie", ignoreCase = true)) {
            traktIntegrationProvider.invalidateWatchedSnapshot(TraktWatchedKind.MOVIES)
        } else if (
            progress.contentType.equals("series", ignoreCase = true) ||
            progress.contentType.equals("tv", ignoreCase = true)
        ) {
            invalidateEpisodeProgressCache(progress.contentId)
            invalidateShowNextUpCache(progress.contentId)
            traktIntegrationProvider.invalidateWatchedSnapshot(TraktWatchedKind.SHOWS)
        }
        refreshNow()
    }

    internal suspend fun rollbackQueuedHistoryAdd(progress: WatchProgress) {
        applyOptimisticRemoval(
            contentId = progress.contentId,
            season = progress.season,
            episode = progress.episode
        )
        refreshNow()
    }

    internal fun buildHistoryRemoveRequestForOutbox(
        contentId: String,
        season: Int?,
        episode: Int?,
        removeShow: Boolean
    ): TraktHistoryRemoveRequestDto? {
        val parsed = parseContentIds(contentId)
        val ids = toTraktIds(parsed)
        if (!ids.hasAnyId()) return null

        return when {
            removeShow -> {
                TraktHistoryRemoveRequestDto(
                    shows = listOf(
                        TraktHistoryShowRemoveDto(ids = ids)
                    )
                )
            }

            season != null && episode != null -> {
                TraktHistoryRemoveRequestDto(
                    shows = listOf(
                        TraktHistoryShowRemoveDto(
                            ids = ids,
                            seasons = listOf(
                                TraktHistorySeasonRemoveDto(
                                    number = season,
                                    episodes = listOf(TraktHistoryEpisodeRemoveDto(number = episode))
                                )
                            )
                        )
                    )
                )
            }

            else -> {
                TraktHistoryRemoveRequestDto(
                    movies = listOf(TraktMovieDto(ids = ids))
                )
            }
        }
    }

    internal suspend fun resolvePlaybackDeleteIdsForOutbox(
        contentId: String,
        season: Int?,
        episode: Int?
    ): List<Long> {
        val playbackMovies = getPlayback("movies", force = true)
        val playbackEpisodes = getPlayback("episodes", force = true)
        val target = contentId.trim()

        val movieIds = playbackMovies
            .filter { normalizeContentId(it.movie?.ids) == target }
            .mapNotNull { it.id }

        val episodeIds = playbackEpisodes
            .filter { item ->
                val sameContent = normalizeContentId(item.show?.ids) == target
                val sameEpisode = if (season != null && episode != null) {
                    item.episode?.season == season && item.episode.number == episode
                } else {
                    true
                }
                sameContent && sameEpisode
            }
            .mapNotNull { it.id }

        return (movieIds + episodeIds).distinct()
    }

    internal suspend fun reconcileQueuedHistoryRemoveSuccess(
        contentId: String,
        season: Int?,
        episode: Int?,
        removeShow: Boolean
    ) {
        val parsed = parseContentIds(contentId)
        val ids = toTraktIds(parsed)
        val canonicalId = normalizeContentId(ids = ids, fallback = contentId.trim())

        when {
            removeShow -> {
                remoteProgress.update { items ->
                    items.filterNot {
                        it.contentId == canonicalId && (
                            it.contentType.equals("series", ignoreCase = true) ||
                                it.contentType.equals("tv", ignoreCase = true)
                        )
                    }
                }
                myShowsNextUp.update { items -> items.filterNot { it.contentId == canonicalId } }
                myShowsNextUpAll.update { items -> items.filterNot { it.contentId == canonicalId } }
                invalidateEpisodeProgressCache(canonicalId)
                invalidateShowNextUpCache(canonicalId)
                traktIntegrationProvider.invalidateWatchedSnapshot(TraktWatchedKind.SHOWS)
            }

            season != null && episode != null -> {
                invalidateEpisodeProgressCache(contentId)
                invalidateShowNextUpCache(contentId)
                traktIntegrationProvider.invalidateWatchedSnapshot(TraktWatchedKind.SHOWS)
            }

            else -> {
                traktIntegrationProvider.invalidateWatchedSnapshot(TraktWatchedKind.MOVIES)
            }
        }
        refreshNow()
    }

    internal suspend fun rollbackQueuedHistoryRemove(
        contentId: String,
        season: Int?,
        episode: Int?,
        removeShow: Boolean
    ) {
        refreshNow()
    }

    internal suspend fun reconcileQueuedPlaybackDeleteSuccess(
        contentId: String,
        season: Int?,
        episode: Int?,
        clearShow: Boolean
    ) {
        if (clearShow) {
            invalidateEpisodeProgressCache(contentId)
            invalidateShowNextUpCache(contentId)
        }
        refreshNow()
    }

    internal suspend fun rollbackQueuedPlaybackDelete(
        contentId: String,
        season: Int?,
        episode: Int?,
        clearShow: Boolean
    ) {
        refreshNow()
    }

    suspend fun isMovieWatched(contentId: String): Boolean {
        val rawKey = contentId.trim()
        val canonicalKey = canonicalLookupKey(rawKey)
        val watchedMovies = getWatchedMoviesSnapshot(forceRefresh = false)
        return watchedMovies.contains(rawKey) || watchedMovies.contains(canonicalKey)
    }

    suspend fun removeProgress(contentId: String, season: Int?, episode: Int?) {
        Log.d(
            TAG,
            "removeProgress start contentId=$contentId season=$season episode=$episode"
        )
        applyOptimisticRemoval(contentId, season, episode)
        val playbackMovies = getPlayback("movies", force = true)
        val playbackEpisodes = getPlayback("episodes", force = true)

        val target = contentId.trim()
        playbackMovies
            .filter { normalizeContentId(it.movie?.ids) == target }
            .forEach { item ->
                item.id?.let { playbackId ->
                    Log.d(TAG, "removeProgress deleting movie playbackId=$playbackId")
                    traktProgressMutationExecutor.deletePlayback(playbackId)
                }
            }

        playbackEpisodes
            .filter { item ->
                val sameContent = normalizeContentId(item.show?.ids) == target
                val sameEpisode = if (season != null && episode != null) {
                    item.episode?.season == season && item.episode.number == episode
                } else {
                    true
                }
                sameContent && sameEpisode
            }
            .forEach { item ->
                item.id?.let { playbackId ->
                    Log.d(
                        TAG,
                        "removeProgress deleting episode playbackId=$playbackId s=${item.episode?.season} e=${item.episode?.number}"
                    )
                    traktProgressMutationExecutor.deletePlayback(playbackId)
                }
            }

        Log.d(TAG, "removeProgress refreshNow contentId=$contentId")
        refreshNow()
    }

    suspend fun clearShowProgress(contentId: String) {
        val parsed = parseContentIds(contentId)
        val ids = toTraktIds(parsed)
        val canonicalId = normalizeContentId(ids = ids, fallback = contentId.trim())
        if (canonicalId.isBlank()) return

        applyOptimisticRemoval(contentId = canonicalId, season = null, episode = null)

        val playbackEpisodes = getPlayback("episodes", force = true)
        playbackEpisodes
            .filter { normalizeContentId(it.show?.ids) == canonicalId }
            .forEach { item ->
                item.id?.let { playbackId ->
                    traktProgressMutationExecutor.deletePlayback(playbackId)
                }
            }

        if (ids.hasAnyId()) {
            val removeBody = TraktHistoryRemoveRequestDto(
                shows = listOf(
                    TraktHistoryShowRemoveDto(ids = ids)
                )
            )
            traktProgressMutationExecutor.removeHistory(removeBody)
        }

        remoteProgress.update { items ->
            items.filterNot {
                it.contentId == canonicalId && (
                    it.contentType.equals("series", ignoreCase = true) ||
                        it.contentType.equals("tv", ignoreCase = true)
                )
            }
        }
        myShowsNextUp.update { items ->
            items.filterNot { it.contentId == canonicalId }
        }
        myShowsNextUpAll.update { items ->
            items.filterNot { it.contentId == canonicalId }
        }
        invalidateEpisodeProgressCache(canonicalId)
        invalidateShowNextUpCache(canonicalId)
        refreshNow()
    }

    suspend fun removeFromHistory(contentId: String, season: Int?, episode: Int?) {
        applyOptimisticRemoval(contentId, season, episode)

        val parsed = parseContentIds(contentId)
        val ids = toTraktIds(parsed)
        if (!ids.hasAnyId()) {
            refreshNow()
            return
        }

        val likelySeries = season != null && episode != null

        val removeBody = if (likelySeries) {
            TraktHistoryRemoveRequestDto(
                shows = listOf(
                    TraktHistoryShowRemoveDto(
                        ids = ids,
                        seasons = listOf(
                            TraktHistorySeasonRemoveDto(
                                number = season,
                                episodes = listOf(TraktHistoryEpisodeRemoveDto(number = episode))
                            )
                        )
                    )
                )
            )
        } else {
            TraktHistoryRemoveRequestDto(
                movies = listOf(TraktMovieDto(ids = ids))
            )
        }

        traktProgressMutationExecutor.removeHistory(removeBody)

        if (!likelySeries) {
            traktIntegrationProvider.invalidateWatchedSnapshot(TraktWatchedKind.MOVIES)
        } else {
            invalidateEpisodeProgressCache(contentId)
            invalidateShowNextUpCache(contentId)
        }
        refreshNow()
    }

    private suspend fun refreshRemoteSnapshot() {
        if (!traktIntegrationProvider.isCircuitClosed()) {
            trace("refreshRemoteSnapshot: circuit breaker open, skipping")
            throw IOException("Trakt circuit breaker is open")
        }

        val force = System.currentTimeMillis() < forceRefreshUntilMs
        val activityChanged = force || hasActivityChanged()
        val cachedAiringTransition = !force && hasCachedNextUpAirDateTransition()
        if (!activityChanged && !cachedAiringTransition) return

        if (hiddenProgressStale && hasLoadedHiddenProgress) {
            getHiddenProgressSnapshot(forceRefresh = true)
        }

        val progressSnapshot = if (activityChanged) {
            fetchAllProgressSnapshot(force = force)
        } else {
            remoteProgress.value
        }
        if (activityChanged) {
            remoteProgress.value = progressSnapshot
            hasLoadedRemoteProgress.value = true
            reconcileOptimistic(progressSnapshot)
        }

        val watchedShows = getWatchedShowsSnapshot(forceRefresh = activityChanged || force)
        val hiddenProgress = getHiddenProgressSnapshot(forceRefresh = false)
        val allNextUpSnapshot = deriveNextUpFromWatchedShows(
            watchedShows = watchedShows,
            hiddenProgress = hiddenProgress,
            forceValidation = activityChanged
        )
        hydrateMetadata(
            progressList = progressSnapshot +
                allNextUpSnapshot.map(::nextUpEntryToWatchProgress)
        )
        myShowsNextUp.value = allNextUpSnapshot
        myShowsNextUpAll.value = allNextUpSnapshot
    }

    private suspend fun hasActivityChanged(): Boolean {
        val activities = getRecentActivities(maxAgeMs = 0L)
            ?: return !hasLoadedRemoteProgress.value
        val moviesWatchedAt = activities.movies?.watchedAt
        if (moviesWatchedAt != lastKnownMoviesWatchedAt) {
            lastKnownMoviesWatchedAt = moviesWatchedAt
            traktIntegrationProvider.invalidateWatchedSnapshot(TraktWatchedKind.MOVIES)
            trace("last_activities: movies.watched_at changed -> invalidate watched-movies snapshot")
        }
        val watchedShowsFingerprint = activities.episodes?.watchedAt.orEmpty()
        if (watchedShowsFingerprint != lastKnownWatchedShowsFingerprint) {
            lastKnownWatchedShowsFingerprint = watchedShowsFingerprint
            traktIntegrationProvider.invalidateWatchedSnapshot(TraktWatchedKind.SHOWS)
            trace("last_activities: watched-show candidate fingerprint changed")
            val version = showNextUpActivityVersion.incrementAndGet()
            trace("last_activities: watched-show candidate fingerprint changed -> next-up cache version=$version")
        }
        val episodeFingerprint = listOfNotNull(
            activities.episodes?.pausedAt,
            activities.episodes?.watchedAt
        ).joinToString("|")
        if (episodeFingerprint != lastKnownEpisodeActivityFingerprint) {
            lastKnownEpisodeActivityFingerprint = episodeFingerprint
            val version = episodeProgressActivityVersion.incrementAndGet()
            trace("last_activities: episodes changed -> show-progress cache version=$version")
            val nextUpVersion = showNextUpActivityVersion.incrementAndGet()
            trace("last_activities: episodes changed -> next-up cache version=$nextUpVersion")
        }
        val hiddenProgressFingerprint = listOfNotNull(
            activities.shows?.hiddenAt,
            activities.shows?.droppedAt,
            activities.seasons?.hiddenAt
        ).joinToString("|")
        if (hiddenProgressFingerprint != lastKnownHiddenProgressFingerprint) {
            hiddenProgressStale = true
            lastKnownHiddenProgressFingerprint = hiddenProgressFingerprint
            val version = showNextUpActivityVersion.incrementAndGet()
            trace("last_activities: hidden-progress changed -> next-up cache version=$version")
        }
        val fingerprint = listOfNotNull(
            activities.movies?.pausedAt,
            activities.movies?.watchedAt,
            activities.episodes?.pausedAt,
            activities.episodes?.watchedAt,
            activities.shows?.hiddenAt,
            activities.shows?.droppedAt,
            activities.seasons?.hiddenAt
        ).joinToString("|")

        val changed = fingerprint != lastKnownActivityFingerprint
        lastKnownActivityFingerprint = fingerprint
        if (changed) {
            trace("last_activities: fingerprint changed")
        }
        return changed
    }

    private suspend fun invalidateEpisodeProgressCache(contentId: String) {
        val rawKey = contentId.trim()
        if (rawKey.isBlank()) return
        val canonicalKey = canonicalLookupKey(rawKey)
        val keys = setOf(rawKey, canonicalKey).filter { it.isNotBlank() }
        if (keys.isEmpty()) return

        episodeProgressState.update { current ->
            current.toMutableMap().apply {
                keys.forEach { remove(it) }
            }
        }
        episodeProgressMutex.withLock {
            keys.forEach { key ->
                episodeProgressLastAttemptAtMs.remove(key)
                inFlightEpisodeProgressKeys.remove(key)
            }
        }
        trace("episode-progress cache invalidated: keys=${keys.joinToString()}")
    }

    private suspend fun invalidateShowNextUpCache(contentId: String) {
        val rawKey = contentId.trim()
        if (rawKey.isBlank()) return
        val canonicalKey = canonicalLookupKey(rawKey)
        val keys = setOf(rawKey, canonicalKey).filter { it.isNotBlank() }
        if (keys.isEmpty()) return

        showNextUpState.update { current ->
            current.toMutableMap().apply {
                keys.forEach { remove(it) }
            }
        }
        showNextUpMutex.withLock {
            keys.forEach { key ->
                showNextUpLastAttemptAtMs.remove(key)
                inFlightShowNextUpKeys.remove(key)
                nextUpValidationCache.remove(key)
                nextUpValidationBypassKeys.add(key)
            }
        }
        trace("next-up cache invalidated: keys=${keys.joinToString()}")
    }

    private suspend fun ensureEpisodeProgressSnapshot(
        contentId: String,
        forceRefresh: Boolean
    ): Map<Pair<Int, Int>, WatchProgress> {
        val cacheKey = canonicalLookupKey(contentId)
        val now = System.currentTimeMillis()

        var cachedEntry: EpisodeProgressCacheEntry? = null
        var shouldFetch = false

        episodeProgressMutex.withLock {
            val existing = episodeProgressState.value[cacheKey]
            cachedEntry = existing
            if (!forceRefresh && isEpisodeProgressCacheFresh(existing, now)) {
                trace("episode-progress cache hit: show=$cacheKey episodes=${existing?.progress?.size ?: 0}")
                return@withLock
            }

            val lastAttempt = episodeProgressLastAttemptAtMs[cacheKey] ?: 0L
            if (!forceRefresh && now - lastAttempt < episodeProgressFetchThrottleMs) {
                trace("episode-progress fetch throttled: show=$cacheKey delta=${now - lastAttempt}ms")
                return@withLock
            }

            if (!inFlightEpisodeProgressKeys.add(cacheKey)) {
                trace("episode-progress fetch already in-flight: show=$cacheKey")
                return@withLock
            }

            episodeProgressLastAttemptAtMs[cacheKey] = now
            shouldFetch = true
        }

        if (!shouldFetch) {
            return cachedEntry?.progress ?: episodeProgressState.value[cacheKey]?.progress.orEmpty()
        }

        return try {
            trace("episode-progress fetch: show=$cacheKey force=$forceRefresh")
            val result = fetchEpisodeProgressSnapshot(contentId = cacheKey)
            val fetchedAt = System.currentTimeMillis()
            val activityVersion = episodeProgressActivityVersion.get()
            episodeProgressState.update { current ->
                current + (
                    cacheKey to EpisodeProgressCacheEntry(
                        progress = result.progress,
                        updatedAtMs = fetchedAt,
                        activityVersion = activityVersion,
                        hasCompletedSnapshot = result.hasCompletedSnapshot
                    )
                )
            }
            trace(
                "episode-progress cache refreshed: show=$cacheKey episodes=${result.progress.size} full=${result.hasCompletedSnapshot}"
            )
            result.progress
        } catch (e: Exception) {
            Log.w(TAG, "Failed to fetch episode progress for show=$cacheKey", e)
            episodeProgressState.value[cacheKey]?.progress ?: cachedEntry?.progress.orEmpty()
        } finally {
            episodeProgressMutex.withLock {
                inFlightEpisodeProgressKeys.remove(cacheKey)
            }
        }
    }

    private fun isEpisodeProgressCacheFresh(
        entry: EpisodeProgressCacheEntry?,
        now: Long
    ): Boolean {
        if (entry == null) return false
        if (!entry.hasCompletedSnapshot) return false
        if (entry.activityVersion != episodeProgressActivityVersion.get()) return false
        return now - entry.updatedAtMs <= episodeProgressCacheTtlMs
    }

    private suspend fun getWatchedMoviesSnapshot(forceRefresh: Boolean): Set<String> {
        if (forceRefresh) {
            traktIntegrationProvider.invalidateWatchedSnapshot(TraktWatchedKind.MOVIES)
        }
        val items = when (val result = traktIntegrationProvider.getWatched(type = "movies")) {
            is IntegrationCallResult.Success -> result.value
            else -> {
                trace("watched-movies fetch: request returned null (network/auth failure)")
                return emptySet()
            }
        }
        return items.flatMap { item -> traktIdLookupKeys(item.movie?.ids, MediaKind.MOVIE) }.toSet()
    }

    private fun canonicalLookupKey(contentId: String): String {
        val parsed = parseContentIds(contentId)
        val canonical = normalizeContentId(toTraktIds(parsed))
        return if (canonical.isNotBlank()) canonical else contentId.trim()
    }

    private suspend fun getWatchedShowsSnapshot(forceRefresh: Boolean): Map<String, WatchedShowIndexEntry> {
        if (forceRefresh) {
            traktIntegrationProvider.invalidateWatchedSnapshot(TraktWatchedKind.SHOWS)
        }
        val items = when (val result = traktIntegrationProvider.getWatchedShows()) {
            is IntegrationCallResult.Success -> result.value
            else -> {
                trace("watched-shows fetch: request returned null (network/auth failure)")
                return emptyMap()
            }
        }
        val entries = items.mapNotNull(::mapWatchedShowItem)
        return buildMap<String, WatchedShowIndexEntry> {
            entries.forEach { entry ->
                put(entry.canonicalContentId, entry)
                entry.aliasContentIds.forEach { alias -> put(alias, entry) }
            }
        }
    }

    @VisibleForTesting
    internal suspend fun testOnlyProjectWatchedShows(): Map<String, WatchedShowIndexEntry> =
        getWatchedShowsSnapshot(forceRefresh = false)

    @VisibleForTesting
    internal suspend fun testOnlyDeriveNextUp(): List<NextUpEntry> {
        val watchedShows = getWatchedShowsSnapshot(forceRefresh = false)
        val hiddenProgress = getHiddenProgressSnapshot(forceRefresh = false)
        return deriveNextUpFromWatchedShows(watchedShows = watchedShows, hiddenProgress = hiddenProgress)
    }

    @VisibleForTesting
    internal suspend fun testOnlyDeriveNextUpCandidates(): List<NextUpEntry> {
        val watchedShows = getWatchedShowsSnapshot(forceRefresh = false)
        val hiddenProgress = getHiddenProgressSnapshot(forceRefresh = false)
        return watchedShows.values
            .asSequence()
            .distinctBy { it.canonicalContentId }
            .filter { showInfo ->
                val entryAliases = showInfo.aliasContentIds + showInfo.canonicalContentId
                val anyHiddenMatch = entryAliases.any {
                    it in hiddenProgress.hiddenShowIds || it in hiddenProgress.droppedShowIds
                }
                !anyHiddenMatch
            }
            .sortedByDescending { it.lastWatchedAtMs }
            .map { showInfo ->
                NextUpEntry(
                    contentId = showInfo.canonicalContentId,
                    contentType = "series",
                    name = showInfo.name,
                    season = 0,
                    episode = 0,
                    episodeTitle = null,
                    videoId = showInfo.canonicalContentId,
                    firstAired = null,
                    firstAiredMs = 0L,
                    activityAtMs = showInfo.lastWatchedAtMs,
                    traktShowId = showInfo.traktShowId,
                    traktEpisodeId = null
                )
            }
            .toList()
    }

    private fun mapWatchedShowItem(item: TraktWatchedShowItemDto): WatchedShowIndexEntry? {
        val show = item.show ?: return null
        val ids = show.ids ?: return null
        val resetAtMs = parseIsoOptionalToMillis(item.resetAt)

        val animeCanonical = resolveAnimeCanonicalIfApplicable(ids)
        val kind = if (animeCanonical != null) MediaKind.ANIME else MediaKind.SHOW
        val canonicalContentId = normalizeContentId(
            ids = ids,
            kind = kind,
            animeCanonical = animeCanonical
        ).takeIf { it.isNotBlank() } ?: return null

        val aliasContentIds = buildSet {
            if (animeCanonical != null) add(animeCanonical)
            addAll(traktIdLookupKeys(ids, kind = MediaKind.SHOW))
        }

        val watchedEpisodes = item.seasons.orEmpty().flatMap { season ->
            val seasonNumber = season.number ?: return@flatMap emptyList()
            season.episodes.orEmpty().mapNotNull { episode ->
                val episodeNumber = episode.number ?: return@mapNotNull null
                // parseIsoToMillis returns System.currentTimeMillis() when last_watched_at is
                // missing/unparseable, so episodes without timestamps are kept (they sort as "now"
                // and will not be older than reset_at).
                val watchedAtMs = parseIsoToMillis(episode.lastWatchedAt)
                if (resetAtMs != null && watchedAtMs < resetAtMs) return@mapNotNull null
                seasonNumber to episodeNumber
            }
        }.toSet()

        return WatchedShowIndexEntry(
            canonicalContentId = canonicalContentId,
            aliasContentIds = aliasContentIds,
            name = show.title ?: canonicalContentId,
            lastWatchedAtMs = parseIsoToMillis(item.lastWatchedAt),
            resetAtMs = resetAtMs,
            traktShowId = ids.trakt,
            watchedEpisodes = watchedEpisodes
        )
    }

    private fun resolveAnimeCanonicalIfApplicable(ids: TraktIdsDto): String? {
        val candidates = listOfNotNull(
            ids.tvdb?.let { AnimeStremioId(AnimeIdSource.TVDB, it.toString()) },
            ids.tmdb?.let { AnimeStremioId(AnimeIdSource.TMDB, it.toString()) },
            ids.imdb?.takeIf { it.isNotBlank() }?.let { AnimeStremioId(AnimeIdSource.IMDB, it) }
        )
        for (candidate in candidates) {
            val kitsuId = animeIdMappingService.resolveKitsuId(candidate, ContentMediaKind.SERIES)
            if (!kitsuId.isNullOrBlank()) return "kitsu:$kitsuId"
        }
        return null
    }

    private fun parseIsoOptionalToMillis(value: String?): Long? {
        if (value.isNullOrBlank()) return null
        return runCatching { java.time.Instant.parse(value).toEpochMilli() }.getOrNull()
    }

    private suspend fun getHiddenProgressSnapshot(forceRefresh: Boolean): HiddenProgressSnapshot {
        val now = System.currentTimeMillis()
        return hiddenProgressMutex.withLock {
            val hasFreshCache = hasLoadedHiddenProgress &&
                !hiddenProgressStale &&
                now - hiddenProgressUpdatedAtMs <= hiddenProgressCacheTtlMs
            if (!forceRefresh && hasFreshCache) {
                trace("hidden-progress cache hit: shows=${hiddenProgressState.value.hiddenShowIds.size}")
                return@withLock hiddenProgressState.value
            }
            if (!forceRefresh && now - hiddenProgressLastAttemptAtMs < hiddenProgressFetchThrottleMs) {
                trace("hidden-progress fetch throttled: ${now - hiddenProgressLastAttemptAtMs}ms since last attempt")
                return@withLock hiddenProgressState.value
            }

            hiddenProgressLastAttemptAtMs = now
            val hiddenShows = fetchHiddenItems(section = "progress_watched", type = "show")
            val hiddenSeasons = fetchHiddenItems(section = "progress_watched", type = "season")
            val droppedShows = fetchHiddenItems(section = "dropped", type = "show")

            val snapshot = HiddenProgressSnapshot(
                hiddenShowIds = hiddenShows.flatMap { item ->
                    showAliasKeys(item.show?.ids)
                }.toSet(),
                hiddenSeasonKeys = hiddenSeasons.mapNotNull { item ->
                    val contentId = normalizeContentId(item.show?.ids, kind = MediaKind.SHOW)
                    val season = item.season?.number
                    if (contentId.isBlank() || season == null || season <= 0) {
                        null
                    } else {
                        hiddenSeasonKey(contentId, season)
                    }
                }.toSet(),
                droppedShowIds = droppedShows.flatMap { item ->
                    showAliasKeys(item.show?.ids)
                }.toSet()
            )

            hiddenProgressState.value = snapshot
            hiddenProgressUpdatedAtMs = System.currentTimeMillis()
            hasLoadedHiddenProgress = true
            hiddenProgressStale = false
            trace(
                "hidden-progress cache refreshed: hiddenShows=${snapshot.hiddenShowIds.size} hiddenSeasons=${snapshot.hiddenSeasonKeys.size} dropped=${snapshot.droppedShowIds.size}"
            )
            snapshot
        }
    }

    private suspend fun fetchHiddenItems(
        section: String,
        type: String
    ): List<TraktHiddenItemDto> {
        val items = mutableListOf<TraktHiddenItemDto>()
        var page = 1
        val limit = 100

        while (true) {
            val pageResult = when (val result = traktIntegrationProvider.getHiddenItems(
                section = section,
                type = type,
                page = page,
                limit = limit
            )) {
                is IntegrationCallResult.Success -> result.value
                else -> break
            }
            val body = pageResult.body
            if (body.isEmpty()) break
            items += body

            val pageCount = pageResult.pageCount ?: 1
            if (page >= pageCount || body.size < limit) break
            page += 1
        }

        return items
    }

    /**
     * Resolve Trakt integer episode IDs for all (season, episode-number) pairs in [episodeNumbers].
     * Calls [fetchEpisodeSummary] in parallel (one request per episode).
     *
     * Returns a map from episode-number to [TraktEpisodeRef]. Episodes whose Trakt ID cannot be
     * resolved are absent from the map.
     *
     * @param showContentId the content ID of the show (e.g. "tt1234567" or "trakt:12345")
     * @param season the season number
     * @param episodeNumbers the list of episode numbers to resolve
     */
    suspend fun resolveSeasonEpisodeTraktIds(
        showContentId: String,
        season: Int,
        episodeNumbers: List<Int>
    ): Map<Int, com.nexio.tv.data.repository.trakt.TraktEpisodeRef> {
        if (episodeNumbers.isEmpty()) return emptyMap()
        val pathId = toTraktPathId(showContentId)
        val seasonEpisodes: List<TraktEpisodeSummaryDto> =
            when (val result = traktIntegrationProvider.getSeasonEpisodes(
                id = pathId,
                season = season,
                extended = "full"
            )) {
                is IntegrationCallResult.Success -> result.value
                else -> emptyList()
            }

        val byNumber: Map<Int, Int> = seasonEpisodes
            .mapNotNull { dto ->
                val n = dto.number ?: return@mapNotNull null
                val tid = dto.ids?.trakt ?: return@mapNotNull null
                n to tid
            }
            .toMap()

        val requested = episodeNumbers.toSet()
        val resolved = byNumber.filterKeys { it in requested }
        val unresolvedEpisodes = requested - resolved.keys
        if (unresolvedEpisodes.isNotEmpty()) {
            Log.w(
                "TraktProgressService",
                "resolveSeasonEpisodeTraktIds: unable to resolve trakt ids for showId=$showContentId " +
                    "season=$season episodes=$unresolvedEpisodes"
            )
        }

        return resolved.mapValues { (episodeNumber, traktId) ->
            com.nexio.tv.data.repository.trakt.TraktEpisodeRef(
                episodeNumber = episodeNumber,
                traktId = traktId
            )
        }
    }

    private suspend fun fetchEpisodeSummary(
        pathId: String,
        season: Int,
        episode: Int
    ): TraktEpisodeSummaryDto? {
        return when (val result = traktIntegrationProvider.getEpisodeSummary(
            id = pathId,
            season = season,
            episode = episode,
            extended = "full"
        )) {
            is IntegrationCallResult.Success -> result.value
            else -> null
        }
    }

    private suspend fun deriveNextUpFromWatchedShows(
        watchedShows: Map<String, WatchedShowIndexEntry>,
        hiddenProgress: HiddenProgressSnapshot,
        forceValidation: Boolean = false
    ): List<NextUpEntry> {
        val entries = watchedShows.values
            .asSequence()
            .distinctBy { it.canonicalContentId }
            .filter { showInfo ->
                val entryAliases = showInfo.aliasContentIds + showInfo.canonicalContentId
                val anyHiddenMatch = entryAliases.any {
                    it in hiddenProgress.hiddenShowIds || it in hiddenProgress.droppedShowIds
                }
                !anyHiddenMatch
            }
            .sortedByDescending { it.lastWatchedAtMs }
            .map { showInfo ->
                DerivedNextUpCandidate(
                    entry = NextUpEntry(
                        contentId = showInfo.canonicalContentId,
                        contentType = "series",
                        name = showInfo.name,
                        season = 0,
                        episode = 0,
                        episodeTitle = null,
                        videoId = showInfo.canonicalContentId,
                        firstAired = null,
                        firstAiredMs = 0L,
                        activityAtMs = showInfo.lastWatchedAtMs,
                        traktShowId = showInfo.traktShowId,
                        traktEpisodeId = null
                    ),
                    weakDerivation = true
                )
            }
            .toList()

        return validateNextUpCandidates(
            candidates = entries,
            hiddenProgress = hiddenProgress,
            forceValidation = forceValidation
        )
    }

    private suspend fun validateNextUpCandidates(
        candidates: List<DerivedNextUpCandidate>,
        hiddenProgress: HiddenProgressSnapshot,
        forceValidation: Boolean = false
    ): List<NextUpEntry> {
        if (candidates.isEmpty()) return emptyList()

        val now = System.currentTimeMillis()
        val cacheSnapshot = showNextUpMutex.withLock {
            nextUpValidationCache.mapValues { it.value.freshness() }
        }
        val bypassSnapshot = showNextUpMutex.withLock {
            nextUpValidationBypassKeys.toSet()
        }
        val validationCandidates = candidates.mapIndexed { index, candidate ->
            val key = canonicalLookupKey(candidate.entry.contentId)
            TraktNextUpValidationCandidate(
                contentId = key,
                activityAtMs = candidate.entry.activityAtMs,
                visibleRank = index + 1,
                weakDerivation = candidate.weakDerivation,
                mutationAffected = key in bypassSnapshot,
                staleValidation = forceValidation || cacheSnapshot[key]?.isFresh(now) == false
            )
        }
        val selectedKeys = TraktNextUpValidationPolicy.selectCandidates(
            candidates = validationCandidates,
            nowMs = now,
            cache = cacheSnapshot,
            visibleCandidateLimit = nextUpValidationVisibleCandidateLimit,
            validationBudget = nextUpValidationBudget
        ).mapTo(linkedSetOf()) { it.contentId }

        val freshCachedResults = showNextUpMutex.withLock {
            nextUpValidationCache
                .filterValues { it.freshness().isFresh(now) }
                .mapValues { it.value.result }
        }
        val validatedResults = coroutineScope {
            candidates
                .filter { canonicalLookupKey(it.entry.contentId) in selectedKeys }
                .map { candidate ->
                    async {
                        val key = canonicalLookupKey(candidate.entry.contentId)
                        val result = nextUpValidationSemaphore.withPermit {
                            validateNextUpCandidate(candidate.entry, hiddenProgress)
                        }
                        val ttl = if (result is TraktNextUpValidationResult.CurrentAiredNextEpisode) {
                            nextUpValidationPositiveTtlMs
                        } else {
                            nextUpValidationNegativeTtlMs
                        }
                        showNextUpMutex.withLock {
                            nextUpValidationCache[key] = CachedNextUpValidation(
                                result = result,
                                updatedAtMs = System.currentTimeMillis(),
                                ttlMs = ttl
                            )
                            nextUpValidationBypassKeys.remove(key)
                        }
                        key to result
                    }
                }
                .awaitAll()
                .toMap()
        }
        val resultsByKey = freshCachedResults + validatedResults

        return candidates.mapNotNull { candidate ->
            val key = canonicalLookupKey(candidate.entry.contentId)
            val result = resultsByKey[key]
            if (result != null) {
                TraktNextUpValidationPolicy.resolvePublishableCandidate(
                    localCandidate = candidate.entry,
                    validationResult = result,
                    nowMs = now,
                    weakDerivation = candidate.weakDerivation
                )
            } else {
                candidate.entry.takeIf {
                    !candidate.weakDerivation &&
                    AirDateGate.isAired(
                        firstAiredMs = it.firstAiredMs,
                        tmdbAirDate = it.firstAired,
                        nowMs = now
                    )
                }
            }
        }.sortedByDescending { it.activityAtMs }
    }

    private suspend fun validateNextUpCandidate(
        candidate: NextUpEntry,
        hiddenProgress: HiddenProgressSnapshot
    ): TraktNextUpValidationResult {
        return try {
            val progress = when (val result = traktIntegrationProvider.getShowProgressWatched(
                id = toTraktPathId(candidate.contentId),
                lastActivity = "watched"
            )) {
                is IntegrationCallResult.Success -> result.value
                else -> return TraktNextUpValidationResult.Failed
            }

            val nextEpisode = progress.nextEpisode
                ?: return TraktNextUpValidationResult.NoCurrentAiredNextEpisode
            val season = nextEpisode.season ?: return TraktNextUpValidationResult.NoCurrentAiredNextEpisode
            val episode = nextEpisode.number ?: return TraktNextUpValidationResult.NoCurrentAiredNextEpisode
            val canonicalId = normalizeContentId(toTraktIds(parseContentIds(candidate.contentId)), kind = MediaKind.SHOW)
            if (hiddenProgress.hiddenSeasonKeys.contains(hiddenSeasonKey(candidate.contentId, season)) ||
                hiddenProgress.hiddenSeasonKeys.contains(hiddenSeasonKey(canonicalId, season))
            ) {
                trace("next-up validation suppressed hidden season: show=$canonicalId season=$season")
                return TraktNextUpValidationResult.NoCurrentAiredNextEpisode
            }

            val episodeInfo = findEpisodeInfo(candidate.contentId, season, episode)
            TraktNextUpValidationResult.CurrentAiredNextEpisode(
                candidate.copy(
                    season = season,
                    episode = episode,
                    episodeTitle = nextEpisode.title ?: candidate.episodeTitle,
                    videoId = episodeInfo.videoId,
                    firstAired = episodeInfo.released,
                    firstAiredMs = 0L,
                    traktEpisodeId = nextEpisode.ids?.trakt ?: candidate.traktEpisodeId
                )
            )
        } catch (e: Exception) {
            Log.w(TAG, "Failed to validate next-up for show=${candidate.contentId}", e)
            TraktNextUpValidationResult.Failed
        }
    }

    private fun hiddenSeasonKey(contentId: String, season: Int): String {
        return "${contentId.trim()}_s$season"
    }

    private fun showAliasKeys(ids: TraktIdsDto?): List<String> {
        if (ids == null) return emptyList()
        val canonical = normalizeContentId(ids, kind = MediaKind.SHOW)
        val aliases = traktIdLookupKeys(ids, kind = MediaKind.SHOW)
        return buildList {
            if (canonical.isNotBlank()) add(canonical)
            addAll(aliases)
        }.distinct()
    }

    private fun hasCachedNextUpAirDateTransition(nowMs: Long = System.currentTimeMillis()): Boolean {
        val visibleKeys = myShowsNextUp.value
            .mapTo(hashSetOf()) { hiddenSeasonKey(it.contentId, it.season) + "e${it.episode}" }
        return showNextUpState.value.values.any { cacheEntry ->
            val entry = cacheEntry.entry ?: return@any false
            if (entry.firstAiredMs <= 0L || entry.firstAiredMs > nowMs) return@any false
            val key = hiddenSeasonKey(entry.contentId, entry.season) + "e${entry.episode}"
            key !in visibleKeys
        }
    }

    private fun nextUpEntryToWatchProgress(entry: NextUpEntry): WatchProgress {
        return WatchProgress(
            contentId = entry.contentId,
            contentType = entry.contentType,
            name = entry.name,
            poster = entry.poster,
            backdrop = entry.backdrop,
            logo = entry.logo,
            videoId = entry.videoId,
            season = entry.season,
            episode = entry.episode,
            episodeTitle = entry.episodeTitle,
            position = 0L,
            duration = 0L,
            lastWatched = entry.activityAtMs,
            source = WatchProgress.SOURCE_TRAKT_SHOW_PROGRESS,
            traktShowId = entry.traktShowId,
            traktEpisodeId = entry.traktEpisodeId
        )
    }

    private suspend fun fetchAllProgressSnapshot(force: Boolean = false): List<WatchProgress> {
        val recentCompletedEpisodes = fetchRecentEpisodeHistorySnapshot()
        val inProgressMovies = getPlayback("movies", force = force).mapNotNull { mapPlaybackMovie(it) }
        val inProgressEpisodes = getPlayback("episodes", force = force).mapNotNull { mapPlaybackEpisode(it) }

        val mergedByKey = linkedMapOf<String, WatchProgress>()

        recentCompletedEpisodes
            .sortedByDescending { it.lastWatched }
            .forEach { progress ->
                mergedByKey[progressKey(progress)] = progress
            }

        (inProgressMovies + inProgressEpisodes)
            .sortedByDescending { it.lastWatched }
            .forEach { progress ->
                mergedByKey[progressKey(progress)] = progress
            }

        return mergedByKey.values.sortedByDescending { it.lastWatched }
    }

    private suspend fun fetchRecentEpisodeHistorySnapshot(): List<WatchProgress> {
        val results = linkedMapOf<String, WatchProgress>()
        var page = 1
        val pageLimit = 100
        val maxPages = 20

        while (page <= maxPages) {
            val pageResult = when (val result = traktIntegrationProvider.getEpisodeHistory(
                page = page,
                limit = pageLimit
            )) {
                is IntegrationCallResult.Success -> result.value
                else -> break
            }
            val items = pageResult.body
            if (items.isEmpty()) break

            var shouldStop = false
            items.forEach { item ->
                val mapped = mapEpisodeHistoryItem(item) ?: return@forEach
                val existing = results[mapped.contentId]
                if (existing == null || shouldPreferEpisodeHistoryEntry(existing, mapped)) {
                    results[mapped.contentId] = mapped
                }
                if (results.size >= maxRecentEpisodeHistoryEntries) {
                    shouldStop = true
                    return@forEach
                }
            }

            val pageCount = pageResult.pageCount
            if (items.size < pageLimit || shouldStop || (pageCount != null && page >= pageCount)) break
            page += 1
        }

        return results.values.toList()
    }

    private fun mapEpisodeHistoryItem(item: TraktUserEpisodeHistoryItemDto): WatchProgress? {
        val show = item.show ?: return null
        val episode = item.episode ?: return null
        val season = episode.season ?: return null
        val number = episode.number ?: return null

        val contentId = normalizeContentId(show.ids)
        if (contentId.isBlank()) return null

        val lastWatched = parseIsoToMillis(item.watchedAt)
        // Avoid expensive metadata lookups for each history row.
        val videoId = "$contentId:$season:$number"

        return WatchProgress(
            contentId = contentId,
            contentType = "series",
            name = show.title ?: contentId,
            poster = null,
            backdrop = null,
            logo = null,
            videoId = videoId,
            season = season,
            episode = number,
            episodeTitle = episode.title,
            position = 1L,
            duration = 1L,
            lastWatched = lastWatched,
            progressPercent = 100f,
            source = WatchProgress.SOURCE_TRAKT_HISTORY,
            traktShowId = show.ids?.trakt,
            traktEpisodeId = episode.ids?.trakt
        )
    }

    private suspend fun fetchEpisodeProgressSnapshot(
        contentId: String
    ): EpisodeProgressFetchResult {
        val pathId = toTraktPathId(contentId)
        val completed = mutableMapOf<Pair<Int, Int>, WatchProgress>()
        var hasCompletedSnapshot = false

        val progressResponse = when (val result = traktIntegrationProvider.getShowProgressWatched(id = pathId)) {
            is IntegrationCallResult.Success -> result.value
            else -> null
        }

        if (progressResponse != null) {
            hasCompletedSnapshot = true
            val seasons = progressResponse.seasons.orEmpty()
            seasons.forEach { season ->
                mapSeasonProgress(contentId, season).forEach { progress ->
                    val seasonNum = progress.season ?: return@forEach
                    val episodeNum = progress.episode ?: return@forEach
                    completed[seasonNum to episodeNum] = progress
                }
            }
        }

        val inProgress = getPlayback(
            type = "episodes"
        )
            .mapNotNull { mapPlaybackEpisode(it) }
            .filter { it.contentId == contentId }

        inProgress.forEach { progress ->
            val seasonNum = progress.season ?: return@forEach
            val episodeNum = progress.episode ?: return@forEach
            completed[seasonNum to episodeNum] = progress
        }

        return EpisodeProgressFetchResult(
            progress = completed,
            hasCompletedSnapshot = hasCompletedSnapshot
        )
    }

    private suspend fun getPlayback(
        type: String,
        force: Boolean = false,
        startAt: String? = null,
        endAt: String? = null
    ): List<TraktPlaybackItemDto> {
        val now = System.currentTimeMillis()
        if (startAt == null && endAt == null) {
            cacheMutex.withLock {
                val cache = when (type) {
                    "movies" -> cachedMoviesPlayback
                    "episodes" -> cachedEpisodesPlayback
                    else -> null
                }
                if (!force && cache != null && now - cache.updatedAtMs <= playbackCacheTtlMs) {
                    return cache.value
                }
            }
        }

        val value = when (val result = traktIntegrationProvider.getPlayback(
            type = type,
            startAt = startAt,
            endAt = endAt
        )) {
            is IntegrationCallResult.Success -> result.value
            else -> emptyList()
        }
        if (startAt == null && endAt == null) {
            cacheMutex.withLock {
                val timed = TimedCache(value = value, updatedAtMs = now)
                when (type) {
                    "movies" -> cachedMoviesPlayback = timed
                    "episodes" -> cachedEpisodesPlayback = timed
                }
            }
        }
        return value
    }

    private suspend fun mapPlaybackMovie(item: TraktPlaybackItemDto): WatchProgress? {
        val movie = item.movie ?: return null
        val contentId = normalizeContentId(movie.ids)
        if (contentId.isBlank()) return null

        return WatchProgress(
            contentId = contentId,
            contentType = "movie",
            name = movie.title ?: contentId,
            poster = null,
            backdrop = null,
            logo = null,
            videoId = contentId,
            season = null,
            episode = null,
            episodeTitle = null,
            position = 0L,
            duration = 0L,
            lastWatched = parseIsoToMillis(item.pausedAt),
            progressPercent = item.progress?.coerceIn(0f, 100f),
            source = WatchProgress.SOURCE_TRAKT_PLAYBACK,
            traktPlaybackId = item.id,
            traktMovieId = movie.ids?.trakt
        )
    }

    private suspend fun mapPlaybackEpisode(item: TraktPlaybackItemDto): WatchProgress? {
        val show = item.show ?: return null
        val episode = item.episode ?: return null
        val season = episode.season ?: return null
        val number = episode.number ?: return null

        val contentId = normalizeContentId(show.ids)
        if (contentId.isBlank()) return null
        val videoId = resolveEpisodeVideoId(contentId, season, number)

        return WatchProgress(
            contentId = contentId,
            contentType = "series",
            name = show.title ?: contentId,
            poster = null,
            backdrop = null,
            logo = null,
            videoId = videoId,
            season = season,
            episode = number,
            episodeTitle = episode.title,
            position = 0L,
            duration = 0L,
            lastWatched = parseIsoToMillis(item.pausedAt),
            progressPercent = item.progress?.coerceIn(0f, 100f),
            source = WatchProgress.SOURCE_TRAKT_PLAYBACK,
            traktPlaybackId = item.id,
            traktShowId = show.ids?.trakt,
            traktEpisodeId = episode.ids?.trakt
        )
    }

    private fun mapSeasonProgress(
        contentId: String,
        season: TraktShowSeasonProgressDto
    ): List<WatchProgress> {
        val seasonNumber = season.number ?: return emptyList()
        return season.episodes.orEmpty()
            .filter { it.completed == true }
            .mapNotNull { episode ->
                val episodeNumber = episode.number ?: return@mapNotNull null
                WatchProgress(
                    contentId = contentId,
                    contentType = "series",
                    name = contentId,
                    poster = null,
                    backdrop = null,
                    logo = null,
                    videoId = "$contentId:$seasonNumber:$episodeNumber",
                    season = seasonNumber,
                    episode = episodeNumber,
                    episodeTitle = null,
                    position = 1L,
                    duration = 1L,
                    lastWatched = parseIsoToMillis(episode.lastWatchedAt),
                    progressPercent = 100f,
                    source = WatchProgress.SOURCE_TRAKT_SHOW_PROGRESS
                )
            }
    }

    internal data class ResolvedEpisodeInfo(
        val videoId: String,
        val released: String? = null
    )

    private suspend fun resolveEpisodeVideoId(
        contentId: String,
        season: Int,
        episode: Int
    ): String = resolveEpisodeInfo(contentId, season, episode).videoId

    private suspend fun resolveEpisodeInfo(
        contentId: String,
        season: Int,
        episode: Int
    ): ResolvedEpisodeInfo = findEpisodeInfo(contentId, season, episode)

    private fun episodeInfoCacheKey(contentId: String, season: Int, episode: Int): String =
        "$contentId:$season:$episode"

    @VisibleForTesting
    internal fun testOnlyPutEpisodeInfo(contentId: String, season: Int, episode: Int, info: ResolvedEpisodeInfo) {
        episodeInfoCache[episodeInfoCacheKey(contentId, season, episode)] = info
    }

    @VisibleForTesting
    internal fun testOnlyEpisodeInfoCacheContains(contentId: String, season: Int, episode: Int): Boolean =
        episodeInfoCache.containsKey(episodeInfoCacheKey(contentId, season, episode))

    internal suspend fun findEpisodeInfo(
        contentId: String,
        season: Int,
        episode: Int
    ): ResolvedEpisodeInfo {
        val key = episodeInfoCacheKey(contentId, season, episode)
        episodeInfoCache[key]?.let { return it }

        val request = MetadataRequest(
            contentId = contentId,
            contentType = ContentType.fromString("series"),
            sourceContext = MetadataSourceContext(itemType = "series"),
            seasonNumber = season,
            depth = MetadataDepth.SEASON
        )
        val episodeMap = try {
            metadataRouterFacade.fetchTvEpisodeEnrichment(
                metadataRequest = request,
                tvRequest = TvMetadataRequest(
                    contentId = contentId,
                    contentType = ContentType.fromString("series"),
                    seasonNumbers = listOf(season)
                )
            ).value
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "findEpisodeInfo fetchTvEpisodeEnrichment failed for $contentId s${season}e${episode}: ${e.message}", e)
            null
        }

        val episodeMeta = episodeMap?.get(season to episode)
        val info = ResolvedEpisodeInfo(
            videoId = key,
            released = episodeMeta?.airDate
        )
        episodeInfoCache[key] = info
        return info
    }

    private fun progressKey(progress: WatchProgress): String {
        return if (progress.season != null && progress.episode != null) {
            "${progress.contentId}_s${progress.season}e${progress.episode}"
        } else {
            progress.contentId
        }
    }

    private fun hasSuccessfulHistoryAdd(body: TraktHistoryAddResponseDto?): Boolean {
        val added = body?.added ?: return false
        val addedCount = (added.movies ?: 0) +
            (added.episodes ?: 0) +
            (added.shows ?: 0) +
            (added.seasons ?: 0)
        return addedCount > 0
    }

    private fun hasHistoryAddNotFound(body: TraktHistoryAddResponseDto?): Boolean {
        val notFound = body?.notFound ?: return false
        return !notFound.movies.isNullOrEmpty() ||
            !notFound.shows.isNullOrEmpty() ||
            !notFound.seasons.isNullOrEmpty() ||
            !notFound.episodes.isNullOrEmpty()
    }

    private fun buildHistoryAddRequest(
        progress: WatchProgress,
        title: String?,
        year: Int?
    ): TraktHistoryAddRequestDto? {
        val ids = resolveHistoryIds(progress)
        if (!ids.hasAnyId()) return null
        val watchedAt = toTraktUtcDateTime(progress.lastWatched)

        val normalizedType = progress.contentType.lowercase()
        val isEpisode = normalizedType in listOf("series", "tv") &&
            progress.season != null && progress.episode != null

        return if (isEpisode) {
            TraktHistoryAddRequestDto(
                shows = listOf(
                    TraktHistoryShowAddDto(
                        title = title,
                        year = year,
                        ids = ids,
                        seasons = listOf(
                            TraktHistorySeasonAddDto(
                                number = progress.season,
                                episodes = listOf(
                                    TraktHistoryEpisodeAddDto(
                                        number = progress.episode,
                                        watchedAt = watchedAt
                                    )
                                )
                            )
                        )
                    )
                )
            )
        } else {
            TraktHistoryAddRequestDto(
                movies = listOf(
                    TraktHistoryMovieAddDto(
                        title = title,
                        year = year,
                        ids = ids,
                        watchedAt = watchedAt
                    )
                )
            )
        }
    }

    private fun resolveHistoryIds(progress: WatchProgress): TraktIdsDto {
        val contentIds = toTraktIds(parseContentIds(progress.contentId))
        if (contentIds.hasAnyId()) return contentIds

        val videoIds = toTraktIds(parseContentIds(progress.videoId))
        if (videoIds.hasAnyId()) return videoIds

        return contentIds
    }

    private fun toTraktUtcDateTime(lastWatchedMs: Long): String {
        val safeMs = if (lastWatchedMs > 0L) lastWatchedMs else System.currentTimeMillis()
        val formatter = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US).apply {
            timeZone = TimeZone.getTimeZone("UTC")
        }
        return formatter.format(Date(safeMs))
    }

    private fun enrichWithMetadata(
        progress: WatchProgress,
        metadataMap: Map<String, ContentMetadata>
    ): WatchProgress {
        val metadata = metadataMap[progress.contentId] ?: return progress
        val episodeMeta = if (progress.season != null && progress.episode != null) {
            metadata.episodes[progress.season to progress.episode]
        } else {
            null
        }
        val shouldOverrideName = progress.name.isBlank() || progress.name == progress.contentId
        val backdrop = progress.backdrop
            ?: metadata.backdrop
            ?: episodeMeta?.thumbnail

        return progress.copy(
            name = if (shouldOverrideName) metadata.name ?: progress.name else progress.name,
            poster = progress.poster ?: metadata.poster,
            backdrop = backdrop,
            logo = progress.logo ?: metadata.logo,
            episodeTitle = progress.episodeTitle ?: episodeMeta?.title
        )
    }

    private fun reconcileOptimistic(remote: List<WatchProgress>) {
        val remoteByKey = remote.associateBy { progressKey(it) }
        val now = System.currentTimeMillis()
        optimisticProgress.update { current ->
            current.filter { (key, entry) ->
                if (entry.expiresAtMs <= now) return@filter false
                val remoteProgress = remoteByKey[key] ?: return@filter true
                val closeEnough = abs(remoteProgress.progressPercentage - entry.progress.progressPercentage) <= 0.03f
                val remoteNewer = remoteProgress.lastWatched >= entry.progress.lastWatched - 1_000L
                !(closeEnough && remoteNewer)
            }
        }
    }

    private fun requestFastSync() {
        val now = System.currentTimeMillis()
        if (now - lastFastSyncRequestMs < fastSyncThrottleMs) return
        lastFastSyncRequestMs = now
        forceRefreshUntilMs = now + 30_000L
        refreshSignals.tryEmit(Unit)
    }

    private fun hydrateMetadata(progressList: List<WatchProgress>) {
        val sorted = progressList.sortedByDescending { it.lastWatched }
        val uniqueByContent = linkedMapOf<String, WatchProgress>()
        sorted.forEach { progress ->
            if (uniqueByContent.size < metadataHydrationLimit) {
                uniqueByContent.putIfAbsent(progress.contentId, progress)
            }
        }

        uniqueByContent.values.forEach { progress ->
            val contentId = progress.contentId
            if (contentId.isBlank()) return@forEach
            if (metadataState.value.containsKey(contentId)) return@forEach

            scope.launch {
                val shouldFetch = metadataMutex.withLock {
                    if (metadataState.value.containsKey(contentId)) return@withLock false
                    if (inFlightMetadataKeys.contains(contentId)) return@withLock false
                    inFlightMetadataKeys.add(contentId)
                    true
                }
                if (!shouldFetch) return@launch

                try {
                    metadataFetchSemaphore.withPermit {
                        val metadata = fetchContentMetadata(
                            contentId = contentId,
                            contentType = progress.contentType
                        ) ?: return@launch
                        metadataState.update { current ->
                            current + (contentId to metadata)
                        }
                    }
                } finally {
                    metadataMutex.withLock {
                        inFlightMetadataKeys.remove(contentId)
                    }
                }
            }
        }
    }

    internal suspend fun fetchContentMetadata(
        contentId: String,
        contentType: String
    ): ContentMetadata? {
        val request = MetadataRequest(
            contentId = contentId,
            contentType = ContentType.fromString(contentType),
            sourceContext = MetadataSourceContext(itemType = contentType),
            depth = MetadataDepth.DETAIL_CORE
        )
        val canonical = try {
            metadataRouterFacade.resolveRequest(request)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "fetchContentMetadata resolveRequest failed for $contentId: ${e.message}", e)
            return null
        }
        if (canonical.route == null) return null
        val display = canonical.displayMetadata
        return ContentMetadata(
            name = display.title,
            poster = display.poster,
            backdrop = display.backdrop,
            logo = display.logo,
            episodes = emptyMap()
        )
    }

    suspend fun addHistoryBatch(
        episodes: List<TraktHistoryEpisodeAddDto>
    ): Response<TraktHistoryAddResponseDto> {
        val body = TraktHistoryAddRequestDto(episodes = episodes)
        return traktProgressMutationExecutor.addHistory(body)
            ?: throw IllegalStateException("Trakt authorized request returned null")
    }
}
