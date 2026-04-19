package com.nexio.tv.data.repository

import android.util.Log
import com.nexio.tv.core.profile.ProfileManager
import com.nexio.tv.data.local.SimklAuthDataStore
import com.nexio.tv.data.local.SimklLibrarySnapshotStore
import com.nexio.tv.data.remote.dto.simkl.SimklAddToListRequestDto
import com.nexio.tv.data.remote.dto.simkl.SimklHistoryShowPayloadDto
import com.nexio.tv.data.remote.dto.simkl.SimklHistoryRemoveRequestDto
import com.nexio.tv.data.remote.dto.simkl.SimklIdsDto
import com.nexio.tv.data.remote.dto.simkl.SimklMediaRefDto
import com.nexio.tv.data.repository.simkl.SimklLibraryMutationAdapter
import com.nexio.tv.data.trakt.outbox.TraktMutationOutboxCoordinator
import com.nexio.tv.domain.model.LibraryEntry
import com.nexio.tv.domain.model.LibraryEntryInput
import com.nexio.tv.domain.model.LibraryListTab
import com.nexio.tv.domain.model.ListMembershipChanges
import com.nexio.tv.domain.model.ListMembershipSnapshot
import com.nexio.tv.domain.model.TrackingProvider
import com.nexio.tv.domain.repository.MetaRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.time.Instant
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class SimklLibraryService @Inject constructor(
    private val remote: SimklTrackingRemoteDataSource,
    private val simklAuthDataStore: SimklAuthDataStore,
    private val traktMutationOutboxCoordinator: TraktMutationOutboxCoordinator,
    private val snapshotStore: SimklLibrarySnapshotStore,
    private val metaRepository: MetaRepository,
    private val profileManager: ProfileManager? = null
) {
    data class LibraryRollbackState(
        val listTabs: List<LibraryListTab> = emptyList(),
        val entriesByList: Map<String, List<LibraryEntry>> = emptyMap()
    )

    private data class Snapshot(
        val listTabs: List<LibraryListTab> = emptyList(),
        val entriesByList: Map<String, List<LibraryEntry>> = emptyMap(),
        val allEntries: List<LibraryEntry> = emptyList(),
        val membershipByContent: Map<String, Set<String>> = emptyMap(),
        val updatedAtMs: Long = 0L
    )

    private data class LibraryMetadata(
        val name: String?,
        val poster: String?,
        val background: String?,
        val logo: String?,
        val description: String?,
        val releaseInfo: String?,
        val imdbRating: Float?,
        val genres: List<String>
    )

    private data class ActivityTimestamps(
        val lastTvShowsAllAt: String? = null,
        val lastMoviesAllAt: String? = null,
        val lastAnimeAllAt: String? = null,
        val lastTvShowsRemovedFromListAt: String? = null,
        val lastMoviesRemovedFromListAt: String? = null,
        val lastAnimeRemovedFromListAt: String? = null
    )

    companion object {
        const val WATCHLIST_KEY = "simkl:plantowatch"
        const val WATCHING_KEY = "simkl:watching"
        const val COMPLETED_KEY = "simkl:completed"
        const val HOLD_KEY = "simkl:hold"
        const val DROPPED_KEY = "simkl:dropped"
        private val STATUS_KEYS = listOf(WATCHLIST_KEY, WATCHING_KEY, COMPLETED_KEY, HOLD_KEY, DROPPED_KEY)
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val refreshMutex = Mutex()
    private val snapshotState = MutableStateFlow(buildEmptySnapshot())
    private val metadataState = MutableStateFlow<Map<String, LibraryMetadata>>(emptyMap())
    private val refreshingState = MutableStateFlow(false)
    private val hasCacheState = MutableStateFlow(false)
    private val metadataMutex = Mutex()
    private val inFlightMetadataKeys = mutableSetOf<String>()
    private val metadataHydrationLimit = 80
    private val metadataFetchSemaphore = Semaphore(4)
    // Per-type activity timestamps — stored from /sync/activities and used as date_from on delta syncs
    private var lastTvShowsAllAt: String? = null
    private var lastMoviesAllAt: String? = null
    private var lastAnimeAllAt: String? = null
    private var lastTvShowsRemovedFromListAt: String? = null
    private var lastMoviesRemovedFromListAt: String? = null
    private var lastAnimeRemovedFromListAt: String? = null
    private val autoRefreshThrottleMs = 6L * 60 * 60 * 1000L
    private var lastAutoRefreshMs = 0L

    init {
        restoreSnapshotForProfile(activeProfileId())
        profileManager?.let { manager ->
            scope.launch {
                manager.activeProfileId
                    .collectLatest { profileId ->
                        restoreSnapshotForProfile(profileId)
                    }
            }
        }
    }

    fun observeListTabs(): Flow<List<LibraryListTab>> =
        snapshotState.map { it.listTabs }.distinctUntilChanged()

    fun observeAllItems(): Flow<List<LibraryEntry>> =
        combine(snapshotState, metadataState) { snapshot, metadata ->
            enrichEntries(snapshot.allEntries, metadata)
        }.distinctUntilChanged()

    fun observeMembership(itemId: String, itemType: String): Flow<Set<String>> {
        val key = contentKey(itemId, itemType)
        return snapshotState.map { it.membershipByContent[key].orEmpty() }.distinctUntilChanged()
    }

    fun observeIsRefreshing(): Flow<Boolean> = refreshingState
    fun observeHasCache(): Flow<Boolean> = hasCacheState

    suspend fun getMembershipSnapshot(item: LibraryEntryInput): ListMembershipSnapshot {
        ensureFresh()
        val membership = snapshotState.value.membershipByContent[contentKey(item.itemId, item.itemType)].orEmpty()
        return ListMembershipSnapshot(
            listMembership = snapshotState.value.listTabs.associate { it.key to membership.contains(it.key) }
        )
    }

    suspend fun toggleWatchlist(item: LibraryEntryInput) {
        ensureFresh()
        val snapshot = getMembershipSnapshot(item).listMembership
        if (snapshot[WATCHLIST_KEY] == true) {
            performOptimisticMutation(
                optimistic = { current -> removeItemFromAllLists(current, item) }
            ) { before, profileId ->
                val body = buildRemoveBody(item)
                traktMutationOutboxCoordinator.enqueueAndDrain(
                    SimklLibraryMutationAdapter.buildRemoveEnvelope(
                        body = body,
                        rollbackState = rollbackStateFor(before),
                        profileId = profileId
                    )
                )
            }
        } else {
            performOptimisticMutation(
                optimistic = { current -> addItemToList(current, item, WATCHLIST_KEY) }
            ) { before, profileId ->
                val body = buildAddBody(item, WATCHLIST_KEY)
                traktMutationOutboxCoordinator.enqueueAndDrain(
                    SimklLibraryMutationAdapter.buildAddToListEnvelope(
                        listKey = WATCHLIST_KEY,
                        body = body,
                        rollbackState = rollbackStateFor(before),
                        profileId = profileId
                    )
                )
            }
        }
    }

    suspend fun applyMembershipChanges(item: LibraryEntryInput, changes: ListMembershipChanges) {
        ensureFresh()
        val current = getMembershipSnapshot(item).listMembership
        val desiredEnabled = changes.desiredMembership.filterValues { it }.keys
        val currentEnabled = current.filterValues { it }.keys
        if (desiredEnabled == currentEnabled) return

        if (desiredEnabled.isEmpty()) {
            performOptimisticMutation(
                optimistic = { current -> removeItemFromAllLists(current, item) }
            ) { before, profileId ->
                traktMutationOutboxCoordinator.enqueueAndDrain(
                    SimklLibraryMutationAdapter.buildRemoveEnvelope(
                        body = buildRemoveBody(item),
                        rollbackState = rollbackStateFor(before),
                        profileId = profileId
                    )
                )
            }
        } else {
            val target = STATUS_KEYS.firstOrNull { it in desiredEnabled } ?: WATCHLIST_KEY
            performOptimisticMutation(
                optimistic = { current -> addItemToList(removeItemFromAllLists(current, item), item, target) }
            ) { before, profileId ->
                traktMutationOutboxCoordinator.enqueueAndDrain(
                    SimklLibraryMutationAdapter.buildAddToListEnvelope(
                        listKey = target,
                        body = buildAddBody(item, target),
                        rollbackState = rollbackStateFor(before),
                        profileId = profileId
                    )
                )
            }
        }
    }

    suspend fun refreshNow(force: Boolean = false) {
        val profileId = activeProfileId()
        if (!simklAuthDataStore.stateForProfile(profileId).first().isAuthenticated) {
            if (profileId == activeProfileId()) {
                clearInMemorySnapshot()
            }
            return
        }
        ensureFresh(force, profileId)
    }

    internal suspend fun refreshProfile(profileId: Int, force: Boolean = true) {
        if (!simklAuthDataStore.stateForProfile(profileId).first().isAuthenticated) {
            if (profileId == activeProfileId()) {
                clearInMemorySnapshot()
            }
            return
        }
        ensureFresh(force, profileId)
    }

    private suspend fun ensureFresh(
        force: Boolean = false,
        profileId: Int = activeProfileId()
    ) = withContext(Dispatchers.IO) {
        refreshMutex.withLock {
            val now = System.currentTimeMillis()
            if (
                profileId == activeProfileId() &&
                !force &&
                snapshotState.value.updatedAtMs > 0L &&
                now - lastAutoRefreshMs < autoRefreshThrottleMs
            ) {
                return@withLock
            }
            val session = TrackingAuthSession(TrackingProvider.SIMKL, profileId)
            val activeAtStart = profileId == activeProfileId()
            if (activeAtStart) refreshingState.value = true
            runCatching {
                val activitiesResponse = remote.getLastActivities(session)
                if (!activitiesResponse.isSuccessful) {
                    Log.w("SimklLibraryService", "Failed to fetch SIMKL activities (${activitiesResponse.code()}), skipping sync")
                    return@runCatching
                }
                val activities = activitiesResponse.body()
                val currentTvShowsAll = activities?.tvShows?.all
                val currentMoviesAll = activities?.movies?.all
                val currentAnimeAll = activities?.anime?.all

                val previousTimestamps = if (profileId == activeProfileId()) {
                    currentActivityTimestamps()
                } else {
                    persistedActivityTimestamps(profileId)
                }
                val previousMetadata = if (profileId == activeProfileId()) {
                    metadataState.value
                } else {
                    persistedMetadataForProfile(profileId)
                }

                // Detect first sync: no per-type timestamps saved yet
                val isFirstSync = previousTimestamps.lastTvShowsAllAt == null &&
                    previousTimestamps.lastMoviesAllAt == null &&
                    previousTimestamps.lastAnimeAllAt == null

                // Skip entirely if nothing changed across any type
                if (!force && !isFirstSync &&
                    currentTvShowsAll == previousTimestamps.lastTvShowsAllAt &&
                    currentMoviesAll == previousTimestamps.lastMoviesAllAt &&
                    currentAnimeAll == previousTimestamps.lastAnimeAllAt
                ) {
                    if (profileId == activeProfileId()) {
                        lastAutoRefreshMs = now
                    }
                    return@runCatching
                }

                val snapshot = if (force || isFirstSync) {
                    // Phase 1: single GET /sync/all-items/ — no type filter, no date_from
                    fetchInitialSnapshot(session)
                } else {
                    // Phase 2: per-type delta using type-specific saved timestamps
                    fetchDeltaAndMerge(
                        activities = activities,
                        session = session,
                        previousTimestamps = previousTimestamps,
                        baseSnapshot = if (profileId == activeProfileId()) {
                            snapshotState.value
                        } else {
                            snapshotFromPersisted(profileId)
                        }
                    )
                } ?: return@runCatching

                // Save per-type timestamps from this activities response
                val updatedTimestamps = ActivityTimestamps(
                    lastTvShowsAllAt = currentTvShowsAll,
                    lastMoviesAllAt = currentMoviesAll,
                    lastAnimeAllAt = currentAnimeAll,
                    lastTvShowsRemovedFromListAt = activities?.tvShows?.removedFromList,
                    lastMoviesRemovedFromListAt = activities?.movies?.removedFromList,
                    lastAnimeRemovedFromListAt = activities?.anime?.removedFromList
                )
                persistSnapshot(snapshot, previousMetadata, profileId, updatedTimestamps)
                if (profileId == activeProfileId()) {
                    applyActivityTimestamps(updatedTimestamps)
                    lastAutoRefreshMs = now
                    snapshotState.value = snapshot
                    hasCacheState.value = snapshot.updatedAtMs > 0L || snapshot.allEntries.isNotEmpty()
                    hydrateMetadata(snapshot.allEntries, profileId)
                }
            }.onFailure {
                Log.w("SimklLibraryService", "Failed to refresh SIMKL library", it)
            }
            if (activeAtStart || profileId == activeProfileId()) {
                refreshingState.value = false
            }
        }
    }

    private suspend fun fetchInitialSnapshot(session: TrackingAuthSession): Snapshot? {
        // Spec: first sync uses GET /sync/all-items/ with no type filter and no date_from
        val response = remote.getAllItems(dateFrom = null, extended = "full", session = session)
        if (!response.isSuccessful) {
            Log.w("SimklLibraryService", "Failed to fetch initial SIMKL all-items (${response.code()})")
            return null
        }
        return buildSnapshotFromItems(response.body().orEmpty())
    }

    private suspend fun fetchDeltaAndMerge(
        activities: com.nexio.tv.data.remote.dto.simkl.SimklLastActivitiesResponseDto?,
        session: TrackingAuthSession,
        previousTimestamps: ActivityTimestamps,
        baseSnapshot: Snapshot
    ): Snapshot? {
        // If removals changed for ANY type, fall back to a full re-fetch so removed items are cleaned up
        val removalsChanged =
            activities?.tvShows?.removedFromList != previousTimestamps.lastTvShowsRemovedFromListAt ||
            activities?.movies?.removedFromList != previousTimestamps.lastMoviesRemovedFromListAt ||
            activities?.anime?.removedFromList != previousTimestamps.lastAnimeRemovedFromListAt
        if (removalsChanged) {
            return fetchInitialSnapshot(session)
        }

        // Per-type delta: fetch only types whose timestamp changed, using the saved timestamp as date_from
        data class TypeConfig(val type: String, val extended: String, val currentAll: String?, val savedAll: String?)
        val types = listOf(
            TypeConfig("shows", "full", activities?.tvShows?.all, previousTimestamps.lastTvShowsAllAt),
            TypeConfig("movies", "full", activities?.movies?.all, previousTimestamps.lastMoviesAllAt),
            TypeConfig("anime", "full_anime_seasons", activities?.anime?.all, previousTimestamps.lastAnimeAllAt)
        )

        var merged = baseSnapshot
        for (tc in types) {
            if (tc.currentAll == tc.savedAll) continue  // no changes for this type
            val response = remote.getAllItemsByType(
                type = tc.type,
                dateFrom = tc.savedAll,
                extended = tc.extended,
                session = session
            )
            if (!response.isSuccessful) {
                Log.w("SimklLibraryService", "Failed to fetch SIMKL ${tc.type} delta (${response.code()})")
                continue
            }
            merged = mergeLibraryDelta(merged, response.body().orEmpty())
        }
        return merged.copy(updatedAtMs = System.currentTimeMillis())
    }

    private fun buildSnapshotFromItems(items: List<com.nexio.tv.data.remote.dto.simkl.SimklLibraryItemDto>): Snapshot {
        val tabs = listOf(
            LibraryListTab(key = WATCHLIST_KEY, title = "SIMKL Watchlist", type = LibraryListTab.Type.WATCHLIST),
            LibraryListTab(key = WATCHING_KEY, title = "Watching", type = LibraryListTab.Type.SERVICE),
            LibraryListTab(key = COMPLETED_KEY, title = "Completed", type = LibraryListTab.Type.SERVICE),
            LibraryListTab(key = HOLD_KEY, title = "On Hold", type = LibraryListTab.Type.SERVICE),
            LibraryListTab(key = DROPPED_KEY, title = "Dropped", type = LibraryListTab.Type.SERVICE)
        )
        val entriesByList = linkedMapOf<String, MutableList<LibraryEntry>>()
        STATUS_KEYS.forEach { entriesByList[it] = mutableListOf() }
        val membership = linkedMapOf<String, MutableSet<String>>()
        items.mapNotNull { dto -> mapLibraryItem(dto) }.forEach { entry ->
            val listKey = entry.listKeys.firstOrNull() ?: WATCHLIST_KEY
            entriesByList.getOrPut(listKey) { mutableListOf() }.add(entry)
            membership.getOrPut(contentKey(entry.id, entry.type)) { linkedSetOf() }.add(listKey)
        }
        val deduped = linkedMapOf<String, LibraryEntry>()
        entriesByList.values.flatten().sortedByDescending { it.listedAt }.forEach { entry ->
            val key = contentKey(entry.id, entry.type)
            deduped[key] = entry.copy(listKeys = membership[key].orEmpty())
        }
        return Snapshot(
            listTabs = tabs,
            entriesByList = entriesByList.mapValues { (key, entries) ->
                entries.map { it.copy(listKeys = membership[contentKey(it.id, it.type)].orEmpty()) }
            },
            allEntries = deduped.values.toList(),
            membershipByContent = membership.mapValues { it.value.toSet() },
            updatedAtMs = System.currentTimeMillis()
        )
    }

    private fun mergeLibraryDelta(
        current: Snapshot,
        deltaItems: List<com.nexio.tv.data.remote.dto.simkl.SimklLibraryItemDto>
    ): Snapshot {
        val entriesByList = current.entriesByList.mapValues { it.value.toMutableList() }
            .toMutableMap<String, MutableList<LibraryEntry>>()
        STATUS_KEYS.forEach { key -> entriesByList.getOrPut(key) { mutableListOf() } }
        deltaItems.mapNotNull { dto -> mapLibraryItem(dto) }.forEach { entry ->
            val targetListKey = entry.listKeys.firstOrNull() ?: WATCHLIST_KEY
            val ck = contentKey(entry.id, entry.type)
            STATUS_KEYS.forEach { listKey ->
                entriesByList[listKey]?.removeAll { contentKey(it.id, it.type) == ck }
            }
            entriesByList.getOrPut(targetListKey) { mutableListOf() }.add(entry)
        }
        val membership = linkedMapOf<String, MutableSet<String>>()
        entriesByList.forEach { (listKey, entries) ->
            entries.forEach { entry ->
                membership.getOrPut(contentKey(entry.id, entry.type)) { linkedSetOf() }.add(listKey)
            }
        }
        val deduped = linkedMapOf<String, LibraryEntry>()
        entriesByList.values.flatten().sortedByDescending { it.listedAt }.forEach { entry ->
            val key = contentKey(entry.id, entry.type)
            deduped[key] = entry.copy(listKeys = membership[key].orEmpty())
        }
        return Snapshot(
            listTabs = current.listTabs,
            entriesByList = entriesByList.mapValues { (_, entries) ->
                entries.map { it.copy(listKeys = membership[contentKey(it.id, it.type)].orEmpty()) }
            },
            allEntries = deduped.values.toList(),
            membershipByContent = membership.mapValues { it.value.toSet() },
            updatedAtMs = System.currentTimeMillis()
        )
    }

    private fun mapLibraryItem(dto: com.nexio.tv.data.remote.dto.simkl.SimklLibraryItemDto): LibraryEntry? {
        val media = dto.movie ?: dto.show ?: dto.anime ?: return null
        val ids = media.ids ?: dto.ids ?: return null
        val contentId = ids.imdb?.takeIf { it.isNotBlank() }
            ?: ids.tmdb?.takeIf { it.isNotBlank() }?.let { "tmdb:$it" }
            ?: ids.simkl?.let { "simkl:$it" }
            ?: ids.simklId?.let { "simkl:$it" }
            ?: return null
        val normalizedType = if (dto.movie != null) "movie" else "series"
        val status = dto.status?.takeIf { it.isNotBlank() } ?: "plantowatch"
        return LibraryEntry(
            id = contentId,
            type = normalizedType,
            name = media.title ?: contentId,
            poster = null,
            background = null,
            logo = null,
            description = null,
            releaseInfo = dto.lastWatchedAt ?: dto.date ?: dto.released,
            imdbRating = null,
            genres = emptyList(),
            addonBaseUrl = null,
            listKeys = setOf("simkl:$status"),
            listedAt = parseMillis(dto.lastWatchedAt ?: dto.lastUpdatedAt ?: dto.date ?: dto.released)
        )
    }

    private fun maxTimestamp(vararg timestamps: String?): String? =
        timestamps.filterNotNull().filter { it.isNotBlank() }.maxOrNull()

    internal suspend fun rollbackQueuedLibraryMutation(
        rollbackState: LibraryRollbackState,
        profileId: Int = activeProfileId()
    ) {
        if (profileId != activeProfileId()) {
            ensureFresh(force = true, profileId = profileId)
            return
        }
        val restored = rebuildSnapshotFromRollback(rollbackState)
        snapshotState.value = restored
        hasCacheState.value = restored.updatedAtMs > 0L || restored.allEntries.isNotEmpty()
        persistSnapshot(restored, metadataState.value, profileId)
    }

    private suspend fun performOptimisticMutation(
        optimistic: (Snapshot) -> Snapshot,
        remoteMutation: suspend (before: Snapshot, profileId: Int) -> Unit
    ) {
        val before = snapshotState.value
        val profileId = activeProfileId()
        val after = optimistic(before)
        snapshotState.value = after
        hasCacheState.value = after.updatedAtMs > 0L || after.allEntries.isNotEmpty()
        persistSnapshot(after, metadataState.value, profileId)
        runCatching {
            remoteMutation(before, profileId)
        }.onFailure {
            snapshotState.value = before
            hasCacheState.value = before.updatedAtMs > 0L || before.allEntries.isNotEmpty()
            persistSnapshot(before, metadataState.value, profileId)
            throw it
        }
    }

    private fun buildAddBody(item: LibraryEntryInput, listKey: String): SimklAddToListRequestDto {
        return when (normalizeItemType(item.itemType)) {
            "movie" -> com.nexio.tv.data.remote.dto.simkl.SimklAddToListRequestDto(
                to = listKey.removePrefix("simkl:"),
                movies = listOf(item.toSimklMediaRef())
            )
            else -> com.nexio.tv.data.remote.dto.simkl.SimklAddToListRequestDto(
                to = listKey.removePrefix("simkl:"),
                shows = listOf(item.toSimklMediaRef())
            )
        }
    }

    private fun buildRemoveBody(item: LibraryEntryInput): SimklHistoryRemoveRequestDto {
        return when (normalizeItemType(item.itemType)) {
            "movie" -> SimklHistoryRemoveRequestDto(
                movies = listOf(item.toSimklMediaRef())
            )
            else -> {
                val showPayload = SimklHistoryShowPayloadDto(
                    title = item.title,
                    year = item.year,
                    ids = item.toSimklMediaRef().ids
                )
                SimklHistoryRemoveRequestDto(
                    shows = listOf(showPayload)
                )
            }
        }
    }

    private fun LibraryEntryInput.toSimklMediaRef(): SimklMediaRefDto {
        return SimklMediaRefDto(
            title = title,
            year = year,
            ids = SimklIdsDto(
                imdb = imdbId,
                tmdb = tmdbId?.toString(),
                simkl = parseSimklId(itemId)
            )
        )
    }

    private fun normalizeItemType(type: String): String =
        if (type.equals("movie", ignoreCase = true)) "movie" else "series"

    private fun contentKey(itemId: String, itemType: String): String = "${normalizeItemType(itemType)}:${itemId.trim()}"

    private fun parseMillis(value: String?): Long =
        value?.takeIf { it.isNotBlank() }?.let { runCatching { Instant.parse(it).toEpochMilli() }.getOrNull() }
            ?: System.currentTimeMillis()

    private fun parseSimklId(itemId: String): Long? =
        itemId.removePrefix("simkl:").takeIf { itemId.startsWith("simkl:") }?.toLongOrNull()

    private fun rollbackStateFor(snapshot: Snapshot): LibraryRollbackState =
        LibraryRollbackState(
            listTabs = snapshot.listTabs,
            entriesByList = snapshot.entriesByList
        )

    private fun rebuildSnapshotFromRollback(rollback: LibraryRollbackState): Snapshot {
        val membership = linkedMapOf<String, MutableSet<String>>()
        rollback.entriesByList.forEach { (listKey, entries) ->
            entries.forEach { entry ->
                membership.getOrPut(contentKey(entry.id, entry.type)) { linkedSetOf() }.add(listKey)
            }
        }
        val allEntries = linkedMapOf<String, LibraryEntry>()
        rollback.entriesByList.values.flatten().sortedByDescending { it.listedAt }.forEach { entry ->
            val key = contentKey(entry.id, entry.type)
            allEntries[key] = entry.copy(listKeys = membership[key].orEmpty())
        }
        return Snapshot(
            listTabs = rollback.listTabs,
            entriesByList = rollback.entriesByList.mapValues { (_, entries) ->
                entries.map { it.copy(listKeys = membership[contentKey(it.id, it.type)].orEmpty()) }
            },
            allEntries = allEntries.values.toList(),
            membershipByContent = membership.mapValues { it.value.toSet() },
            updatedAtMs = System.currentTimeMillis()
        )
    }

    private fun enrichEntries(
        entries: List<LibraryEntry>,
        metadataMap: Map<String, LibraryMetadata>
    ): List<LibraryEntry> {
        return entries.map { entry ->
            val metadata = metadataMap[contentKey(entry.id, entry.type)] ?: return@map entry
            val shouldOverrideName = entry.name.isBlank() || entry.name == entry.id
            entry.copy(
                name = if (shouldOverrideName) metadata.name ?: entry.name else entry.name,
                poster = entry.poster ?: metadata.poster,
                background = entry.background ?: metadata.background,
                logo = entry.logo ?: metadata.logo,
                description = entry.description ?: metadata.description,
                releaseInfo = entry.releaseInfo ?: metadata.releaseInfo,
                imdbRating = entry.imdbRating ?: metadata.imdbRating,
                genres = if (entry.genres.isEmpty()) metadata.genres else entry.genres
            )
        }
    }

    private fun hydrateMetadata(entries: List<LibraryEntry>, profileId: Int = activeProfileId()) {
        entries.take(metadataHydrationLimit).forEach { entry ->
            val key = contentKey(entry.id, entry.type)
            if (metadataState.value.containsKey(key)) return@forEach
            scope.launch {
                val shouldFetch = metadataMutex.withLock {
                    if (metadataState.value.containsKey(key)) return@withLock false
                    if (inFlightMetadataKeys.contains(key)) return@withLock false
                    inFlightMetadataKeys.add(key)
                    true
                }
                if (!shouldFetch) return@launch
                try {
                    metadataFetchSemaphore.withPermit {
                        val metadata = fetchMetadata(entry) ?: return@launch
                        val updatedMetadata = metadataMutex.withLock {
                            val current = metadataState.value
                            if (current.containsKey(key)) return@withLock null
                            current + (key to metadata)
                        }
                        if (updatedMetadata != null) {
                            if (profileId == activeProfileId()) {
                                metadataState.value = updatedMetadata
                                persistSnapshot(snapshotState.value, updatedMetadata, profileId)
                            }
                        }
                    }
                } finally {
                    metadataMutex.withLock { inFlightMetadataKeys.remove(key) }
                }
            }
        }
    }

    private suspend fun fetchMetadata(entry: LibraryEntry): LibraryMetadata? {
        val typeCandidates = if (entry.type == "movie") listOf("movie") else listOf("series", "tv")
        val idCandidates = buildList {
            add(entry.id)
            entry.imdbId?.takeIf { it.isNotBlank() }?.let(::add)
            entry.tmdbId?.let { add("tmdb:$it") }
            if (entry.id.startsWith("tmdb:")) add(entry.id.substringAfter(':'))
            if (entry.id.startsWith("simkl:")) add(entry.id.substringAfter(':'))
        }.distinct()
        for (type in typeCandidates) {
            for (id in idCandidates) {
                val result = withTimeoutOrNull(3500L) {
                    metaRepository.getMetaFromAllAddons(
                        type = type,
                        id = id,
                        cacheOnDisk = false,
                        origin = "library"
                    ).first { it !is com.nexio.tv.core.network.NetworkResult.Loading }
                } ?: continue
                val meta = (result as? com.nexio.tv.core.network.NetworkResult.Success)?.data ?: continue
                return LibraryMetadata(
                    name = meta.name,
                    poster = meta.poster,
                    background = meta.background,
                    logo = meta.logo,
                    description = meta.description,
                    releaseInfo = meta.releaseInfo,
                    imdbRating = meta.imdbRating,
                    genres = meta.genres
                )
            }
        }
        return null
    }

    private fun addItemToList(snapshot: Snapshot, item: LibraryEntryInput, listKey: String): Snapshot {
        val entry = item.toLibraryEntry(listKey)
        val entriesByList = snapshot.entriesByList.toMutableMap()
        entriesByList[listKey] = (entriesByList[listKey].orEmpty().filterNot { it.id == entry.id && it.type == entry.type } + entry)
            .sortedByDescending { it.listedAt }
        STATUS_KEYS.filterNot { it == listKey }.forEach { otherKey ->
            entriesByList[otherKey] = entriesByList[otherKey].orEmpty().filterNot { it.id == entry.id && it.type == entry.type }
        }
        return rebuildSnapshotFromRollback(LibraryRollbackState(listTabs = snapshot.listTabs, entriesByList = entriesByList))
    }

    private fun removeItemFromAllLists(snapshot: Snapshot, item: LibraryEntryInput): Snapshot {
        val entriesByList = snapshot.entriesByList.mapValues { (_, entries) ->
            entries.filterNot { it.id == item.itemId && normalizeItemType(it.type) == normalizeItemType(item.itemType) }
        }
        return rebuildSnapshotFromRollback(LibraryRollbackState(listTabs = snapshot.listTabs, entriesByList = entriesByList))
    }

    private fun LibraryEntryInput.toLibraryEntry(listKey: String): LibraryEntry {
        return LibraryEntry(
            id = itemId,
            type = normalizeItemType(itemType),
            name = title,
            poster = poster,
            posterShape = posterShape,
            background = background,
            logo = logo,
            description = description,
            releaseInfo = releaseInfo,
            imdbRating = imdbRating,
            genres = genres,
            addonBaseUrl = addonBaseUrl,
            listKeys = setOf(listKey),
            listedAt = System.currentTimeMillis(),
            imdbId = imdbId,
            tmdbId = tmdbId
        )
    }

    private fun buildEmptySnapshot() = Snapshot(
        listTabs = listOf(
            LibraryListTab(key = WATCHLIST_KEY, title = "SIMKL Watchlist", type = LibraryListTab.Type.WATCHLIST),
            LibraryListTab(key = WATCHING_KEY, title = "Watching", type = LibraryListTab.Type.SERVICE),
            LibraryListTab(key = COMPLETED_KEY, title = "Completed", type = LibraryListTab.Type.SERVICE),
            LibraryListTab(key = HOLD_KEY, title = "On Hold", type = LibraryListTab.Type.SERVICE),
            LibraryListTab(key = DROPPED_KEY, title = "Dropped", type = LibraryListTab.Type.SERVICE)
        )
    )

    private fun restoreSnapshotForProfile(profileId: Int) {
        val persisted = snapshotStore.read(profileId)
        if (persisted == null) {
            clearInMemorySnapshot()
            return
        }
        restorePersistedSnapshot(persisted)
    }

    private fun snapshotFromPersisted(profileId: Int): Snapshot {
        val persisted = snapshotStore.read(profileId) ?: return buildEmptySnapshot()
        return rebuildSnapshotFromRollback(
            LibraryRollbackState(
                listTabs = persisted.listTabs,
                entriesByList = persisted.entriesByList
            )
        ).copy(updatedAtMs = persisted.updatedAtMs)
    }

    private fun restorePersistedSnapshot(persisted: SimklLibrarySnapshotStore.Snapshot) {
        val restored = rebuildSnapshotFromRollback(
            LibraryRollbackState(
                listTabs = persisted.listTabs,
                entriesByList = persisted.entriesByList
            )
        ).copy(updatedAtMs = persisted.updatedAtMs)
        metadataState.value = persisted.metadataByContentKey.mapValues { (_, metadata) ->
            LibraryMetadata(
                name = metadata.name,
                poster = metadata.poster,
                background = metadata.background,
                logo = metadata.logo,
                description = metadata.description,
                releaseInfo = metadata.releaseInfo,
                imdbRating = metadata.imdbRating,
                genres = metadata.genres
            )
        }
        snapshotState.value = restored
        hasCacheState.value = restored.updatedAtMs > 0L || restored.allEntries.isNotEmpty()
        applyActivityTimestamps(
            ActivityTimestamps(
                lastTvShowsAllAt = persisted.lastTvShowsAllAt,
                lastMoviesAllAt = persisted.lastMoviesAllAt,
                lastAnimeAllAt = persisted.lastAnimeAllAt,
                lastTvShowsRemovedFromListAt = persisted.lastTvShowsRemovedFromListAt,
                lastMoviesRemovedFromListAt = persisted.lastMoviesRemovedFromListAt,
                lastAnimeRemovedFromListAt = persisted.lastAnimeRemovedFromListAt
            )
        )
    }

    private fun clearInMemorySnapshot() {
        snapshotState.value = buildEmptySnapshot()
        metadataState.value = emptyMap()
        hasCacheState.value = false
        lastTvShowsAllAt = null
        lastMoviesAllAt = null
        lastAnimeAllAt = null
        lastTvShowsRemovedFromListAt = null
        lastMoviesRemovedFromListAt = null
        lastAnimeRemovedFromListAt = null
        lastAutoRefreshMs = 0L
    }

    private fun persistSnapshot(
        snapshot: Snapshot,
        metadata: Map<String, LibraryMetadata>,
        profileId: Int = activeProfileId(),
        timestamps: ActivityTimestamps = currentActivityTimestamps()
    ) {
        snapshotStore.write(
            SimklLibrarySnapshotStore.Snapshot(
                listTabs = snapshot.listTabs,
                entriesByList = snapshot.entriesByList,
                metadataByContentKey = metadata.mapValues { (_, value) ->
                    SimklLibrarySnapshotStore.PersistedLibraryMetadata(
                        name = value.name,
                        poster = value.poster,
                        background = value.background,
                        logo = value.logo,
                        description = value.description,
                        releaseInfo = value.releaseInfo,
                        imdbRating = value.imdbRating,
                        genres = value.genres
                    )
                },
                updatedAtMs = snapshot.updatedAtMs,
                lastTvShowsAllAt = timestamps.lastTvShowsAllAt,
                lastMoviesAllAt = timestamps.lastMoviesAllAt,
                lastAnimeAllAt = timestamps.lastAnimeAllAt,
                lastTvShowsRemovedFromListAt = timestamps.lastTvShowsRemovedFromListAt,
                lastMoviesRemovedFromListAt = timestamps.lastMoviesRemovedFromListAt,
                lastAnimeRemovedFromListAt = timestamps.lastAnimeRemovedFromListAt
            ),
            profileId
        )
    }

    private fun currentActivityTimestamps(): ActivityTimestamps {
        return ActivityTimestamps(
            lastTvShowsAllAt = lastTvShowsAllAt,
            lastMoviesAllAt = lastMoviesAllAt,
            lastAnimeAllAt = lastAnimeAllAt,
            lastTvShowsRemovedFromListAt = lastTvShowsRemovedFromListAt,
            lastMoviesRemovedFromListAt = lastMoviesRemovedFromListAt,
            lastAnimeRemovedFromListAt = lastAnimeRemovedFromListAt
        )
    }

    private fun persistedActivityTimestamps(profileId: Int): ActivityTimestamps {
        val persisted = snapshotStore.read(profileId) ?: return ActivityTimestamps()
        return ActivityTimestamps(
            lastTvShowsAllAt = persisted.lastTvShowsAllAt,
            lastMoviesAllAt = persisted.lastMoviesAllAt,
            lastAnimeAllAt = persisted.lastAnimeAllAt,
            lastTvShowsRemovedFromListAt = persisted.lastTvShowsRemovedFromListAt,
            lastMoviesRemovedFromListAt = persisted.lastMoviesRemovedFromListAt,
            lastAnimeRemovedFromListAt = persisted.lastAnimeRemovedFromListAt
        )
    }

    private fun persistedMetadataForProfile(profileId: Int): Map<String, LibraryMetadata> {
        return snapshotStore.read(profileId)?.metadataByContentKey.orEmpty().mapValues { (_, metadata) ->
            LibraryMetadata(
                name = metadata.name,
                poster = metadata.poster,
                background = metadata.background,
                logo = metadata.logo,
                description = metadata.description,
                releaseInfo = metadata.releaseInfo,
                imdbRating = metadata.imdbRating,
                genres = metadata.genres
            )
        }
    }

    private fun applyActivityTimestamps(timestamps: ActivityTimestamps) {
        lastTvShowsAllAt = timestamps.lastTvShowsAllAt
        lastMoviesAllAt = timestamps.lastMoviesAllAt
        lastAnimeAllAt = timestamps.lastAnimeAllAt
        lastTvShowsRemovedFromListAt = timestamps.lastTvShowsRemovedFromListAt
        lastMoviesRemovedFromListAt = timestamps.lastMoviesRemovedFromListAt
        lastAnimeRemovedFromListAt = timestamps.lastAnimeRemovedFromListAt
    }

    private fun activeProfileId(): Int = profileManager?.activeProfileId?.value ?: 1
}
