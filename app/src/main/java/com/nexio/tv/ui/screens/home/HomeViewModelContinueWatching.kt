package com.nexio.tv.ui.screens.home

import android.util.Log
import androidx.lifecycle.viewModelScope
import com.nexio.tv.core.anime.AnimeStremioId
import com.nexio.tv.core.anime.projection.AnimeSeasonProjectionResolver
import com.nexio.tv.core.anime.projection.AnimeSourceIdentity
import com.nexio.tv.core.anime.projection.EpisodeProjectionTarget
import com.nexio.tv.core.anime.projection.SourceEpisodeCoordinate
import com.nexio.tv.core.metadata.router.MetadataDepth
import com.nexio.tv.core.metadata.router.MetadataRequest
import com.nexio.tv.core.metadata.router.MetadataSourceContext
import com.nexio.tv.core.tvdb.TvMetadataRequest
import com.nexio.tv.data.repository.ContinueWatchingNextUpRef
import com.nexio.tv.data.repository.ContinueWatchingMetadataSnapshot
import com.nexio.tv.data.repository.ContinueWatchingRecord
import com.nexio.tv.data.repository.ContinueWatchingResumeRef
import com.nexio.tv.data.repository.ContinueWatchingSnapshot
import com.nexio.tv.data.repository.ContinueWatchingSnapshotService
import com.nexio.tv.data.repository.ContinueWatchingTimelineRow
import com.nexio.tv.data.repository.ResumeIdentity
import com.nexio.tv.data.repository.TrackingNextUpEntry
import com.nexio.tv.data.repository.TrackingScrobbleItem
import com.nexio.tv.data.repository.buildMixedContinueWatchingTimeline
import com.nexio.tv.data.repository.toSafeResumeIdentity
import com.nexio.tv.domain.model.HydratedHomeOverlay
import kotlinx.coroutines.CancellationException
import com.nexio.tv.domain.model.ContentType
import com.nexio.tv.domain.model.HomeDisplayMetadata
import com.nexio.tv.domain.model.MetaPreview
import com.nexio.tv.domain.model.PosterShape
import com.nexio.tv.domain.model.ProviderIds
import com.nexio.tv.domain.model.TmdbSettings
import com.nexio.tv.domain.model.WatchProgress
import com.nexio.tv.domain.model.homeDisplayItemKey
import com.nexio.tv.domain.model.DisplaySourceRank
import com.nexio.tv.domain.model.toArtworkBundleFromDisplayFields
import com.nexio.tv.domain.model.toHomeDisplayMetadata
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChangedBy
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale

private val CONTINUE_WATCHING_EPISODE_SUFFIX = Regex(":s\\d+e\\d+$", RegexOption.IGNORE_CASE)
private const val LEGACY_TEKENFILMS_ID_PREFIX = "tekenfilms:"

/**
 * Pure dedup function: collapses entries that share the same projected identity key into one row.
 * The first occurrence of each key is kept; subsequent duplicates are dropped.
 * Order of surviving entries is preserved.
 *
 * @param items the raw timeline list (already sorted / interleaved)
 * @param identityKeyFor maps each item to its projected identity key; defaults to content-id
 */
internal fun dedupContinueWatchingByProjectedIdentity(
    items: List<ContinueWatchingItem>,
    identityKeyFor: (ContinueWatchingItem) -> String = { it.contentId() }
): List<ContinueWatchingItem> {
    val seen = linkedSetOf<String>()
    return items.filter { item ->
        val keys = continueWatchingDedupKeys(item, identityKeyFor(item))
        if (keys.any { key -> key in seen }) {
            false
        } else {
            seen.addAll(keys)
            true
        }
    }
}

private fun continueWatchingDedupKeys(
    item: ContinueWatchingItem,
    primaryKey: String
): List<String> = buildList {
    primaryKey.trim().takeIf { it.isNotEmpty() }?.let(::add)
    seriesParentDedupKey(item, primaryKey)?.let(::add)
    seriesParentDedupKey(item, item.contentId())?.let(::add)
    nextUpTitleEpisodeKey(item)?.let(::add)
}

private fun seriesParentDedupKey(item: ContinueWatchingItem, rawKey: String): String? {
    if (!isSeriesType(item.contentType())) return null
    val normalized = rawKey
        .trim()
        .removePrefix("series:")
        .replace(CONTINUE_WATCHING_EPISODE_SUFFIX, "")
        .takeIf { it.isNotEmpty() }
        ?: return null
    return "series-parent:$normalized"
}

private fun nextUpTitleEpisodeKey(item: ContinueWatchingItem): String? {
    if (item !is ContinueWatchingItem.NextUp) return null
    val normalizedTitle = item.info.name
        .trim()
        .lowercase(Locale.ROOT)
        .replace(Regex("\\s+"), " ")
        .takeIf { it.isNotEmpty() }
        ?: return null
    return "next-up:${item.info.contentType.lowercase(Locale.ROOT)}:$normalizedTitle:s${item.info.season}e${item.info.episode}"
}

/**
 * Resolves projected identity keys for all items in [items].
 *
 * For items whose `contentId` starts with `kitsu:`, the resolver is called with
 * [EpisodeProjectionTarget.CONTINUE_WATCHING] and the resulting
 * `targetCoordinate?.identityKey` is used.  If resolution fails or the item is not
 * kitsu-sourced, the raw `contentId` is returned as the identity key.
 *
 * The returned map has one entry per item index.
 */
internal suspend fun resolveProjectedContinueWatchingIdentityKeys(
    items: List<ContinueWatchingItem>,
    resolver: AnimeSeasonProjectionResolver
): Map<Int, String> {
    val result = mutableMapOf<Int, String>()
    // Indexed iteration to avoid ArrayList$Itr capture in continuation.
    // resolver.resolveWork/resolveEpisodeProjection are suspending and would otherwise pin the items list.
    for (index in items.indices) {
        val item = items[index]
        val contentId = item.contentId()
        val fallbackKey = item.canonicalOrContentKey()
        val season = item.season()
        val episode = item.episode()
        val key = if (
            AnimeStremioId.isExplicitAnimeOnlyId(contentId) &&
            season != null &&
            episode != null
        ) {
            try {
                val kitsuId = contentId.removePrefix("kitsu:")
                val work = resolver.resolveWork(
                    AnimeSourceIdentity(sourceKitsuId = kitsuId, animeStremioId = null)
                )
                val projection = resolver.resolveEpisodeProjection(
                    work = work,
                    sourceEpisode = SourceEpisodeCoordinate(
                        sourceKitsuId = kitsuId,
                        season = season,
                        episode = episode
                    ),
                    target = EpisodeProjectionTarget.CONTINUE_WATCHING
                )
                projection.targetCoordinate?.identityKey
                    ?: projection.sourceKitsuCoordinate.identityKey
            } catch (_: Exception) {
                fallbackKey
            }
        } else {
            fallbackKey
        }
        result[index] = key
    }
    return result
}

internal fun continueWatchingIdentityMetadataRequest(item: ContinueWatchingItem): MetadataRequest =
    MetadataRequest(
        contentId = item.contentId(),
        contentType = ContentType.fromString(item.contentType()),
        sourceContext = MetadataSourceContext(
            itemType = item.contentType(),
            previewStableIds = providerIdsFromContinueWatchingContentId(item.contentId())
        ),
        seasonNumber = item.season(),
        depth = MetadataDepth.IDENTITY
    )

internal fun shouldEnrichContinueWatchingProviderMetadata(
    items: List<ContinueWatchingItem>,
    traktUpNextItems: List<ContinueWatchingItem.NextUp>,
    settings: TmdbSettings
): Boolean {
    if (!settings.useBasicInfo) return false
    if (settings.isActive) return true
    return items.any { item -> isSeriesType(item.contentType()) } ||
        traktUpNextItems.any { item -> isSeriesType(item.contentType()) }
}

internal suspend fun <T, R> mapContinueWatchingEnrichmentWithLimit(
    items: List<T>,
    maxConcurrency: Int,
    transform: suspend (T) -> R
): List<R> = coroutineScope {
    val semaphore = Semaphore(maxConcurrency.coerceAtLeast(1))
    items.map { item ->
        async(Dispatchers.IO) {
            semaphore.withPermit {
                transform(item)
            }
        }
    }.awaitAll()
}

internal fun buildContinueWatchingItemsForSnapshot(
    snapshot: ContinueWatchingSnapshot,
    nowMs: Long
): List<ContinueWatchingItem> {
    if (snapshot.records.isEmpty()) {
        return buildRawContinueWatchingItemsForSnapshot(snapshot, nowMs)
    }

    val timeline = buildMixedContinueWatchingTimeline(
        resumeItems = snapshot.records,
        nextUpItems = visibleContinueWatchingNextUpItems(snapshot),
        resumeRef = ::resumeRefForContinueWatchingRecord,
        nextUpRef = ::canonicalNextUpRefForContinueWatching
    )
    val rawResumeByLookupKey = snapshot.resumeItems.mapNotNull { progress ->
        runCatching { progress.toSafeResumeIdentity().lookupKey() to progress }.getOrNull()
    }.toMap()

    return timeline.mapNotNull { row ->
        when (row) {
            is ContinueWatchingTimelineRow.Resume -> row.value.toContinueWatchingItem(
                rawResumeByLookupKey = rawResumeByLookupKey,
                displayMetadataByItemKey = snapshot.displayMetadataByItemKey
            )
            is ContinueWatchingTimelineRow.NextUp ->
                row.value.toContinueWatchingNextUp(snapshot.displayMetadataByItemKey, nowMs)
        }
    }.filter { item ->
        !item.hasLegacyTekenfilmsContinueWatchingId() &&
            (item !is ContinueWatchingItem.NextUp || item.info.hasAired)
    }.dedupByCanonicalOrContentKey()
}

private fun buildRawContinueWatchingItemsForSnapshot(
    snapshot: ContinueWatchingSnapshot,
    nowMs: Long
): List<ContinueWatchingItem> {
    val timeline = buildMixedContinueWatchingTimeline(
        resumeItems = snapshot.resumeItems,
        nextUpItems = visibleContinueWatchingNextUpItems(snapshot),
        resumeRef = ::resumeRefForContinueWatching,
        nextUpRef = ::nextUpRefForContinueWatching
    )
    return timeline.map { row ->
        when (row) {
            is ContinueWatchingTimelineRow.Resume -> row.value.toContinueWatchingInProgress(snapshot.displayMetadataByItemKey)
            is ContinueWatchingTimelineRow.NextUp -> row.value.toContinueWatchingNextUp(snapshot.displayMetadataByItemKey, nowMs)
        }
    }.filter { item ->
        !item.hasLegacyTekenfilmsContinueWatchingId() &&
            (item !is ContinueWatchingItem.NextUp || item.info.hasAired)
    }.dedupByCanonicalOrContentKey()
}

private fun ContinueWatchingItem.hasLegacyTekenfilmsContinueWatchingId(): Boolean {
    return legacyTekenfilmsContinueWatchingIds().any { id ->
        id.trim().startsWith(LEGACY_TEKENFILMS_ID_PREFIX, ignoreCase = true)
    }
}

private fun ContinueWatchingItem.legacyTekenfilmsContinueWatchingIds(): List<String> {
    return when (this) {
        is ContinueWatchingItem.InProgress -> listOfNotNull(
            progress.contentId,
            progress.videoId,
            streamFetchVideoId
        )
        is ContinueWatchingItem.NextUp -> listOfNotNull(
            info.contentId,
            info.videoId,
            info.streamFetchVideoId
        )
    }
}

private fun visibleContinueWatchingNextUpItems(snapshot: ContinueWatchingSnapshot): List<TrackingNextUpEntry> {
    if (snapshot.traktUpNextItems.isEmpty()) return snapshot.nextUpItems
    val seen = linkedSetOf<String>()
    return (snapshot.nextUpItems + snapshot.traktUpNextItems).filter { entry ->
        seen.add(visibleNextUpEntryKey(entry))
    }
}

private fun visibleNextUpEntryKey(entry: TrackingNextUpEntry): String =
    listOf(
        entry.contentType.trim().lowercase(),
        entry.contentId.trim().lowercase(),
        entry.season.toString(),
        entry.episode.toString()
    ).joinToString("|")

private fun List<ContinueWatchingItem>.dedupByCanonicalOrContentKey(): List<ContinueWatchingItem> {
    val seen = linkedSetOf<String>()
    return filter { item -> seen.add(item.canonicalOrContentKey()) }
}

internal sealed interface ProfileScopedEmission<out T> {
    val session: HomeProfileSession

    data class Loading(
        override val session: HomeProfileSession
    ) : ProfileScopedEmission<Nothing>

    data class Success<T>(
        override val session: HomeProfileSession,
        val value: T
    ) : ProfileScopedEmission<T>

    data class Error(
        override val session: HomeProfileSession,
        val throwable: Throwable
    ) : ProfileScopedEmission<Nothing>
}

@OptIn(ExperimentalCoroutinesApi::class)
internal fun continueWatchingProfileScopedEmissions(
    activeHomeProfileSession: Flow<HomeProfileSession>,
    observeProfileSnapshot: (Int) -> Flow<ContinueWatchingSnapshot>
): Flow<ProfileScopedEmission<ContinueWatchingSnapshot>> {
    return activeHomeProfileSession
        .distinctUntilChangedBy { it.profileSessionKey }
        .flatMapLatest { session ->
            observeProfileSnapshot(session.profileId)
                .map<ContinueWatchingSnapshot, ProfileScopedEmission<ContinueWatchingSnapshot>> { snapshot ->
                    ProfileScopedEmission.Success(session = session, value = snapshot)
                }
                .onStart {
                    emit(ProfileScopedEmission.Loading(session))
                    emit(ProfileScopedEmission.Success(session = session, value = ContinueWatchingSnapshot()))
                }
                .catch { error ->
                    if (error is CancellationException) throw error
                    emit(ProfileScopedEmission.Error(session = session, throwable = error))
                }
        }
}

internal fun HomeViewModel.loadContinueWatchingPipeline() {
    viewModelScope.launch {
        continueWatchingProfileScopedEmissions(
            activeHomeProfileSession = activeHomeProfileSession,
            observeProfileSnapshot = { profileId ->
                continueWatchingSnapshotService.observeProfileSnapshot(profileId)
            }
        ).collectLatest { emission ->
            val session = emission.session
            if (!isCurrentHomeSession(session)) {
                emitStaleContinueWatchingEmission("emission", session)
                Log.d(HomeViewModel.TAG, "Skipping stale continue watching emission")
                return@collectLatest
            }
            when (emission) {
                is ProfileScopedEmission.Loading -> {
                    continueWatchingEnrichmentJob?.cancel()
                    emitContinueWatchingInitialGateState(
                        session = session,
                        state = "loading",
                        reason = "snapshot_observe_started"
                    )
                    _displayContinueWatchingItems.value = emptyList()
                    _uiState.update { state ->
                        state.copy(
                            traktUpNextItems = emptyList(),
                            homeReadiness = HomeInitialReadiness
                                .started(sessionId = session.sessionId, profileId = session.profileId)
                                .markLoading(HomeInitialGate.CONTINUE_WATCHING),
                            initialContinueWatchingResolved = false
                        )
                    }
                }
                is ProfileScopedEmission.Error -> {
                    continueWatchingEnrichmentJob?.cancel()
                    Log.w(
                        HomeViewModel.TAG,
                        "Continue watching snapshot failed: ${emission.throwable.message}"
                    )
                    emitContinueWatchingInitialGateState(
                        session = session,
                        state = "failed_nonblocking",
                        reason = "snapshot_error"
                    )
                    _displayContinueWatchingItems.value = emptyList()
                    _uiState.update { state ->
                        state.copy(
                            traktUpNextItems = emptyList(),
                            homeReadiness = state.homeReadiness
                                .forHomeSession(session)
                                .markFailedNonBlocking(HomeInitialGate.CONTINUE_WATCHING, "snapshot_error"),
                            initialContinueWatchingResolved = true
                        )
                    }
                }
                is ProfileScopedEmission.Success -> {
                    applyContinueWatchingSnapshotForSession(session, emission.value)
                }
            }
        }
    }
}

private fun HomeInitialReadiness.forHomeSession(session: HomeProfileSession): HomeInitialReadiness {
    return if (sessionId == session.sessionId && profileId == session.profileId) {
        this
    } else {
        HomeInitialReadiness.started(sessionId = session.sessionId, profileId = session.profileId)
    }
}

private fun HomeInitialReadiness.markContinueWatchingGateResolved(
    session: HomeProfileSession,
    reason: String
): HomeInitialReadiness {
    return forHomeSession(session)
        .markResolved(HomeInitialGate.CONTINUE_WATCHING, reason)
}

private fun HomeViewModel.emitContinueWatchingInitialGateState(
    session: HomeProfileSession,
    state: String,
    reason: String
) {
    traceEvents.emitHomeInitialGateStateChanged(
        profileId = session.profileId,
        sessionId = session.sessionId,
        gate = HomeInitialGate.CONTINUE_WATCHING.name,
        state = state,
        reason = reason
    )
}

private fun HomeViewModel.emitStaleContinueWatchingEmission(
    source: String,
    session: HomeProfileSession
) {
    traceEvents.emitHomeProfileEmissionIgnoredStale(
        source = source,
        profileId = session.profileId,
        sessionId = session.sessionId
    )
}

private fun HomeViewModel.emitContinueWatchingResolvedGateTransition(
    session: HomeProfileSession,
    before: HomeInitialReadiness,
    after: HomeInitialReadiness,
    reason: String
) {
    if (
        !before.isResolved(HomeInitialGate.CONTINUE_WATCHING) &&
        after.profileId == session.profileId &&
        after.sessionId == session.sessionId &&
        after.isResolved(HomeInitialGate.CONTINUE_WATCHING)
    ) {
        emitContinueWatchingInitialGateState(
            session = session,
            state = "resolved",
            reason = reason
        )
    }
}

private suspend fun HomeViewModel.applyContinueWatchingSnapshotForSession(
    session: HomeProfileSession,
    snapshot: ContinueWatchingSnapshot
) {
    if (!isCurrentHomeSession(session)) {
        emitStaleContinueWatchingEmission("snapshot_apply", session)
        Log.d(HomeViewModel.TAG, "Skipping stale continue watching snapshot")
        return
    }
    continueWatchingEnrichmentJob?.cancel()
    continueWatchingEnrichmentJob = null
    continueWatchingSnapshotVersion += 1L
    val snapshotVersion = continueWatchingSnapshotVersion

    val nowMs = System.currentTimeMillis()
    val rawItems = buildContinueWatchingItemsForSnapshot(snapshot, nowMs)
    // Apply anime projection dedup: kitsu:X S3E1 and tvdb:Y S3E1 that map to the same
    // projected coordinate collapse to one entry, eliminating cross-source duplicates.
    val projectedKeys = try {
        resolveProjectedContinueWatchingIdentityKeys(rawItems, animeSeasonProjectionResolver)
    } catch (_: Exception) {
        emptyMap<Int, String>()
    }
    val itemsWithoutOverlaySidecars = dedupContinueWatchingByProjectedIdentity(rawItems) { item ->
        val idx = rawItems.indexOfFirst { it === item }
        projectedKeys[idx] ?: item.canonicalOrContentKey()
    }
    val items = withContinueWatchingOverlaySidecars(
        preserveContinueWatchingEpisodeText(
            incoming = itemsWithoutOverlaySidecars,
            current = _displayContinueWatchingItems.value
        )
    )
    val traktUpNextRawItems = snapshot.traktUpNextItems.map { entry ->
        entry.toContinueWatchingNextUp(snapshot.displayMetadataByItemKey, nowMs)
    }.filter { it.info.hasAired }
    val traktUpNextItemsWithoutOverlaySidecars = preserveContinueWatchingEpisodeText(
        incoming = traktUpNextRawItems,
        current = _uiState.value.traktUpNextItems
    )
    val traktUpNextItems = withContinueWatchingOverlaySidecars(traktUpNextItemsWithoutOverlaySidecars)
        .filterIsInstance<ContinueWatchingItem.NextUp>()

    if (!isCurrentHomeSession(session)) {
        emitStaleContinueWatchingEmission("publish", session)
        Log.d(HomeViewModel.TAG, "Skipping stale continue watching publish")
        return
    }
    if (snapshotVersion != continueWatchingSnapshotVersion) {
        emitStaleContinueWatchingEmission("publish_superseded", session)
        Log.d(HomeViewModel.TAG, "Skipping superseded continue watching publish")
        return
    }

    val readinessReason = if (items.isEmpty() && traktUpNextItems.isEmpty()) {
        "first_snapshot_empty"
    } else {
        "first_snapshot"
    }
    val readinessBeforePublish = _uiState.value.homeReadiness
    // Capture the pre-write CW equality BEFORE we publish to the StateFlow —
    // otherwise the dedupe predicate inside the _uiState.update lambda below
    // would compare items to themselves (always true) and the readiness /
    // initialContinueWatchingResolved side-effects would fire on no-op CW
    // changes that should have short-circuited.
    val cwUnchanged = _displayContinueWatchingItems.value == items
    if (snapshotVersion == continueWatchingSnapshotVersion && !cwUnchanged) {
        _displayContinueWatchingItems.value = items
    }
    _uiState.update { state ->
        if (snapshotVersion != continueWatchingSnapshotVersion) return@update state
        if (
            cwUnchanged &&
            state.traktUpNextItems == traktUpNextItems &&
            state.initialContinueWatchingResolved &&
            state.homeReadiness.isResolved(HomeInitialGate.CONTINUE_WATCHING)
        ) {
            state
        } else {
            state.copy(
                traktUpNextItems = traktUpNextItems,
                homeReadiness = state.homeReadiness.markContinueWatchingGateResolved(
                    session = session,
                    reason = readinessReason
                ),
                initialContinueWatchingResolved = true
            )
        }
    }
    emitContinueWatchingResolvedGateTransition(
        session = session,
        before = readinessBeforePublish,
        after = _uiState.value.homeReadiness,
        reason = readinessReason
    )

    val settings = currentTmdbSettings
    if (
        shouldEnrichContinueWatchingProviderMetadata(items, traktUpNextItems, settings) &&
        isNonPlaybackHomeWorkAllowed()
    ) {
        continueWatchingEnrichmentJob = viewModelScope.launch {
            try {
                if (!isCurrentHomeSession(session)) {
                    emitStaleContinueWatchingEmission("enrichment_start", session)
                    return@launch
                }
                if (snapshotVersion != continueWatchingSnapshotVersion) {
                    emitStaleContinueWatchingEmission("enrichment_start_superseded", session)
                    return@launch
                }
                if (!isNonPlaybackHomeWorkAllowed()) return@launch
                val enrichedItems = withContinueWatchingOverlaySidecars(
                    enrichContinueWatchingItems(items, settings)
                )
                if (!isCurrentHomeSession(session)) {
                    emitStaleContinueWatchingEmission("enrichment_items", session)
                    return@launch
                }
                if (snapshotVersion != continueWatchingSnapshotVersion) {
                    emitStaleContinueWatchingEmission("enrichment_items_superseded", session)
                    return@launch
                }
                if (!isNonPlaybackHomeWorkAllowed()) return@launch
                val enrichedTraktItems = withContinueWatchingOverlaySidecars(
                    enrichContinueWatchingNextUpItems(traktUpNextItems, settings)
                ).filterIsInstance<ContinueWatchingItem.NextUp>()
                if (!isCurrentHomeSession(session)) {
                    emitStaleContinueWatchingEmission("enrichment", session)
                    Log.d(HomeViewModel.TAG, "Skipping stale continue watching enrichment")
                    return@launch
                }
                if (snapshotVersion != continueWatchingSnapshotVersion) {
                    emitStaleContinueWatchingEmission("enrichment_superseded", session)
                    Log.d(HomeViewModel.TAG, "Skipping superseded continue watching enrichment")
                    return@launch
                }
                val readinessBeforeEnrichedPublish = _uiState.value.homeReadiness
                // Same dedupe-before-write pattern as applyContinueWatchingSnapshotForSession;
                // the inner predicate must compare against the pre-write value.
                val cwUnchanged = _displayContinueWatchingItems.value == enrichedItems
                if (snapshotVersion == continueWatchingSnapshotVersion && !cwUnchanged) {
                    _displayContinueWatchingItems.value = enrichedItems
                }
                _uiState.update { state ->
                    if (snapshotVersion != continueWatchingSnapshotVersion) return@update state
                    if (
                        cwUnchanged &&
                        state.traktUpNextItems == enrichedTraktItems &&
                        state.initialContinueWatchingResolved &&
                        state.homeReadiness.isResolved(HomeInitialGate.CONTINUE_WATCHING)
                    ) {
                        state
                    } else {
                        state.copy(
                            traktUpNextItems = enrichedTraktItems,
                            homeReadiness = state.homeReadiness.markContinueWatchingGateResolved(
                                session = session,
                                reason = readinessReason
                            ),
                            initialContinueWatchingResolved = true
                        )
                    }
                }
                emitContinueWatchingResolvedGateTransition(
                    session = session,
                    before = readinessBeforeEnrichedPublish,
                    after = _uiState.value.homeReadiness,
                    reason = readinessReason
                )
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(HomeViewModel.TAG, "Continue watching metadata enrichment failed: ${e.message}")
            }
        }
    }
}

private suspend fun HomeViewModel.withContinueWatchingOverlaySidecars(
    items: List<ContinueWatchingItem>
): List<ContinueWatchingItem> {
    if (items.isEmpty()) return items
    val itemKeys = items.mapTo(linkedSetOf()) { item ->
        homeDisplayItemKey(item.contentType(), item.contentId())
    }
    val overlays = runCatching {
        withContext(Dispatchers.IO) {
            hydratedHomeOverlayStore.readForItemKeys(
                itemKeys = itemKeys,
                languageTag = profileBoundary.currentLanguageTag(),
                policyVersion = CONTINUE_WATCHING_OVERLAY_POLICY_VERSION
            )
        }
    }.onFailure { error ->
        Log.d(HomeViewModel.TAG, "Unable to read hydrated overlay sidecars for Continue Watching", error)
    }.getOrDefault(emptyMap())
    if (overlays.isEmpty()) return items
    return mergeContinueWatchingOverlaySidecars(items, overlays)
}

internal fun mergeContinueWatchingOverlaySidecars(
    items: List<ContinueWatchingItem>,
    overlaysByItemKey: Map<String, HydratedHomeOverlay>
): List<ContinueWatchingItem> {
    if (items.isEmpty() || overlaysByItemKey.isEmpty()) return items
    return items.map { item ->
        val overlay = overlaysByItemKey[homeDisplayItemKey(item.contentType(), item.contentId())]
            ?: return@map item
        item.withOverlaySidecar(overlay)
    }
}

private fun ContinueWatchingItem.withOverlaySidecar(
    overlay: HydratedHomeOverlay
): ContinueWatchingItem {
    val current = displayMetadata()
    val merged = current.copy(
        imdbId = current.imdbId ?: overlay.imdbId ?: overlay.fields.imdbId,
        originalLanguage = current.originalLanguage ?: overlay.fields.originalLanguage,
        runtime = current.runtime ?: overlay.fields.runtime,
        releaseInfo = current.releaseInfo ?: overlay.fields.releaseInfo,
        imdbRating = current.imdbRating ?: overlay.fields.imdbRating,
        ratingSource = current.ratingSource ?: overlay.fields.ratingSource
    )
    return when (this) {
        is ContinueWatchingItem.InProgress -> copy(displayMetadata = merged)
        is ContinueWatchingItem.NextUp -> copy(info = info.copy(displayMetadata = merged))
    }
}

private const val CONTINUE_WATCHING_OVERLAY_POLICY_VERSION = 1

internal suspend fun HomeViewModel.enrichContinueWatchingItems(
    items: List<ContinueWatchingItem>,
    settings: TmdbSettings
): List<ContinueWatchingItem> {
    if (!isNonPlaybackHomeWorkAllowed()) return items
    return mapContinueWatchingEnrichmentWithLimit(
        items = items,
        maxConcurrency = HomeViewModel.CONTINUE_WATCHING_ENRICHMENT_CONCURRENCY
    ) { item ->
        if (!isNonPlaybackHomeWorkAllowed()) {
            item
        } else {
            enrichContinueWatchingItemWithProvider(item)
        }
    }
}

internal suspend fun HomeViewModel.enrichContinueWatchingNextUpItems(
    items: List<ContinueWatchingItem.NextUp>,
    settings: TmdbSettings
): List<ContinueWatchingItem.NextUp> {
    if (!isNonPlaybackHomeWorkAllowed()) return items
    return mapContinueWatchingEnrichmentWithLimit(
        items = items,
        maxConcurrency = HomeViewModel.CONTINUE_WATCHING_ENRICHMENT_CONCURRENCY
    ) { item ->
        if (!isNonPlaybackHomeWorkAllowed()) {
            item
        } else {
            enrichContinueWatchingItemWithProvider(item) as? ContinueWatchingItem.NextUp ?: item
        }
    }
}

internal suspend fun HomeViewModel.enrichContinueWatchingItemWithProvider(
    item: ContinueWatchingItem
): ContinueWatchingItem {
    val contentId = when (item) {
        is ContinueWatchingItem.InProgress -> item.progress.contentId
        is ContinueWatchingItem.NextUp -> item.info.contentId
    }
    val metadataLanguage = profileBoundary.currentLanguageTag()
    val isTvdbContent = contentId.startsWith("tvdb:", ignoreCase = true)
    return try {
        val providerPreview = item.toContinueWatchingProviderPreview()
        val localizedPreview = if (isTvdbContent) {
            providerPreview
        } else {
            overlayProviderLocalizedMetadataForHome(
                item = providerPreview,
                fallbackContentId = item.providerFallbackContentId(),
                providerLocalizedMetadataResolver = providerLocalizedMetadataResolver,
                profileBoundary = profileBoundary
            )
        }
        val localizedEpisodeMetadata = localizedContinueWatchingEpisodeMetadata(
            metadataRouterFacade = metadataRouterFacade,
            item = item,
            language = metadataLanguage
        )

        val existing = when (item) {
            is ContinueWatchingItem.InProgress -> item.displayMetadata
            is ContinueWatchingItem.NextUp -> item.info.displayMetadata
        }

        // Phase 3.8 — rank-aware slot merge replacing the prior
        // `localizedPreview.toHomeDisplayMetadata().coalesceWith(existing)`.
        // The slot-conversion helpers in SlotConversions.kt coerce null/blank
        // fields to DisplaySourceRank.EMPTY, so this preserves the
        // localizedPreview-wins-on-non-null semantic of coalesceWith while
        // routing through the typed-slot rank machinery.
        val enrichedMetadata = run {
            val nowMs = System.currentTimeMillis()
            val previewMetadata = localizedPreview.toHomeDisplayMetadata()
            val previewSlots = previewMetadata.toResolvedFieldSlots(
                nowMs = nowMs,
                rank = DisplaySourceRank.STALE_RESOLVED,
            )
            val existingSlots = existing?.toResolvedFieldSlots(
                nowMs = nowMs,
                rank = DisplaySourceRank.FIRST_PAINT,
            )
            HomeRailProjectionReducer.reduce(
                firstPaint = existingSlots ?: previewSlots,
                overlay = if (existingSlots != null) previewSlots else null,
                existing = null,
                profile = null,
            ).toHomeDisplayMetadata().let { reduced ->
                val currentImdb = existing?.imdbId?.trim()?.takeIf { it.isNotEmpty() }
                reduced.copy(
                    originalLanguage = previewMetadata.originalLanguage ?: existing?.originalLanguage,
                    imdbId = if (currentImdb != null && currentImdb != reduced.imdbId) {
                        currentImdb
                    } else {
                        previewMetadata.imdbId ?: reduced.imdbId
                    },
                    tomatoesRating = previewMetadata.tomatoesRating ?: existing?.tomatoesRating
                )
            }
        }

        when (item) {
            is ContinueWatchingItem.InProgress -> item.copy(
                displayMetadata = enrichedMetadata,
                episodeDescription = localizedEpisodeMetadata?.overview
                    ?: enrichedMetadata.description
                    ?: item.episodeDescription,
                genres = enrichedMetadata.genres.ifEmpty { item.genres },
                releaseInfo = enrichedMetadata.releaseInfo ?: item.releaseInfo
            )
            is ContinueWatchingItem.NextUp -> item.copy(
                info = item.info.copy(
                    displayMetadata = enrichedMetadata,
                    name = enrichedMetadata.title?.takeIf { it.isNotBlank() } ?: item.info.name,
                    poster = enrichedMetadata.displayPoster,
                    backdrop = enrichedMetadata.displayBackdrop,
                    logo = enrichedMetadata.displayLogo,
                    episodeTitle = localizedEpisodeMetadata?.title ?: item.info.episodeTitle,
                    episodeDescription = localizedEpisodeMetadata?.overview
                        ?: enrichedMetadata.description
                        ?: item.info.episodeDescription,
                    genres = enrichedMetadata.genres.ifEmpty { item.info.genres },
                    releaseInfo = enrichedMetadata.releaseInfo ?: item.info.releaseInfo
                )
            )
        }
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        // F2-G-04: broad catch is intentional — best-effort enrichment; UI prefers stale display
        // metadata over crashing the row. NPE from null injectable in test harness is logged but
        // not rethrown (safe in correctly-wired production where Hilt guarantees non-null).
        Log.w(HomeViewModel.TAG, "Provider enrichment failed for continue watching item $contentId: ${e.message}")
        item
    }
}

internal suspend fun HomeViewModel.recordContinueWatchingRouteContextForPlayback(
    item: ContinueWatchingItem
) {
    try {
        val contentType = ContentType.fromString(item.contentType())
        val route = metadataRouterFacade.routeRequest(
            MetadataRequest(
                contentId = item.contentId(),
                contentType = contentType,
                sourceContext = MetadataSourceContext(
                    itemType = item.contentType(),
                    addonMetadata = item.displayMetadata()
                ),
                seasonNumber = item.season(),
                depth = MetadataDepth.DETAIL_CORE
            )
        )
        continueWatchingSnapshotService.recordMetadataSnapshot(
            itemKey = homeDisplayItemKey(item.contentType(), item.contentId()),
            metadataSnapshot = ContinueWatchingMetadataSnapshot.fromRoute(
                route = route,
                clickTimeSlots = item.displayMetadata().toResolvedFieldSlots(
                    nowMs = System.currentTimeMillis(),
                    rank = DisplaySourceRank.FIRST_PAINT,
                )
            )
        )
    } catch (e: CancellationException) {
        throw e
    } catch (_: Exception) {
        // Playback navigation should not be blocked by route-context persistence.
    }
}

private fun ContinueWatchingItem.toContinueWatchingProviderPreview(): MetaPreview {
    return when (this) {
        is ContinueWatchingItem.InProgress -> {
            val displayMetadata = displayMetadata()
            MetaPreview(
                id = progress.contentId,
                type = ContentType.fromString(progress.contentType),
                rawType = progress.contentType,
                name = displayMetadata.title ?: progress.name,
                poster = displayMetadata.displayPoster,
                posterShape = PosterShape.LANDSCAPE,
                background = displayMetadata.displayBackdrop,
                logo = displayMetadata.displayLogo,
                description = displayMetadata.description ?: episodeDescription ?: progress.episodeTitle,
                releaseInfo = displayMetadata.releaseInfo ?: releaseInfo,
                runtime = displayMetadata.runtime,
                imdbRating = displayMetadata.imdbRating ?: episodeImdbRating,
                tomatoesRating = displayMetadata.tomatoesRating,
                genres = displayMetadata.genres.takeIf { it.isNotEmpty() } ?: genres,
                language = null,
                posterProviderTag = displayMetadata.posterProviderTag,
                artwork = displayMetadata.toArtworkBundleFromDisplayFields(),
                firstPaintStableIds = providerIdsForContinueWatchingProviderPreview(
                    contentId = progress.contentId,
                    displayMetadata = displayMetadata
                )
            )
        }

        is ContinueWatchingItem.NextUp -> {
            val displayMetadata = displayMetadata()
            MetaPreview(
                id = info.contentId,
                type = ContentType.fromString(info.contentType),
                rawType = info.contentType,
                name = displayMetadata.title ?: info.name,
                poster = displayMetadata.displayPoster,
                posterShape = PosterShape.LANDSCAPE,
                background = displayMetadata.displayBackdrop,
                logo = displayMetadata.displayLogo,
                description = displayMetadata.description ?: info.episodeDescription ?: info.episodeTitle,
                releaseInfo = displayMetadata.releaseInfo ?: info.releaseInfo ?: info.released,
                runtime = displayMetadata.runtime,
                imdbRating = displayMetadata.imdbRating ?: info.imdbRating,
                tomatoesRating = displayMetadata.tomatoesRating,
                genres = displayMetadata.genres.takeIf { it.isNotEmpty() } ?: info.genres,
                language = null,
                posterProviderTag = displayMetadata.posterProviderTag,
                artwork = displayMetadata.toArtworkBundleFromDisplayFields(),
                firstPaintStableIds = providerIdsForContinueWatchingProviderPreview(
                    contentId = info.contentId,
                    displayMetadata = displayMetadata
                )
            )
        }
    }
}

private fun providerIdsForContinueWatchingProviderPreview(
    contentId: String,
    displayMetadata: HomeDisplayMetadata
): ProviderIds {
    val ids = providerIdsFromContinueWatchingContentId(contentId)
    val displayImdb = displayMetadata.imdbId
        ?.trim()
        ?.takeIf { it.startsWith("tt", ignoreCase = true) }
    return if (displayImdb != null && ids.imdb.isNullOrBlank()) {
        ids.copy(imdb = displayImdb)
    } else {
        ids
    }
}

internal data class LocalizedContinueWatchingEpisodeMetadata(
    val title: String?,
    val overview: String?
)

internal suspend fun localizedContinueWatchingEpisodeMetadata(
    metadataRouterFacade: com.nexio.tv.core.metadata.router.MetadataRouterFacade,
    item: ContinueWatchingItem,
    language: String? = null
): LocalizedContinueWatchingEpisodeMetadata? {
    val season = item.season() ?: return null
    val episode = item.episode() ?: return null
    if (!isSeriesType(item.contentType())) return null
    if (item.contentId().startsWith("tvdb:", ignoreCase = true)) return null

    val episodeMetadata = metadataRouterFacade.fetchTvEpisodeEnrichment(
        metadataRequest = MetadataRequest(
            contentId = item.contentId(),
            contentType = ContentType.fromString(item.contentType()),
            sourceContext = MetadataSourceContext(itemType = item.contentType()),
            language = language,
            seasonNumber = season,
            depth = MetadataDepth.SEASON
        ),
        tvRequest = TvMetadataRequest(
            contentId = item.contentId(),
            fallbackContentId = item.providerFallbackContentId(),
            contentType = ContentType.fromString(item.contentType()),
            language = language,
            seasonNumbers = listOf(season)
        )
    ).value?.get(season to episode) ?: return null

    val title = episodeMetadata.title?.takeIf { it.isNotBlank() }
    val overview = episodeMetadata.overview?.takeIf { it.isNotBlank() }
    if (title == null && overview == null) return null
    return LocalizedContinueWatchingEpisodeMetadata(
        title = title,
        overview = overview
    )
}

private fun ContinueWatchingItem.providerFallbackContentId(): String {
    return when (this) {
        is ContinueWatchingItem.InProgress -> progress.videoId
        is ContinueWatchingItem.NextUp -> info.videoId
    }
}

internal fun preserveContinueWatchingEpisodeText(
    incoming: List<ContinueWatchingItem>,
    current: List<ContinueWatchingItem>
): List<ContinueWatchingItem> {
    if (incoming.isEmpty() || current.isEmpty()) return incoming

    val currentByKey = HashMap<ContinueWatchingEpisodeTextKey, ContinueWatchingItem>(current.size)
    for (index in current.indices) {
        val item = current[index]
        val keys = item.episodeTextKeys()
        for (keyIndex in keys.indices) {
            currentByKey[keys[keyIndex]] = item
        }
    }
    if (currentByKey.isEmpty()) return incoming

    var changed = false
    val merged = ArrayList<ContinueWatchingItem>(incoming.size)
    for (index in incoming.indices) {
        val item = incoming[index]
        val currentItem = item.episodeTextKeys().firstNotNullOfOrNull { key -> currentByKey[key] }
        val next = item.preserveEpisodeTextFrom(currentItem)
        if (next !== item) changed = true
        merged += next
    }
    return if (changed) merged else incoming
}

private data class ContinueWatchingEpisodeTextKey(
    val itemKind: String,
    val contentType: String,
    val identity: String,
    val season: Int,
    val episode: Int
)

private fun ContinueWatchingItem.episodeTextKeys(): List<ContinueWatchingEpisodeTextKey> {
    val season = season() ?: return emptyList()
    val episode = episode() ?: return emptyList()
    val itemKind = when (this) {
        is ContinueWatchingItem.InProgress -> "in_progress"
        is ContinueWatchingItem.NextUp -> "next_up"
    }
    val contentType = contentType().trim().lowercase(Locale.ROOT)
    val identities = linkedSetOf<String>()
    canonicalOrContentKey()
        .trim()
        .lowercase(Locale.ROOT)
        .takeIf { it.isNotEmpty() }
        ?.let(identities::add)
    contentId()
        .trim()
        .lowercase(Locale.ROOT)
        .takeIf { it.isNotEmpty() }
        ?.let(identities::add)
    if (identities.isEmpty()) return emptyList()

    val keys = ArrayList<ContinueWatchingEpisodeTextKey>(identities.size)
    for (identity in identities) {
        keys += ContinueWatchingEpisodeTextKey(
            itemKind = itemKind,
            contentType = contentType,
            identity = identity,
            season = season,
            episode = episode
        )
    }
    return keys
}

private fun ContinueWatchingItem.preserveEpisodeTextFrom(
    current: ContinueWatchingItem?
): ContinueWatchingItem {
    if (current == null) return this
    return when {
        this is ContinueWatchingItem.NextUp && current is ContinueWatchingItem.NextUp -> {
            val title = current.info.episodeTitle.preferredEpisodeTextOver(info.episodeTitle)
            val description = current.info.episodeDescription.preferredEpisodeTextOver(info.episodeDescription)
            if (title == info.episodeTitle && description == info.episodeDescription) {
                this
            } else {
                copy(
                    info = info.copy(
                        episodeTitle = title,
                        episodeDescription = description
                    )
                )
            }
        }
        this is ContinueWatchingItem.InProgress && current is ContinueWatchingItem.InProgress -> {
            val title = current.progress.episodeTitle.preferredEpisodeTextOver(progress.episodeTitle)
            val description = current.episodeDescription.preferredEpisodeTextOver(episodeDescription)
            if (title == progress.episodeTitle && description == episodeDescription) {
                this
            } else {
                copy(
                    progress = progress.copy(episodeTitle = title),
                    episodeDescription = description
                )
            }
        }
        else -> this
    }
}

private fun String?.preferredEpisodeTextOver(fallback: String?): String? {
    val current = this?.trim()?.takeIf { it.isNotEmpty() }
    return current ?: fallback
}

private fun parseEpisodeReleaseDate(raw: String?): LocalDate? {
    if (raw.isNullOrBlank()) return null
    val value = raw.trim()
    val zone = ZoneId.systemDefault()

    return runCatching {
        Instant.parse(value).atZone(zone).toLocalDate()
    }.getOrNull() ?: runCatching {
        OffsetDateTime.parse(value).toInstant().atZone(zone).toLocalDate()
    }.getOrNull() ?: runCatching {
        LocalDateTime.parse(value).toLocalDate()
    }.getOrNull() ?: runCatching {
        LocalDate.parse(value)
    }.getOrNull() ?: runCatching {
        val datePortion = Regex("\\b\\d{4}-\\d{2}-\\d{2}\\b").find(value)?.value
            ?: return@runCatching null
        LocalDate.parse(datePortion)
    }.getOrNull()
}

private fun formatEpisodeAirDateLabel(releaseDate: LocalDate): String {
    val todayLocal = LocalDate.now(ZoneId.systemDefault())
    val formatter = if (releaseDate.year == todayLocal.year) {
        DateTimeFormatter.ofPattern("MMM d", Locale.getDefault())
    } else {
        DateTimeFormatter.ofPattern("MMM d, yyyy", Locale.getDefault())
    }
    return releaseDate.format(formatter)
}

/**
 * When TVDB provides an exact air-time instant, format it as device-local date + 24h time
 * (e.g., "Apr 23, 03:00"). This gives users a clear visual indicator that TVDB timing is
 * active and shows the converted local release time.
 *
 * Falls back to null when no TVDB instant is available (caller should use date-only label).
 */
private fun formatDeviceLocalAirLabel(tvdbInstantMs: Long?, deviceLocalDateTimeStr: String?): String? {
    if (tvdbInstantMs == null || tvdbInstantMs <= 0L) return null

    val deviceZone = ZoneId.systemDefault()
    val deviceLocal: ZonedDateTime = (
        if (!deviceLocalDateTimeStr.isNullOrBlank()) {
            runCatching { ZonedDateTime.parse(deviceLocalDateTimeStr).withZoneSameInstant(deviceZone) }.getOrNull()
        } else null
    ) ?: runCatching { Instant.ofEpochMilli(tvdbInstantMs).atZone(deviceZone) }.getOrNull()
        ?: return null

    val todayLocal = LocalDate.now(deviceZone)
    val pattern = if (deviceLocal.toLocalDate().year == todayLocal.year) "MMM d, HH:mm" else "MMM d yyyy, HH:mm"
    return deviceLocal.format(DateTimeFormatter.ofPattern(pattern, Locale.getDefault()))
}

private fun nextUpDismissKey(contentId: String): String {
    return contentId.trim()
}

internal fun shouldClearShowProgressForContinueWatchingRemoval(
    contentType: String?,
    season: Int?,
    episode: Int?,
    isNextUp: Boolean
): Boolean {
    if (isNextUp) return true
    if (isSeriesType(contentType)) return true
    return contentType.isNullOrBlank() && season != null && episode != null
}

internal fun HomeViewModel.removeContinueWatchingPipeline(
    contentId: String,
    season: Int? = null,
    episode: Int? = null,
    isNextUp: Boolean = false
) {
    val matchingItem = _displayContinueWatchingItems.value.firstOrNull { item ->
        nextUpDismissKey(item.contentId()) == nextUpDismissKey(contentId) &&
            (season == null || item.season() == season) &&
            (episode == null || item.episode() == episode)
    }
    val shouldClearShow = shouldClearShowProgressForContinueWatchingRemoval(
        contentType = matchingItem?.contentType(),
        season = season,
        episode = episode,
        isNextUp = isNextUp
    )

    if (shouldClearShow) {
        val targetId = nextUpDismissKey(contentId)
        val filteredContinueWatching = _displayContinueWatchingItems.value.filterNot { item ->
            nextUpDismissKey(item.contentId()) == targetId
        }
        if (_displayContinueWatchingItems.value != filteredContinueWatching) {
            _displayContinueWatchingItems.value = filteredContinueWatching
        }
        _uiState.update { state ->
            state.copy(
                traktUpNextItems = state.traktUpNextItems.filterNot { item ->
                    nextUpDismissKey(item.info.contentId) == targetId
                }
            )
        }
        viewModelScope.launch {
            continueWatchingSnapshotService.removeAllForShow(targetId)
            continueWatchingSnapshotService.removeShowOptimistically(targetId)
            runCatching {
                watchProgressRepository.clearShowProgress(profileManager.activeProfileSession.value, targetId)
                continueWatchingSnapshotService.ensureFresh(force = true)
            }.onFailure { error ->
                if (error is CancellationException) throw error
                Log.w(HomeViewModel.TAG, "Failed to clear show progress for $targetId", error)
            }
        }
        return
    }
    // For InProgress items: find the matching resume entry for optimistic removal + rollback.
    val capturedProgress = _displayContinueWatchingItems.value
        .filterIsInstance<ContinueWatchingItem.InProgress>()
        .firstOrNull { item ->
            item.progress.contentId == contentId &&
                (season == null || item.progress.season == season) &&
                (episode == null || item.progress.episode == episode)
        }?.progress
    viewModelScope.launch {
        Log.d(
            HomeViewModel.TAG,
            "removeContinueWatching requested contentId=$contentId season=$season episode=$episode isNextUp=$isNextUp"
        )
        if (capturedProgress != null) {
            continueWatchingSnapshotService.removeResumeEntry(capturedProgress.videoId)
        }
        runCatching {
            watchProgressRepository.removeProgress(
                profileSession = profileManager.activeProfileSession.value,
                contentId = contentId,
                season = season,
                episode = episode
            )
            continueWatchingSnapshotService.ensureFresh(force = true)
        }.onFailure { error ->
            if (error is CancellationException) throw error
            if (capturedProgress != null) {
                runCatching { continueWatchingSnapshotService.reinsertResumeEntry(capturedProgress) }
                    .onFailure {
                        if (it is CancellationException) throw it
                        Log.w(HomeViewModel.TAG, "Failed to rollback removeResumeEntry", it)
                    }
            }
            Log.w(HomeViewModel.TAG, "Failed to remove continue watching progress for $contentId", error)
        }
    }
}

internal fun HomeViewModel.markContinueWatchingAsWatchedPipeline(item: ContinueWatchingItem) {
    val nextUpTargetId: String? = if (item is ContinueWatchingItem.NextUp) {
        val targetId = nextUpDismissKey(item.info.contentId)
        val filteredContinueWatching = _displayContinueWatchingItems.value.filterNot { current ->
            current is ContinueWatchingItem.NextUp &&
                nextUpDismissKey(current.info.contentId) == targetId
        }
        if (_displayContinueWatchingItems.value != filteredContinueWatching) {
            _displayContinueWatchingItems.value = filteredContinueWatching
        }
        _uiState.update { state ->
            state.copy(
                traktUpNextItems = state.traktUpNextItems.filterNot { current ->
                    nextUpDismissKey(current.info.contentId) == targetId
                }
            )
        }
        targetId
    } else {
        null
    }

    val episodeRef = when (item) {
        is ContinueWatchingItem.InProgress -> {
            val p = item.progress
            if (p.season != null && p.episode != null) {
                listOf(ContinueWatchingSnapshotService.EpisodeRef(p.contentId, p.season, p.episode))
            } else emptyList()
        }
        is ContinueWatchingItem.NextUp -> {
            val info = item.info
            listOf(ContinueWatchingSnapshotService.EpisodeRef(info.contentId, info.season, info.episode))
        }
    }
    val capturedProgress = (item as? ContinueWatchingItem.InProgress)?.progress

    viewModelScope.launch {
        if (nextUpTargetId != null) {
            continueWatchingSnapshotService.removeShowOptimistically(nextUpTargetId)
        }
        if (episodeRef.isNotEmpty()) {
            continueWatchingSnapshotService.applyEpisodesMarked(episodeRef)
        }
        try {
            val now = System.currentTimeMillis()
            val progress = when (item) {
                is ContinueWatchingItem.InProgress -> item.progress
                is ContinueWatchingItem.NextUp -> WatchProgress(
                    contentId = item.info.contentId,
                    contentType = item.info.contentType,
                    name = item.info.name,
                    poster = item.displayMetadata().displayPoster,
                    backdrop = item.displayMetadata().displayBackdrop,
                    logo = item.displayMetadata().displayLogo,
                    videoId = item.info.videoId,
                    season = item.info.season,
                    episode = item.info.episode,
                    episodeTitle = item.info.episodeTitle,
                    position = 1L,
                    duration = 1L,
                    lastWatched = now,
                    progressPercent = 100f
                )
            }
            watchProgressRepository.markAsCompleted(profileManager.activeProfileSession.value, progress)
        } catch (error: CancellationException) {
            throw error
        } catch (error: Throwable) {
            Log.w(HomeViewModel.TAG, "Failed to mark continue-watching item as watched", error)
            if (episodeRef.isNotEmpty()) {
                val rollback = listOfNotNull(capturedProgress)
                runCatching { continueWatchingSnapshotService.rollbackEpisodes(rollback) }
                    .onFailure {
                        if (it is CancellationException) throw it
                        Log.w(HomeViewModel.TAG, "Failed to rollback applyEpisodesMarked", it)
                    }
            }
        } finally {
            runCatching {
                continueWatchingSnapshotService.ensureFresh(force = true)
            }.onFailure { error ->
                if (error is CancellationException) throw error
                Log.w(HomeViewModel.TAG, "Failed to refresh continue-watching snapshot after mark watched", error)
            }
        }
    }
}

internal fun HomeViewModel.checkInContinueWatchingPipeline(item: ContinueWatchingItem) {
    viewModelScope.launch {
        val scrobbleItem = buildTrackingScrobbleItemForContinueWatching(item)
        if (scrobbleItem == null) {
            Log.d(HomeViewModel.TAG, "Skipped tracking check-in: missing/unsupported IDs for item=$item")
            return@launch
        }
        runCatching {
            // ARCHITECTURE: checkin runs in ambient-profile context — there's no playback session
            // to bind a specific owner profile. ownerProfileId reflects the active profile at
            // invocation time. ownerSessionId is not available here; the boundary check in the
            // scrobble layer falls back to the current active session. (F2-H-03)
            trackingScrobbleService.checkin(scrobbleItem, ownerProfileId = activeHomeProfileSession.profileId)
        }.onFailure { error ->
            if (error is CancellationException) throw error
            Log.w(HomeViewModel.TAG, "Failed tracking check-in for continue-watching item", error)
        }
    }
}

private fun buildTrackingScrobbleItemForContinueWatching(item: ContinueWatchingItem): TrackingScrobbleItem? {
    return when (item) {
        is ContinueWatchingItem.InProgress -> {
            if (item.progress.contentId.isBlank()) return null
            if (
                (item.progress.contentType.equals("series", ignoreCase = true) ||
                    item.progress.contentType.equals("tv", ignoreCase = true)) &&
                item.progress.season != null &&
                item.progress.episode != null
            ) {
                TrackingScrobbleItem.Episode(
                    contentId = item.progress.contentId,
                    showTitle = item.progress.name,
                    showYear = null,
                    season = item.progress.season,
                    number = item.progress.episode,
                    episodeTitle = item.progress.episodeTitle
                )
            } else {
                TrackingScrobbleItem.Movie(
                    contentId = item.progress.contentId,
                    title = item.progress.name,
                    year = null
                )
            }
        }

        is ContinueWatchingItem.NextUp -> {
            if (item.info.contentId.isBlank()) return null
            TrackingScrobbleItem.Episode(
                contentId = item.info.contentId,
                showTitle = item.info.name,
                showYear = null,
                season = item.info.season,
                number = item.info.episode,
                episodeTitle = item.info.episodeTitle
            )
        }
    }
}

private fun resumeRefForContinueWatching(progress: WatchProgress): ContinueWatchingResumeRef {
    val suppressNextUp = progress.season != null &&
        progress.episode != null &&
        (progress.contentType.equals("series", ignoreCase = true) || progress.contentType.equals("tv", ignoreCase = true))
    return ContinueWatchingResumeRef(
        contentId = progress.contentId,
        activityAtMs = progress.lastWatched,
        suppressNextUp = suppressNextUp
    )
}

private fun resumeRefForContinueWatchingRecord(record: ContinueWatchingRecord): ContinueWatchingResumeRef {
    val primaryResume = record.primaryResumeIdentity()
    return ContinueWatchingResumeRef(
        contentId = record.parentId,
        activityAtMs = record.updatedAt,
        suppressNextUp = record.episodeContext != null || primaryResume?.isEpisode == true
    )
}

private fun nextUpRefForContinueWatching(
    entry: com.nexio.tv.data.repository.TrackingNextUpEntry
): ContinueWatchingNextUpRef {
    return ContinueWatchingNextUpRef(
        contentId = entry.contentId,
        activityAtMs = entry.activityAtMs,
        firstAiredMs = entry.firstAiredMs,
        availabilityInstantMs = entry.tvdbAvailabilityInstantMs
    )
}

private fun canonicalNextUpRefForContinueWatching(
    entry: com.nexio.tv.data.repository.TrackingNextUpEntry
): ContinueWatchingNextUpRef {
    return ContinueWatchingNextUpRef(
        contentId = if (isSeriesType(entry.contentType)) {
            homeDisplayItemKey(entry.contentType, entry.contentId)
        } else {
            entry.contentId
        },
        activityAtMs = entry.activityAtMs,
        firstAiredMs = entry.firstAiredMs,
        availabilityInstantMs = entry.tvdbAvailabilityInstantMs
    )
}

private fun ContinueWatchingRecord.toContinueWatchingItem(
    rawResumeByLookupKey: Map<String, WatchProgress>,
    displayMetadataByItemKey: Map<String, HomeDisplayMetadata>
): ContinueWatchingItem? {
    val resumeIdentity = primaryResumeIdentity()
    if (resumeIdentity != null) {
        return toContinueWatchingInProgress(
            resumeIdentity = resumeIdentity,
            rawResumeByLookupKey = rawResumeByLookupKey,
            displayMetadataByItemKey = displayMetadataByItemKey
        )
    }
    return toSyntheticNextUp(displayMetadataByItemKey)
}

private fun ContinueWatchingRecord.primaryResumeIdentity(): ResumeIdentity? {
    return resumeIdentities.firstOrNull { it.lookupKey() == primaryResumeLookupKey }
        ?: resumeIdentities.maxByOrNull { it.lastWatchedMs }
}

private fun ContinueWatchingRecord.toContinueWatchingInProgress(
    resumeIdentity: ResumeIdentity,
    rawResumeByLookupKey: Map<String, WatchProgress>,
    displayMetadataByItemKey: Map<String, HomeDisplayMetadata>
): ContinueWatchingItem.InProgress {
    val canonicalKey = identityKey()
    val contentType = contentTypeForUi()
    val stableContentId = primaryStableContentId() ?: resumeIdentity.contentId
    val stableVideoId = if (
        stableContentId != resumeIdentity.contentId &&
        resumeIdentity.season != null &&
        resumeIdentity.episode != null
    ) {
        "$stableContentId:${resumeIdentity.season}:${resumeIdentity.episode}"
    } else {
        resumeIdentity.videoId
    }
    val displayMetadata = displayMetadataByItemKey[homeDisplayItemKey(contentType, stableContentId)]
        ?: displayMetadataByItemKey[homeDisplayItemKey(contentType, resumeIdentity.contentId)]
    val rawProgress = rawResumeByLookupKey[resumeIdentity.lookupKey()]
    val baseProgress = rawProgress ?: WatchProgress(
        contentId = stableContentId,
        contentType = contentType,
        name = displayMetadata?.title ?: parentId,
        poster = displayMetadata?.displayPoster,
        backdrop = displayMetadata?.displayBackdrop,
        logo = displayMetadata?.displayLogo,
        videoId = stableVideoId,
        season = resumeIdentity.season,
        episode = resumeIdentity.episode,
        episodeTitle = null,
        position = resumeIdentity.positionMs,
        duration = resumeIdentity.durationMs ?: durationMs,
        lastWatched = resumeIdentity.lastWatchedMs,
        progressPercent = resumeIdentity.progressPercent
    )
    return baseProgress.copy(
        contentId = stableContentId,
        contentType = contentType,
        name = displayMetadata?.title ?: baseProgress.name,
        poster = displayMetadata?.displayPoster ?: baseProgress.poster,
        backdrop = displayMetadata?.displayBackdrop ?: baseProgress.backdrop,
        logo = displayMetadata?.displayLogo ?: baseProgress.logo,
        videoId = stableVideoId,
        season = resumeIdentity.season,
        episode = resumeIdentity.episode,
        position = positionMs,
        duration = durationMs,
        lastWatched = updatedAt,
        progressPercent = resumeIdentity.progressPercent
    ).toContinueWatchingInProgress(displayMetadataByItemKey).copy(
        canonicalKey = canonicalKey,
        streamFetchVideoId = streamFetchIdentity?.videoId
    ).withResolvedImdb(displayIdentity?.providerIds?.imdb)
}

private fun ContinueWatchingRecord.primaryStableContentId(): String? {
    val tmdb = displayIdentity?.providerIds?.tmdb
        ?: canonicalKey?.canonicalParent?.providerIds?.tmdb
        ?: idBundle.tmdb
    if (!tmdb.isNullOrBlank()) return "tmdb:${tmdb.removePrefix("tmdb:").removePrefix("tv:")}"

    val kitsu = displayIdentity?.providerIds?.kitsu
        ?: canonicalKey?.canonicalParent?.providerIds?.kitsu
        ?: idBundle.kitsu
    if (!kitsu.isNullOrBlank()) return "kitsu:${kitsu.removePrefix("kitsu:")}"

    return null
}

// Enrich displayMetadata.imdbId from ContinueWatchingRecord.displayIdentity.providerIds.imdb.
// This is the field populated by ContinueWatchingIdentityResolver via
// StableIdBundleResolver and reliably persisted to the on-disk snapshot — see
// /data/data/.../continue-watching-snapshot-v1/p1.json shows canonical IMDB ids for
// every record under canonicalKey.canonicalParent.providerIds.imdb and the matching
// displayIdentity.providerIds.imdb. The earlier sibling field ContinueWatchingRecord.idBundle
// was added but is not serialized through the current snapshot schema, so reading from it
// is a no-op for restored snapshots.
//
// Stream resolution downstream (NexioNavHost.continueWatchingImdbHint -> Stream route ->
// stream provider) reads displayMetadata.imdbId first and falls back to contentId-as-imdb
// only when the canonical id is itself a tt-prefix IMDB. For TV with canonical "tvdb:N" the
// fallback returns null, so without this plumb the stream picker has no IMDB to query
// IMDB-keyed addons (Wyzie etc.) with — which is the user-visible "no eligible links" bug
// for TV CW tiles. Movies sidestep it incidentally because their canonical id IS the IMDB.
private fun ContinueWatchingItem.InProgress.withResolvedImdb(
    resolvedImdb: String?
): ContinueWatchingItem.InProgress {
    val resolved = resolvedImdb?.trim()?.takeIf { it.isNotEmpty() } ?: return this
    val current = displayMetadata
    if (!current?.imdbId.isNullOrBlank()) return this
    val updated = (current ?: HomeDisplayMetadata()).copy(imdbId = resolved)
    return copy(displayMetadata = updated)
}

private fun ContinueWatchingRecord.toSyntheticNextUp(
    displayMetadataByItemKey: Map<String, HomeDisplayMetadata>
): ContinueWatchingItem.NextUp? {
    val episode = episodeContext ?: return null
    val contentType = contentTypeForUi()
    val contentId = streamFetchIdentity?.contentId ?: parentId
    val cachedMetadata = displayMetadataByItemKey[homeDisplayItemKey(contentType, contentId)]
    // Backstop displayMetadata.imdbId from the record's displayIdentity (same source the
    // resolver populates and that IS serialized in the snapshot, unlike the never-persisted
    // ContinueWatchingRecord.idBundle field). See withResolvedImdb above for rationale.
    val displayMetadata = if (cachedMetadata?.imdbId.isNullOrBlank()) {
        val resolved = displayIdentity?.providerIds?.imdb?.trim()?.takeIf { it.isNotEmpty() }
        if (resolved != null) {
            (cachedMetadata ?: HomeDisplayMetadata()).copy(imdbId = resolved)
        } else {
            cachedMetadata
        }
    } else {
        cachedMetadata
    }
    return ContinueWatchingItem.NextUp(
        NextUpInfo(
            contentId = contentId,
            contentType = contentType,
            name = displayMetadata?.title ?: parentId,
            poster = displayMetadata?.displayPoster,
            backdrop = displayMetadata?.displayBackdrop,
            logo = displayMetadata?.displayLogo,
            displayMetadata = displayMetadata,
            videoId = streamFetchIdentity?.videoId ?: contentId,
            season = episode.season,
            episode = episode.number,
            episodeTitle = null,
            episodeDescription = displayMetadata?.description,
            thumbnail = displayMetadata?.displayThumbnail,
            released = null,
            hasAired = true,
            airDateLabel = null,
            lastWatched = updatedAt,
            imdbRating = displayMetadata?.imdbRating,
            genres = displayMetadata?.genres.orEmpty(),
            releaseInfo = displayMetadata?.releaseInfo,
            canonicalKey = identityKey(),
            streamFetchVideoId = streamFetchIdentity?.videoId
        )
    )
}

private fun ContinueWatchingRecord.contentTypeForUi(): String {
    val mediaKind = canonicalKey?.mediaKind?.name?.lowercase(Locale.US)
    return when {
        mediaKind == "movie" -> ContentType.MOVIE.toApiString()
        mediaKind == "series" || episodeContext != null -> ContentType.SERIES.toApiString()
        parentId.startsWith("movie:", ignoreCase = true) ||
            contentId.startsWith("movie:", ignoreCase = true) -> ContentType.MOVIE.toApiString()
        else -> ContentType.SERIES.toApiString()
    }
}

private fun WatchProgress.toContinueWatchingInProgress(
    displayMetadataByItemKey: Map<String, HomeDisplayMetadata>
): ContinueWatchingItem.InProgress {
    val displayMetadata = displayMetadataByItemKey[homeDisplayItemKey(contentType, contentId)]
    val seriesEpisodeRating = displayMetadata?.imdbRating.takeIf { isSeriesType(contentType) }
    return ContinueWatchingItem.InProgress(
        progress = this,
        displayMetadata = displayMetadata,
        episodeDescription = displayMetadata?.description,
        episodeImdbRating = seriesEpisodeRating,
        genres = displayMetadata?.genres.orEmpty(),
        releaseInfo = displayMetadata?.releaseInfo
    )
}

private fun com.nexio.tv.data.repository.TrackingNextUpEntry.toContinueWatchingNextUp(
    displayMetadataByItemKey: Map<String, HomeDisplayMetadata>,
    nowMs: Long
): ContinueWatchingItem.NextUp {
    val hasAired = com.nexio.tv.data.repository.AirDateGate.isAired(
        tvdbAvailabilityInstantMs,
        firstAiredMs,
        firstAired,
        nowMs
    )
    val airLabel = if (!hasAired) {
        formatDeviceLocalAirLabel(tvdbAvailabilityInstantMs, tvdbAvailabilityDeviceLocalDateTime)
            ?: parseEpisodeReleaseDate(firstAired)?.let(::formatEpisodeAirDateLabel)
    } else null
    val displayMetadata = displayMetadataByItemKey[homeDisplayItemKey(contentType, contentId)]
    return ContinueWatchingItem.NextUp(
        NextUpInfo(
            contentId = contentId,
            contentType = contentType,
            name = displayMetadata?.title ?: name,
            poster = displayMetadata?.displayPoster ?: poster,
            backdrop = displayMetadata?.displayBackdrop ?: backdrop,
            logo = displayMetadata?.displayLogo ?: logo,
            displayMetadata = displayMetadata,
            videoId = videoId,
            season = season,
            episode = episode,
            episodeTitle = episodeTitle,
            episodeDescription = displayMetadata?.description,
            thumbnail = null,
            released = firstAired,
            hasAired = hasAired,
            airDateLabel = airLabel,
            lastWatched = activityAtMs,
            imdbRating = displayMetadata?.imdbRating,
            genres = displayMetadata?.genres.orEmpty(),
            releaseInfo = displayMetadata?.releaseInfo
        )
    )
}

internal fun HomeViewModel.enrichContinueWatchingWithCurrentSettings() {
    val settings = currentTmdbSettings
    val currentItems = _displayContinueWatchingItems.value
    val currentTraktItems = _uiState.value.traktUpNextItems
    if (!isNonPlaybackHomeWorkAllowed()) return
    if (!shouldEnrichContinueWatchingProviderMetadata(currentItems, currentTraktItems, settings)) return
    if (currentItems.isEmpty() && currentTraktItems.isEmpty()) return

    continueWatchingEnrichmentJob?.cancel()
    continueWatchingEnrichmentJob = viewModelScope.launch {
        try {
            if (!isNonPlaybackHomeWorkAllowed()) return@launch
            val enrichedItems = enrichContinueWatchingItems(currentItems, settings)
            if (!isNonPlaybackHomeWorkAllowed()) return@launch
            val enrichedTraktItems = enrichContinueWatchingNextUpItems(currentTraktItems, settings)
            if (_displayContinueWatchingItems.value != enrichedItems) {
                _displayContinueWatchingItems.value = enrichedItems
            }
            _uiState.update { state ->
                state.copy(
                    traktUpNextItems = enrichedTraktItems
                )
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(HomeViewModel.TAG, "Continue watching metadata enrichment failed: ${e.message}")
        }
    }
}
