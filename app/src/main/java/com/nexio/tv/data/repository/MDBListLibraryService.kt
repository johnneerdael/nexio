package com.nexio.tv.data.repository

import com.nexio.tv.data.remote.api.MDBListApi
import com.nexio.tv.data.remote.dto.mdblist.MDBListWatchlistItemDto
import com.nexio.tv.domain.model.LibraryEntry
import com.nexio.tv.domain.model.PosterShape
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
) {
    private val rows = MutableStateFlow<List<LibraryEntry>>(emptyList())
    private val refreshMutex = Mutex()
    private var lastRefreshMs: Long = 0L
    private val cacheTtlMs = 6L * 60 * 60 * 1_000L

    fun observeAllItems(): Flow<List<LibraryEntry>> = rows

    suspend fun refreshNow(force: Boolean = false) {
        ensureFresh(force)
    }

    suspend fun ensureFresh(force: Boolean = false) {
        refreshMutex.withLock {
            val now = System.currentTimeMillis()
            if (!force && rows.value.isNotEmpty() && now - lastRefreshMs < cacheTtlMs) return

            val settings = settingsReader.settings.first()
            val apiKey = settings.apiKey.trim()
            if (!settings.enabled || apiKey.isBlank()) {
                rows.value = emptyList()
                lastRefreshMs = now
                return
            }

            val response = api.getWatchlistItems(
                apiKey = apiKey,
                limit = WATCHLIST_LIMIT,
                offset = 0,
                unified = true,
            )
            val body = response.body()
            if (!response.isSuccessful || body == null) {
                rows.value = emptyList()
                lastRefreshMs = now
                return
            }

            rows.value = buildRows(body.movies.orEmpty(), body.shows.orEmpty())
            lastRefreshMs = now
        }
    }

    private fun buildRows(
        movies: List<MDBListWatchlistItemDto>,
        shows: List<MDBListWatchlistItemDto>,
    ): List<LibraryEntry> {
        val out = ArrayList<LibraryEntry>(movies.size + shows.size)
        for (i in movies.indices) {
            out += movies[i].toLibraryEntry(type = "movie")
        }
        for (i in shows.indices) {
            out += shows[i].toLibraryEntry(type = "series")
        }
        return out
    }

    private fun MDBListWatchlistItemDto.toLibraryEntry(type: String): LibraryEntry {
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
            listKeys = setOf(WATCHLIST_KEY),
            imdbId = imdb?.takeIf { it.isNotBlank() },
            tmdbId = tmdb,
        )
    }

    companion object {
        const val WATCHLIST_KEY = "mdblist:watchlist"
        private const val WATCHLIST_LIMIT = 1000
    }
}
