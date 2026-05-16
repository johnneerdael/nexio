package com.nexio.tv.data.repository

import android.content.Context
import android.util.Log
import androidx.annotation.VisibleForTesting
import com.nexio.tv.core.integration.ActiveRailTracker
import com.nexio.tv.core.integration.ActiveProfileSession
import com.nexio.tv.core.integration.IntegrationOwnershipService
import com.nexio.tv.core.integration.ProfileBoundaryEnforcer
import com.nexio.tv.core.integration.ProfileBoundaryException
import com.nexio.tv.core.integration.RailKeyFactory
import com.nexio.tv.core.integration.RailMediaIdentityResolver
import com.nexio.tv.core.integration.RailMembership
import com.nexio.tv.core.locale.AppLocaleResolver
import com.nexio.tv.core.metadata.router.MetadataDepth
import com.nexio.tv.core.metadata.router.MetadataDecisionReason
import com.nexio.tv.core.metadata.router.MetadataRequest
import com.nexio.tv.core.metadata.router.MetadataRouterFacade
import com.nexio.tv.core.metadata.router.MetadataMediaKind
import com.nexio.tv.core.metadata.router.MetadataPrimaryProvider
import com.nexio.tv.core.metadata.router.MetadataSourceContext
import com.nexio.tv.core.artwork.toLegacyArtworkString
import com.nexio.tv.core.tvdb.TvEpisodeMetadata
import com.nexio.tv.core.tvdb.TvMetadataRequest
import com.nexio.tv.core.tvdb.TvdbAirAvailabilityCalculator
import com.nexio.tv.data.local.integration.MediaIdentityEntity
import com.nexio.tv.data.local.integration.RailCacheEntity
import com.nexio.tv.data.local.integration.RailItemEntity
import com.nexio.tv.core.profile.ProfileManager
import kotlinx.coroutines.CancellationException
import com.nexio.tv.core.scheduler.ContinueWatchingAirScheduler
import com.nexio.tv.data.local.MetadataDiskCacheStore
import com.nexio.tv.data.local.ContinueWatchingSnapshotStore
import com.nexio.tv.data.local.TraktSettingsDataStore
import com.nexio.tv.domain.model.ContentIdentity
import com.nexio.tv.domain.model.ContentType
import com.nexio.tv.domain.model.HomeDisplayMetadata
import com.nexio.tv.domain.model.DisplaySourceRank
import com.nexio.tv.domain.model.ProviderId
import com.nexio.tv.domain.model.ProviderIds
import com.nexio.tv.domain.model.ResolvedDisplayItem
import com.nexio.tv.domain.model.homeDisplayItemKey
import com.nexio.tv.domain.model.TrackingProvider
import com.nexio.tv.domain.model.WatchProgress
import com.nexio.tv.ui.screens.home.toHomeDisplayMetadata
import com.nexio.tv.ui.screens.home.toResolvedFieldSlots
import com.nexio.tv.domain.repository.WatchProgressRepository
import dagger.hilt.android.qualifiers.ApplicationContext
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
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton

private const val REFRESH_FAILURE_RETRY_MS = 15 * 60_000L
private val NEXT_UP_SERIES_TITLE_TOKEN = Regex("[^a-z0-9]+")
private val NEXT_UP_SERIES_WHITESPACE = Regex("\\s+")

private object NoopContinueWatchingAirScheduler : ContinueWatchingAirScheduler {
    override fun scheduleSoonest(triggerAtMs: Long?) = Unit
    override fun cancel() = Unit
}

data class ContinueWatchingSnapshot(
    val resumeItems: List<WatchProgress> = emptyList(),
    val nextUpItems: List<TrackingNextUpEntry> = emptyList(),
    val traktUpNextItems: List<TrackingNextUpEntry> = emptyList(),
    val records: List<ContinueWatchingRecord> = emptyList(),
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
    val legacyResumeRecords = snapshot.resumeItems.map { progress ->
        val parentId = progress.contentId
        val season = progress.season
        val episode = progress.episode
        val episodeContext = if (season != null && episode != null) {
            ContinueWatchingRecord.EpisodeContext(
                season = season,
                number = episode
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
    val syntheticNextUpRecords = snapshot.nextUpItems.map { entry ->
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
    if (snapshot.records.isEmpty()) {
        return legacyResumeRecords + syntheticNextUpRecords
    }

    val canonicalKeys = snapshot.records.map { it.identityKey() }.toSet()
    val contentIds = snapshot.records.map { it.contentId }.toSet()
    val missingSyntheticNextUpRecords = syntheticNextUpRecords.filterNot { record ->
        record.identityKey() in canonicalKeys || record.contentId in contentIds
    }
    return snapshot.records + missingSyntheticNextUpRecords
}

internal fun deriveLocalNextUpEntry(
    seed: WatchProgress,
    episodeMap: Map<Pair<Int, Int>, TvEpisodeMetadata>
): TrackingNextUpEntry? {
    val seedSeason = seed.season ?: return null
    val seedEpisode = seed.episode ?: return null
    val nextEpisode = episodeMap.entries
        .asSequence()
        .mapNotNull { (key, metadata) ->
            val season = metadata.seasonNumber ?: key.first
            val episode = metadata.episodeNumber ?: key.second
            if (season <= 0 || episode <= 0) return@mapNotNull null
            (season to episode) to metadata
        }
        .sortedWith(
            compareBy<Pair<Pair<Int, Int>, TvEpisodeMetadata>> { it.first.first }
                .thenBy { it.first.second }
        )
        .dropWhile { (episodeKey, _) ->
            episodeKey.first < seedSeason || (episodeKey.first == seedSeason && episodeKey.second <= seedEpisode)
        }
        .firstOrNull()
        ?: return null
    val season = nextEpisode.first.first
    val episode = nextEpisode.first.second
    val metadata = nextEpisode.second
    val contentId = seed.contentId.trim().takeIf { it.isNotBlank() } ?: return null
    val firstAired = metadata.airDate
    val firstAiredMs = firstAired
        ?.takeIf { it.isNotBlank() }
        ?.let { raw ->
            AirDateGate.pendingTriggerMs(
                firstAiredMs = 0L,
                availabilityInstantMs = null,
                tmdbAirDate = raw
            )
        }
        ?: 0L

    return TrackingNextUpEntry(
        contentId = contentId,
        contentType = seed.contentType.takeIf { it.isNotBlank() } ?: "series",
        name = seed.name.takeIf { it.isNotBlank() } ?: contentId,
        season = season,
        episode = episode,
        episodeTitle = metadata.title,
        videoId = "$contentId:$season:$episode",
        firstAired = firstAired,
        firstAiredMs = firstAiredMs,
        activityAtMs = seed.lastWatched,
        poster = seed.poster,
        backdrop = seed.backdrop,
        logo = seed.logo,
        traktShowId = seed.traktShowId,
        traktEpisodeId = null
    )
}

private fun ContinueWatchingSnapshot.withInvalidatedCanonicalRecords(): ContinueWatchingSnapshot {
    return if (records.isEmpty()) this else copy(records = emptyList())
}

private data class CanonicalRecordRemovalRef(
    val contentId: String,
    val videoId: String? = null,
    val season: Int? = null,
    val episode: Int? = null
)

private fun ContinueWatchingSnapshot.withCanonicalRecordsRemovedFor(
    refs: List<CanonicalRecordRemovalRef>
): ContinueWatchingSnapshot {
    if (records.isEmpty() || refs.isEmpty()) return this
    val normalizedRefs = refs
        .mapNotNull { ref ->
            val contentId = ref.contentId.trim()
            if (contentId.isBlank()) return@mapNotNull null
            ref.copy(
                contentId = contentId,
                videoId = ref.videoId?.trim()?.takeIf { it.isNotBlank() }
            )
        }
    if (normalizedRefs.isEmpty()) return this

    return copy(
        records = records.filterNot { record ->
            normalizedRefs.any { ref -> record.matchesRemovalRef(ref) }
        }
    )
}

private fun ContinueWatchingRecord.matchesRemovalRef(ref: CanonicalRecordRemovalRef): Boolean {
    if (resumeIdentities.any { identity -> identity.matchesRemovalRef(ref) }) return true
    if (ref.videoId != null && contentId == ref.videoId) return true
    if (parentId != ref.contentId) return false
    if (ref.season == null && ref.episode == null) return true
    return episodeContext?.let { context ->
        context.season == ref.season && context.number == ref.episode
    } == true
}

private fun ResumeIdentity.matchesRemovalRef(ref: CanonicalRecordRemovalRef): Boolean {
    if (ref.videoId != null && videoId == ref.videoId) return true
    if (contentId != ref.contentId) return false
    if (ref.season == null && ref.episode == null) return true
    return season == ref.season && episode == ref.episode
}

private fun WatchProgress.toCanonicalRecordRemovalRef(): CanonicalRecordRemovalRef =
    CanonicalRecordRemovalRef(
        contentId = contentId,
        videoId = videoId,
        season = season,
        episode = episode
    )

private data class LiveContinueWatchingSnapshotEmission(
    val profileId: Int,
    val hasLoadedRemoteSnapshot: Boolean,
    val snapshot: ContinueWatchingSnapshot?,
    val retainMissingRows: Boolean = false,
    val completedProgress: List<WatchProgress> = emptyList()
)

private class TvdbEpisodeProjectionCache {
    private val episodeMaps = mutableMapOf<String, Map<Pair<Int, Int>, TvEpisodeMetadata>>()

    suspend fun getOrFetch(
        contentType: String,
        contentId: String,
        fallbackContentId: String,
        fetch: suspend () -> Map<Pair<Int, Int>, TvEpisodeMetadata>
    ): Map<Pair<Int, Int>, TvEpisodeMetadata> {
        val key = keyFor(contentType, contentId, fallbackContentId)
        if (episodeMaps.containsKey(key)) return episodeMaps.getValue(key)
        return fetch().also { episodeMaps[key] = it }
    }

    private fun keyFor(
        contentType: String,
        contentId: String,
        fallbackContentId: String
    ): String {
        val normalizedType = contentType.trim().lowercase().ifBlank { "series" }
        val normalizedContentId = contentId.trim().ifBlank {
            fallbackContentId
                .trim()
                .substringBeforeLast(":", fallbackContentId.trim())
        }
        return "$normalizedType|$normalizedContentId"
    }
}

@Singleton
@OptIn(ExperimentalCoroutinesApi::class)
class ContinueWatchingSnapshotService @Inject constructor(
    private val watchProgressRepository: WatchProgressRepository,
    private val trackingProgressService: TrackingProgressService,
    private val trackingProviderStateService: TrackingProviderStateService,
    private val traktSettingsDataStore: TraktSettingsDataStore,
    private val metadataDiskCacheStore: MetadataDiskCacheStore,
    private val snapshotStore: ContinueWatchingSnapshotStore,
    private val continueWatchingIdentityResolver: ContinueWatchingIdentityResolver,
    private val airScheduler: ContinueWatchingAirScheduler = NoopContinueWatchingAirScheduler,
    private val profileManager: ProfileManager? = null,
    private val ownershipService: IntegrationOwnershipService? = null,
    private val activeRailTracker: ActiveRailTracker = ActiveRailTracker(),
    private val identityResolver: RailMediaIdentityResolver = RailMediaIdentityResolver(),
    private val metadataRouterFacade: MetadataRouterFacade? = null,
    @ApplicationContext private val appContext: Context? = null
) {
    @VisibleForTesting
    constructor(
        watchProgressRepository: WatchProgressRepository,
        trackingProgressService: TrackingProgressService,
        trackingProviderStateService: TrackingProviderStateService,
        traktSettingsDataStore: TraktSettingsDataStore,
        metadataDiskCacheStore: MetadataDiskCacheStore,
        snapshotStore: ContinueWatchingSnapshotStore,
        airScheduler: ContinueWatchingAirScheduler = NoopContinueWatchingAirScheduler,
        profileManager: ProfileManager? = null,
        ownershipService: IntegrationOwnershipService? = null,
        activeRailTracker: ActiveRailTracker = ActiveRailTracker(),
        identityResolver: RailMediaIdentityResolver = RailMediaIdentityResolver(),
        metadataRouterFacade: MetadataRouterFacade? = null,
        @ApplicationContext appContext: Context? = null
    ) : this(
        watchProgressRepository = watchProgressRepository,
        trackingProgressService = trackingProgressService,
        trackingProviderStateService = trackingProviderStateService,
        traktSettingsDataStore = traktSettingsDataStore,
        metadataDiskCacheStore = metadataDiskCacheStore,
        snapshotStore = snapshotStore,
        continueWatchingIdentityResolver = ContinueWatchingIdentityResolver(),
        airScheduler = airScheduler,
        profileManager = profileManager,
        ownershipService = ownershipService,
        activeRailTracker = activeRailTracker,
        identityResolver = identityResolver,
        metadataRouterFacade = metadataRouterFacade,
        appContext = appContext
    )

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
    private val persistedTvPlaybackRefreshLock = Any()
    private var lastPersistedTvPlaybackRefreshMs = 0L

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
                    val languageTag = activeLanguageTag()
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
                                        profileId = profileId,
                                        languageTag = languageTag,
                                        allProgress = allProgress,
                                        nextUpEntries = emptyList(),
                                        traktUpNextEntries = emptyList()
                                    ),
                                    retainMissingRows = false
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
                                        profileId = profileId,
                                        languageTag = languageTag,
                                        allProgress = allProgress,
                                        nextUpEntries = nextUpEntries,
                                        traktUpNextEntries = traktUpNextEntries
                                    ),
                                    retainMissingRows = true,
                                    completedProgress = allProgress.filter { it.isCompleted() }
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
                    val publishSnapshot = if (emission.retainMissingRows) {
                        retainStableRowsFromPreviousSnapshot(
                            candidate = snapshot,
                            previous = rawSnapshotState.value.snapshot,
                            completedProgress = emission.completedProgress
                        )
                    } else {
                        snapshot
                    }
                    updateSnapshot(
                        snapshot = publishSnapshot,
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
            val normalized = upgradeStaleRouteSnapshots(sanitizePersistedSnapshot(persisted))
            if (normalized != persisted) {
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
            if (hasRemoteTvPlaybackResume(normalized)) {
                requestTrackingRefreshAfterPersistedTvPlaybackRestore()
            }
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
    }

    fun observeContinueWatching(profileId: Int): Flow<List<ContinueWatchingRecord>> {
        require(profileId > 0) { "profileId must be positive" }
        return observeSnapshot()
            .filter { it.profileId == profileId }
            .map { owned -> owned.toContinueWatchingRecords() }
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

    suspend fun hydrateFromResolvedDisplaySurface(
        profileId: Int,
        resolvedItems: List<ResolvedDisplayItem>
    ) {
        if (resolvedItems.isEmpty()) return
        refreshMutex.withLock {
            val current = rawSnapshotState.value
            if (current.profileId != profileId) return
            val updated = mergeResolvedDisplaySnapshot(
                snapshot = current.snapshot,
                profileId = profileId,
                resolvedItems = resolvedItems
            )
            if (updated == current.snapshot) return
            val session = sessionForProfile(profileId)
            if (!canPublishProfileWrite(session)) return
            syncContinueWatchingRail(updated, profileId)
            snapshotStore.write(updated, profileId = profileId)
            rawSnapshotState.value = current.copy(snapshot = updated)
            activeRailTracker.markActive(RailKeyFactory.continueWatching(profileId))
            emitWrite(
                profileId = profileId,
                recordCount = updated.resumeItems.size + updated.nextUpItems.size + updated.traktUpNextItems.size
            )
        }
    }

    @VisibleForTesting
    internal fun mergeResolvedDisplaySnapshot(
        snapshot: ContinueWatchingSnapshot,
        profileId: Int,
        resolvedItems: List<ResolvedDisplayItem>
    ): ContinueWatchingSnapshot {
        if (resolvedItems.isEmpty()) return snapshot
        val targets = buildContinueWatchingResolvedTargets(snapshot)
        if (targets.isEmpty()) return snapshot
        val wantedAliases = HashSet<String>(targets.size * 4)
        for (i in targets.indices) {
            wantedAliases += targets[i].aliases
        }

        val resolvedByKey = HashMap<String, ResolvedDisplayItem>(targets.size)
        val resolvedByAlias = HashMap<String, ResolvedDisplayItem>(targets.size * 2)
        for (i in resolvedItems.indices) {
            val item = resolvedItems[i]
            if (item.itemKey in wantedAliases) {
                resolvedByKey[item.itemKey] = item
            }
            val aliases = resolvedDisplayAliases(item)
            for (alias in aliases) {
                if (alias in wantedAliases) {
                    resolvedByAlias.putIfAbsent(alias, item)
                }
            }
        }

        val metadataByKey = LinkedHashMap(snapshot.displayMetadataByItemKey)
        val recordsByIdentity = LinkedHashMap<String, ContinueWatchingRecord>()
        for (i in snapshot.records.indices) {
            val record = snapshot.records[i]
            recordsByIdentity[record.identityKey()] = record
        }
        var changed = false
        for (i in targets.indices) {
            val target = targets[i]
            val resolved = target.aliases.firstNotNullOfOrNull { alias ->
                resolvedByKey[alias] ?: resolvedByAlias[alias]
            } ?: continue
            val currentMetadata = metadataByKey[target.itemKey]
            val mergedMetadata = currentMetadata.mergeResolvedDisplay(resolved)
            if (mergedMetadata.hasRenderableDisplayMetadata() && mergedMetadata != currentMetadata) {
                metadataByKey[target.itemKey] = mergedMetadata
                changed = true
            }
            target.progress?.let { progress ->
                val record = progress.toResolvedContinueWatchingRecord(
                    resolved = resolved,
                    profileId = profileId,
                    displayMetadata = mergedMetadata,
                    updatedAt = snapshot.updatedAtMs.takeIf { it > 0L } ?: System.currentTimeMillis()
                )
                val previous = recordsByIdentity[record.identityKey()]
                if (previous != record) {
                    recordsByIdentity[record.identityKey()] = record
                    changed = true
                }
            }
        }
        if (!changed) return snapshot
        return snapshot.copy(
            records = recordsByIdentity.values.toList(),
            displayMetadataByItemKey = metadataByKey,
            updatedAtMs = System.currentTimeMillis()
        )
    }

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
                val removed = current.snapshot.resumeItems.filter { it.videoId == videoId }
                current.copy(
                    snapshot = current.snapshot.copy(
                        resumeItems = current.snapshot.resumeItems - removed.toSet()
                    ).withCanonicalRecordsRemovedFor(
                        removed.map { it.toCanonicalRecordRemovalRef() }
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
                val removed = current.snapshot.resumeItems.filter { it.contentId == showId }
                current.copy(
                    snapshot = current.snapshot.copy(
                        resumeItems = current.snapshot.resumeItems - removed.toSet()
                    ).withCanonicalRecordsRemovedFor(
                        removed.map { it.toCanonicalRecordRemovalRef() }
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
                current.copy(
                    snapshot = current.snapshot.copy(
                        resumeItems = merged
                    ).withInvalidatedCanonicalRecords()
                )
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
                val removedResumes = current.snapshot.resumeItems.filter { progress ->
                    episodes.any { ref ->
                        progress.contentId == ref.showId &&
                            progress.season == ref.seasonNumber &&
                            progress.episode == ref.episodeNumber
                    }
                }
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
                    ).withCanonicalRecordsRemovedFor(
                        removedResumes.map { it.toCanonicalRecordRemovalRef() }
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
                    ).withInvalidatedCanonicalRecords()
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

    internal suspend fun buildRawSnapshotForTest(
        profileId: Int = activeProfileId(),
        languageTag: String = activeLanguageTag(),
        allProgress: List<WatchProgress>,
        nextUpEntries: List<TrackingNextUpEntry>,
        traktUpNextEntries: List<TrackingNextUpEntry>
    ): ContinueWatchingSnapshot {
        return buildRawSnapshot(
            profileId = profileId,
            languageTag = languageTag,
            allProgress = allProgress,
            nextUpEntries = nextUpEntries,
            traktUpNextEntries = traktUpNextEntries
        )
    }

    private suspend fun buildRawSnapshot(
        profileId: Int,
        languageTag: String,
        allProgress: List<WatchProgress>,
        nextUpEntries: List<TrackingNextUpEntry>,
        traktUpNextEntries: List<TrackingNextUpEntry>
    ): ContinueWatchingSnapshot {
        val nowMs = System.currentTimeMillis()
        val completionAnchors = completionAnchorsByContent(allProgress)
        val watchedAnchors = ContinueWatchingCanonicalization.watchedAnchorsFromProgress(allProgress)
        val resumeItems = selectResumeItemsForContinueWatching(allProgress)
            .filterNot { progress ->
                ContinueWatchingCanonicalization.isSuppressedByWatchedAnchors(
                    lookupKeys = ContinueWatchingCanonicalization.lookupKeysForRawContentId(progress.contentId),
                    season = progress.season,
                    episode = progress.episode,
                    updatedAtMs = progress.lastWatched,
                    anchors = watchedAnchors
                )
            }
        val projectionCache = TvdbEpisodeProjectionCache()
        val localNextUpEntries = deriveLocalNextUpEntries(allProgress, projectionCache)
        // Indexed-for instead of `resumeItems.map { ... suspend ... }`. resolveOrFallback
        // suspends, and List.map's iterator pins resumeItems into the calling continuation
        // across every suspension (HARD RULE #4 in CLAUDE.md). Heap dump showed
        // ContinueWatchingSnapshotService$buildRawSnapshot$1.L$9 holding live iterators.
        val resolvedRecords = ArrayList<ContinueWatchingRecord>(resumeItems.size)
        for (i in resumeItems.indices) {
            resolvedRecords += continueWatchingIdentityResolver.resolveOrFallback(
                RawContinueWatchingInput(
                    profileId = profileId,
                    progress = resumeItems[i],
                    languageTag = languageTag
                )
            )
        }
        val records = ContinueWatchingMerger.merge(resolvedRecords)
        val normalizedProviderNextUpItems = normalizeFilterProjectDedupeNextUpEntries(
            entries = nextUpEntries,
            completionAnchors = completionAnchors,
            watchedAnchors = watchedAnchors,
            projectionCache = projectionCache
        )
        val normalizedLocalNextUpItems = normalizeFilterProjectDedupeNextUpEntries(
            entries = localNextUpEntries,
            completionAnchors = completionAnchors,
            watchedAnchors = emptyList(),
            projectionCache = projectionCache
        )
        val normalizedNextUpItems = (normalizedProviderNextUpItems + normalizedLocalNextUpItems)
            .asSequence()
            .sortedByDescending { it.activityAtMs }
            .distinctBy { "${it.contentId}|${it.season}|${it.episode}" }
            .toList()
            .dedupeNextUpBySeriesIdentity()
        val nextUpCandidateSelection = splitNextUpCandidatesForContinueWatching(
            resumes = resumeItems.map(::resumeRefForProgress),
            nextUpItems = normalizedNextUpItems,
            nextUpRef = ::nextUpRefForEntry,
            nowMs = nowMs
        )
        val nextUpMainCandidates = nextUpCandidateSelection.mainFeedItems
        val nextUpItems = nextUpMainCandidates.filter { entry ->
            ContinueWatchingCanonicalization.isMainFeedAiredNextUp(entry, nowMs)
        }
        val normalizedTraktUpNextItems = normalizeFilterProjectDedupeNextUpEntries(
            entries = traktUpNextEntries,
            completionAnchors = completionAnchors,
            watchedAnchors = watchedAnchors,
            projectionCache = projectionCache
        )
        val syntheticRailCandidates = splitNextUpCandidatesForContinueWatching(
            resumes = resumeItems.map(::resumeRefForProgress),
            nextUpItems = normalizedTraktUpNextItems,
            nextUpRef = ::nextUpRefForEntry,
            nowMs = nowMs
        ).syntheticRailItems
        val traktUpNextItems = syntheticRailCandidates.filter { entry ->
            ContinueWatchingCanonicalization.isMainFeedAiredNextUp(entry, nowMs)
        }

        // Resume items carry no air-date data; running them through AirDateGate keeps all
        // three rails on a single uniform gate site. With firstAiredMs=0 and tmdbAirDate=null,
        // isAired() returns true — so this is a no-op for resumes today, but preserves the
        // contract that every rail participates in gating.
        val gatedResumeItems = resumeItems.filter {
            AirDateGate.isAired(firstAiredMs = 0L, tmdbAirDate = null, nowMs = nowMs)
        }

        val scheduledReemit = buildList {
            addAll(nextUpCandidateSelection.syntheticRailItems.filter { entry ->
                val triggerMs = ContinueWatchingCanonicalization.pendingTriggerMs(entry)
                triggerMs != null && triggerMs > nowMs
            })
            addAll(syntheticRailCandidates.filter { entry ->
                val triggerMs = ContinueWatchingCanonicalization.pendingTriggerMs(entry)
                triggerMs != null && triggerMs > nowMs
            })
        }

        return ContinueWatchingSnapshot(
            resumeItems = gatedResumeItems,
            nextUpItems = nextUpItems,
            traktUpNextItems = traktUpNextItems,
            records = records,
            updatedAtMs = nowMs,
            scheduledReemit = scheduledReemit
        )
    }

    private suspend fun deriveLocalNextUpEntries(
        allProgress: List<WatchProgress>,
        projectionCache: TvdbEpisodeProjectionCache
    ): List<TrackingNextUpEntry> {
        val facade = metadataRouterFacade ?: return emptyList()
        val latestCompletedByContent = linkedMapOf<String, WatchProgress>()
        for (i in allProgress.indices) {
            val progress = allProgress[i]
            if (!progress.isCompleted()) continue
            if (!progress.contentType.equals("series", ignoreCase = true) &&
                !progress.contentType.equals("tv", ignoreCase = true) &&
                !progress.contentType.equals("anime", ignoreCase = true)
            ) {
                continue
            }
            if (progress.season == null || progress.episode == null) continue
            val contentId = progress.contentId.trim()
            if (contentId.isBlank()) continue
            val existing = latestCompletedByContent[contentId]
            if (existing == null || shouldPreferCompletedSeed(existing, progress)) {
                latestCompletedByContent[contentId] = progress
            }
        }
        if (latestCompletedByContent.isEmpty()) return emptyList()

        val seeds = latestCompletedByContent.values
            .sortedByDescending { it.lastWatched }
            .take(30)
        val entries = ArrayList<TrackingNextUpEntry>(seeds.size)
        for (i in seeds.indices) {
            val seed = seeds[i]
            val episodeMap = fetchLocalNextUpEpisodeMap(facade, seed, projectionCache)
            val entry = deriveLocalNextUpEntry(seed, episodeMap) ?: continue
            entries += entry
        }
        return TvdbContinueWatchingTimingEnricher(
            metadataRouterFacade = facade,
            availabilityCalculator = TvdbAirAvailabilityCalculator()
        ).enrich(entries)
    }

    private suspend fun normalizeFilterProjectDedupeNextUpEntries(
        entries: List<TrackingNextUpEntry>,
        completionAnchors: Map<String, ContinueWatchingCompletionAnchor>,
        watchedAnchors: List<ContinueWatchingWatchedAnchor>,
        projectionCache: TvdbEpisodeProjectionCache
    ): List<TrackingNextUpEntry> {
        val normalizedEntries = entries
            .asSequence()
            .mapNotNull(::normalizeNextUpEntry)
            .sortedByDescending { it.activityAtMs }
            .distinctBy { "${it.contentId}|${it.season}|${it.episode}" }
            .toList()
        val projectedEntries = projectNextUpEntriesToCanonicalCoordinates(
            entries = normalizedEntries,
            projectionCache = projectionCache
        )
        return projectedEntries
            .asSequence()
            .filterNot { entry ->
                isNextUpSuppressedByCompletionAnchor(entry, completionAnchors[entry.contentId])
            }
            .filterNot { entry ->
                ContinueWatchingCanonicalization.isSuppressedByWatchedAnchors(
                    lookupKeys = ContinueWatchingCanonicalization.lookupKeysForRawContentId(entry.contentId),
                    season = entry.season,
                    episode = entry.episode,
                    updatedAtMs = entry.activityAtMs,
                    anchors = watchedAnchors
                )
            }
            .sortedByDescending { it.activityAtMs }
            .distinctBy { "${it.contentId}|${it.season}|${it.episode}" }
            .toList()
    }

    private suspend fun projectNextUpEntriesToCanonicalCoordinates(
        entries: List<TrackingNextUpEntry>,
        projectionCache: TvdbEpisodeProjectionCache
    ): List<TrackingNextUpEntry> {
        val facade = metadataRouterFacade ?: return entries
        if (entries.isEmpty()) return entries

        val projectedEntries = ArrayList<TrackingNextUpEntry>(entries.size)
        var changed = false
        for (i in entries.indices) {
            val entry = entries[i]
            if (entry.contentType.trim().equals("anime", ignoreCase = true)) {
                projectedEntries += entry
                continue
            }
            val projected = projectNextUpEntryToCanonicalCoordinate(facade, entry, projectionCache)
            if (projected != null) {
                projectedEntries += projected
                changed = true
            } else {
                projectedEntries += entry
            }
        }
        return if (changed) projectedEntries else entries
    }

    private suspend fun projectNextUpEntryToCanonicalCoordinate(
        facade: MetadataRouterFacade,
        entry: TrackingNextUpEntry,
        projectionCache: TvdbEpisodeProjectionCache
    ): TrackingNextUpEntry? {
        return try {
            val canonicalContentId = canonicalTvdbContentId(facade, entry)
            val episodes = projectionCache.getOrFetch(
                contentType = entry.contentType,
                contentId = entry.contentId,
                fallbackContentId = entry.videoId
            ) {
                facade.fetchTvEpisodeEnrichment(
                    metadataRequest = MetadataRequest(
                        contentId = entry.contentId,
                        contentType = ContentType.SERIES,
                        sourceContext = MetadataSourceContext(itemType = entry.contentType),
                        depth = MetadataDepth.SEASON
                    ),
                    tvRequest = TvMetadataRequest(
                        contentId = entry.contentId,
                        fallbackContentId = entry.videoId,
                        contentType = ContentType.SERIES,
                        seasonNumbers = emptyList()
                    )
                ).value.orEmpty()
            }
            val projected = ContinueWatchingEpisodeCoordinateProjector.projectFromEpisodeMap(
                contentType = entry.contentType,
                requestedSeason = entry.season,
                requestedEpisode = entry.episode,
                requestedTitle = entry.episodeTitle,
                requestedFirstAired = entry.firstAired,
                episodes = episodes
            ) ?: return null

            val projectedContentId = canonicalContentId ?: entry.contentId
            entry.copy(
                contentId = projectedContentId,
                season = projected.season,
                episode = projected.episode,
                episodeTitle = projected.episodeTitle ?: entry.episodeTitle,
                videoId = "$projectedContentId:${projected.season}:${projected.episode}",
                firstAired = projected.firstAired ?: entry.firstAired,
                firstAiredMs = projected.firstAired
                    ?.let { AirDateGate.pendingTriggerMs(0L, null, it) }
                    ?: entry.firstAiredMs
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(
                "ContinueWatching",
                "next-up coordinate projection failed for ${entry.contentId} s${entry.season}e${entry.episode}: ${e.message}",
                e
            )
            null
        }
    }

    private suspend fun canonicalTvdbContentId(
        facade: MetadataRouterFacade,
        entry: TrackingNextUpEntry
    ): String? {
        return runCatching {
            val route = facade.routeRequest(
                MetadataRequest(
                    contentId = entry.contentId,
                    contentType = ContentType.SERIES,
                    sourceContext = MetadataSourceContext(itemType = entry.contentType),
                    seasonNumber = entry.season,
                    depth = MetadataDepth.SEASON
                )
            )
            if (route.provider != MetadataPrimaryProvider.TVDB) return@runCatching null
            val tvdbTarget = route.targetIds[MetadataPrimaryProvider.TVDB]
                ?.substringAfter("tvdb:", missingDelimiterValue = route.targetIds[MetadataPrimaryProvider.TVDB].orEmpty())
                ?.trim()
                ?.takeIf { it.isNotBlank() }
            tvdbTarget?.let { "tvdb:$it" }
        }.getOrNull()
    }

    private fun shouldPreferCompletedSeed(
        existing: WatchProgress,
        candidate: WatchProgress
    ): Boolean {
        if (candidate.lastWatched != existing.lastWatched) return candidate.lastWatched > existing.lastWatched
        val candidateSeason = candidate.season ?: -1
        val existingSeason = existing.season ?: -1
        if (candidateSeason != existingSeason) return candidateSeason > existingSeason
        return (candidate.episode ?: -1) > (existing.episode ?: -1)
    }

    private suspend fun fetchLocalNextUpEpisodeMap(
        facade: MetadataRouterFacade,
        seed: WatchProgress,
        projectionCache: TvdbEpisodeProjectionCache
    ): Map<Pair<Int, Int>, TvEpisodeMetadata> {
        val contentType = ContentType.fromString(seed.contentType)
        return try {
            projectionCache.getOrFetch(
                contentType = seed.contentType,
                contentId = seed.contentId,
                fallbackContentId = seed.videoId
            ) {
                facade.fetchTvEpisodeEnrichment(
                    metadataRequest = MetadataRequest(
                        contentId = seed.contentId,
                        contentType = contentType,
                        sourceContext = MetadataSourceContext(itemType = seed.contentType),
                        depth = MetadataDepth.SEASON
                    ),
                    tvRequest = TvMetadataRequest(
                        contentId = seed.contentId,
                        fallbackContentId = seed.videoId,
                        contentType = contentType,
                        seasonNumbers = emptyList()
                    )
                ).value.orEmpty()
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w("ContinueWatching", "local next-up episode map failed for ${seed.contentId}: ${e.message}", e)
            emptyMap()
        }
    }

    private fun retainStableRowsFromPreviousSnapshot(
        candidate: ContinueWatchingSnapshot,
        previous: ContinueWatchingSnapshot,
        completedProgress: List<WatchProgress>
    ): ContinueWatchingSnapshot {
        if (previous.updatedAtMs <= 0L) return candidate
        if (
            previous.resumeItems.isEmpty() &&
            previous.nextUpItems.isEmpty() &&
            previous.traktUpNextItems.isEmpty() &&
            previous.records.isEmpty()
        ) {
            return candidate
        }

        val completionAnchors = completionAnchorsByContent(completedProgress)
        val watchedAnchors = ContinueWatchingCanonicalization.watchedAnchorsFromProgress(completedProgress)
        val retainedResumeItems = retainMissingResumeItems(
            candidate = candidate.resumeItems,
            previous = previous.resumeItems,
            completionAnchors = completionAnchors,
            watchedAnchors = watchedAnchors
        )
        val retainedNextUpItems = retainMissingNextUpItems(
            candidate = candidate.nextUpItems,
            previous = previous.nextUpItems,
            completionAnchors = completionAnchors,
            watchedAnchors = watchedAnchors
        )
        val retainedTraktUpNextItems = retainMissingNextUpItems(
            candidate = candidate.traktUpNextItems,
            previous = previous.traktUpNextItems,
            completionAnchors = completionAnchors,
            watchedAnchors = watchedAnchors
        )
        val retainedRecords = retainMissingRecords(
            candidate = candidate.records,
            previous = previous.records,
            completionAnchors = completionAnchors,
            watchedAnchors = watchedAnchors
        )

        if (
            retainedResumeItems === candidate.resumeItems &&
            retainedNextUpItems === candidate.nextUpItems &&
            retainedTraktUpNextItems === candidate.traktUpNextItems &&
            retainedRecords === candidate.records
        ) {
            return candidate
        }

        return candidate.copy(
            resumeItems = retainedResumeItems,
            nextUpItems = retainedNextUpItems,
            traktUpNextItems = retainedTraktUpNextItems,
            records = retainedRecords,
            displayMetadataByItemKey = previous.displayMetadataByItemKey + candidate.displayMetadataByItemKey,
            metadataSnapshotsByItemKey = previous.metadataSnapshotsByItemKey + candidate.metadataSnapshotsByItemKey
        )
    }

    private fun retainMissingResumeItems(
        candidate: List<WatchProgress>,
        previous: List<WatchProgress>,
        completionAnchors: Map<String, ContinueWatchingCompletionAnchor>,
        watchedAnchors: List<ContinueWatchingWatchedAnchor>
    ): List<WatchProgress> {
        if (previous.isEmpty()) return candidate
        val byKey = LinkedHashMap<String, WatchProgress>(candidate.size + previous.size)
        candidate.forEach { progress ->
            byKey[resumeRetentionKey(progress)] = progress
        }
        var retainedAny = false
        previous.forEach { progress ->
            val key = resumeRetentionKey(progress)
            if (key in byKey) return@forEach
            if (isSuppressedByCompletionAnchor(progress, completionAnchors[progress.contentId])) return@forEach
            if (
                ContinueWatchingCanonicalization.isSuppressedByWatchedAnchors(
                    lookupKeys = ContinueWatchingCanonicalization.lookupKeysForRawContentId(progress.contentId),
                    season = progress.season,
                    episode = progress.episode,
                    updatedAtMs = progress.lastWatched,
                    anchors = watchedAnchors
                )
            ) {
                return@forEach
            }
            byKey[key] = progress
            retainedAny = true
        }
        if (!retainedAny) return candidate
        return byKey.values.sortedByDescending { it.lastWatched }
    }

    private fun retainMissingNextUpItems(
        candidate: List<TrackingNextUpEntry>,
        previous: List<TrackingNextUpEntry>,
        completionAnchors: Map<String, ContinueWatchingCompletionAnchor>,
        watchedAnchors: List<ContinueWatchingWatchedAnchor>
    ): List<TrackingNextUpEntry> {
        if (previous.isEmpty()) return candidate
        val byKey = LinkedHashMap<String, TrackingNextUpEntry>(candidate.size + previous.size)
        candidate.forEach { entry ->
            byKey[nextUpRetentionKey(entry)] = entry
        }
        var retainedAny = false
        previous.forEach { entry ->
            val key = nextUpRetentionKey(entry)
            if (key in byKey) return@forEach
            if (isNextUpSuppressedByCompletionAnchor(entry, completionAnchors[entry.contentId])) return@forEach
            if (
                ContinueWatchingCanonicalization.isSuppressedByWatchedAnchors(
                    lookupKeys = ContinueWatchingCanonicalization.lookupKeysForRawContentId(entry.contentId),
                    season = entry.season,
                    episode = entry.episode,
                    updatedAtMs = entry.activityAtMs,
                    anchors = watchedAnchors
                )
            ) {
                return@forEach
            }
            byKey[key] = entry
            retainedAny = true
        }
        if (!retainedAny) return candidate
        return byKey.values.sortedByDescending { it.activityAtMs }
    }

    private fun retainMissingRecords(
        candidate: List<ContinueWatchingRecord>,
        previous: List<ContinueWatchingRecord>,
        completionAnchors: Map<String, ContinueWatchingCompletionAnchor>,
        watchedAnchors: List<ContinueWatchingWatchedAnchor>
    ): List<ContinueWatchingRecord> {
        if (previous.isEmpty()) return candidate
        val byKey = LinkedHashMap<String, ContinueWatchingRecord>(candidate.size + previous.size)
        candidate.forEach { record ->
            byKey[recordRetentionKey(record)] = record
        }
        var retainedAny = false
        previous.forEach { record ->
            val key = recordRetentionKey(record)
            if (key in byKey) return@forEach
            if (record.isSuppressedByCompletionAnchor(completionAnchorForRecord(record, completionAnchors))) {
                return@forEach
            }
            if (
                ContinueWatchingCanonicalization.isSuppressedByWatchedAnchors(
                    lookupKeys = completionAnchorLookupKeysForRecord(record).toSet(),
                    season = record.episodeContext?.season,
                    episode = record.episodeContext?.number,
                    updatedAtMs = record.updatedAt,
                    anchors = watchedAnchors
                )
            ) {
                return@forEach
            }
            byKey[key] = record
            retainedAny = true
        }
        if (!retainedAny) return candidate
        return ContinueWatchingMerger.merge(byKey.values.toList())
    }

    private fun resumeRetentionKey(progress: WatchProgress): String =
        "${progress.contentId}|${progress.videoId}|${progress.season ?: -1}|${progress.episode ?: -1}"

    private fun nextUpRetentionKey(entry: TrackingNextUpEntry): String =
        "${entry.contentId}|${entry.season}|${entry.episode}"

    private fun recordRetentionKey(record: ContinueWatchingRecord): String =
        "${record.parentId}|${record.contentId}|${record.episodeContext?.season ?: -1}|${record.episodeContext?.number ?: -1}"

    private fun completionAnchorForRecord(
        record: ContinueWatchingRecord,
        completionAnchors: Map<String, ContinueWatchingCompletionAnchor>
    ): ContinueWatchingCompletionAnchor? {
        return completionAnchorLookupKeysForRecord(record)
            .firstNotNullOfOrNull { key -> completionAnchors[key] }
    }

    private fun completionAnchorLookupKeysForRecord(record: ContinueWatchingRecord): List<String> {
        val keys = LinkedHashSet<String>()

        fun add(rawKey: String?) {
            val key = rawKey?.trim()?.takeIf { it.isNotEmpty() } ?: return
            keys += key
            stripTypedMediaPrefix(key)?.let { stripped ->
                keys += stripped
                stripEpisodeSuffix(stripped)?.let { keys += it }
            }
            stripEpisodeSuffix(key)?.let { keys += it }
        }

        add(record.parentId)
        add(record.contentId)
        record.resumeIdentities.forEach { identity ->
            add(identity.contentId)
            add(identity.videoId)
        }
        record.canonicalKey?.canonicalParent?.let { identity -> keys.addContentIdentityKeys(identity) }
        record.displayIdentity?.let { identity -> keys.addContentIdentityKeys(identity) }
        add(record.idBundle.priorityKey())

        return keys.toList()
    }

    private fun MutableSet<String>.addContentIdentityKeys(identity: ContentIdentity) {
        identity.canonicalProvider?.let { provider ->
            identity.canonicalId?.let { id ->
                add("${provider.name.lowercase()}:$id")
            }
        }
        val providerIds = identity.providerIds
        providerIds.imdb?.let { add("imdb:$it") }
        providerIds.tmdb?.let { add("tmdb:$it") }
        providerIds.tvdb?.let { add("tvdb:$it") }
        providerIds.trakt?.let { add("trakt:$it") }
        providerIds.simkl?.let { add("simkl:$it") }
        providerIds.kitsu?.let { add("kitsu:$it") }
        providerIds.mal?.let { add("mal:$it") }
        providerIds.anilist?.let { add("anilist:$it") }
        providerIds.anidb?.let { add("anidb:$it") }
    }

    private fun stripTypedMediaPrefix(key: String): String? {
        val seriesPrefix = "series:"
        val moviePrefix = "movie:"
        val stripped = when {
            key.startsWith(seriesPrefix) -> key.removePrefix(seriesPrefix)
            key.startsWith(moviePrefix) -> key.removePrefix(moviePrefix)
            else -> null
        }
        return stripped?.takeIf { it.isNotBlank() && it != key }
    }

    private fun stripEpisodeSuffix(key: String): String? {
        val suffixStart = key.lastIndexOf(":s")
        if (suffixStart <= 0 || !key.endsWithEpisodeSuffix(suffixStart)) return null
        return key.substring(0, suffixStart).takeIf { it.isNotBlank() }
    }

    private fun String.endsWithEpisodeSuffix(suffixStart: Int): Boolean {
        val eIndex = indexOf('e', startIndex = suffixStart + 2)
        if (eIndex <= suffixStart + 2 || eIndex == lastIndex) return false
        return substring(suffixStart + 2, eIndex).all(Char::isDigit) &&
            substring(eIndex + 1).all(Char::isDigit)
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

    private fun ContinueWatchingRecord.isSuppressedByCompletionAnchor(
        anchor: ContinueWatchingCompletionAnchor?
    ): Boolean {
        if (anchor == null) return false
        if (updatedAt <= anchor.lastWatched) return true

        val context = episodeContext ?: return false
        val recordSeason = context.season
        val recordEpisode = context.number
        val anchorSeason = anchor.season ?: return false
        val anchorEpisode = anchor.episode ?: return false

        return recordSeason < anchorSeason ||
            (recordSeason == anchorSeason && recordEpisode <= anchorEpisode)
    }

    private fun shouldTreatAsResumeForContinueWatching(progress: WatchProgress): Boolean {
        if (progress.isInProgress()) return true
        if (progress.isCompleted()) return false
        return progress.position > 0L || progress.progressPercent?.let { it > 0f } == true
    }

    private fun sanitizeSnapshot(snapshot: ContinueWatchingSnapshot): ContinueWatchingSnapshot {
        val nowMs = System.currentTimeMillis()
        val resumeItems = snapshot.resumeItems
            .mapNotNull(::normalizeResumeItem)
            .sortedByDescending { it.lastWatched }
            .distinctBy { it.contentId }
        val nextUpItems = snapshot.nextUpItems
            .mapNotNull(::normalizeNextUpEntry)
            .sortedByDescending { it.activityAtMs }
            .distinctBy { it.contentId }
            .dedupeNextUpBySeriesIdentity()
        val mainFeedNextUpItems = splitNextUpCandidatesForContinueWatching(
            resumes = resumeItems.map(::resumeRefForProgress),
            nextUpItems = nextUpItems,
            nextUpRef = ::nextUpRefForEntry,
            nowMs = nowMs
        )
        val sanitizedNextUpItems = mainFeedNextUpItems.mainFeedItems.filter { entry ->
            ContinueWatchingCanonicalization.isMainFeedAiredNextUp(entry, nowMs)
        }
        val traktUpNextItems = snapshot.traktUpNextItems
            .mapNotNull(::normalizeNextUpEntry)
            .sortedByDescending { it.activityAtMs }
            .distinctBy { it.contentId }
            .dedupeNextUpBySeriesIdentity()
        val traktUpNextSelection = splitNextUpCandidatesForContinueWatching(
            resumes = resumeItems.map(::resumeRefForProgress),
            nextUpItems = traktUpNextItems,
            nextUpRef = ::nextUpRefForEntry,
            nowMs = nowMs
        )
        val sanitizedTraktUpNextItems = traktUpNextSelection.syntheticRailItems.filter { entry ->
            ContinueWatchingCanonicalization.isMainFeedAiredNextUp(entry, nowMs)
        }
        val scheduledReemitByKey = LinkedHashMap<String, TrackingNextUpEntry>()
        fun addScheduledReemit(entry: TrackingNextUpEntry) {
            val normalized = normalizeNextUpEntry(entry) ?: return
            val triggerMs = ContinueWatchingCanonicalization.pendingTriggerMs(normalized)
            if (triggerMs == null || triggerMs <= nowMs) return
            scheduledReemitByKey.putIfAbsent(
                "${normalized.contentId}|${normalized.season}|${normalized.episode}",
                normalized
            )
        }
        snapshot.scheduledReemit.forEach(::addScheduledReemit)
        (mainFeedNextUpItems.syntheticRailItems + traktUpNextSelection.syntheticRailItems)
            .forEach(::addScheduledReemit)
        val activeItemKeys = buildSet {
            resumeItems.forEach { progress ->
                add(homeDisplayItemKey(progress.contentType, progress.contentId))
            }
            sanitizedNextUpItems.forEach { entry ->
                add(homeDisplayItemKey(entry.contentType, entry.contentId))
            }
            sanitizedTraktUpNextItems.forEach { entry ->
                add(homeDisplayItemKey(entry.contentType, entry.contentId))
            }
        }
        val updatedAtMs = if (snapshot.updatedAtMs > 0L) snapshot.updatedAtMs else nowMs
        return ContinueWatchingSnapshot(
            resumeItems = resumeItems,
            nextUpItems = sanitizedNextUpItems,
            traktUpNextItems = sanitizedTraktUpNextItems,
            records = snapshot.records,
            displayMetadataByItemKey = snapshot.displayMetadataByItemKey.filterKeys { it in activeItemKeys },
            metadataSnapshotsByItemKey = snapshot.metadataSnapshotsByItemKey.filterKeys { it in activeItemKeys },
            updatedAtMs = updatedAtMs,
            scheduledReemit = scheduledReemitByKey.values.toList()
        )
    }

    private fun sanitizePersistedSnapshot(snapshot: ContinueWatchingSnapshot): ContinueWatchingSnapshot {
        val sanitized = sanitizeSnapshot(snapshot)
        if (sanitized.nextUpItems.isEmpty() && sanitized.traktUpNextItems.isEmpty()) {
            return sanitized
        }
        val activeItemKeys = buildSet {
            sanitized.resumeItems.forEach { progress ->
                add(homeDisplayItemKey(progress.contentType, progress.contentId))
            }
        }
        return sanitized.copy(
            nextUpItems = emptyList(),
            traktUpNextItems = emptyList(),
            displayMetadataByItemKey = sanitized.displayMetadataByItemKey.filterKeys { it in activeItemKeys },
            metadataSnapshotsByItemKey = sanitized.metadataSnapshotsByItemKey.filterKeys { it in activeItemKeys }
        )
    }

    private fun hasRemoteTvPlaybackResume(snapshot: ContinueWatchingSnapshot): Boolean {
        val resumeItems = snapshot.resumeItems
        for (i in resumeItems.indices) {
            val progress = resumeItems[i]
            if (
                isRemotePlaybackSource(progress.source) &&
                progress.season != null &&
                progress.episode != null
            ) {
                return true
            }
        }
        return false
    }

    private fun isRemotePlaybackSource(source: String): Boolean =
        source == WatchProgress.SOURCE_TRAKT_PLAYBACK ||
            source == WatchProgress.SOURCE_SIMKL_PLAYBACK ||
            source == WatchProgress.SOURCE_MDBLIST_PLAYBACK

    private fun requestTrackingRefreshAfterPersistedTvPlaybackRestore() {
        val now = System.currentTimeMillis()
        val shouldRefresh = synchronized(persistedTvPlaybackRefreshLock) {
            if (now - lastPersistedTvPlaybackRefreshMs < minRefreshIntervalMs) {
                false
            } else {
                lastPersistedTvPlaybackRefreshMs = now
                true
            }
        }
        if (!shouldRefresh) return
        scope.launch {
            runCatching { trackingProgressService.refreshNow() }
                .onFailure { error ->
                    Log.w(
                        "ContinueWatching",
                        "Failed to refresh tracking progress after persisted TV playback restore",
                        error
                    )
                }
        }
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
                            addonMetadata = metadataSnapshot.clickTimeSlots.toHomeDisplayMetadata()
                        ),
                        depth = MetadataDepth.DETAIL_CORE
                    )
                )
                ContinueWatchingMetadataSnapshot.fromRoute(
                    route = route,
                    clickTimeSlots = metadataSnapshot.clickTimeSlots
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

    private fun List<TrackingNextUpEntry>.dedupeNextUpBySeriesIdentity(): List<TrackingNextUpEntry> {
        if (size <= 1) return this
        val seen = linkedSetOf<String>()
        val out = ArrayList<TrackingNextUpEntry>(size)
        for (i in indices) {
            val entry = this[i]
            val key = nextUpSeriesIdentityKey(entry)
            if (seen.add(key)) out += entry
        }
        return out
    }

    private fun nextUpSeriesIdentityKey(entry: TrackingNextUpEntry): String {
        val normalizedTitle = entry.name
            .trim()
            .lowercase(Locale.ROOT)
            .replace(NEXT_UP_SERIES_TITLE_TOKEN, " ")
            .trim()
            .replace(NEXT_UP_SERIES_WHITESPACE, " ")

        if (normalizedTitle.isNotBlank()) {
            return "${entry.contentType.trim().lowercase(Locale.ROOT)}:$normalizedTitle"
        }

        val lookupKeys = ContinueWatchingCanonicalization.lookupKeysForRawContentId(entry.contentId)
        if (lookupKeys.isNotEmpty()) {
            return lookupKeys.sorted().joinToString(separator = "|")
        }

        return entry.contentId.trim().lowercase(Locale.ROOT)
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

    private fun activeLanguageTag(): String =
        appContext?.let { AppLocaleResolver.resolveEffectiveAppLanguageTag(it) } ?: "en-US"

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
                clickTimeSlots = routeUpgradedSnapshot.metadataSnapshotsByItemKey[itemKey]?.clickTimeSlots,
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
                addonMetadata = routedSnapshot?.clickTimeSlots?.toHomeDisplayMetadata()
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

    private data class ContinueWatchingResolvedTarget(
        val itemKey: String,
        val aliases: Set<String>,
        val progress: WatchProgress?
    )

    private fun buildContinueWatchingResolvedTargets(
        snapshot: ContinueWatchingSnapshot
    ): List<ContinueWatchingResolvedTarget> {
        val targets = ArrayList<ContinueWatchingResolvedTarget>(
            snapshot.resumeItems.size + snapshot.nextUpItems.size + snapshot.traktUpNextItems.size
        )
        for (i in snapshot.resumeItems.indices) {
            val progress = snapshot.resumeItems[i]
            val itemKey = homeDisplayItemKey(progress.contentType, progress.contentId)
            targets += ContinueWatchingResolvedTarget(
                itemKey = itemKey,
                aliases = continueWatchingResolvedAliases(
                    contentType = progress.contentType,
                    contentId = progress.contentId,
                    displayMetadata = snapshot.displayMetadataByItemKey[itemKey],
                    traktMovieId = progress.traktMovieId,
                    traktShowId = progress.traktShowId
                ),
                progress = progress
            )
        }
        for (i in snapshot.nextUpItems.indices) {
            val entry = snapshot.nextUpItems[i]
            val itemKey = homeDisplayItemKey(entry.contentType, entry.contentId)
            targets += ContinueWatchingResolvedTarget(
                itemKey = itemKey,
                aliases = continueWatchingResolvedAliases(
                    contentType = entry.contentType,
                    contentId = entry.contentId,
                    displayMetadata = snapshot.displayMetadataByItemKey[itemKey],
                    traktMovieId = null,
                    traktShowId = entry.traktShowId
                ),
                progress = null
            )
        }
        for (i in snapshot.traktUpNextItems.indices) {
            val entry = snapshot.traktUpNextItems[i]
            val itemKey = homeDisplayItemKey(entry.contentType, entry.contentId)
            targets += ContinueWatchingResolvedTarget(
                itemKey = itemKey,
                aliases = continueWatchingResolvedAliases(
                    contentType = entry.contentType,
                    contentId = entry.contentId,
                    displayMetadata = snapshot.displayMetadataByItemKey[itemKey],
                    traktMovieId = null,
                    traktShowId = entry.traktShowId
                ),
                progress = null
            )
        }
        return targets
    }

    private fun continueWatchingResolvedAliases(
        contentType: String,
        contentId: String,
        displayMetadata: HomeDisplayMetadata?,
        traktMovieId: Int?,
        traktShowId: Int?
    ): Set<String> {
        val type = ContentType.fromString(contentType).toApiString()
        val aliases = linkedSetOf(homeDisplayItemKey(type, contentId))
        providerIdsFromRawContinueWatchingContentId(contentId).addDisplayAliases(type, aliases)
        displayMetadata?.imdbId?.let { aliases += "$type:imdb:${it.trim()}" }
        traktMovieId?.let { aliases += "$type:trakt:$it" }
        traktShowId?.let { aliases += "$type:trakt:$it" }
        return aliases.filterTo(linkedSetOf()) { it.substringAfterLast(':').isNotBlank() }
    }

    private fun resolvedDisplayAliases(item: ResolvedDisplayItem): Set<String> {
        val type = item.itemType.toApiString()
        val aliases = linkedSetOf(
            item.itemKey,
            homeDisplayItemKey(type, item.contentId)
        )
        item.stableIds.addDisplayAliases(type, aliases)
        item.imdbId?.trim()?.takeIf { it.isNotEmpty() }?.let { aliases += "$type:imdb:$it" }
        return aliases
    }

    private fun ProviderIds.addDisplayAliases(type: String, aliases: MutableSet<String>) {
        imdb?.trim()?.takeIf { it.isNotEmpty() }?.let { aliases += "$type:imdb:$it" }
        tmdb?.trim()?.takeIf { it.isNotEmpty() }?.let { aliases += "$type:tmdb:$it" }
        tvdb?.trim()?.takeIf { it.isNotEmpty() }?.let { aliases += "$type:tvdb:$it" }
        trakt?.trim()?.takeIf { it.isNotEmpty() }?.let { aliases += "$type:trakt:$it" }
        simkl?.trim()?.takeIf { it.isNotEmpty() }?.let { aliases += "$type:simkl:$it" }
        kitsu?.trim()?.takeIf { it.isNotEmpty() }?.let { aliases += "$type:kitsu:$it" }
        mal?.trim()?.takeIf { it.isNotEmpty() }?.let { aliases += "$type:mal:$it" }
        anilist?.trim()?.takeIf { it.isNotEmpty() }?.let { aliases += "$type:anilist:$it" }
        anidb?.trim()?.takeIf { it.isNotEmpty() }?.let { aliases += "$type:anidb:$it" }
    }

    private fun providerIdsFromRawContinueWatchingContentId(contentId: String): ProviderIds {
        val value = contentId.trim().lowercase()
        if (value.isBlank()) return ProviderIds()
        return when {
            value.startsWith("tt") -> ProviderIds(imdb = value)
            value.startsWith("imdb:") ->
                ProviderIds(imdb = value.substringAfter(':').takeIf { it.isNotBlank() })
            value.startsWith("tvdb:") ->
                ProviderIds(tvdb = value.substringAfter(':').takeIf { it.isNotBlank() })
            value.startsWith("tmdb:tv:") ->
                ProviderIds(tmdb = value.substringAfter("tmdb:tv:").takeIf { it.isNotBlank() })
            value.startsWith("tmdb:") ->
                ProviderIds(tmdb = value.substringAfter(':').takeIf { it.isNotBlank() })
            value.startsWith("trakt:") ->
                ProviderIds(trakt = value.substringAfter(':').takeIf { it.isNotBlank() })
            else -> ProviderIds()
        }
    }

    private fun HomeDisplayMetadata?.mergeResolvedDisplay(
        resolved: ResolvedDisplayItem
    ): HomeDisplayMetadata {
        val current = this
        return HomeDisplayMetadata(
            title = resolved.display.title ?: current?.title,
            logo = resolved.artwork.logo.toLegacyArtworkString() ?: current?.logo,
            description = resolved.display.overview ?: current?.description,
            genres = resolved.display.genres.ifEmpty { current?.genres.orEmpty() },
            releaseInfo = resolved.display.releaseDate ?: resolved.display.year?.toString() ?: current?.releaseInfo,
            runtime = resolved.display.runtimeText ?: current?.runtime,
            imdbRating = resolved.rating?.value?.toFloat() ?: current?.imdbRating,
            ratingSource = resolved.rating?.source ?: current?.ratingSource,
            tomatoesRating = resolved.display.tomatoesRating ?: current?.tomatoesRating,
            originalLanguage = current?.originalLanguage,
            imdbId = resolved.imdbId ?: resolved.stableIds.imdb ?: current?.imdbId,
            poster = resolved.artwork.poster.toLegacyArtworkString() ?: current?.poster,
            posterProviderTag = current?.posterProviderTag,
            backdrop = resolved.artwork.backdrop.toLegacyArtworkString() ?: current?.backdrop,
            thumbnail = resolved.artwork.thumbnail.toLegacyArtworkString() ?: current?.thumbnail,
            artwork = resolved.artwork
        )
    }

    private fun WatchProgress.toResolvedContinueWatchingRecord(
        resolved: ResolvedDisplayItem,
        profileId: Int,
        displayMetadata: HomeDisplayMetadata,
        updatedAt: Long
    ): ContinueWatchingRecord {
        val providerIds = resolved.stableIds.withProgressIds(this, resolved.imdbId)
        val canonicalProvider = resolved.canonicalProvider.toProviderId()
            ?: providerIds.bestCanonicalProvider()
        val canonicalId = resolved.canonicalId?.trim()?.takeIf { it.isNotEmpty() }
            ?: providerIds.idFor(canonicalProvider)
        val displayIdentity = ContentIdentity(
            canonicalProvider = canonicalProvider,
            canonicalId = canonicalId,
            providerIds = providerIds
        )
        val episodeContext = if (season != null && episode != null) {
            ContinueWatchingRecord.EpisodeContext(season, episode)
        } else {
            null
        }
        val canonicalKey = if (canonicalProvider != null && !canonicalId.isNullOrBlank()) {
            ContinueWatchingCanonicalKey(
                mediaKind = resolved.mediaKind,
                canonicalParent = displayIdentity,
                season = episodeContext?.season,
                episode = episodeContext?.number,
                profileId = profileId
            )
        } else {
            null
        }
        val streamIdentity = streamFetchIdentityFromResolved(
            mediaKind = resolved.mediaKind,
            providerIds = providerIds,
            season = episodeContext?.season,
            episode = episodeContext?.number,
            canonicalIdentity = displayIdentity,
            resumeVideoId = videoId
        )
        val contentIdForRecord = if (episodeContext != null) {
            "${contentId}:s${episodeContext.season}e${episodeContext.number}"
        } else {
            contentId
        }
        return ContinueWatchingRecord(
            profileId = profileId,
            parentId = contentId,
            contentId = contentIdForRecord,
            provider = TrackingProvider.TRAKT,
            routingVersion = ContinueWatchingMetadataSnapshot.CURRENT_ROUTING_VERSION,
            positionMs = position,
            durationMs = duration,
            episodeContext = episodeContext,
            clickTimeDisplayMetadata = ContinueWatchingMetadataSnapshot(
                routingVersion = ContinueWatchingMetadataSnapshot.CURRENT_ROUTING_VERSION,
                parentId = resolved.parentId,
                primaryProvider = canonicalProvider.toMetadataPrimaryProvider(),
                decisionReason = MetadataDecisionReason.PROVIDER_NATIVE_DIRECT,
                clickTimeSlots = displayMetadata.toResolvedFieldSlots(
                    nowMs = System.currentTimeMillis(),
                    rank = DisplaySourceRank.RESOLVED
                )
            ),
            source = ContinueWatchingRecord.Source.REMOTE,
            updatedAt = updatedAt.coerceAtLeast(1L),
            canonicalKey = canonicalKey,
            displayIdentity = displayIdentity,
            streamFetchIdentity = streamIdentity,
            trackingIdentity = TrackingIdentity(
                traktShowId = traktShowId,
                traktEpisodeId = traktEpisodeId,
                traktPlaybackId = traktPlaybackId,
                traktMovieId = traktMovieId,
                providerIds = providerIds
            ),
            resumeIdentities = listOf(toSafeResumeIdentity()),
            identityConfidence = if (streamIdentity != null) IdentityConfidence.HIGH else IdentityConfidence.MEDIUM,
            identityWarnings = emptyList(),
            languageTag = activeLanguageTag(),
            idBundle = providerIds.toContinueWatchingIdBundle(
                season = episodeContext?.season,
                episode = episodeContext?.number
            )
        )
    }

    private fun ProviderIds.withProgressIds(
        progress: WatchProgress,
        resolvedImdbId: String?
    ): ProviderIds = copy(
        imdb = imdb ?: resolvedImdbId?.trim()?.takeIf { it.isNotEmpty() }
            ?: progress.contentId.takeIf { it.startsWith("tt", ignoreCase = true) },
        trakt = trakt
            ?: progress.traktMovieId?.toString()
            ?: progress.traktShowId?.toString()
    )

    private fun ProviderIds.bestCanonicalProvider(): ProviderId? = when {
        !tmdb.isNullOrBlank() -> ProviderId.TMDB
        !tvdb.isNullOrBlank() -> ProviderId.TVDB
        !imdb.isNullOrBlank() -> ProviderId.IMDB
        !kitsu.isNullOrBlank() -> ProviderId.KITSU
        !trakt.isNullOrBlank() -> ProviderId.TRAKT
        !simkl.isNullOrBlank() -> ProviderId.SIMKL
        else -> null
    }

    private fun ProviderIds.idFor(provider: ProviderId?): String? = when (provider) {
        ProviderId.TMDB -> tmdb
        ProviderId.TVDB -> tvdb
        ProviderId.IMDB -> imdb
        ProviderId.KITSU -> kitsu
        ProviderId.TRAKT -> trakt
        ProviderId.SIMKL -> simkl
        else -> null
    }?.trim()?.takeIf { it.isNotEmpty() }

    private fun String?.toProviderId(): ProviderId? {
        val clean = this?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        return ProviderId.values().firstOrNull { it.name.equals(clean, ignoreCase = true) }
    }

    private fun ProviderId?.toMetadataPrimaryProvider(): MetadataPrimaryProvider = when (this) {
        ProviderId.TMDB -> MetadataPrimaryProvider.TMDB
        ProviderId.TVDB -> MetadataPrimaryProvider.TVDB
        ProviderId.KITSU -> MetadataPrimaryProvider.KITSU
        ProviderId.IMDB -> MetadataPrimaryProvider.IMDB
        ProviderId.TRAKT -> MetadataPrimaryProvider.TRAKT
        ProviderId.SIMKL -> MetadataPrimaryProvider.SIMKL
        else -> MetadataPrimaryProvider.IMDB
    }

    private fun streamFetchIdentityFromResolved(
        mediaKind: MetadataMediaKind,
        providerIds: ProviderIds,
        season: Int?,
        episode: Int?,
        canonicalIdentity: ContentIdentity,
        resumeVideoId: String
    ): StreamFetchIdentity? {
        val imdbId = providerIds.imdb?.takeIf { it.matches(Regex("^tt\\d+$")) } ?: return null
        return if (mediaKind == MetadataMediaKind.MOVIE || season == null || episode == null) {
            StreamFetchIdentity(
                contentId = imdbId,
                videoId = imdbId,
                idScheme = StreamIdScheme.IMDB_MOVIE,
                confidence = IdentityConfidence.HIGH,
                trace = listOf(
                    "resolved home surface hydrated continue watching movie stream id",
                    "source mediaKind=$mediaKind canonical=${canonicalIdentity.canonicalProvider}:${canonicalIdentity.canonicalId} resumeVideoId=$resumeVideoId"
                )
            )
        } else {
            StreamFetchIdentity(
                contentId = imdbId,
                videoId = "$imdbId:$season:$episode",
                idScheme = StreamIdScheme.IMDB_EPISODE,
                confidence = IdentityConfidence.HIGH,
                trace = listOf(
                    "resolved home surface hydrated continue watching episode stream id",
                    "source mediaKind=$mediaKind canonical=${canonicalIdentity.canonicalProvider}:${canonicalIdentity.canonicalId} resumeVideoId=$resumeVideoId"
                )
            )
        }
    }

    private fun ProviderIds.toContinueWatchingIdBundle(
        season: Int?,
        episode: Int?
    ): ContinueWatchingIdBundle = ContinueWatchingIdBundle(
        imdb = imdb,
        tmdb = tmdb,
        tvdb = tvdb,
        kitsu = kitsu,
        mal = mal,
        anilist = anilist,
        anidb = anidb,
        trakt = trakt,
        simkl = simkl,
        season = season,
        episode = episode
    )

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
