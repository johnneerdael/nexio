package com.nexio.tv.data.integration.mdblist

import com.nexio.tv.data.remote.api.MDBListApi
import com.nexio.tv.data.remote.dto.mdblist.MDBListCreateListRequestDto
import com.nexio.tv.data.remote.dto.mdblist.MDBListListItemsResponseDto
import com.nexio.tv.data.remote.dto.mdblist.MDBListUpdateListRequestDto
import com.nexio.tv.data.remote.dto.mdblist.MDBListUserListDto
import com.nexio.tv.data.remote.dto.mdblist.MDBListWatchlistResponseDto
import com.nexio.tv.data.remote.dto.mdblist.MDBListWatchlistItemDto
import com.nexio.tv.core.profile.ProfileManager
import com.nexio.tv.data.local.MDBListLibrarySnapshotStore
import com.nexio.tv.data.repository.MDBListSettingsReader
import com.nexio.tv.domain.model.LibraryEntry
import com.nexio.tv.domain.model.LibraryEntryInput
import com.nexio.tv.domain.model.LibraryListTab
import com.nexio.tv.domain.model.PosterShape
import com.nexio.tv.domain.model.TraktListPrivacy
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
@OptIn(ExperimentalCoroutinesApi::class)
class MDBListLibraryService @Inject constructor(
    private val api: MDBListApi,
    private val settingsReader: MDBListSettingsReader,
    private val snapshotStore: MDBListLibrarySnapshotStore,
    private val profileManager: ProfileManager,
    private val rateLimitGuard: MDBListRateLimitGuard,
) {
    private class RuntimeState(
        snapshot: MDBListLibrarySnapshotStore.Snapshot? = null
    ) {
        val rows = MutableStateFlow(snapshot?.rows ?: emptyList())
        val tabs = MutableStateFlow(snapshot?.tabs ?: emptyList())
        val refreshing = MutableStateFlow(false)
        var lastRefreshMs: Long = snapshot?.updatedAtMs ?: 0L
        var cachedListKey: String? = snapshot?.selectedListKey

        fun clear(now: Long) {
            rows.value = emptyList()
            tabs.value = emptyList()
            cachedListKey = null
            lastRefreshMs = now
        }
    }

    private val refreshMutex = Mutex()
    private val states = mutableMapOf<Int, RuntimeState>()

    private fun stateFor(profileId: Int = activeProfileId()): RuntimeState =
        synchronized(states) {
            states.getOrPut(profileId) {
                RuntimeState(snapshotStore.read(profileId))
            }
        }

    fun observeAllItems(): Flow<List<LibraryEntry>> =
        profileManager.activeProfileId.flatMapLatest { profileId -> stateFor(profileId).rows }

    fun observeListTabs(): Flow<List<LibraryListTab>> =
        profileManager.activeProfileId.flatMapLatest { profileId -> stateFor(profileId).tabs }

    fun observeIsRefreshing(): Flow<Boolean> =
        profileManager.activeProfileId.flatMapLatest { profileId -> stateFor(profileId).refreshing }

    suspend fun refreshNow(force: Boolean = false, selectedListKey: String? = null) {
        ensureFresh(force = force, selectedListKey = selectedListKey)
    }

    suspend fun removeWatchlistItem(item: LibraryEntryInput) {
        val profileId = activeProfileId()
        val runtime = stateFor(profileId)
        val settings = settingsReader.settingsForProfile(profileId).first()
        val apiKey = settings.apiKey.trim()
        if (!settings.enabled || apiKey.isBlank()) return
        rateLimitGuard.throwIfBlocked()
        val response = api.mutateWatchlistItems(
            action = "remove",
            apiKey = apiKey,
            body = com.nexio.tv.data.repository.mdblist.MDBListIdMapper.watchlistPayloadFor(item),
        )
        if (rateLimitGuard.noteResponse(response) != null) {
            throw IllegalStateException("MDBList library request rate limited")
        }
        runtime.rows.value = removeItem(runtime.rows.value, item)
        persistSnapshot(profileId, runtime)
    }

    suspend fun ensureFresh(force: Boolean = false, selectedListKey: String? = null) {
        ensureFreshForProfile(
            profileId = activeProfileId(),
            force = force,
            selectedListKey = selectedListKey
        )
    }

    private suspend fun ensureFreshForProfile(
        profileId: Int,
        force: Boolean = false,
        selectedListKey: String? = null
    ) {
        refreshMutex.withLock {
            val runtime = stateFor(profileId)
            val now = System.currentTimeMillis()
            val selectedListId = listIdFromKey(selectedListKey)
            val listKey = selectedListId?.let(::personalListKey) ?: WATCHLIST_KEY
            if (!force && runtime.cachedListKey == listKey && runtime.lastRefreshMs > 0L) {
                return
            }

            runtime.refreshing.value = true
            try {
                val settings = settingsReader.settingsForProfile(profileId).first()
                val apiKey = settings.apiKey.trim()
                if (!settings.enabled || apiKey.isBlank()) {
                    runtime.clear(now)
                    persistSnapshot(profileId, runtime, now)
                    return
                }

                rateLimitGuard.throwIfBlocked()
                val listsResponse = api.getMyLists(apiKey = apiKey, sort = "ranked", unified = false)
                if (rateLimitGuard.noteResponse(listsResponse) != null) {
                    throw IllegalStateException("MDBList library request rate limited")
                }
                if (!listsResponse.isSuccessful) {
                    throw IllegalStateException("MDBList list tabs request failed (${listsResponse.code()})")
                }
                val userLists = listsResponse.body().orEmpty()
                runtime.tabs.value = buildTabs(userLists)

                val body = if (selectedListId != null) {
                    val response = api.getListItems(
                        listId = selectedListId,
                        apiKey = apiKey,
                        limit = WATCHLIST_LIMIT,
                        offset = 0,
                        unified = true
                    )
                    if (rateLimitGuard.noteResponse(response) != null) {
                        throw IllegalStateException("MDBList library request rate limited")
                    }
                    response.toWatchlistBody()
                } else {
                    val response = api.getWatchlistItems(
                        apiKey = apiKey,
                        limit = WATCHLIST_LIMIT,
                        offset = 0,
                        unified = true,
                    )
                    if (rateLimitGuard.noteResponse(response) != null) {
                        throw IllegalStateException("MDBList library request rate limited")
                    }
                    response.bodyIfSuccessful()
                }

                if (body == null) {
                    throw IllegalStateException("MDBList library request failed")
                }

                runtime.rows.value = buildRows(body.movies.orEmpty(), body.shows.orEmpty(), listKey = listKey)
                runtime.cachedListKey = listKey
                runtime.lastRefreshMs = now
                persistSnapshot(profileId, runtime, now)
            } finally {
                runtime.refreshing.value = false
            }
        }
    }

    suspend fun createStaticList(name: String, private: Boolean) {
        val profileId = activeProfileId()
        val apiKey = requireApiKey(profileId) ?: return
        rateLimitGuard.throwIfBlocked()
        val response = api.createStaticList(apiKey, MDBListCreateListRequestDto(name = name, private = private))
        if (rateLimitGuard.noteResponse(response) != null) {
            throw IllegalStateException("MDBList library request rate limited")
        }
        ensureFreshForProfile(profileId = profileId, force = true)
    }

    suspend fun updateStaticList(listId: String, name: String, private: Boolean) {
        val profileId = activeProfileId()
        val apiKey = requireApiKey(profileId) ?: return
        val id = listId.toLongOrNull() ?: listIdFromKey(listId) ?: return
        rateLimitGuard.throwIfBlocked()
        val response = api.updateStaticList(id, apiKey, MDBListUpdateListRequestDto(name = name, private = private))
        if (rateLimitGuard.noteResponse(response) != null) {
            throw IllegalStateException("MDBList library request rate limited")
        }
        ensureFreshForProfile(profileId = profileId, force = true, selectedListKey = personalListKey(id))
    }

    suspend fun deleteStaticList(listId: String) {
        val profileId = activeProfileId()
        val apiKey = requireApiKey(profileId) ?: return
        val id = listId.toLongOrNull() ?: listIdFromKey(listId) ?: return
        rateLimitGuard.throwIfBlocked()
        val response = api.deleteStaticList(id, apiKey)
        if (rateLimitGuard.noteResponse(response) != null) {
            throw IllegalStateException("MDBList library request rate limited")
        }
        ensureFreshForProfile(profileId = profileId, force = true)
    }

    private fun buildRows(
        movies: List<MDBListWatchlistItemDto>,
        shows: List<MDBListWatchlistItemDto>,
        listKey: String,
    ): List<LibraryEntry> {
        val out = ArrayList<LibraryEntry>(movies.size + shows.size)
        for (i in movies.indices) {
            out += movies[i].toLibraryEntry(type = "movie", listKey = listKey)
        }
        for (i in shows.indices) {
            out += shows[i].toLibraryEntry(type = "series", listKey = listKey)
        }
        return out
    }

    private fun removeItem(current: List<LibraryEntry>, item: LibraryEntryInput): List<LibraryEntry> {
        val itemImdb = item.imdbId?.takeIf { it.isNotBlank() }
        val itemTmdb = item.tmdbId
        val out = ArrayList<LibraryEntry>(current.size)
        for (i in current.indices) {
            val row = current[i]
            val matches = (itemImdb != null && row.imdbId == itemImdb) ||
                (itemTmdb != null && row.tmdbId == itemTmdb) ||
                row.id == item.itemId
            if (!matches) out += row
        }
        return out
    }

    private fun buildTabs(lists: List<MDBListUserListDto>): List<LibraryListTab> {
        val out = ArrayList<LibraryListTab>(lists.size + 1)
        out += LibraryListTab(
            key = WATCHLIST_KEY,
            title = "Watchlist",
            type = LibraryListTab.Type.WATCHLIST
        )
        for (i in lists.indices) {
            val list = lists[i]
            val mutableStatic = list.dynamic != true && list.type.equals("static", ignoreCase = true)
            out += LibraryListTab(
                key = personalListKey(list.id),
                title = list.name?.takeIf { it.isNotBlank() } ?: "MDBList ${list.id}",
                type = LibraryListTab.Type.PERSONAL,
                mdbListId = list.id,
                mdbListSlug = list.slug,
                mdbListType = list.type,
                description = list.description,
                privacy = if (list.private == true) TraktListPrivacy.PRIVATE else TraktListPrivacy.PUBLIC,
                isMutableStaticList = mutableStatic
            )
        }
        return out
    }

    private fun personalListKey(listId: Long): String = "$PERSONAL_KEY_PREFIX$listId"

    private fun persistSnapshot(
        profileId: Int,
        runtime: RuntimeState,
        updatedAtMs: Long = System.currentTimeMillis()
    ) {
        snapshotStore.write(
            MDBListLibrarySnapshotStore.Snapshot(
                rows = runtime.rows.value,
                tabs = runtime.tabs.value,
                selectedListKey = runtime.cachedListKey,
                updatedAtMs = updatedAtMs
            ),
            profileId
        )
    }

    private fun activeProfileId(): Int = profileManager.activeProfileId.value

    private fun listIdFromKey(key: String?): Long? {
        return key?.removePrefix(PERSONAL_KEY_PREFIX)?.takeIf { it != key }?.toLongOrNull()
    }

    private suspend fun requireApiKey(profileId: Int): String? {
        val settings = settingsReader.settingsForProfile(profileId).first()
        val apiKey = settings.apiKey.trim()
        return apiKey.takeIf { settings.enabled && it.isNotBlank() }
    }

    private fun retrofit2.Response<MDBListWatchlistResponseDto>.bodyIfSuccessful(): MDBListWatchlistResponseDto? {
        return if (isSuccessful) body() else null
    }

    private fun retrofit2.Response<MDBListListItemsResponseDto>.toWatchlistBody(): MDBListWatchlistResponseDto? {
        val body = if (isSuccessful) body() else null
        return body?.let {
            MDBListWatchlistResponseDto(
                movies = it.movies,
                shows = it.shows
            )
        }
    }

    private fun MDBListWatchlistItemDto.toLibraryEntry(type: String, listKey: String): LibraryEntry {
        val stableId = imdb?.takeIf { it.isNotBlank() }
            ?: tmdb?.let { "tmdb:$it" }
            ?: tvdb?.let { "tvdb:$it" }
            ?: "${type}:${title.orEmpty()}:${year ?: 0}"
        return LibraryEntry(
            id = stableId,
            type = type,
            name = title?.takeIf { it.isNotBlank() } ?: stableId,
            poster = null,
            posterShape = PosterShape.POSTER,
            background = null,
            logo = null,
            description = null,
            releaseInfo = year?.toString(),
            imdbRating = null,
            genres = emptyList(),
            addonBaseUrl = null,
            listKeys = setOf(listKey),
            imdbId = imdb?.takeIf { it.isNotBlank() },
            tmdbId = tmdb,
        )
    }

    companion object {
        const val WATCHLIST_KEY = "mdblist:watchlist"
        const val PERSONAL_KEY_PREFIX = "mdblist:list:"
        private const val WATCHLIST_LIMIT = 1000
    }
}
