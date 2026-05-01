package com.nexio.tv.data.repository

import android.os.SystemClock
import android.util.Log
import com.nexio.tv.core.integration.IntegrationOwnershipService
import com.nexio.tv.core.integration.RailKeyFactory
import com.nexio.tv.core.profile.ProfileManager
import com.nexio.tv.data.integration.trakt.TraktIntegrationProvider
import com.nexio.tv.data.local.DebugSettingsDataStore
import com.nexio.tv.data.local.TraktAuthDataStore
import com.nexio.tv.data.local.TraktLibrarySnapshotStore
import com.nexio.tv.data.repository.trakt.TraktLibraryMutationAdapter
import com.nexio.tv.data.trakt.outbox.TraktMutationEnvelope
import com.nexio.tv.data.trakt.outbox.ProviderMutationOutboxCoordinator
import com.nexio.tv.data.repository.hasAnyId
import com.nexio.tv.data.repository.normalizeContentId
import com.nexio.tv.data.repository.parseContentIds
import com.nexio.tv.data.repository.parseIsoToMillis
import com.nexio.tv.data.repository.toTraktIds
import com.nexio.tv.data.remote.dto.trakt.TraktCreateOrUpdateListRequestDto
import com.nexio.tv.data.remote.dto.trakt.TraktIdsDto
import com.nexio.tv.data.remote.dto.trakt.TraktListItemDto
import com.nexio.tv.data.remote.dto.trakt.TraktListItemsMutationRequestDto
import com.nexio.tv.data.remote.dto.trakt.TraktListMovieRequestItemDto
import com.nexio.tv.data.remote.dto.trakt.TraktListShowRequestItemDto
import com.nexio.tv.data.remote.dto.trakt.TraktListSummaryDto
import com.nexio.tv.data.remote.dto.trakt.TraktReorderListsRequestDto
import com.nexio.tv.domain.model.LibraryEntry
import com.nexio.tv.domain.model.LibraryEntryInput
import com.nexio.tv.domain.model.LibraryListTab
import com.nexio.tv.domain.model.ListMembershipChanges
import com.nexio.tv.domain.model.ListMembershipSnapshot
import com.nexio.tv.domain.model.TrackingProvider
import com.nexio.tv.domain.model.TraktListPrivacy
import com.nexio.tv.core.metadata.router.MetadataDepth
import com.nexio.tv.core.metadata.router.MetadataRequest
import com.nexio.tv.core.metadata.router.MetadataRouterFacade
import com.nexio.tv.core.metadata.router.MetadataSourceContext
import com.nexio.tv.domain.model.ContentType
import com.nexio.tv.domain.model.HomeDisplayMetadata
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class TraktLibraryService @Inject constructor(
    private val traktIntegrationProvider: TraktIntegrationProvider,
    private val traktAuthService: TraktRepositoryAuthGateway,
    private val traktMutationOutboxCoordinator: ProviderMutationOutboxCoordinator,
    private val metadataRouterFacade: MetadataRouterFacade,
    private val debugSettingsDataStore: DebugSettingsDataStore,
    private val traktAuthDataStore: TraktAuthDataStore,
    private val snapshotStore: TraktLibrarySnapshotStore,
    private val profileManager: ProfileManager? = null,
    private val ownershipService: IntegrationOwnershipService? = null
) {
    data class LibraryRollbackState(
        val listTabs: List<LibraryListTab> = emptyList(),
        val entriesByList: Map<String, List<LibraryEntry>> = emptyMap(),
        val replaceAll: Boolean = false
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

    private data class Snapshot(
        val listTabs: List<LibraryListTab> = emptyList(),
        val entriesByList: Map<String, List<LibraryEntry>> = emptyMap(),
        val allEntries: List<LibraryEntry> = emptyList(),
        val membershipByContent: Map<String, Set<String>> = emptyMap(),
        val updatedAtMs: Long = 0L
    )

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val snapshotState = MutableStateFlow(Snapshot())
    private val metadataState = MutableStateFlow<Map<String, LibraryMetadata>>(emptyMap())
    private val hasCacheState = MutableStateFlow(false)
    private val refreshingState = MutableStateFlow(false)
    private val refreshMutex = Mutex()
    private val metadataMutex = Mutex()
    private val inFlightMetadataKeys = mutableSetOf<String>()
    private var lastRefreshMs: Long = 0L

    private val cacheTtlMs = 24L * 60 * 60 * 1_000L
    private val metadataHydrationLimit = 110
    private val listFetchConcurrency = 3
    private val metadataFetchSemaphore = Semaphore(5)

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

    fun observeListTabs(): Flow<List<LibraryListTab>> {
        return snapshotState
            .map { it.listTabs }
            .distinctUntilChanged()
    }

    fun observeAllItems(): Flow<List<LibraryEntry>> {
        return combine(snapshotState, metadataState) { snapshot, metadata ->
            enrichEntries(snapshot.allEntries, metadata)
        }.distinctUntilChanged()
    }

    fun observeMembership(itemId: String, itemType: String): Flow<Set<String>> {
        val key = contentKey(itemId = itemId, itemType = itemType)
        return snapshotState
            .map { snapshot -> snapshot.membershipByContent[key].orEmpty() }
            .distinctUntilChanged()
    }

    fun observeIsRefreshing(): Flow<Boolean> {
        return refreshingState
    }

    fun observeHasCache(): Flow<Boolean> {
        return hasCacheState
    }

    suspend fun getMembershipSnapshot(item: LibraryEntryInput): ListMembershipSnapshot {
        ensureFresh()
        val snapshot = snapshotState.value
        val key = contentKey(item.itemId, item.itemType)
        val memberships = snapshot.membershipByContent[key].orEmpty()
        val map = snapshot.listTabs.associate { tab ->
            tab.key to memberships.contains(tab.key)
        }
        return ListMembershipSnapshot(listMembership = map)
    }

    suspend fun toggleWatchlist(item: LibraryEntryInput) {
        ensureFresh()
        val key = contentKey(item.itemId, item.itemType)
        val currentMembership = snapshotState.value.membershipByContent[key].orEmpty()
        val isInWatchlist = currentMembership.contains(WATCHLIST_KEY)
        if (isInWatchlist) {
            performOptimisticMutation(
                optimistic = { snapshot -> removeItemFromList(snapshot, item, WATCHLIST_KEY) }
            ) { before, _, _ ->
                removeFromWatchlist(
                    item = item,
                    rollbackState = rollbackStateForList(before, WATCHLIST_KEY)
                )
            }
        } else {
            performOptimisticMutation(
                optimistic = { snapshot -> addItemToList(snapshot, item, WATCHLIST_KEY) }
            ) { before, _, _ ->
                addToWatchlist(
                    item = item,
                    rollbackState = rollbackStateForList(before, WATCHLIST_KEY)
                )
            }
        }
    }

    suspend fun applyMembershipChanges(
        item: LibraryEntryInput,
        changes: ListMembershipChanges
    ) {
        ensureFresh()
        val current = getMembershipSnapshot(item).listMembership
        val desired = changes.desiredMembership
        val keys = (current.keys + desired.keys).distinct()

        keys.forEach { listKey ->
            val before = current[listKey] == true
            val after = desired[listKey] == true
            if (before == after) return@forEach

            if (listKey == WATCHLIST_KEY) {
                if (after) {
                    performOptimisticMutation(
                        optimistic = { snapshot -> addItemToList(snapshot, item, WATCHLIST_KEY) }
                    ) { before, _, _ ->
                        addToWatchlist(
                            item = item,
                            rollbackState = rollbackStateForList(before, WATCHLIST_KEY)
                        )
                    }
                } else {
                    performOptimisticMutation(
                        optimistic = { snapshot -> removeItemFromList(snapshot, item, WATCHLIST_KEY) }
                    ) { before, _, _ ->
                        removeFromWatchlist(
                            item = item,
                            rollbackState = rollbackStateForList(before, WATCHLIST_KEY)
                        )
                    }
                }
            } else {
                val listId = listIdFromKey(listKey) ?: return@forEach
                if (after) {
                    performOptimisticMutation(
                        optimistic = { snapshot -> addItemToList(snapshot, item, listKey) }
                    ) { before, _, _ ->
                        addToPersonalList(
                            listId = listId,
                            item = item,
                            rollbackState = rollbackStateForList(before, listKey)
                        )
                    }
                } else {
                    performOptimisticMutation(
                        optimistic = { snapshot -> removeItemFromList(snapshot, item, listKey) }
                    ) { before, _, _ ->
                        removeFromPersonalList(
                            listId = listId,
                            item = item,
                            rollbackState = rollbackStateForList(before, listKey)
                        )
                    }
                }
            }
        }
    }

    suspend fun createPersonalList(
        name: String,
        description: String?,
        privacy: TraktListPrivacy
    ) {
        val request = TraktCreateOrUpdateListRequestDto(
            name = name,
            description = description,
            privacy = privacy.apiValue
        )
        val provisionalKey = provisionalListKey()
        performOptimisticMutation(
            optimistic = { snapshot ->
                val provisionalTab = LibraryListTab(
                    key = provisionalKey,
                    title = name,
                    type = LibraryListTab.Type.PERSONAL,
                    description = description,
                    privacy = privacy
                )
                val updatedTabs = snapshot.listTabs + provisionalTab
                val updatedEntries = snapshot.entriesByList + (provisionalKey to emptyList())
                rebuildSnapshot(updatedTabs, updatedEntries)
            }
        ) {
            enqueueLibraryMutation(
                TraktLibraryMutationAdapter.buildCreateListEnvelope(
                    provisionalKey = provisionalKey,
                    body = request
                )
            )
        }
    }

    suspend fun updatePersonalList(
        listId: String,
        name: String,
        description: String?,
        privacy: TraktListPrivacy
    ) {
        performOptimisticMutation(
            optimistic = { snapshot ->
                val updatedTabs = snapshot.listTabs.map { tab ->
                    if (matchesPersonalListIdentifier(tab, listId)) {
                        tab.copy(title = name, description = description, privacy = privacy)
                    } else {
                        tab
                    }
                }
                rebuildSnapshot(updatedTabs, snapshot.entriesByList)
            }
        ) { before, _, _ ->
            enqueueLibraryMutation(
                TraktLibraryMutationAdapter.buildUpdateListEnvelope(
                    listId = listId,
                    body = TraktCreateOrUpdateListRequestDto(
                        name = name,
                        description = description,
                        privacy = privacy.apiValue
                    ),
                    rollbackState = rollbackState(before)
                )
            )
        }
    }

    suspend fun deletePersonalList(listId: String) {
        performOptimisticMutation(
            optimistic = { snapshot ->
                val removedKeys = snapshot.listTabs
                    .filter { matchesPersonalListIdentifier(it, listId) }
                    .map { it.key }
                    .toSet()
                val updatedTabs = snapshot.listTabs.filterNot { it.key in removedKeys }
                val updatedEntries = snapshot.entriesByList.filterKeys { it !in removedKeys }
                rebuildSnapshot(updatedTabs, updatedEntries)
            }
        ) { before, _, _ ->
            enqueueLibraryMutation(
                TraktLibraryMutationAdapter.buildDeleteListEnvelope(
                    listId = listId,
                    rollbackState = rollbackState(before)
                )
            )
        }
    }

    suspend fun reorderPersonalLists(orderedListIds: List<String>) {
        val rank = orderedListIds.mapNotNull { raw ->
            raw.removePrefix(PERSONAL_KEY_PREFIX).toLongOrNull()
        }
        if (rank.isEmpty()) return

        performOptimisticMutation(
            optimistic = { snapshot ->
                val personalTabs = snapshot.listTabs.filter { it.type == LibraryListTab.Type.PERSONAL }
                val orderedTabs = orderedListIds.mapNotNull { id ->
                    personalTabs.firstOrNull { matchesPersonalListIdentifier(it, id) }
                }.distinctBy { it.key }
                val remainingTabs = snapshot.listTabs.filter { tab ->
                    tab.type != LibraryListTab.Type.PERSONAL || orderedTabs.none { it.key == tab.key }
                }
                rebuildSnapshot(
                    tabs = remainingTabs + orderedTabs,
                    rawEntriesByList = snapshot.entriesByList
                )
            }
        ) { before, _, _ ->
            enqueueLibraryMutation(
                TraktLibraryMutationAdapter.buildReorderListsEnvelope(
                    rank = rank,
                    rollbackState = rollbackState(before)
                )
            )
        }
    }

    private suspend fun enqueueLibraryMutation(
        envelope: TraktMutationEnvelope
    ) {
        traktMutationOutboxCoordinator.enqueueAndDrain(envelope.copy(profileId = activeProfileId()))
    }

    suspend fun refreshNow() {
        refresh(force = true)
    }

    suspend fun ensureFresh() {
        refresh(force = false)
    }

    private suspend fun refresh(force: Boolean, profileId: Int = activeProfileId()): Boolean {
        val now = System.currentTimeMillis()
        return refreshMutex.withLock {
            // F2-F-07: manual staleness guard — `profileId == activeProfileId()` is functionally
            // correct but inconsistent with assertCanWriteProfileState. Future migration: route
            // through ProfileBoundaryEnforcer for unified observability (boundary_check trace
            // events fire on PASS + FAIL). Until then, this guard prevents stale-profile writes
            // without emitting a boundary_check trace event.
            if (
                profileId == activeProfileId() &&
                !force &&
                now - lastRefreshMs <= cacheTtlMs &&
                snapshotState.value.updatedAtMs > 0L
            ) {
                return@withLock true
            }

            val activeAtStart = profileId == activeProfileId()
            if (activeAtStart) refreshingState.value = true
            try {
                val session = TrackingAuthSession(TrackingProvider.TRAKT, profileId)
                val previousMetadata = if (profileId == activeProfileId()) {
                    metadataState.value
                } else {
                    persistedMetadataForProfile(profileId)
                }
                val refreshed = runCatching { fetchSnapshot(session) }.getOrNull() ?: return@withLock false
                val baseSnapshot = applyMetadata(refreshed, previousMetadata)
                val primedMetadata = primeMetadata(baseSnapshot.allEntries, previousMetadata)
                val snapshotToPersist = applyMetadata(baseSnapshot, primedMetadata)
                persistAndRestoreSnapshot(snapshotToPersist, primedMetadata, profileId = profileId)
                if (profileId == activeProfileId()) {
                    hydrateMetadata(snapshotState.value.allEntries, profileId)
                }
                true
            } finally {
                if (activeAtStart || profileId == activeProfileId()) {
                    refreshingState.value = false
                }
            }
        }
    }

    private suspend fun performOptimisticMutation(
        optimistic: (Snapshot) -> Snapshot,
        mutation: suspend () -> Unit
    ) {
        performOptimisticMutation(optimistic = optimistic) { _, _, _ ->
            mutation()
        }
    }

    private suspend fun <T> performOptimisticMutation(
        optimistic: (Snapshot) -> Snapshot,
        mutation: suspend (before: Snapshot, beforeMetadata: Map<String, LibraryMetadata>, optimisticSnapshot: Snapshot) -> T
    ) {
        val before = snapshotState.value
        val beforeMetadata = metadataState.value
        val profileId = activeProfileId()
        val optimisticSnapshot = applyMetadata(optimistic(before), beforeMetadata)
        persistAndRestoreSnapshot(optimisticSnapshot, beforeMetadata, profileId = profileId)
        hydrateMetadata(snapshotState.value.allEntries, profileId)
        try {
            mutation(before, beforeMetadata, optimisticSnapshot)
        } catch (error: Throwable) {
            persistAndRestoreSnapshot(before, beforeMetadata, profileId = profileId)
            throw error
        }
    }

    private fun addItemToList(snapshot: Snapshot, item: LibraryEntryInput, listKey: String): Snapshot {
        val key = contentKey(item.itemId, item.itemType)
        val normalizedType = normalizeItemType(item.itemType)
        val normalizedId = normalizeContentId(resolveIds(item), fallback = item.itemId.trim())
            .ifBlank { item.itemId.trim() }
        val existing = snapshot.allEntries.firstOrNull { contentKey(it.id, it.type) == key }
        val entry = (existing ?: LibraryEntry(
            id = normalizedId,
            type = normalizedType,
            name = item.title.ifBlank { normalizedId },
            poster = item.poster,
            posterShape = item.posterShape,
            background = item.background,
            logo = item.logo,
            description = item.description,
            releaseInfo = item.releaseInfo ?: item.year?.toString(),
            imdbRating = item.imdbRating,
            genres = item.genres,
            addonBaseUrl = item.addonBaseUrl,
            imdbId = item.imdbId,
            tmdbId = item.tmdbId,
            traktId = item.traktId
        )).copy(
            listedAt = System.currentTimeMillis(),
            listKeys = existing?.listKeys.orEmpty() + listKey
        )

        val existingEntries = snapshot.entriesByList[listKey].orEmpty()
        val updatedListEntries = listOf(entry) + existingEntries.filterNot { contentKey(it.id, it.type) == key }
        val updatedEntriesByList = snapshot.entriesByList + (listKey to updatedListEntries)
        return rebuildSnapshot(snapshot.listTabs, updatedEntriesByList)
    }

    private fun removeItemFromList(snapshot: Snapshot, item: LibraryEntryInput, listKey: String): Snapshot {
        val key = contentKey(item.itemId, item.itemType)
        val updatedEntriesByList = snapshot.entriesByList + (
            listKey to snapshot.entriesByList[listKey].orEmpty()
                .filterNot { contentKey(it.id, it.type) == key }
        )
        return rebuildSnapshot(snapshot.listTabs, updatedEntriesByList)
    }

    private fun rebuildSnapshot(
        tabs: List<LibraryListTab>,
        rawEntriesByList: Map<String, List<LibraryEntry>>
    ): Snapshot {
        val membership = mutableMapOf<String, MutableSet<String>>()
        rawEntriesByList.forEach { (listKey, entries) ->
            entries.forEach { entry ->
                val lists = membership.getOrPut(contentKey(entry.id, entry.type)) { mutableSetOf() }
                lists.add(listKey)
                for (alias in allContentKeys(entry)) {
                    membership.getOrPut(alias) { mutableSetOf() }.add(listKey)
                }
            }
        }

        val allEntriesByContent = linkedMapOf<String, LibraryEntry>()
        rawEntriesByList.values.flatten()
            .sortedByDescending { it.listedAt }
            .forEach { entry ->
                val key = contentKey(entry.id, entry.type)
                allEntriesByContent[key] = entry.copy(listKeys = membership[key].orEmpty())
            }

        val entriesByList = rawEntriesByList.mapValues { (_, entries) ->
            entries.map { entry ->
                val key = contentKey(entry.id, entry.type)
                entry.copy(listKeys = membership[key].orEmpty())
            }
        }

        return Snapshot(
            listTabs = tabs,
            entriesByList = entriesByList,
            allEntries = allEntriesByContent.values.toList(),
            membershipByContent = membership.mapValues { it.value.toSet() },
            updatedAtMs = System.currentTimeMillis()
        )
    }

    private suspend fun fetchSnapshot(session: TrackingAuthSession): Snapshot {
        val watchlistEntries = fetchWatchlistEntries(session)

        val personalLists = fetchPersonalLists(session)
        val personalTabs = personalLists.tabs
        val personalEntriesByList = personalLists.entriesByList

        val tabs = buildList {
            add(
                LibraryListTab(
                    key = WATCHLIST_KEY,
                    title = "Trakt Watchlist",
                    type = LibraryListTab.Type.WATCHLIST,
                    sortBy = "rank",
                    sortHow = "asc"
                )
            )
            addAll(personalTabs)
        }

        val rawEntriesByList = linkedMapOf<String, List<LibraryEntry>>().apply {
            put(WATCHLIST_KEY, watchlistEntries)
            personalTabs.forEach { tab ->
                put(tab.key, personalEntriesByList[tab.key].orEmpty())
            }
        }

        val membership = mutableMapOf<String, MutableSet<String>>()
        rawEntriesByList.forEach { (listKey, entries) ->
            entries.forEach { entry ->
                val primaryKey = contentKey(entry.id, entry.type)
                membership.getOrPut(primaryKey) { mutableSetOf() }.add(listKey)
                for (alias in allContentKeys(entry)) {
                    membership.getOrPut(alias) { mutableSetOf() }.add(listKey)
                }
            }
        }

        val allEntriesByContent = linkedMapOf<String, LibraryEntry>()
        rawEntriesByList.values.flatten()
            .sortedByDescending { it.listedAt }
            .forEach { entry ->
                val key = contentKey(entry.id, entry.type)
                allEntriesByContent[key] = entry
            }

        val allEntries = allEntriesByContent.map { (key, entry) ->
            entry.copy(listKeys = membership[key].orEmpty())
        }.sortedByDescending { it.listedAt }

        val entriesByList = rawEntriesByList.mapValues { (_, entries) ->
            entries.map { entry ->
                entry.copy(listKeys = membership[contentKey(entry.id, entry.type)].orEmpty())
            }
        }

        return Snapshot(
            listTabs = tabs,
            entriesByList = entriesByList,
            allEntries = allEntries,
            membershipByContent = membership.mapValues { it.value.toSet() },
            updatedAtMs = System.currentTimeMillis()
        )
    }

    private suspend fun fetchWatchlistEntries(session: TrackingAuthSession): List<LibraryEntry> {
        val moviesResponse = traktIntegrationProvider.getWatchlist(
            session = session,
            type = "movies"
        ) ?: throw IllegalStateException("Failed to fetch watchlist movies")

        val showsResponse = traktIntegrationProvider.getWatchlist(
            session = session,
            type = "shows"
        ) ?: throw IllegalStateException("Failed to fetch watchlist shows")

        if (!moviesResponse.isSuccessful || !showsResponse.isSuccessful) {
            throw IllegalStateException("Failed to fetch watchlist")
        }

        return (moviesResponse.body().orEmpty() + showsResponse.body().orEmpty())
            .mapNotNull { mapListItem(listKey = WATCHLIST_KEY, item = it) }
            .sortedWith(
                compareBy<LibraryEntry> { it.traktRank ?: Int.MAX_VALUE }
                    .thenByDescending { it.listedAt }
            )
    }

    private data class PersonalListFetchResult(
        val tabs: List<LibraryListTab>,
        val entriesByList: Map<String, List<LibraryEntry>>
    )

    private suspend fun fetchPersonalLists(session: TrackingAuthSession): PersonalListFetchResult {
        val response = traktIntegrationProvider.getUserLists(
            session = session,
            id = ME_PATH
        ) ?: throw IllegalStateException("Failed to fetch personal lists")

        if (!response.isSuccessful) {
            throw IllegalStateException("Failed to fetch personal lists (${response.code()})")
        }

        val personal = response.body().orEmpty()
            .filter { it.type.equals("personal", ignoreCase = true) }

        val tabs = personal.mapNotNull { mapListTab(it) }
        val entriesByList = coroutineScope {
            val semaphore = Semaphore(listFetchConcurrency)
            tabs.map { tab ->
                async {
                    semaphore.withPermit {
                        val listIdPath = tab.traktListId?.toString() ?: tab.slug
                        if (listIdPath.isNullOrBlank()) {
                            tab.key to emptyList()
                        } else {
                            val movies = fetchPersonalListItems(session, listIdPath, "movie", tab.key)
                            val shows = fetchPersonalListItems(session, listIdPath, "show", tab.key)
                            tab.key to (movies + shows).sortedWith(
                                compareBy<LibraryEntry> { it.traktRank ?: Int.MAX_VALUE }
                                    .thenByDescending { it.listedAt }
                            )
                        }
                    }
                }
            }.map { it.await() }.toMap()
        }

        return PersonalListFetchResult(
            tabs = tabs,
            entriesByList = entriesByList
        )
    }

    private suspend fun fetchPersonalListItems(
        session: TrackingAuthSession,
        listIdPath: String,
        type: String,
        listKey: String
    ): List<LibraryEntry> {
        val response = traktIntegrationProvider.getUserListItems(
            session = session,
            id = ME_PATH,
            listId = listIdPath,
            type = type
        ) ?: throw IllegalStateException("Failed to fetch list items")

        if (!response.isSuccessful) {
            throw IllegalStateException("Failed to fetch list items (${response.code()})")
        }
        return response.body().orEmpty()
            .mapNotNull { mapListItem(listKey = listKey, item = it) }
    }

    private fun mapListTab(dto: TraktListSummaryDto): LibraryListTab? {
        val traktId = dto.ids?.trakt
        val slug = dto.ids?.slug
        val listIdPath = traktId?.toString() ?: slug ?: return null

        return LibraryListTab(
            key = PERSONAL_KEY_PREFIX + listIdPath,
            title = dto.name?.takeIf { it.isNotBlank() } ?: "List",
            type = LibraryListTab.Type.PERSONAL,
            traktListId = traktId,
            slug = slug,
            description = dto.description,
            privacy = TraktListPrivacy.fromApi(dto.privacy),
            sortBy = dto.sortBy,
            sortHow = dto.sortHow
        )
    }

    private fun mapListItem(listKey: String, item: TraktListItemDto): LibraryEntry? {
        val normalizedType = when (item.type?.lowercase()) {
            "movie" -> "movie"
            "show" -> "series"
            else -> return null
        }

        val mediaTitle = when (normalizedType) {
            "movie" -> item.movie?.title
            else -> item.show?.title
        }

        val mediaYear = when (normalizedType) {
            "movie" -> item.movie?.year
            else -> item.show?.year
        }

        val ids = when (normalizedType) {
            "movie" -> item.movie?.ids
            else -> item.show?.ids
        }

        val fallbackId = when {
            ids?.trakt != null -> "trakt:${ids.trakt}"
            item.id != null -> "trakt-item:${item.id}"
            !mediaTitle.isNullOrBlank() -> "${normalizedType}:${mediaTitle.lowercase()}:${mediaYear ?: 0}"
            else -> null
        } ?: return null

        val contentId = normalizeContentId(ids, fallback = fallbackId)
        if (contentId.isBlank()) return null

        return LibraryEntry(
            id = contentId,
            type = normalizedType,
            name = mediaTitle ?: contentId,
            poster = null,
            background = null,
            logo = null,
            description = null,
            releaseInfo = mediaYear?.toString(),
            imdbRating = null,
            genres = emptyList(),
            addonBaseUrl = null,
            listKeys = setOf(listKey),
            listedAt = parseIsoToMillis(item.listedAt),
            traktRank = item.rank,
            imdbId = ids?.imdb?.takeIf { it.isNotBlank() },
            tmdbId = ids?.tmdb,
            traktId = ids?.trakt
        )
    }

    private suspend fun addToWatchlist(
        item: LibraryEntryInput,
        rollbackState: LibraryRollbackState
    ) {
        val body = buildMutationBody(item)
        enqueueLibraryMutation(
            TraktLibraryMutationAdapter.buildWatchlistAddEnvelope(
                body = body,
                rollbackState = rollbackState
            )
        )
    }

    private suspend fun removeFromWatchlist(
        item: LibraryEntryInput,
        rollbackState: LibraryRollbackState
    ) {
        val body = buildMutationBody(item)
        enqueueLibraryMutation(
            TraktLibraryMutationAdapter.buildWatchlistRemoveEnvelope(
                body = body,
                rollbackState = rollbackState
            )
        )
    }

    private suspend fun addToPersonalList(
        listId: String,
        item: LibraryEntryInput,
        rollbackState: LibraryRollbackState
    ) {
        val body = buildMutationBody(item)
        enqueueLibraryMutation(
            TraktLibraryMutationAdapter.buildListAddEnvelope(
                listId = listId,
                body = body,
                rollbackState = rollbackState
            )
        )
    }

    private suspend fun removeFromPersonalList(
        listId: String,
        item: LibraryEntryInput,
        rollbackState: LibraryRollbackState
    ) {
        val body = buildMutationBody(item)
        enqueueLibraryMutation(
            TraktLibraryMutationAdapter.buildListRemoveEnvelope(
                listId = listId,
                body = body,
                rollbackState = rollbackState
            )
        )
    }

    private fun buildMutationBody(item: LibraryEntryInput): TraktListItemsMutationRequestDto {
        val ids = resolveIds(item)
        if (!ids.hasAnyId()) {
            throw IllegalStateException("Missing compatible IDs for Trakt list operation")
        }

        val normalizedType = normalizeItemType(item.itemType)
        return if (normalizedType == "movie") {
            TraktListItemsMutationRequestDto(
                movies = listOf(
                    TraktListMovieRequestItemDto(
                        title = item.title,
                        year = item.year,
                        ids = ids
                    )
                )
            )
        } else {
            TraktListItemsMutationRequestDto(
                shows = listOf(
                    TraktListShowRequestItemDto(
                        title = item.title,
                        year = item.year,
                        ids = ids
                    )
                )
            )
        }
    }

    private fun resolveIds(item: LibraryEntryInput): TraktIdsDto {
        val parsed = parseContentIds(item.itemId)
        return TraktIdsDto(
            trakt = item.traktId ?: parsed.trakt,
            imdb = item.imdbId ?: parsed.imdb,
            tmdb = item.tmdbId ?: parsed.tmdb
        )
    }

    private fun errorMessageForCode(code: Int, defaultMessage: String): String {
        return when (code) {
            401, 403 -> "Trakt authentication expired"
            404 -> "Trakt list not found"
            420 -> "Trakt list limit reached. Upgrade required."
            else -> "$defaultMessage ($code)"
        }
    }

    private fun listIdFromKey(key: String): String? {
        if (!key.startsWith(PERSONAL_KEY_PREFIX)) return null
        return key.removePrefix(PERSONAL_KEY_PREFIX).takeIf { it.isNotBlank() }
    }

    private fun matchesPersonalListIdentifier(tab: LibraryListTab, identifier: String): Boolean {
        if (tab.type != LibraryListTab.Type.PERSONAL) return false
        val normalized = identifier.removePrefix(PERSONAL_KEY_PREFIX)
        val tabKeySuffix = tab.key.removePrefix(PERSONAL_KEY_PREFIX)
        return tab.key == "$PERSONAL_KEY_PREFIX$normalized" ||
            tabKeySuffix == normalized ||
            tab.traktListId?.toString() == normalized ||
            tab.slug == normalized
    }

    private fun contentKey(itemId: String, itemType: String): String {
        val normalizedType = normalizeItemType(itemType)
        val parsed = parseContentIds(itemId)
        val normalizedId = normalizeContentId(toTraktIds(parsed), fallback = itemId.trim())
        val stableId = normalizedId.ifBlank { itemId.trim() }
        return "$normalizedType:$stableId"
    }

    private fun allContentKeys(entry: LibraryEntry): Set<String> {
        val type = normalizeItemType(entry.type)
        val keys = mutableSetOf(contentKey(entry.id, entry.type))
        entry.imdbId?.takeIf { it.isNotBlank() }?.let { keys.add("$type:$it") }
        entry.tmdbId?.let { keys.add("$type:tmdb:$it") }
        entry.traktId?.let { keys.add("$type:trakt:$it") }
        return keys
    }

    private fun normalizeItemType(itemType: String): String {
        return when (itemType.lowercase()) {
            "movie" -> "movie"
            "series", "show", "tv" -> "series"
            else -> itemType.lowercase()
        }
    }

    internal suspend fun reconcileQueuedCreateListSuccess(
        provisionalKey: String,
        createdList: TraktListSummaryDto?,
        profileId: Int = activeProfileId()
    ) {
        val createdTab = createdList?.let(::mapListTab)
        if (createdTab == null) {
            refresh(force = true, profileId = profileId)
            return
        }
        if (profileId != activeProfileId()) {
            refresh(force = true, profileId = profileId)
            return
        }
        val current = snapshotState.value
        val provisionalEntries = current.entriesByList[provisionalKey].orEmpty()
        var replaced = false
        val updatedTabs = current.listTabs.mapNotNull { tab ->
            if (tab.key == provisionalKey) {
                replaced = true
                createdTab
            } else if (tab.key == createdTab.key) {
                replaced = true
                null
            } else {
                tab
            }
        }.let { tabs ->
            if (replaced) tabs else tabs + createdTab
        }
        val updatedEntries = current.entriesByList
            .filterKeys { it != provisionalKey && it != createdTab.key }
            .toMutableMap()
            .apply {
                put(
                    createdTab.key,
                    provisionalEntries.map { entry ->
                        entry.copy(
                            listKeys = entry.listKeys
                                .map { key -> if (key == provisionalKey) createdTab.key else key }
                                .toSet()
                        )
                    }
                )
            }

        persistAndRestoreSnapshot(
            snapshot = rebuildSnapshot(updatedTabs, updatedEntries),
            metadata = metadataState.value,
            profileId = profileId
        )
    }

    internal suspend fun rollbackQueuedLibraryMutation(
        rollbackState: LibraryRollbackState,
        provisionalKeyToRemove: String? = null,
        profileId: Int = activeProfileId()
    ) {
        if (profileId != activeProfileId()) {
            refresh(force = true, profileId = profileId)
            return
        }
        if (provisionalKeyToRemove != null) {
            val current = snapshotState.value
            val updatedTabs = current.listTabs.filterNot { it.key == provisionalKeyToRemove }
            val updatedEntries = current.entriesByList.filterKeys { it != provisionalKeyToRemove }
            persistAndRestoreSnapshot(
                snapshot = rebuildSnapshot(updatedTabs, updatedEntries),
                metadata = metadataState.value,
                profileId = profileId
            )
        } else if (rollbackState.replaceAll) {
            persistAndRestoreSnapshot(
                snapshot = rebuildSnapshot(rollbackState.listTabs, rollbackState.entriesByList),
                metadata = metadataState.value,
                profileId = profileId
            )
        } else {
            val current = snapshotState.value
            val updatedTabs = mergeRollbackTabs(
                currentTabs = current.listTabs,
                rollbackTabs = rollbackState.listTabs
            )
            val updatedEntries = current.entriesByList.toMutableMap().apply {
                rollbackState.entriesByList.forEach { (key, entries) ->
                    this[key] = entries.map { entry -> entry.copy(listKeys = entry.listKeys.toSet()) }
                }
            }
            persistAndRestoreSnapshot(
                snapshot = rebuildSnapshot(updatedTabs, updatedEntries),
                metadata = metadataState.value,
                profileId = profileId
            )
        }
        refresh(force = true, profileId = profileId)
    }

    private fun rollbackState(snapshot: Snapshot): LibraryRollbackState {
        return LibraryRollbackState(
            listTabs = snapshot.listTabs,
            entriesByList = snapshot.entriesByList.mapValues { (_, entries) ->
                entries.map { entry -> entry.copy(listKeys = entry.listKeys.toSet()) }
            },
            replaceAll = true
        )
    }

    private fun rollbackStateForList(snapshot: Snapshot, listKey: String): LibraryRollbackState {
        return LibraryRollbackState(
            entriesByList = mapOf(
                listKey to snapshot.entriesByList[listKey].orEmpty().map { entry ->
                    entry.copy(listKeys = entry.listKeys.toSet())
                }
            )
        )
    }

    private fun mergeRollbackTabs(
        currentTabs: List<LibraryListTab>,
        rollbackTabs: List<LibraryListTab>
    ): List<LibraryListTab> {
        if (rollbackTabs.isEmpty()) return currentTabs
        val rollbackByKey = rollbackTabs.associateBy { it.key }
        val merged = currentTabs.map { tab -> rollbackByKey[tab.key] ?: tab }.toMutableList()
        rollbackTabs.forEach { rollbackTab ->
            if (merged.none { it.key == rollbackTab.key }) {
                merged.add(rollbackTab)
            }
        }
        return merged
    }

    private fun provisionalListKey(): String = PERSONAL_KEY_PREFIX + "pending:${UUID.randomUUID()}"

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

    private fun applyMetadata(
        snapshot: Snapshot,
        metadataMap: Map<String, LibraryMetadata>
    ): Snapshot {
        if (snapshot.allEntries.isEmpty() || metadataMap.isEmpty()) return snapshot
        return snapshot.copy(
            entriesByList = snapshot.entriesByList.mapValues { (_, entries) ->
                enrichEntries(entries, metadataMap)
            },
            allEntries = enrichEntries(snapshot.allEntries, metadataMap)
        )
    }

    private suspend fun primeMetadata(
        entries: List<LibraryEntry>,
        existingMetadata: Map<String, LibraryMetadata>
    ): Map<String, LibraryMetadata> {
        if (entries.isEmpty()) return existingMetadata

        val claimedEntries = metadataMutex.withLock {
            entries.take(metadataHydrationLimit)
                .map { contentKey(it.id, it.type) to it }
                .distinctBy { it.first }
                .mapNotNull { (key, entry) ->
                    if (existingMetadata.containsKey(key) || inFlightMetadataKeys.contains(key)) {
                        null
                    } else {
                        inFlightMetadataKeys.add(key)
                        key to entry
                    }
                }
        }

        if (claimedEntries.isEmpty()) return existingMetadata

        val fetchedMetadata = try {
            coroutineScope {
                claimedEntries.map { (key, entry) ->
                    async {
                        metadataFetchSemaphore.withPermit {
                            key to fetchMetadata(entry)
                        }
                    }
                }.mapNotNull { deferred ->
                    val (key, metadata) = deferred.await()
                    metadata?.let { key to it }
                }.toMap()
            }
        } finally {
            metadataMutex.withLock {
                claimedEntries.forEach { (key, _) -> inFlightMetadataKeys.remove(key) }
            }
        }

        if (fetchedMetadata.isEmpty()) return existingMetadata

        return existingMetadata + fetchedMetadata
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
                                persistAndRestoreSnapshot(snapshotState.value, updatedMetadata, profileId = profileId)
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
        val request = MetadataRequest(
            contentId = entry.id,
            contentType = ContentType.fromString(entry.type),
            sourceContext = MetadataSourceContext(
                itemType = entry.type,
                addonMetadata = HomeDisplayMetadata(
                    title = entry.name,
                    poster = entry.poster,
                    backdrop = entry.background,
                    logo = entry.logo,
                    description = entry.description,
                    releaseInfo = entry.releaseInfo,
                    imdbRating = entry.imdbRating,
                    genres = entry.genres
                )
            ),
            depth = MetadataDepth.DETAIL_CORE
        )
        val canonical = try {
            metadataRouterFacade.resolveRequest(request)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "fetchMetadata resolveRequest failed for ${entry.id}: ${e.message}", e)
            return null
        }
        if (canonical.route == null) return null
        val display = canonical.displayMetadata
        return LibraryMetadata(
            name = display.title ?: entry.name,
            poster = display.poster ?: entry.poster,
            background = display.backdrop ?: entry.background,
            logo = display.logo ?: entry.logo,
            description = display.description ?: entry.description,
            releaseInfo = display.releaseInfo ?: entry.releaseInfo,
            imdbRating = display.imdbRating ?: entry.imdbRating,
            genres = display.genres.takeIf { it.isNotEmpty() } ?: entry.genres
        )
    }

    private fun restorePersistedState(persisted: TraktLibrarySnapshotStore.Snapshot) {
        logDebug(
            "restore persisted updatedAtMs=${persisted.updatedAtMs} " +
                "listTabs=${persisted.listTabs.size} " +
                "listsWithEntries=${persisted.entriesByList.count { it.value.isNotEmpty() }} " +
                "metadata=${persisted.metadataByContentKey.size}"
        )
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
        snapshotState.value = rebuildSnapshot(
            tabs = persisted.listTabs,
            rawEntriesByList = persisted.entriesByList
        ).copy(updatedAtMs = persisted.updatedAtMs)
        lastRefreshMs = persisted.updatedAtMs
        hasCacheState.value = hasCache(persisted)
        logDebug(
            "restore applied updatedAtMs=${snapshotState.value.updatedAtMs} " +
                "listTabs=${snapshotState.value.listTabs.size} " +
                "allEntries=${snapshotState.value.allEntries.size} " +
                "hasCache=${hasCacheState.value}"
        )
    }

    private fun restoreSnapshotForProfile(profileId: Int) {
        val persisted = snapshotStore.read(profileId)
        if (persisted == null) {
            logDebug("restore found no persisted snapshot profile=$profileId")
            clearInMemorySnapshot()
            return
        }
        restorePersistedState(persisted)
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

    private suspend fun persistAndRestoreSnapshot(
        snapshot: Snapshot,
        metadata: Map<String, LibraryMetadata>,
        profileId: Int = activeProfileId()
    ) {
        logDebug(
            "persist start updatedAtMs=${snapshot.updatedAtMs} " +
                "listTabs=${snapshot.listTabs.size} " +
                "listsWithEntries=${snapshot.entriesByList.count { it.value.isNotEmpty() }} " +
                "metadata=${metadata.size}"
        )
        val persisted = TraktLibrarySnapshotStore.Snapshot(
            listTabs = snapshot.listTabs,
            entriesByList = snapshot.entriesByList,
            metadataByContentKey = metadata.mapValues { (_, value) ->
                TraktLibrarySnapshotStore.PersistedLibraryMetadata(
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
            updatedAtMs = snapshot.updatedAtMs
        )
        if (!hasCache(persisted)) {
            logDebug("persist aborted no cacheable content; clearing snapshot store")
            runCatching {
                ownershipService?.syncRails(
                    RailKeyFactory.traktLibraryNamespace(profileId),
                    emptyList()
                )
            }
            snapshotStore.clear(profileId)
            if (profileId == activeProfileId()) {
                clearInMemorySnapshot()
            }
            return
        }
        ownershipService?.syncRails(
            RailKeyFactory.traktLibraryNamespace(profileId),
            snapshotStore.buildRailMemberships(persisted, profileId)
        )
        snapshotStore.write(persisted, profileId)
        val restored = snapshotStore.read(profileId)
        if (restored == null) {
            logDebug("persist readback returned null; falling back to in-memory persisted snapshot")
        }
        if (profileId == activeProfileId()) {
            restorePersistedState(restored ?: persisted)
        }
    }

    private fun clearCachedState() {
        logDebug("clear cached state")
        clearInMemorySnapshot()
        refreshingState.value = false
        ownershipService?.let { ownership ->
            scope.launch {
                ownership.syncRails(
                    RailKeyFactory.traktLibraryNamespace(activeProfileId()),
                    emptyList()
                )
            }
        }
        snapshotStore.clear(activeProfileId())
    }

    private fun clearInMemorySnapshot() {
        logDebug(
            "clear in-memory snapshot previousUpdatedAtMs=${snapshotState.value.updatedAtMs} " +
                "previousTabs=${snapshotState.value.listTabs.size} " +
                "previousEntries=${snapshotState.value.allEntries.size}"
        )
        snapshotState.value = Snapshot()
        metadataState.value = emptyMap()
        lastRefreshMs = 0L
        hasCacheState.value = false
    }

    private fun hasCache(snapshot: Snapshot): Boolean {
        return snapshot.updatedAtMs > 0L ||
            snapshot.listTabs.isNotEmpty() ||
            snapshot.entriesByList.values.any { entries -> entries.isNotEmpty() }
    }

    private fun hasCache(snapshot: TraktLibrarySnapshotStore.Snapshot): Boolean {
        return snapshot.updatedAtMs > 0L ||
            snapshot.listTabs.isNotEmpty() ||
            snapshot.entriesByList.values.any { entries -> entries.isNotEmpty() }
    }

    private fun logDebug(message: String) {
        runCatching { Log.d(TAG, message) }
    }

    private fun activeProfileId(): Int = profileManager?.activeProfileId?.value ?: 1

    companion object {
        private const val TAG = "TraktLibraryService"
        const val WATCHLIST_KEY = "watchlist"
        const val PERSONAL_KEY_PREFIX = "personal:"
        private const val ME_PATH = "me"
    }
}
