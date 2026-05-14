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
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class MDBListLibraryService @Inject constructor(
    private val api: MDBListApi,
    private val settingsReader: MDBListSettingsReader,
    private val snapshotStore: MDBListLibrarySnapshotStore,
    private val profileManager: ProfileManager,
) {
    private val rows = MutableStateFlow<List<LibraryEntry>>(emptyList())
    private val tabs = MutableStateFlow<List<LibraryListTab>>(emptyList())
    private val refreshing = MutableStateFlow(false)
    private val refreshMutex = Mutex()
    private var lastRefreshMs: Long = 0L
    private var cachedListKey: String? = null

    init {
        snapshotStore.read(activeProfileId())?.let { snapshot ->
            rows.value = snapshot.rows
            tabs.value = snapshot.tabs
            cachedListKey = snapshot.selectedListKey
            lastRefreshMs = snapshot.updatedAtMs
        }
    }

    fun observeAllItems(): Flow<List<LibraryEntry>> = rows
    fun observeListTabs(): Flow<List<LibraryListTab>> = tabs
    fun observeIsRefreshing(): Flow<Boolean> = refreshing

    suspend fun refreshNow(force: Boolean = false, selectedListKey: String? = null) {
        ensureFresh(force = force, selectedListKey = selectedListKey)
    }

    suspend fun removeWatchlistItem(item: LibraryEntryInput) {
        val settings = settingsReader.settings.first()
        val apiKey = settings.apiKey.trim()
        if (!settings.enabled || apiKey.isBlank()) return
        api.mutateWatchlistItems(
            action = "remove",
            apiKey = apiKey,
            body = com.nexio.tv.data.repository.mdblist.MDBListIdMapper.watchlistPayloadFor(item),
        )
        rows.value = removeItem(rows.value, item)
    }

    suspend fun ensureFresh(force: Boolean = false, selectedListKey: String? = null) {
        refreshMutex.withLock {
            val now = System.currentTimeMillis()
            val selectedListId = listIdFromKey(selectedListKey)
            val listKey = selectedListId?.let(::personalListKey) ?: WATCHLIST_KEY
            if (cachedListKey == listKey && lastRefreshMs > 0L) {
                return
            }

            refreshing.value = true
            try {
                val settings = settingsReader.settings.first()
                val apiKey = settings.apiKey.trim()
                if (!settings.enabled || apiKey.isBlank()) {
                    rows.value = emptyList()
                    tabs.value = emptyList()
                    cachedListKey = null
                    lastRefreshMs = now
                    persistSnapshot(now)
                    return
                }

                val listsResponse = api.getMyLists(apiKey = apiKey, sort = "ranked", unified = false)
                val userLists = if (listsResponse.isSuccessful) listsResponse.body().orEmpty() else emptyList()
                tabs.value = buildTabs(userLists)

                val body = if (selectedListId != null) {
                    api.getListItems(
                        listId = selectedListId,
                        apiKey = apiKey,
                        limit = WATCHLIST_LIMIT,
                        offset = 0,
                        unified = true
                    ).toWatchlistBody()
                } else {
                    api.getWatchlistItems(
                        apiKey = apiKey,
                        limit = WATCHLIST_LIMIT,
                        offset = 0,
                        unified = true,
                    ).bodyIfSuccessful()
                }

                if (body == null) {
                    rows.value = emptyList()
                    cachedListKey = listKey
                    lastRefreshMs = now
                    persistSnapshot(now)
                    return
                }

                rows.value = buildRows(body.movies.orEmpty(), body.shows.orEmpty(), listKey = listKey)
                cachedListKey = listKey
                lastRefreshMs = now
                persistSnapshot(now)
            } finally {
                refreshing.value = false
            }
        }
    }

    suspend fun createStaticList(name: String, private: Boolean) {
        val apiKey = requireApiKey() ?: return
        api.createStaticList(apiKey, MDBListCreateListRequestDto(name = name, private = private))
        ensureFresh(force = true)
    }

    suspend fun updateStaticList(listId: String, name: String, private: Boolean) {
        val apiKey = requireApiKey() ?: return
        val id = listId.toLongOrNull() ?: listIdFromKey(listId) ?: return
        api.updateStaticList(id, apiKey, MDBListUpdateListRequestDto(name = name, private = private))
        ensureFresh(force = true, selectedListKey = personalListKey(id))
    }

    suspend fun deleteStaticList(listId: String) {
        val apiKey = requireApiKey() ?: return
        val id = listId.toLongOrNull() ?: listIdFromKey(listId) ?: return
        api.deleteStaticList(id, apiKey)
        ensureFresh(force = true)
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

    private fun persistSnapshot(updatedAtMs: Long = System.currentTimeMillis()) {
        snapshotStore.write(
            MDBListLibrarySnapshotStore.Snapshot(
                rows = rows.value,
                tabs = tabs.value,
                selectedListKey = cachedListKey,
                updatedAtMs = updatedAtMs
            ),
            activeProfileId()
        )
    }

    private fun activeProfileId(): Int = profileManager.activeProfileId.value

    private fun listIdFromKey(key: String?): Long? {
        return key?.removePrefix(PERSONAL_KEY_PREFIX)?.takeIf { it != key }?.toLongOrNull()
    }

    private suspend fun requireApiKey(): String? {
        val settings = settingsReader.settings.first()
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
