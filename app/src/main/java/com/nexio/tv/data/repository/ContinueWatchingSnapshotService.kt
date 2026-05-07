package com.nexio.tv.data.repository

import android.util.Log
import com.nexio.tv.core.integration.ActiveRailTracker
import com.nexio.tv.core.integration.ActiveProfileSession
import com.nexio.tv.core.integration.IntegrationOwnershipService
import com.nexio.tv.core.integration.ProfileBoundaryEnforcer
import com.nexio.tv.core.integration.ProfileBoundaryException
import com.nexio.tv.core.integration.RailKeyFactory
import com.nexio.tv.core.integration.RailMediaIdentityResolver
import com.nexio.tv.core.integration.RailMembership
import com.nexio.tv.core.metadata.router.MetadataDepth
import com.nexio.tv.core.metadata.router.MetadataRequest
import com.nexio.tv.core.metadata.router.MetadataRouterFacade
import com.nexio.tv.core.metadata.router.MetadataSourceContext
import com.nexio.tv.data.local.integration.MediaIdentityEntity
import com.nexio.tv.data.local.integration.RailCacheEntity
import com.nexio.tv.data.local.integration.RailItemEntity
import com.nexio.tv.core.profile.ProfileManager
import kotlinx.coroutines.CancellationException
import com.nexio.tv.core.scheduler.ContinueWatchingAirScheduler
import com.nexio.tv.data.local.MetadataDiskCacheStore
import com.nexio.tv.data.local.ContinueWatchingSnapshotStore
import com.nexio.tv.data.local.TraktSettingsDataStore
import com.nexio.tv.domain.model.ContentType
import com.nexio.tv.domain.model.HomeDisplayMetadata
import com.nexio.tv.domain.model.homeDisplayItemKey
import com.nexio.tv.domain.model.TrackingProvider
import com.nexio.tv.domain.model.WatchProgress
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
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.filterNotNull
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

private const val REFRESH_FAILURE_RETRY_MS = 15 * 60_000L

private object NoopContinueWatchingAirScheduler : ContinueWatchingAirScheduler {
    override fun scheduleSoonest(triggerAtMs: Long?) = Unit
    override fun cancel() = Unit
}

data class ContinueWatchingSnapshot(
    val resumeItems: List<WatchProgress> = emptyList(),
    val nextUpItems: List<TrackingNextUpEntry> = emptyList(),
    val traktUpNextItems: List<TrackingNextUpEntry> = emptyList(),
    val displayMetadataByItemKey: Map<String, HomeDisplayMetadata> = emptyMap(),
    val metadataSnapshotsByItemKey: Map<String, ContinueWatchingMetadataSnapshot> = emptyMap(),
    val updatedAtMs: Long = 0L,
    /** Entries excluded from rails because their air date has not yet passed. */
    val scheduledReemit: List<TrackingNextUpEntry> = emptyList()
)

data class ProfileOwnedContinueWatchingSnapshot(
    val profileId: Int = 1,
    val snapshot: ContinueWatchingSnapshot = ContinueWatchingSnapshot()
) {
    fun isOwnedBy(activeProfileId: Int): Boolean {
        return profileId == activeProfileId
    }
}

internal fun ProfileOwnedContinueWatchingSnapshot.toContinueWatchingRecords(): List<ContinueWatchingRecord> {
    val now = System.currentTimeMillis().coerceAtLeast(1L)
    val resumeRecords = snapshot.resumeItems.map { progress ->
        val parentId = progress.contentId
        val episodeContext = if (progress.season != null && progress.episode != null) {
            ContinueWatchingRecord.EpisodeContext(
                season = progress.season!!,
                number = progress.episode!!
            )
        } else {
            null
        }
        val itemKey = if (episodeContext != null) {
            "$parentId:s${episodeContext.season}e${episodeContext.number}"
        } else {
            parentId
        }
        ContinueWatchingRecord(
            profileId = profileId,
            parentId = parentId,
            contentId = itemKey,
            provider = TrackingProvider.TRAKT,
            routingVersion = ContinueWatchingMetadataSnapshot.CURRENT_ROUTING_VERSION,
            positionMs = progress.position,
            durationMs = progress.duration,
            episodeContext = episodeContext,
            clickTimeDisplayMetadata = snapshot.metadataSnapshotsByItemKey[itemKey],
            source = ContinueWatchingRecord.Source.LOCAL,
            updatedAt = if (progress.lastWatched > 0L) progress.lastWatched else now
        )
    }
    val nextUpRecords = snapshot.nextUpItems.map { entry ->
        val episodeContext = ContinueWatchingRecord.EpisodeContext(
            season = entry.season,
            number = entry.episode
        )
        val itemKey = "${entry.contentId}:s${entry.season}e${entry.episode}"
        ContinueWatchingRecord(
            profileId = profileId,
            parentId = entry.contentId,
            contentId = itemKey,
            provider = TrackingProvider.TRAKT,
            routingVersion = ContinueWatchingMetadataSnapshot.CURRENT_ROUTING_VERSION,
            positionMs = 0L,
            durationMs = 0L,
            episodeContext = episodeContext,
            clickTimeDisplayMetadata = snapshot.metadataSnapshotsByItemKey[itemKey],
            source = ContinueWatchingRecord.Source.SYNTHETIC,
            updatedAt = if (entry.activityAtMs > 0L) entry.activityAtMs else now
        )
    }
    return resumeRecords + nextUpRecords
}

private data class LiveContinueWatchingSnapshotEmission(
    val profileId: Int,
    val hasLoadedRemoteSnapshot: Boolean,
    val snapshot: ContinueWatchingSnapshot?
)

@Singleton
@OptIn(ExperimentalCoroutinesApi::class)
class ContinueWatchingSnapshotService @Inject constructor(
    private val watchProgressRepository: WatchProgressRepository,
    private val trackingProgressService: TrackingProgressService,
    private val trackingProviderStateService: TrackingProviderStateService,
    private val traktSettingsDataStore: TraktSettingsDataStore,
    private val metadataDiskCacheStore: MetadataDiskCacheStore,
    private val snapshotStore: ContinueWatchingSnapshotStore,
    private val airScheduler: ContinueWatchingAirScheduler = NoopContinueWatchingAirScheduler,
    private val profileManager: ProfileManager? = null,
    private val ownershipService: IntegrationOwnershipService? = null,
    private val activeRailTracker: ActiveRailTracker = ActiveRailTracker(),
    private val identityResolver: RailMediaIdentityResolver = RailMediaIdentityResolver(),
    private val metadataRouterFacade: MetadataRouterFacade? = null
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val rawSnapshotState = MutableStateFlow(ProfileOwnedContinueWatchingSnapshot())
    private val snapshotState = MutableStateFlow(ProfileOwnedContinueWatchingSnapshot())
    private val persistedSnapshotReady = MutableStateFlow(false)
    private val refreshMutex = Mutex()
    private var lastRefreshRequestMs = 0L
    private val minRefreshIntervalMs = 30_000L
    @Volatile
    private var hasSeenAuthenticatedSession = false
    private var reemitJob: Job? = null
    private var currentTimerTargetMs: Long? = null
    private val liveProfileGateLock = Any()
    private val liveProfilesReady = mutableSetOf<Int>()
    private val profilesAwaitingRemoteReset = mutableSetOf<Int>()
    private val profilesThatObservedRemoteReset = mutableSetOf<Int>()

    init {
        synchronized(liveProfileGateLock) {
            liveProfilesReady += activeProfileId()
        }
        scope.coroutineContext[Job]?.invokeOnCompletion {
            reemitJob = null
            currentTimerTargetMs = null
        }
        scope.launch { loadPersistedSnapshotForActiveProfile(clearWhenMissing = false) }

        profileManager?.let { manager ->
            scope.launch {
                manager.profileSwitched.collectLatest { profileId ->
                    markProfileAwaitingLiveReset(profileId)
                    persistedSnapshotReady.value = false
                    loadPersistedSnapshotForActiveProfile(clearWhenMissing = true)
                }
            }
        }

        scope.launch {
            combine(
                rawSnapshotState,
                traktSettingsDataStore.dismissedNextUpKeys
            ) { ownedSnapshot, dismissedKeys ->
                val snapshot = ownedSnapshot.snapshot
                if (dismissedKeys.isEmpty()) {
                    ownedSnapshot
                } else {
                    ownedSnapshot.copy(
                        snapshot = snapshot.copy(
                            nextUpItems = snapshot.nextUpItems.filter { entry ->
                                entry.contentId.trim() !in dismissedKeys
                            },
                            traktUpNextItems = snapshot.traktUpNextItems.filter { entry ->
                                entry.contentId.trim() !in dismissedKeys
                            }
                        )
                    )
                }
            }.collectLatest { filtered ->
                if (filtered != snapshotState.value) {
                    snapshotState.value = filtered
                }
            }
        }

        scope.launch {
            activeProfileIdFlow()
                .distinctUntilChanged()
                .flatMapLatest { profileId ->
                    trackingProviderStateService.stateForProfile(profileId).map { state ->
                        profileId to state.hasAuthenticatedProvider
                    }.distinctUntilChanged()
                }
                .flatMapLatest { (profileId, isAuthenticated) ->
                    if (!isAuthenticated) {
                        ownershipService?.removeRail(RailKeyFactory.continueWatching(profileId))
                        lastRefreshRequestMs = 0L
                        cancelReemitScheduling()
                        hasSeenAuthenticatedSession = false
                        watchProgressRepository.observeProgress(profileId)
                            .map { allProgress ->
                                LiveContinueWatchingSnapshotEmission(
                                profileId = profileId,
                                    hasLoadedRemoteSnapshot = true,
                                    snapshot = buildRawSnapshot(
                                        allProgress = allProgress,
                                        nextUpEntries = emptyList(),
                                        traktUpNextEntries = emptyList()
                                    )
                                )
                            }
                    } else {
                        hasSeenAuthenticatedSession = true
                        combine(
                            trackingProgressService.observeRemoteSnapshotLoaded(),
                            watchProgressRepository.observeProgress(profileId),
                            trackingProgressService.observeContinueWatchingNextUp(),
                            trackingProgressService.observeSyntheticContinueWatchingNextUp()
                        ) { hasLoadedRemoteSnapshot, allProgress, nextUpEntries, traktUpNextEntries ->
                            if (!hasLoadedRemoteSnapshot) {
                                LiveContinueWatchingSnapshotEmission(
                                    profileId = profileId,
                                    hasLoadedRemoteSnapshot = false,
                                    snapshot = null
                                )
                            } else {
                                LiveContinueWatchingSnapshotEmission(
                                    profileId = profileId,
                                    hasLoadedRemoteSnapshot = true,
                                    snapshot = buildRawSnapshot(
                                        allProgress = allProgress,
                                        nextUpEntries = nextUpEntries,
                                        traktUpNextEntries = traktUpNextEntries
                                    )
                                )
                            }
                        }
                    }
                }
                .collectLatest { emission ->
                    noteRemoteSnapshotState(
                        profileId = emission.profileId,
                        hasLoadedRemoteSnapshot = emission.hasLoadedRemoteSnapshot
                    )
                    val snapshot = emission.snapshot ?: return@collectLatest
                    if (!canPublishLiveSnapshot(emission.profileId)) {
                        Log.d(
                            "ContinueWatching",
                            "Skipping live continue watching snapshot before profile=${emission.profileId} remote reset"
                        )
                        return@collectLatest
                    }
                    updateSnapshot(
                        snapshot = snapshot,
                        profileId = emission.profileId,
                        resultSession = activeProfileSession()
                    )
                }
        }
    }

    suspend fun reloadPersistedSnapshotForActiveProfile(clearWhenMissing: Boolean = true) {
        loadPersistedSnapshotForActiveProfile(clearWhenMissing = clearWhenMissing)
    }

    fun rescheduleAirTimeAlarmFromSnapshot() {
        handleScheduledReemit(rawSnapshotState.value.snapshot.scheduledReemit, System.currentTimeMillis())
    }

    private suspend fun loadPersistedSnapshotForActiveProfile(clearWhenMissing: Boolean) {
        try {
            val profileId = activeProfileId()
            val persisted = snapshotStore.read(profileId)
            if (persisted == null) {
                if (clearWhenMissing) {
                    rawSnapshotState.value = ProfileOwnedContinueWatchingSnapshot(profileId = profileId)
                    snapshotState.value = ProfileOwnedContinueWatchingSnapshot(profileId = profileId)
                    lastRefreshRequestMs = 0L
                    cancelReemitScheduling()
                }
                return
            }
            val normalized = upgradeStaleRouteSnapshots(sanitizeSnapshot(persisted))
            if (normalized.metadataSnapshotsByItemKey != persisted.metadataSnapshotsByItemKey) {
                snapshotStore.write(normalized, profileId = profileId)
                emitWrite(
                    profileId = profileId,
                    recordCount = normalized.resumeItems.size + normalized.nextUpItems.size + normalized.traktUpNextItems.size
                )
            }
            val owned = ProfileOwnedContinueWatchingSnapshot(profileId = profileId, snapshot = normalized)
            rawSnapshotState.value = owned
            snapshotState.value = owned
            lastRefreshRequestMs = 0L
            handleScheduledReemit(normalized.scheduledReemit, System.currentTimeMillis())
        } finally {
            persistedSnapshotReady.value = true
        }
    }

    fun observeSnapshot(): Flow<ProfileOwnedContinueWatchingSnapshot> {
        return combine(snapshotState, persistedSnapshotReady) { snapshot, ready ->
            snapshot.takeIf { ready }
        }.filterNotNull().onStart {
            val current = snapshotState.value
            emitRead(
                profileId = current.profileId.takeIf { it > 0 } ?: activeProfileId(),
                recordCount = current.snapshot.resumeItems.size + current.snapshot.nextUpItems.size + current.snapshot.traktUpNextItems.size
            )
            scope.launch {
                runCatching { ensureFresh(force = false) }
                    .onFailure { error ->
                        Log.w("ContinueWatching", "Failed to refresh continue watching snapshot", error)
                    }
            }
        }
    }

    /**
     * F-G-01 path B: typed profile-scoped snapshot flow. Returns the full ContinueWatchingSnapshot
     * (preserving snapshot-shape consumers like displayMetadataByItemKey) but filtered to the
     * requested profile. Replaces the manual `.filter { it.profileId == profileId }` pattern that
     * cluster D Task 4/5 used as a workaround.
     */
    fun observeProfileSnapshot(profileId: Int): Flow<ContinueWatchingSnapshot> {
        require(profileId > 0) { "observeProfileSnapshot.profileId must be positive, got $profileId" }
        return observeSnapshot()
            .filter { it.profileId == profileId }
            .map { it.snapshot }
            .onStart { emit(ContinueWatchingSnapshot()) }
    }

    fun observeContinueWatching(profileId: Int): Flow<List<ContinueWatchingRecord>> {
        require(profileId > 0) { "profileId must be positive" }
        return observeSnapshot().map { owned ->
            if (owned.profileId == profileId) owned.toContinueWatchingRecords() else emptyList()
        }
    }

    suspend fun ensureFresh(force: Boolean) = withContext(Dispatchers.IO) {
        val now = System.currentTimeMillis()
        if (!force && now - lastRefreshRequestMs < minRefreshIntervalMs && snapshotState.value.snapshot.updatedAtMs > 0L) {
            return@withContext
        }

        refreshMutex.withLock {
            val lockedNow = System.currentTimeMillis()
            if (!force &&
                lockedNow - lastRefreshRequestMs < minRefreshIntervalMs &&
                snapshotState.value.snapshot.updatedAtMs > 0L
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
    fun currentRawResumeItems(): List<WatchProgress> = rawSnapshotState.value.snapshot.resumeItems

    suspend fun recordMetadataSnapshot(
        itemKey: String,
        metadataSnapshot: ContinueWatchingMetadataSnapshot
    ) {
        if (itemKey.isBlank()) return
        refreshMutex.withLock {
            val current = rawSnapshotState.value
            val updated = current.snapshot.copy(
                metadataSnapshotsByItemKey = current.snapshot.metadataSnapshotsByItemKey + (itemKey to metadataSnapshot),
                updatedAtMs = System.currentTimeMillis()
            )
            updateSnapshot(
                snapshot = updated,
                profileId = current.profileId,
                resultSession = sessionForProfile(current.profileId)
            )
        }
    }

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
                    snapshot = current.snapshot.copy(
                        resumeItems = current.snapshot.resumeItems.filterNot { it.videoId == videoId }
                    )
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
                    snapshot = current.snapshot.copy(
                        resumeItems = current.snapshot.resumeItems.filterNot { it.contentId == showId }
                    )
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
                val merged = (current.snapshot.resumeItems + entry)
                    .sortedByDescending { it.lastWatched }
                    .distinctBy { it.videoId }
                current.copy(snapshot = current.snapshot.copy(resumeItems = merged))
            }
        }
    }

    /**
     * Returns the current snapshot data from the raw state for use as a rollback baseline.
     * Must be called before [applyEpisodesMarked] to capture the pre-mutation state.
     */
    fun snapshotForRollback(): EpisodeRollbackState = rawSnapshotState.value.snapshot.let { snapshot ->
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
        return rawSnapshotState.value.snapshot.let { snapshot ->
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
                    snapshot = current.snapshot.copy(
                        resumeItems = current.snapshot.resumeItems.filterNot { progress ->
                            episodes.any { ref ->
                                progress.contentId == ref.showId &&
                                    progress.season == ref.seasonNumber &&
                                    progress.episode == ref.episodeNumber
                            }
                        },
                        nextUpItems = current.snapshot.nextUpItems.filterNot { entry ->
                            episodes.any { ref ->
                                entry.contentId == ref.showId &&
                                    entry.season == ref.seasonNumber &&
                                    entry.episode == ref.episodeNumber
                            }
                        },
                        traktUpNextItems = current.snapshot.traktUpNextItems.filterNot { entry ->
                            episodes.any { ref ->
                                entry.contentId == ref.showId &&
                                    entry.season == ref.seasonNumber &&
                                    entry.episode == ref.episodeNumber
                            }
                        }
                    )
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
                val currentSnapshot = current.snapshot
                val rollbackResume = currentSnapshot.resumeItems
                    .associateByTo(LinkedHashMap<String, WatchProgress>()) { it.videoId }
                    .also { existing ->
                        state.resumeItems.forEach { entry -> existing.putIfAbsent(entry.videoId, entry) }
                    }
                    .values
                    .toList()
                    .sortedByDescending { it.lastWatched }

                val rollbackNextUp = (currentSnapshot.nextUpItems + state.nextUpItems)
                    .distinctBy {
                        "${it.contentId}|${it.season}|${it.episode}"
                    }
                    .sortedByDescending { it.activityAtMs }

                val rollbackTraktUpNext = (currentSnapshot.traktUpNextItems + state.traktUpNextItems)
                    .distinctBy {
                        "${it.contentId}|${it.season}|${it.episode}"
                    }
                    .sortedByDescending { it.activityAtMs }

                current.copy(
                    snapshot = currentSnapshot.copy(
                        resumeItems = rollbackResume,
                        nextUpItems = rollbackNextUp,
                        traktUpNextItems = rollbackTraktUpNext
                    )
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
                    snapshot = current.snapshot.copy(
                        nextUpItems = current.snapshot.nextUpItems.filterNot { it.contentId == target },
                        traktUpNextItems = current.snapshot.traktUpNextItems.filterNot { it.contentId == target }
                    )
                )
            }
        }
    }

    fun invalidateLocalizedMetadata() {
        trackingProgressService.invalidateLocalizedMetadata()
        val profileId = activeProfileId()
        snapshotStore.clear(profileId)
        scope.launch { ownershipService?.removeRail(RailKeyFactory.continueWatching(profileId)) }
        cancelReemitScheduling()
        scope.launch {
            rawSnapshotState.update { owned ->
                owned.copy(snapshot = owned.snapshot.copy(displayMetadataByItemKey = emptyMap()))
            }
            snapshotState.value = rawSnapshotState.value
        }
    }

    private fun buildRawSnapshot(
        allProgress: List<WatchProgress>,
        nextUpEntries: List<TrackingNextUpEntry>,
        traktUpNextEntries: List<TrackingNextUpEntry>
    ): ContinueWatchingSnapshot {
        val nowMs = System.currentTimeMillis()
        val completionAnchors = completionAnchorsByContent(allProgress)
        val resumeItems = selectResumeItemsForContinueWatching(allProgress)
        val normalizedNextUpItems = nextUpEntries
            .asSequence()
            .mapNotNull(::normalizeNextUpEntry)
            .filterNot { entry ->
                isNextUpSuppressedByCompletionAnchor(entry, completionAnchors[entry.contentId])
            }
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
            .filterNot { entry ->
                isNextUpSuppressedByCompletionAnchor(entry, completionAnchors[entry.contentId])
            }
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

    private data class ContinueWatchingCompletionAnchor(
        val season: Int?,
        val episode: Int?,
        val lastWatched: Long
    )

    private fun selectResumeItemsForContinueWatching(allProgress: List<WatchProgress>): List<WatchProgress> {
        val completionAnchors = completionAnchorsByContent(allProgress)
        return allProgress
            .asSequence()
            .filter(::shouldTreatAsResumeForContinueWatching)
            .mapNotNull(::normalizeResumeItem)
            .filterNot { progress -> isSuppressedByCompletionAnchor(progress, completionAnchors[progress.contentId]) }
            .sortedByDescending { it.lastWatched }
            .distinctBy { it.contentId }
            .toList()
    }

    private fun completionAnchorsByContent(
        allProgress: List<WatchProgress>
    ): Map<String, ContinueWatchingCompletionAnchor> {
        val anchors = linkedMapOf<String, ContinueWatchingCompletionAnchor>()
        allProgress.forEach { progress ->
            val contentId = progress.contentId.trim()
            if (contentId.isBlank()) return@forEach
            if (!progress.isCompleted()) return@forEach
            val anchor = ContinueWatchingCompletionAnchor(
                season = progress.season,
                episode = progress.episode,
                lastWatched = progress.lastWatched
            )
            val existing = anchors[contentId]
            if (existing == null || shouldPreferCompletionAnchor(existing, anchor)) {
                anchors[contentId] = anchor
            }
        }
        return anchors
    }

    private fun shouldPreferCompletionAnchor(
        existing: ContinueWatchingCompletionAnchor,
        candidate: ContinueWatchingCompletionAnchor
    ): Boolean {
        if (candidate.lastWatched != existing.lastWatched) {
            return candidate.lastWatched > existing.lastWatched
        }
        val existingSeason = existing.season ?: -1
        val candidateSeason = candidate.season ?: -1
        if (candidateSeason != existingSeason) return candidateSeason > existingSeason
        val existingEpisode = existing.episode ?: -1
        val candidateEpisode = candidate.episode ?: -1
        return candidateEpisode > existingEpisode
    }

    private fun isSuppressedByCompletionAnchor(
        progress: WatchProgress,
        anchor: ContinueWatchingCompletionAnchor?
    ): Boolean {
        if (anchor == null) return false
        if (progress.lastWatched <= anchor.lastWatched) return true

        val progressSeason = progress.season ?: return false
        val progressEpisode = progress.episode ?: return false
        val anchorSeason = anchor.season ?: return false
        val anchorEpisode = anchor.episode ?: return false

        return progressSeason < anchorSeason ||
            (progressSeason == anchorSeason && progressEpisode <= anchorEpisode)
    }

    private fun isNextUpSuppressedByCompletionAnchor(
        entry: TrackingNextUpEntry,
        anchor: ContinueWatchingCompletionAnchor?
    ): Boolean {
        if (anchor == null) return false
        val anchorSeason = anchor.season ?: return false
        val anchorEpisode = anchor.episode ?: return false

        return entry.season < anchorSeason ||
            (entry.season == anchorSeason && entry.episode <= anchorEpisode)
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
            metadataSnapshotsByItemKey = snapshot.metadataSnapshotsByItemKey.filterKeys { it in activeItemKeys },
            updatedAtMs = updatedAtMs,
            scheduledReemit = snapshot.scheduledReemit
        )
    }

    internal suspend fun upgradeStaleRouteSnapshots(snapshot: ContinueWatchingSnapshot): ContinueWatchingSnapshot {
        val facade = metadataRouterFacade ?: return snapshot
        val contentTypesByItemKey = contentTypesByItemKey(snapshot)
        val upgradedSnapshots = snapshot.metadataSnapshotsByItemKey.mapValues { (itemKey, metadataSnapshot) ->
            if (!ContinueWatchingMetadataSnapshot.shouldReroute(metadataSnapshot.routingVersion)) {
                return@mapValues metadataSnapshot
            }
            val itemType = contentTypesByItemKey[itemKey].orEmpty()
            runCatching {
                val route = facade.routeRequest(
                    MetadataRequest(
                        contentId = metadataSnapshot.parentId,
                        contentType = ContentType.fromString(itemType),
                        sourceContext = MetadataSourceContext(
                            itemType = itemType,
                            addonMetadata = metadataSnapshot.clickTimeDisplayMetadata
                        ),
                        depth = MetadataDepth.DETAIL_CORE
                    )
                )
                ContinueWatchingMetadataSnapshot.fromRoute(
                    route = route,
                    clickTimeDisplayMetadata = metadataSnapshot.clickTimeDisplayMetadata
                )
            }.getOrElse {
                metadataSnapshot
            }
        }

        return if (upgradedSnapshots == snapshot.metadataSnapshotsByItemKey) {
            snapshot
        } else {
            snapshot.copy(metadataSnapshotsByItemKey = upgradedSnapshots)
        }
    }

    private fun contentTypesByItemKey(snapshot: ContinueWatchingSnapshot): Map<String, String> {
        val itemTypes = linkedMapOf<String, String>()
        snapshot.resumeItems.forEach { progress ->
            itemTypes[homeDisplayItemKey(progress.contentType, progress.contentId)] = progress.contentType
        }
        snapshot.nextUpItems.forEach { entry ->
            itemTypes[homeDisplayItemKey(entry.contentType, entry.contentId)] = entry.contentType
        }
        snapshot.traktUpNextItems.forEach { entry ->
            itemTypes[homeDisplayItemKey(entry.contentType, entry.contentId)] = entry.contentType
        }
        return itemTypes
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

    private suspend fun persistRawSnapshot(
        snapshot: ContinueWatchingSnapshot,
        profileId: Int = activeProfileId(),
        resultSession: ActiveProfileSession = sessionForProfile(profileId)
    ): Boolean {
        val normalized = sanitizeSnapshot(snapshot)
        val hydrated = hydrateSnapshotMetadata(
            snapshot = normalized,
            fallbackMetadata = rawSnapshotState.value.snapshot.displayMetadataByItemKey
        )
        if (!canPublishProfileWrite(resultSession)) {
            return false
        }
        syncContinueWatchingRail(hydrated, profileId)
        snapshotStore.write(hydrated, profileId = profileId)
        emitWrite(
            profileId = profileId,
            recordCount = hydrated.resumeItems.size + hydrated.nextUpItems.size + hydrated.traktUpNextItems.size
        )
        val owned = ProfileOwnedContinueWatchingSnapshot(profileId = profileId, snapshot = hydrated)
        rawSnapshotState.value = owned
        activeRailTracker.markActive(RailKeyFactory.continueWatching(profileId))
        lastRefreshRequestMs = hydrated.updatedAtMs
        return true
    }

    private suspend fun syncContinueWatchingRail(
        snapshot: ContinueWatchingSnapshot,
        profileId: Int
    ) {
        val ownership = ownershipService ?: return
        val now = System.currentTimeMillis()
        val railKey = RailKeyFactory.continueWatching(profileId)
        val resolvedItems = linkedMapOf<String, com.nexio.tv.core.integration.ResolvedRailMediaIdentity>()
        snapshot.resumeItems.forEach { progress ->
            val display = snapshot.displayMetadataByItemKey[
                homeDisplayItemKey(progress.contentType, progress.contentId)
            ]
            val resolved = identityResolver.fromWatchProgress(
                progress = progress,
                title = display?.title ?: progress.name,
                year = parseYear(display?.releaseInfo),
                updatedAtEpochMs = now
            )
            resolvedItems.putIfAbsent(resolved.mediaIdentity.mediaKey, resolved)
        }
        (snapshot.nextUpItems + snapshot.traktUpNextItems).forEach { entry ->
            val display = snapshot.displayMetadataByItemKey[
                homeDisplayItemKey(entry.contentType, entry.contentId)
            ]
            val resolved = identityResolver.fromRawContent(
                mediaType = entry.contentType,
                rawId = entry.contentId,
                title = display?.title ?: entry.name,
                year = parseYear(display?.releaseInfo),
                traktId = entry.traktShowId?.toString(),
                updatedAtEpochMs = now
            )
            resolvedItems.putIfAbsent(resolved.mediaIdentity.mediaKey, resolved)
        }
        ownership.upsertRailMembership(
            RailMembership(
                rail = RailCacheEntity(
                    railKey = railKey,
                    provider = "LOCAL",
                    kind = "CONTINUE_WATCHING",
                    paramsHash = "profile:$profileId",
                    fetchedAtEpochMs = now,
                    expiresAtEpochMs = now + minRefreshIntervalMs,
                    staleUntilEpochMs = now + REFRESH_FAILURE_RETRY_MS
                ),
                items = resolvedItems.values.mapIndexed { index, resolved ->
                    RailItemEntity(
                        key = "$railKey#${resolved.mediaIdentity.mediaKey}",
                        railKey = railKey,
                        mediaKey = resolved.mediaIdentity.mediaKey,
                        position = index,
                        updatedAtEpochMs = now
                    )
                },
                mediaIdentities = resolvedItems.values.map { it.mediaIdentity },
                externalIds = resolvedItems.values.flatMap { it.externalIds }
            )
        )
    }

    private suspend fun updateSnapshot(
        snapshot: ContinueWatchingSnapshot,
        profileId: Int = activeProfileId(),
        resultSession: ActiveProfileSession = sessionForProfile(profileId)
    ) {
        val published = persistRawSnapshot(
            snapshot = snapshot,
            profileId = profileId,
            resultSession = resultSession
        )
        if (published) {
            scheduleReemitIfNeeded(snapshot.scheduledReemit, snapshot.updatedAtMs)
        }
    }

    private fun activeProfileId(): Int = profileManager?.activeProfileId?.value ?: 1

    private fun activeProfileSession(): ActiveProfileSession =
        runCatching { profileManager?.activeProfileSession?.value }.getOrNull()
            ?: ActiveProfileSession(
                profileId = activeProfileId(),
                sessionId = "legacy-profile:${activeProfileId()}",
                sessionOrdinal = 1L,
                startedAtMs = 1L
            )

    private fun sessionForProfile(profileId: Int): ActiveProfileSession {
        val active = activeProfileSession()
        return if (active.profileId == profileId) {
            active
        } else {
            ActiveProfileSession(
                profileId = profileId,
                sessionId = "detached-profile:$profileId:${System.nanoTime()}",
                sessionOrdinal = active.sessionOrdinal,
                startedAtMs = System.currentTimeMillis().coerceAtLeast(1L)
            )
        }
    }

    private fun canPublishProfileWrite(resultSession: ActiveProfileSession): Boolean {
        return try {
            ProfileBoundaryEnforcer.assertCanWriteProfileState(
                resultSession = resultSession,
                activeSession = activeProfileSession()
            )
            true
        } catch (exception: ProfileBoundaryException) {
            Log.d("ContinueWatching", "Skipping stale continue watching publish: ${exception.message}")
            false
        }
    }

    private fun parseYear(value: String?): Int? =
        Regex("(\\d{4})").find(value.orEmpty())?.groupValues?.getOrNull(1)?.toIntOrNull()

    private fun activeProfileIdFlow(): Flow<Int> = profileManager?.activeProfileId ?: flowOf(1)

    private fun markProfileAwaitingLiveReset(profileId: Int) {
        synchronized(liveProfileGateLock) {
            if (profileId !in liveProfilesReady) {
                profilesAwaitingRemoteReset += profileId
                profilesThatObservedRemoteReset -= profileId
            }
        }
    }

    private fun noteRemoteSnapshotState(profileId: Int, hasLoadedRemoteSnapshot: Boolean) {
        synchronized(liveProfileGateLock) {
            if (!hasLoadedRemoteSnapshot && profileId in profilesAwaitingRemoteReset) {
                profilesThatObservedRemoteReset += profileId
            }
        }
    }

    private fun canPublishLiveSnapshot(profileId: Int): Boolean {
        synchronized(liveProfileGateLock) {
            if (profileId !in profilesAwaitingRemoteReset) {
                liveProfilesReady += profileId
                return true
            }
            if (profileId !in profilesThatObservedRemoteReset) {
                return false
            }
            profilesAwaitingRemoteReset -= profileId
            profilesThatObservedRemoteReset -= profileId
            liveProfilesReady += profileId
            return true
        }
    }

    private fun handleScheduledReemit(
        scheduledReemit: List<TrackingNextUpEntry>,
        nowMs: Long
    ) {
        if (AirDateGate.hasDuePending(
                entries = scheduledReemit,
                firstAiredMsSelector = { it.firstAiredMs },
                availabilityInstantMsSelector = { it.tvdbAvailabilityInstantMs },
                tmdbAirDateSelector = { it.firstAired },
                nowMs = nowMs
            )
        ) {
            reemitJob?.cancel()
            reemitJob = null
            currentTimerTargetMs = null
            airScheduler.cancel()
            launchAirTimeRefreshWithRetry()
            return
        }

        scheduleReemitIfNeeded(scheduledReemit, nowMs)
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
        if (soonestMs == null) {
            reemitJob = null
            currentTimerTargetMs = null
            airScheduler.cancel()
            return
        }
        currentTimerTargetMs = soonestMs
        airScheduler.scheduleSoonest(soonestMs)
        val delayMs = (soonestMs - nowMs).coerceAtLeast(0L)
        reemitJob = scope.launch {
            delay(delayMs)
            launchAirTimeRefreshWithRetry()
        }
    }

    private fun launchAirTimeRefreshWithRetry() {
        scope.launch {
            runCatching { ensureFresh(force = true) }
                .onFailure { error ->
                    Log.w(
                        "ContinueWatching",
                        "exact_air_time_diagnostic reason=refresh_failure retryMs=900000",
                        error
                    )
                    currentTimerTargetMs = null
                    airScheduler.scheduleSoonest(System.currentTimeMillis() + REFRESH_FAILURE_RETRY_MS)
                }
        }
    }

    private fun cancelReemitScheduling() {
        reemitJob?.cancel()
        reemitJob = null
        currentTimerTargetMs = null
        airScheduler.cancel()
    }

    private suspend fun hydrateSnapshotMetadata(
        snapshot: ContinueWatchingSnapshot,
        fallbackMetadata: Map<String, HomeDisplayMetadata>
    ): ContinueWatchingSnapshot {
        val routeUpgradedSnapshot = upgradeStaleRouteSnapshots(snapshot)
        val itemKeys = linkedMapOf<String, Pair<String, String>>()
        routeUpgradedSnapshot.resumeItems.forEach { progress ->
            itemKeys[homeDisplayItemKey(progress.contentType, progress.contentId)] =
                progress.contentType to progress.contentId
        }
        routeUpgradedSnapshot.nextUpItems.forEach { entry ->
            itemKeys[homeDisplayItemKey(entry.contentType, entry.contentId)] =
                entry.contentType to entry.contentId
        }
        routeUpgradedSnapshot.traktUpNextItems.forEach { entry ->
            itemKeys[homeDisplayItemKey(entry.contentType, entry.contentId)] =
                entry.contentType to entry.contentId
        }
        if (itemKeys.isEmpty()) {
            return routeUpgradedSnapshot.copy(displayMetadataByItemKey = emptyMap())
        }

        val hydratedMetadata = linkedMapOf<String, HomeDisplayMetadata>()
        itemKeys.forEach { (itemKey, typeAndId) ->
            val (contentType, contentId) = typeAndId
            val fetched = fetchHomeDisplayMetadata(
                contentType = contentType,
                contentId = contentId,
                snapshot = routeUpgradedSnapshot
            )
            val merged = ContinueWatchingMetadataSnapshot.renderDisplayMetadata(
                canonical = fetched,
                clickTime = routeUpgradedSnapshot.metadataSnapshotsByItemKey[itemKey]?.clickTimeDisplayMetadata,
                persistedFallback = fallbackMetadata[itemKey]
            )
            if (merged.hasRenderableDisplayMetadata()) {
                hydratedMetadata[itemKey] = merged
            }
        }

        return routeUpgradedSnapshot.copy(displayMetadataByItemKey = hydratedMetadata)
    }

    internal suspend fun fetchHomeDisplayMetadata(
        contentType: String,
        contentId: String,
        snapshot: ContinueWatchingSnapshot
    ): HomeDisplayMetadata? {
        val itemKey = homeDisplayItemKey(contentType, contentId)
        val routedSnapshot = snapshot.metadataSnapshotsByItemKey[itemKey]
        val request = MetadataRequest(
            contentId = contentId,
            contentType = ContentType.fromString(contentType),
            sourceContext = MetadataSourceContext(
                itemType = contentType,
                addonMetadata = routedSnapshot?.clickTimeDisplayMetadata
            ),
            depth = MetadataDepth.DETAIL_CORE
        )
        val canonical = try {
            metadataRouterFacade?.resolveRequest(request) ?: return null
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w("ContinueWatching", "fetchHomeDisplayMetadata resolveRequest failed for $contentId: ${e.message}", e)
            return null
        }
        return canonical.displayMetadata.takeIf { canonical.route != null }
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

    companion object {
        @Volatile
        private var traceSink: com.nexio.tv.core.trace.RuntimeTraceSink =
            com.nexio.tv.core.trace.NoopRuntimeTraceSink

        @Volatile
        private var traceSessionId: () -> String? = { null }

        private val traceSeq = java.util.concurrent.atomic.AtomicLong(0L)

        @JvmStatic
        fun installTraceSink(
            sink: com.nexio.tv.core.trace.RuntimeTraceSink,
            sessionId: () -> String?
        ) {
            this.traceSink = sink
            this.traceSessionId = sessionId
        }

        internal fun emitWrite(profileId: Int, recordCount: Int) {
            if (traceSink === com.nexio.tv.core.trace.NoopRuntimeTraceSink) return
            val sid = traceSessionId() ?: return
            val profileHash = com.nexio.tv.core.trace.TraceHash.of(sid, profileId.toString())
            traceSink.emit(
                com.nexio.tv.core.trace.TraceEventEnvelope(
                    traceSessionId = sid,
                    sequence = traceSeq.incrementAndGet(),
                    wallClockMs = System.currentTimeMillis(),
                    elapsedRealtimeMs = System.nanoTime() / 1_000_000,
                    threadName = Thread.currentThread().name,
                    eventType = "continue_watching.snapshot_write",
                    payload = mapOf(
                        "profileHash" to profileHash,
                        "profileId" to profileId,
                        "recordCount" to recordCount,
                        "source" to "LOCAL_PERSIST"
                    )
                )
            )
        }

        internal fun emitRead(profileId: Int, recordCount: Int) {
            if (traceSink === com.nexio.tv.core.trace.NoopRuntimeTraceSink) return
            val sid = traceSessionId() ?: return
            val profileHash = com.nexio.tv.core.trace.TraceHash.of(sid, profileId.toString())
            traceSink.emit(
                com.nexio.tv.core.trace.TraceEventEnvelope(
                    traceSessionId = sid,
                    sequence = traceSeq.incrementAndGet(),
                    wallClockMs = System.currentTimeMillis(),
                    elapsedRealtimeMs = System.nanoTime() / 1_000_000,
                    threadName = Thread.currentThread().name,
                    eventType = "continue_watching.snapshot_read",
                    payload = mapOf(
                        "profileHash" to profileHash,
                        "profileId" to profileId,
                        "recordCount" to recordCount,
                        "source" to "OBSERVE_SUBSCRIBE"
                    )
                )
            )
        }
    }
}

private fun HomeDisplayMetadata.hasRenderableDisplayMetadata(): Boolean {
    return title != null ||
        logo != null ||
        description != null ||
        genres.isNotEmpty() ||
        releaseInfo != null ||
        runtime != null ||
        imdbRating != null ||
        tomatoesRating != null ||
        poster != null ||
        posterProviderTag != null ||
        backdrop != null
}
