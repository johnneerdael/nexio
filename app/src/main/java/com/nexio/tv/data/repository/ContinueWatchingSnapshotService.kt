package com.nexio.tv.data.repository

import android.util.Log
import com.nexio.tv.core.network.NetworkResult
import com.nexio.tv.core.profile.ProfileManager
import com.nexio.tv.data.local.MetadataDiskCacheStore
import com.nexio.tv.data.local.ContinueWatchingSnapshotStore
import com.nexio.tv.data.local.TraktSettingsDataStore
import com.nexio.tv.domain.model.HomeDisplayMetadata
import com.nexio.tv.domain.model.Meta
import com.nexio.tv.domain.model.homeDisplayItemKey
import com.nexio.tv.domain.model.mergeFallback
import com.nexio.tv.domain.model.toHomeDisplayMetadata
import com.nexio.tv.domain.model.WatchProgress
import com.nexio.tv.domain.repository.MetaRepository
import com.nexio.tv.domain.repository.WatchProgressRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

data class ContinueWatchingSnapshot(
    val resumeItems: List<WatchProgress> = emptyList(),
    val nextUpItems: List<TrackingNextUpEntry> = emptyList(),
    val traktUpNextItems: List<TrackingNextUpEntry> = emptyList(),
    val displayMetadataByItemKey: Map<String, HomeDisplayMetadata> = emptyMap(),
    val updatedAtMs: Long = 0L,
    /** Entries excluded from rails because their air date has not yet passed. */
    val scheduledReemit: List<TrackingNextUpEntry> = emptyList()
)

@Singleton
@OptIn(ExperimentalCoroutinesApi::class)
class ContinueWatchingSnapshotService @Inject constructor(
    private val watchProgressRepository: WatchProgressRepository,
    private val trackingProgressService: TrackingProgressService,
    private val trackingProviderStateService: TrackingProviderStateService,
    private val traktSettingsDataStore: TraktSettingsDataStore,
    private val metaRepository: MetaRepository,
    private val metadataDiskCacheStore: MetadataDiskCacheStore,
    private val snapshotStore: ContinueWatchingSnapshotStore,
    private val profileManager: ProfileManager? = null
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val rawSnapshotState = MutableStateFlow(ContinueWatchingSnapshot())
    private val snapshotState = MutableStateFlow(ContinueWatchingSnapshot())
    private val refreshMutex = Mutex()
    private var lastRefreshRequestMs = 0L
    private val minRefreshIntervalMs = 30_000L
    @Volatile
    private var hasSeenAuthenticatedSession = false
    private var reemitJob: Job? = null
    private var currentTimerTargetMs: Long? = null

    init {
        scope.coroutineContext[Job]?.invokeOnCompletion {
            reemitJob = null
            currentTimerTargetMs = null
        }
        scope.launch { loadPersistedSnapshotForActiveProfile(clearWhenMissing = false) }

        profileManager?.let { manager ->
            scope.launch {
                manager.profileSwitched.collectLatest {
                    loadPersistedSnapshotForActiveProfile(clearWhenMissing = true)
                }
            }
        }

        scope.launch {
            combine(
                rawSnapshotState,
                traktSettingsDataStore.dismissedNextUpKeys
            ) { snapshot, dismissedKeys ->
                if (dismissedKeys.isEmpty()) {
                    snapshot
                } else {
                    snapshot.copy(
                        nextUpItems = snapshot.nextUpItems.filter { entry ->
                            entry.contentId.trim() !in dismissedKeys
                        },
                        traktUpNextItems = snapshot.traktUpNextItems.filter { entry ->
                            entry.contentId.trim() !in dismissedKeys
                        }
                    )
                }
            }.collectLatest { filtered ->
                if (filtered != snapshotState.value) {
                    snapshotState.value = filtered
                }
            }
        }

        scope.launch {
            trackingProviderStateService.state.map { it.hasAuthenticatedProvider }
                .distinctUntilChanged()
                .flatMapLatest { isAuthenticated ->
                    if (!isAuthenticated) {
                        if (hasSeenAuthenticatedSession) {
                            rawSnapshotState.value = ContinueWatchingSnapshot()
                            snapshotStore.clear()
                            metadataDiskCacheStore.replaceHomeFeedReferences(feedKey = "continue_watching", itemKeys = emptySet())
                            lastRefreshRequestMs = 0L
                            reemitJob?.cancel()
                            reemitJob = null
                            currentTimerTargetMs = null
                        }
                        hasSeenAuthenticatedSession = false
                        flowOf<ContinueWatchingSnapshot?>(null)
                    } else {
                        hasSeenAuthenticatedSession = true
                        combine(
                            trackingProgressService.observeRemoteSnapshotLoaded(),
                            watchProgressRepository.allProgress,
                            trackingProgressService.observeContinueWatchingNextUp(),
                            trackingProgressService.observeSyntheticContinueWatchingNextUp()
                        ) { hasLoadedRemoteSnapshot, allProgress, nextUpEntries, traktUpNextEntries ->
                            if (!hasLoadedRemoteSnapshot) {
                                null
                            } else {
                                buildRawSnapshot(
                                    allProgress = allProgress,
                                    nextUpEntries = nextUpEntries,
                                    traktUpNextEntries = traktUpNextEntries
                                )
                            }
                        }
                    }
                }
                .collectLatest { snapshot ->
                    if (snapshot == null) return@collectLatest
                    updateSnapshot(snapshot)
                }
        }
    }

    suspend fun reloadPersistedSnapshotForActiveProfile(clearWhenMissing: Boolean = true) {
        loadPersistedSnapshotForActiveProfile(clearWhenMissing = clearWhenMissing)
    }

    private suspend fun loadPersistedSnapshotForActiveProfile(clearWhenMissing: Boolean) {
        val persisted = snapshotStore.read()
        if (persisted == null) {
            if (clearWhenMissing) {
                rawSnapshotState.value = ContinueWatchingSnapshot()
                snapshotState.value = ContinueWatchingSnapshot()
                lastRefreshRequestMs = 0L
                reemitJob?.cancel()
                reemitJob = null
                currentTimerTargetMs = null
            }
            return
        }
        val normalized = sanitizeSnapshot(persisted)
        rawSnapshotState.value = normalized
        snapshotState.value = normalized
        lastRefreshRequestMs = normalized.updatedAtMs
        scheduleReemitIfNeeded(normalized.scheduledReemit, normalized.updatedAtMs)
    }

    fun observeSnapshot(): Flow<ContinueWatchingSnapshot> {
        return snapshotState.onStart {
            scope.launch {
                runCatching { ensureFresh(force = false) }
                    .onFailure { error ->
                        Log.w("ContinueWatching", "Failed to refresh continue watching snapshot", error)
                    }
            }
        }
    }

    suspend fun ensureFresh(force: Boolean) = withContext(Dispatchers.IO) {
        val now = System.currentTimeMillis()
        if (!force && now - lastRefreshRequestMs < minRefreshIntervalMs && snapshotState.value.updatedAtMs > 0L) {
            return@withContext
        }

        refreshMutex.withLock {
            val lockedNow = System.currentTimeMillis()
            if (!force &&
                lockedNow - lastRefreshRequestMs < minRefreshIntervalMs &&
                snapshotState.value.updatedAtMs > 0L
            ) {
                return@withLock
            }
            trackingProgressService.refreshNow()
            lastRefreshRequestMs = lockedNow
        }
    }

    /**
     * Current raw resume entries (pre dismissal/next-up filtering). Used by callers that need
     * to look up the exact [WatchProgress] for rollback of an optimistic mutation.
     */
    fun currentRawResumeItems(): List<WatchProgress> = rawSnapshotState.value.resumeItems

    // ── Synchronous rawSnapshotState mutation helpers (Phase 3) ───────────────

    /**
     * Uniquely identifies a resume entry by canonical show/season/episode coordinates.
     */
    data class EpisodeRef(val showId: String, val seasonNumber: Int, val episodeNumber: Int)

    /**
     * Snapshot of entries removed by an optimistic season-mark mutation.
     * Used for rollback of resume + next-up rails if the batched call fails.
     */
    data class EpisodeRollbackState(
        val resumeItems: List<WatchProgress> = emptyList(),
        val nextUpItems: List<TrackingNextUpEntry> = emptyList(),
        val traktUpNextItems: List<TrackingNextUpEntry> = emptyList()
    )

    /**
     * Remove a single resume entry by its [videoId] under [refreshMutex].
     * This is the symmetric counterpart to [reinsertResumeEntry].
     */
    suspend fun removeResumeEntry(videoId: String) {
        refreshMutex.withLock {
            rawSnapshotState.update { current ->
                current.copy(
                    resumeItems = current.resumeItems.filterNot { it.videoId == videoId }
                )
            }
        }
    }

    /**
     * Remove every resume entry whose [WatchProgress.contentId] matches [showId] under [refreshMutex].
     */
    suspend fun removeAllForShow(showId: String) {
        refreshMutex.withLock {
            rawSnapshotState.update { current ->
                current.copy(
                    resumeItems = current.resumeItems.filterNot { it.contentId == showId }
                )
            }
        }
    }

    /**
     * Re-insert a previously removed [WatchProgress] entry, preserving descending-lastWatched order.
     */
    suspend fun reinsertResumeEntry(entry: WatchProgress) {
        refreshMutex.withLock {
            rawSnapshotState.update { current ->
                val merged = (current.resumeItems + entry)
                    .sortedByDescending { it.lastWatched }
                    .distinctBy { it.videoId }
                current.copy(resumeItems = merged)
            }
        }
    }

    /**
     * Returns the current snapshot data from the raw state for use as a rollback baseline.
     * Must be called before [applyEpisodesMarked] to capture the pre-mutation state.
     */
    fun snapshotForRollback(): EpisodeRollbackState = rawSnapshotState.value.let { snapshot ->
        EpisodeRollbackState(
            resumeItems = snapshot.resumeItems,
            nextUpItems = snapshot.nextUpItems,
            traktUpNextItems = snapshot.traktUpNextItems
        )
    }

    /**
     * Returns only the rollback entries that match [episodes].
     * Use this for durable mutation payloads so they only carry the state needed to restore
     * the affected season rows rather than the whole continue-watching snapshot.
     */
    fun snapshotForEpisodes(episodes: List<EpisodeRef>): EpisodeRollbackState {
        if (episodes.isEmpty()) return EpisodeRollbackState()
        val keys = episodes
            .map { "${it.showId}|${it.seasonNumber}|${it.episodeNumber}" }
            .toSet()
        return rawSnapshotState.value.let { snapshot ->
            EpisodeRollbackState(
                resumeItems = snapshot.resumeItems.filter { progress ->
                    progress.season != null &&
                        progress.episode != null &&
                        keys.contains("${progress.contentId}|${progress.season}|${progress.episode}")
                },
                nextUpItems = snapshot.nextUpItems.filter { entry ->
                    keys.contains("${entry.contentId}|${entry.season}|${entry.episode}")
                },
                traktUpNextItems = snapshot.traktUpNextItems.filter { entry ->
                    keys.contains("${entry.contentId}|${entry.season}|${entry.episode}")
                }
            )
        }
    }

    /**
     * Remove all resume entries and next-up entries that match any of the given [episodes].
     * Matches on showId + seasonNumber + episodeNumber.
     */
    suspend fun applyEpisodesMarked(episodes: List<EpisodeRef>) {
        if (episodes.isEmpty()) return
        refreshMutex.withLock {
            rawSnapshotState.update { current ->
                current.copy(
                    resumeItems = current.resumeItems.filterNot { progress ->
                        episodes.any { ref ->
                            progress.contentId == ref.showId &&
                                progress.season == ref.seasonNumber &&
                                progress.episode == ref.episodeNumber
                        }
                    },
                    nextUpItems = current.nextUpItems.filterNot { entry ->
                        episodes.any { ref ->
                            entry.contentId == ref.showId &&
                                entry.season == ref.seasonNumber &&
                                entry.episode == ref.episodeNumber
                        }
                    },
                    traktUpNextItems = current.traktUpNextItems.filterNot { entry ->
                        episodes.any { ref ->
                            entry.contentId == ref.showId &&
                                entry.season == ref.seasonNumber &&
                                entry.episode == ref.episodeNumber
                        }
                    }
                )
            }
        }
    }

    /**
     * Restore entries that were optimistically removed by [applyEpisodesMarked].
     */
    suspend fun rollbackEpisodes(state: EpisodeRollbackState) {
        if (state.resumeItems.isEmpty() && state.nextUpItems.isEmpty() && state.traktUpNextItems.isEmpty()) {
            return
        }
        refreshMutex.withLock {
            rawSnapshotState.update { current ->
                val rollbackResume = current.resumeItems
                    .associateByTo(LinkedHashMap<String, WatchProgress>()) { it.videoId }
                    .also { existing ->
                        state.resumeItems.forEach { entry -> existing.putIfAbsent(entry.videoId, entry) }
                    }
                    .values
                    .toList()
                    .sortedByDescending { it.lastWatched }

                val rollbackNextUp = (current.nextUpItems + state.nextUpItems)
                    .distinctBy {
                        "${it.contentId}|${it.season}|${it.episode}"
                    }
                    .sortedByDescending { it.activityAtMs }

                val rollbackTraktUpNext = (current.traktUpNextItems + state.traktUpNextItems)
                    .distinctBy {
                        "${it.contentId}|${it.season}|${it.episode}"
                    }
                    .sortedByDescending { it.activityAtMs }

                current.copy(
                    resumeItems = rollbackResume,
                    nextUpItems = rollbackNextUp,
                    traktUpNextItems = rollbackTraktUpNext
                )
            }
        }
    }

    suspend fun rollbackEpisodes(episodes: List<WatchProgress>) {
        rollbackEpisodes(
            EpisodeRollbackState(
                resumeItems = episodes
            )
        )
    }

    // ─────────────────────────────────────────────────────────────────────────

    suspend fun removeShowOptimistically(contentId: String) {
        val target = contentId.trim()
        if (target.isBlank()) return
        refreshMutex.withLock {
            rawSnapshotState.update { current ->
                current.copy(
                    nextUpItems = current.nextUpItems.filterNot { it.contentId == target },
                    traktUpNextItems = current.traktUpNextItems.filterNot { it.contentId == target }
                )
            }
        }
    }

    fun invalidateLocalizedMetadata() {
        trackingProgressService.invalidateLocalizedMetadata()
        snapshotStore.clear()
        metadataDiskCacheStore.replaceHomeFeedReferences(feedKey = "continue_watching", itemKeys = emptySet())
        scope.launch {
            rawSnapshotState.update { it.copy(displayMetadataByItemKey = emptyMap()) }
            snapshotState.value = rawSnapshotState.value
        }
    }

    private fun buildRawSnapshot(
        allProgress: List<WatchProgress>,
        nextUpEntries: List<TrackingNextUpEntry>,
        traktUpNextEntries: List<TrackingNextUpEntry>
    ): ContinueWatchingSnapshot {
        val nowMs = System.currentTimeMillis()
        val resumeItems = allProgress
            .asSequence()
            .filter(::shouldTreatAsResumeForContinueWatching)
            .mapNotNull(::normalizeResumeItem)
            .sortedByDescending { it.lastWatched }
            .distinctBy { it.contentId }
            .toList()
        val normalizedNextUpItems = nextUpEntries
            .asSequence()
            .mapNotNull(::normalizeNextUpEntry)
            .sortedByDescending { it.activityAtMs }
            .distinctBy { "${it.contentId}|${it.season}|${it.episode}" }
            .toList()
        val nextUpMainCandidates = splitNextUpCandidatesForContinueWatching(
            resumes = resumeItems.map(::resumeRefForProgress),
            nextUpItems = normalizedNextUpItems,
            nextUpRef = ::nextUpRefForEntry,
            nowMs = nowMs
        ).mainFeedItems
        val nextUpItems = nextUpMainCandidates.filter { entry ->
            AirDateGate.isAired(
                availabilityInstantMs = entry.tvdbAvailabilityInstantMs,
                firstAiredMs = entry.firstAiredMs,
                tmdbAirDate = entry.firstAired,
                nowMs = nowMs
            )
        }
        val normalizedTraktUpNextItems = traktUpNextEntries
            .asSequence()
            .mapNotNull(::normalizeNextUpEntry)
            .sortedByDescending { it.activityAtMs }
            .distinctBy { "${it.contentId}|${it.season}|${it.episode}" }
            .toList()
        val syntheticRailCandidates = splitNextUpCandidatesForContinueWatching(
            resumes = resumeItems.map(::resumeRefForProgress),
            nextUpItems = normalizedTraktUpNextItems,
            nextUpRef = ::nextUpRefForEntry,
            nowMs = nowMs
        ).syntheticRailItems
        val traktUpNextItems = syntheticRailCandidates.filter { entry ->
            AirDateGate.isAired(
                availabilityInstantMs = entry.tvdbAvailabilityInstantMs,
                firstAiredMs = entry.firstAiredMs,
                tmdbAirDate = entry.firstAired,
                nowMs = nowMs
            )
        }

        // Resume items carry no air-date data; running them through AirDateGate keeps all
        // three rails on a single uniform gate site. With firstAiredMs=0 and tmdbAirDate=null,
        // isAired() returns true — so this is a no-op for resumes today, but preserves the
        // contract that every rail participates in gating.
        val gatedResumeItems = resumeItems.filter {
            AirDateGate.isAired(firstAiredMs = 0L, tmdbAirDate = null, nowMs = nowMs)
        }

        val scheduledReemit = buildList {
            addAll(nextUpMainCandidates.filter { entry ->
                !AirDateGate.isAired(
                    availabilityInstantMs = entry.tvdbAvailabilityInstantMs,
                    firstAiredMs = entry.firstAiredMs,
                    tmdbAirDate = entry.firstAired,
                    nowMs = nowMs
                )
            })
            addAll(syntheticRailCandidates.filter { entry ->
                !AirDateGate.isAired(
                    availabilityInstantMs = entry.tvdbAvailabilityInstantMs,
                    firstAiredMs = entry.firstAiredMs,
                    tmdbAirDate = entry.firstAired,
                    nowMs = nowMs
                )
            })
        }

        return ContinueWatchingSnapshot(
            resumeItems = gatedResumeItems,
            nextUpItems = nextUpItems,
            traktUpNextItems = traktUpNextItems,
            updatedAtMs = nowMs,
            scheduledReemit = scheduledReemit
        )
    }

    private fun shouldTreatAsResumeForContinueWatching(progress: WatchProgress): Boolean {
        if (progress.isInProgress()) return true
        if (progress.isCompleted()) return false
        return progress.position > 0L || progress.progressPercent?.let { it > 0f } == true
    }

    private fun sanitizeSnapshot(snapshot: ContinueWatchingSnapshot): ContinueWatchingSnapshot {
        val resumeItems = snapshot.resumeItems
            .mapNotNull(::normalizeResumeItem)
            .sortedByDescending { it.lastWatched }
            .distinctBy { it.contentId }
        val nextUpItems = snapshot.nextUpItems
            .mapNotNull(::normalizeNextUpEntry)
            .sortedByDescending { it.activityAtMs }
            .distinctBy { it.contentId }
        val mainFeedNextUpItems = splitNextUpCandidatesForContinueWatching(
            resumes = resumeItems.map(::resumeRefForProgress),
            nextUpItems = nextUpItems,
            nextUpRef = ::nextUpRefForEntry,
            nowMs = System.currentTimeMillis()
        ).mainFeedItems
        val traktUpNextItems = snapshot.traktUpNextItems
            .mapNotNull(::normalizeNextUpEntry)
            .sortedByDescending { it.activityAtMs }
            .distinctBy { it.contentId }
        val sanitizedTraktUpNextItems = splitNextUpCandidatesForContinueWatching(
            resumes = resumeItems.map(::resumeRefForProgress),
            nextUpItems = traktUpNextItems,
            nextUpRef = ::nextUpRefForEntry,
            nowMs = System.currentTimeMillis()
        ).syntheticRailItems
        val activeItemKeys = buildSet {
            resumeItems.forEach { progress ->
                add(homeDisplayItemKey(progress.contentType, progress.contentId))
            }
            mainFeedNextUpItems.forEach { entry ->
                add(homeDisplayItemKey(entry.contentType, entry.contentId))
            }
            sanitizedTraktUpNextItems.forEach { entry ->
                add(homeDisplayItemKey(entry.contentType, entry.contentId))
            }
        }
        val updatedAtMs = if (snapshot.updatedAtMs > 0L) snapshot.updatedAtMs else System.currentTimeMillis()
        return ContinueWatchingSnapshot(
            resumeItems = resumeItems,
            nextUpItems = mainFeedNextUpItems,
            traktUpNextItems = sanitizedTraktUpNextItems,
            displayMetadataByItemKey = snapshot.displayMetadataByItemKey.filterKeys { it in activeItemKeys },
            updatedAtMs = updatedAtMs,
            scheduledReemit = snapshot.scheduledReemit
        )
    }

    private fun normalizeNextUpEntry(
        entry: TrackingNextUpEntry
    ): TrackingNextUpEntry? {
        return try {
            val contentId = entry.contentId.trim()
            if (contentId.isBlank()) return null
            val season = entry.season.takeIf { it > 0 } ?: return null
            val episode = entry.episode.takeIf { it > 0 } ?: return null
            entry.copy(
                contentId = contentId,
                contentType = entry.contentType.takeIf { it.isNotBlank() } ?: "series",
                name = entry.name.takeIf { it.isNotBlank() } ?: contentId,
                videoId = entry.videoId.takeIf { it.isNotBlank() } ?: "$contentId:$season:$episode"
            )
        } catch (_: Throwable) {
            null
        }
    }

    private suspend fun persistRawSnapshot(snapshot: ContinueWatchingSnapshot) {
        val normalized = sanitizeSnapshot(snapshot)
        val hydrated = hydrateSnapshotMetadata(
            snapshot = normalized,
            fallbackMetadata = rawSnapshotState.value.displayMetadataByItemKey
        )
        val referencedItemKeys = buildSet {
            hydrated.resumeItems.forEach { progress ->
                add(homeDisplayItemKey(progress.contentType, progress.contentId))
            }
            hydrated.nextUpItems.forEach { entry ->
                add(homeDisplayItemKey(entry.contentType, entry.contentId))
            }
            hydrated.traktUpNextItems.forEach { entry ->
                add(homeDisplayItemKey(entry.contentType, entry.contentId))
            }
        }
        rawSnapshotState.value = hydrated
        snapshotStore.write(hydrated)
        metadataDiskCacheStore.replaceHomeFeedReferences(
            feedKey = "continue_watching",
            itemKeys = referencedItemKeys
        )
        metadataDiskCacheStore.removeHomeUnreferencedMetaEntries()
        lastRefreshRequestMs = hydrated.updatedAtMs
    }

    private suspend fun updateSnapshot(snapshot: ContinueWatchingSnapshot) {
        persistRawSnapshot(snapshot)
        scheduleReemitIfNeeded(snapshot.scheduledReemit, snapshot.updatedAtMs)
    }

    private fun scheduleReemitIfNeeded(
        scheduledReemit: List<TrackingNextUpEntry>,
        nowMs: Long
    ) {
        val soonestMs = AirDateGate.soonestPendingMs(
            entries = scheduledReemit,
            firstAiredMsSelector = { it.firstAiredMs },
            availabilityInstantMsSelector = { it.tvdbAvailabilityInstantMs },
            tmdbAirDateSelector = { it.firstAired },
            nowMs = nowMs
        )
        if (soonestMs == currentTimerTargetMs) return
        reemitJob?.cancel()
        currentTimerTargetMs = soonestMs
        if (soonestMs != null) {
            val delayMs = (soonestMs - nowMs).coerceAtLeast(0L)
            reemitJob = scope.launch {
                delay(delayMs)
                runCatching { ensureFresh(force = true) }
                    .onFailure { error ->
                        Log.w("ContinueWatching", "Re-emit refresh failed", error)
                    }
            }
        }
    }

    private suspend fun hydrateSnapshotMetadata(
        snapshot: ContinueWatchingSnapshot,
        fallbackMetadata: Map<String, HomeDisplayMetadata>
    ): ContinueWatchingSnapshot {
        val itemKeys = linkedMapOf<String, Pair<String, String>>()
        snapshot.resumeItems.forEach { progress ->
            itemKeys[homeDisplayItemKey(progress.contentType, progress.contentId)] =
                progress.contentType to progress.contentId
        }
        snapshot.nextUpItems.forEach { entry ->
            itemKeys[homeDisplayItemKey(entry.contentType, entry.contentId)] =
                entry.contentType to entry.contentId
        }
        snapshot.traktUpNextItems.forEach { entry ->
            itemKeys[homeDisplayItemKey(entry.contentType, entry.contentId)] =
                entry.contentType to entry.contentId
        }
        if (itemKeys.isEmpty()) {
            return snapshot.copy(displayMetadataByItemKey = emptyMap())
        }

        val hydratedMetadata = linkedMapOf<String, HomeDisplayMetadata>()
        itemKeys.forEach { (itemKey, typeAndId) ->
            val (contentType, contentId) = typeAndId
            val fetched = fetchHomeDisplayMetadata(
                contentType = contentType,
                contentId = contentId,
                snapshot = snapshot
            )
            val merged = fetched?.mergeFallback(fallbackMetadata[itemKey]) ?: fallbackMetadata[itemKey]
            if (merged != null) {
                hydratedMetadata[itemKey] = merged
            }
        }

        return snapshot.copy(displayMetadataByItemKey = hydratedMetadata)
    }

    private suspend fun fetchHomeDisplayMetadata(
        contentType: String,
        contentId: String,
        snapshot: ContinueWatchingSnapshot
    ): HomeDisplayMetadata? {
        val typeCandidates = buildList {
            val normalized = contentType.trim().lowercase()
            if (normalized.isNotBlank()) add(normalized)
            if (normalized == "tv") add("series")
            if (normalized == "series") add("tv")
            if (normalized != "movie") add("movie")
        }.distinct()
        val idCandidates = buildList {
            val trimmed = contentId.trim()
            add(trimmed)
            if (trimmed.startsWith("tmdb:")) add(trimmed.substringAfter(':'))
            if (trimmed.startsWith("trakt:")) add(trimmed.substringAfter(':'))
            if (trimmed.startsWith("tt", ignoreCase = true)) add("imdb:$trimmed")
        }.distinct()

        typeCandidates.forEach { type ->
            idCandidates.forEach { id ->
                val result = runCatching {
                    metaRepository.getMetaFromAllAddons(
                        type = type,
                        id = id,
                        cacheOnDisk = true,
                        origin = "continue_watching_snapshot"
                    )
                }.getOrNull() ?: return@forEach
                val resolved = runCatching { result.first { it !is NetworkResult.Loading } }.getOrNull()
                val meta = (resolved as? NetworkResult.Success<*>)?.data as? Meta ?: return@forEach
                return buildHomeDisplayMetadata(
                    meta = meta,
                    contentType = type,
                    contentId = contentId,
                    snapshot = snapshot
                )
            }
        }
        return null
    }

    private fun buildHomeDisplayMetadata(
        meta: Meta,
        contentType: String,
        contentId: String,
        snapshot: ContinueWatchingSnapshot
    ): HomeDisplayMetadata {
        val episodeMetadata = if (contentType.equals("series", ignoreCase = true) || contentType.equals("tv", ignoreCase = true)) {
            val progressEntry = snapshot.resumeItems
                .filter { it.contentId == contentId && it.season != null && it.episode != null }
                .maxByOrNull { it.lastWatched }
            val nextUpEntry = snapshot.nextUpItems.firstOrNull { it.contentId == contentId }
            val traktUpNextEntry = snapshot.traktUpNextItems.firstOrNull { it.contentId == contentId }
            val season = progressEntry?.season ?: nextUpEntry?.season ?: traktUpNextEntry?.season
            val episode = progressEntry?.episode ?: nextUpEntry?.episode ?: traktUpNextEntry?.episode
            if (season != null && episode != null) {
                meta.videos.firstOrNull { it.season == season && it.episode == episode }
            } else {
                null
            }
        } else {
            null
        }

        val metaDisplay = meta.toHomeDisplayMetadata()
        return metaDisplay.copy(
            description = episodeMetadata?.overview ?: metaDisplay.description,
            runtime = episodeMetadata?.runtime?.let { "${it}m" } ?: metaDisplay.runtime,
            poster = metaDisplay.poster,
            backdrop = metaDisplay.backdrop ?: episodeMetadata?.thumbnail
        )
    }

    private fun normalizeResumeItem(progress: WatchProgress): WatchProgress? {
        val contentId = progress.contentId.trim()
        val videoId = progress.videoId.trim()
        if (contentId.isBlank() || videoId.isBlank()) return null
        if (!shouldTreatAsResumeForContinueWatching(progress)) return null
        return progress.copy(
            contentId = contentId,
            videoId = videoId,
            contentType = progress.contentType.takeIf { it.isNotBlank() } ?: if (progress.season != null && progress.episode != null) "series" else "movie",
            name = progress.name.takeIf { it.isNotBlank() } ?: contentId
        )
    }

    private fun resumeRefForProgress(progress: WatchProgress): ContinueWatchingResumeRef {
        val suppressNextUp = progress.season != null &&
            progress.episode != null &&
            (progress.contentType.equals("series", ignoreCase = true) || progress.contentType.equals("tv", ignoreCase = true))
        return ContinueWatchingResumeRef(
            contentId = progress.contentId,
            activityAtMs = progress.lastWatched,
            suppressNextUp = suppressNextUp
        )
    }

    private fun nextUpRefForEntry(entry: TrackingNextUpEntry): ContinueWatchingNextUpRef {
        return ContinueWatchingNextUpRef(
            contentId = entry.contentId,
            activityAtMs = entry.activityAtMs,
            firstAiredMs = entry.firstAiredMs,
            availabilityInstantMs = entry.tvdbAvailabilityInstantMs
        )
    }
}
