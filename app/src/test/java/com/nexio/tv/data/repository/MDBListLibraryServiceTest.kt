package com.nexio.tv.data.repository

import com.nexio.tv.data.integration.mdblist.MDBListLibraryService
import com.nexio.tv.data.integration.mdblist.MDBListRateLimitGuard
import com.nexio.tv.data.local.MDBListLibrarySnapshotStore
import com.nexio.tv.core.profile.ProfileManager
import com.nexio.tv.data.remote.api.MDBListApi
import com.nexio.tv.data.remote.dto.mdblist.MDBListListItemsResponseDto
import com.nexio.tv.data.remote.dto.mdblist.MDBListUserListDto
import com.nexio.tv.data.remote.dto.mdblist.MDBListWatchlistItemDto
import com.nexio.tv.data.remote.dto.mdblist.MDBListWatchlistResponseDto
import com.nexio.tv.domain.model.LibraryEntry
import com.nexio.tv.domain.model.MDBListSettings
import com.nexio.tv.domain.model.PosterShape
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import retrofit2.Response

class MDBListLibraryServiceTest {
    @Test
    fun `refresh clears rows and skips api when mdblist is disabled`() = runTest {
        val api = mockk<MDBListApi>(relaxed = true)
        val settings = MutableStateFlow(MDBListSettings(enabled = false, apiKey = "api-key"))
        val service = MDBListLibraryService(
            api = api,
            settingsReader = flowSettingsReader(settings),
            snapshotStore = snapshotStore(),
            profileManager = profileManager(),
            rateLimitGuard = rateLimitGuard()
        )

        service.refreshNow(force = true)

        assertEquals(emptyList<Any>(), service.observeAllItems().first())
        coVerify(exactly = 0) { api.getWatchlistItems(any(), any(), any(), any()) }
    }

    @Test
    fun `refresh maps movies and shows to watchlist library entries`() = runTest {
        val api = mockk<MDBListApi>()
        val settings = MutableStateFlow(MDBListSettings(enabled = true, apiKey = "api-key"))
        coEvery { api.getMyLists(apiKey = "api-key", sort = "ranked", unified = false) } returns Response.success(emptyList())
        coEvery {
            api.getWatchlistItems(
                apiKey = "api-key",
                limit = any(),
                offset = any(),
                unified = true,
            )
        } returns Response.success(
            MDBListWatchlistResponseDto(
                movies = listOf(
                    MDBListWatchlistItemDto(
                        imdb = "tt0137523",
                        tmdb = 550,
                        title = "Fight Club",
                        year = 1999,
                    )
                ),
                shows = listOf(
                    MDBListWatchlistItemDto(
                        imdb = "tt0944947",
                        tmdb = 1399,
                        tvdb = 121361,
                        title = "Game of Thrones",
                        year = 2011,
                    )
                ),
            )
        )

        val service = MDBListLibraryService(
            api = api,
            settingsReader = flowSettingsReader(settings),
            snapshotStore = snapshotStore(),
            profileManager = profileManager(),
            rateLimitGuard = rateLimitGuard()
        )

        service.refreshNow(force = true)

        val rows = service.observeAllItems().first()
        assertEquals(2, rows.size)
        assertEquals("movie", rows[0].type)
        assertEquals("Fight Club", rows[0].name)
        assertEquals("tt0137523", rows[0].imdbId)
        assertEquals(550, rows[0].tmdbId)
        assertEquals(setOf(MDBListLibraryService.WATCHLIST_KEY), rows[0].listKeys)
        assertEquals("series", rows[1].type)
        assertEquals("Game of Thrones", rows[1].name)
        assertEquals("tt0944947", rows[1].imdbId)
        assertEquals(1399, rows[1].tmdbId)
        assertEquals(setOf(MDBListLibraryService.WATCHLIST_KEY), rows[1].listKeys)
    }

    @Test
    fun `refresh loads watchlist and all personal list tabs`() = runTest {
        val api = mockk<MDBListApi>()
        val settings = MutableStateFlow(MDBListSettings(enabled = true, apiKey = "mdb-key"))
        coEvery { api.getMyLists(apiKey = "mdb-key", sort = "ranked", unified = false) } returns Response.success(
            listOf(
                MDBListUserListDto(id = 10, name = "Sci-Fi", slug = "sci-fi", type = "static", dynamic = false, private = true, items = 2),
                MDBListUserListDto(id = 11, name = "Trending", slug = "trending", type = "dynamic", dynamic = true, private = false, items = 20)
            )
        )
        coEvery { api.getWatchlistItems(apiKey = "mdb-key", limit = any(), offset = 0, unified = true) } returns Response.success(
            MDBListWatchlistResponseDto(movies = emptyList(), shows = emptyList())
        )

        val service = MDBListLibraryService(
            api = api,
            settingsReader = flowSettingsReader(settings),
            snapshotStore = snapshotStore(),
            profileManager = profileManager(),
            rateLimitGuard = rateLimitGuard()
        )
        service.refreshNow(force = true)

        val tabs = service.observeListTabs().first()
        assertEquals(listOf("Watchlist", "Sci-Fi", "Trending"), tabs.map { it.title })
        assertTrue(tabs.first { it.title == "Sci-Fi" }.isMutableStaticList)
        assertFalse(tabs.first { it.title == "Trending" }.isMutableStaticList)
    }

    @Test
    fun `selected personal list fetch uses list items endpoint and assigns list key`() = runTest {
        val api = mockk<MDBListApi>()
        val settings = MutableStateFlow(MDBListSettings(enabled = true, apiKey = "mdb-key"))
        coEvery { api.getMyLists(apiKey = "mdb-key", sort = "ranked", unified = false) } returns Response.success(
            listOf(MDBListUserListDto(id = 10, name = "Sci-Fi", slug = "sci-fi", type = "static", dynamic = false, private = true, items = 1))
        )
        coEvery { api.getWatchlistItems(apiKey = "mdb-key", limit = any(), offset = 0, unified = true) } returns Response.success(
            MDBListWatchlistResponseDto(movies = emptyList(), shows = emptyList())
        )
        coEvery {
            api.getListItems(listId = 10, apiKey = "mdb-key", limit = 1000, offset = 0, unified = true)
        } returns Response.success(
            MDBListListItemsResponseDto(
                movies = listOf(MDBListWatchlistItemDto(title = "Arrival", year = 2016, imdb = "tt2543164", tmdb = 329865)),
                shows = emptyList()
            )
        )

        val service = MDBListLibraryService(
            api = api,
            settingsReader = flowSettingsReader(settings),
            snapshotStore = snapshotStore(),
            profileManager = profileManager(),
            rateLimitGuard = rateLimitGuard()
        )
        service.refreshNow(force = true, selectedListKey = "mdblist:list:10")

        val item = service.observeAllItems().first().single()
        assertEquals("Arrival", item.name)
        assertEquals(setOf("mdblist:list:10"), item.listKeys)
    }

    @Test
    fun `failed watchlist refresh preserves cached rows and does not persist empty snapshot`() = runTest {
        val cachedRow = libraryEntry(
            id = "tt0137523",
            name = "Fight Club",
            listKey = MDBListLibraryService.WATCHLIST_KEY
        )
        val api = mockk<MDBListApi>()
        val settings = MutableStateFlow(MDBListSettings(enabled = true, apiKey = "mdb-key"))
        val snapshotStore = snapshotStore(
            snapshot = MDBListLibrarySnapshotStore.Snapshot(
                rows = listOf(cachedRow),
                tabs = emptyList(),
                selectedListKey = MDBListLibraryService.WATCHLIST_KEY,
                updatedAtMs = 123L
            )
        )
        coEvery { api.getMyLists(apiKey = "mdb-key", sort = "ranked", unified = false) } returns Response.success(emptyList())
        coEvery {
            api.getWatchlistItems(apiKey = "mdb-key", limit = any(), offset = 0, unified = true)
        } returns Response.error(429, """{"error":"Daily API limit exceeded!"}""".toResponseBody())

        val service = MDBListLibraryService(
            api = api,
            settingsReader = flowSettingsReader(settings),
            snapshotStore = snapshotStore,
            profileManager = profileManager(),
            rateLimitGuard = rateLimitGuard()
        )

        val result = runCatching { service.refreshNow(force = true) }

        assertTrue(result.isFailure)
        assertEquals(listOf(cachedRow), service.observeAllItems().first())
        coVerify(exactly = 0) { snapshotStore.write(any(), any()) }
    }

    @Test
    fun `active mdblist daily backoff skips watchlist network calls`() = runTest {
        val cachedRow = libraryEntry(
            id = "tt0137523",
            name = "Fight Club",
            listKey = MDBListLibraryService.WATCHLIST_KEY
        )
        val api = mockk<MDBListApi>(relaxed = true)
        val settings = MutableStateFlow(MDBListSettings(enabled = true, apiKey = "mdb-key"))
        val guard = rateLimitGuard(blocked = true)
        val service = MDBListLibraryService(
            api = api,
            settingsReader = flowSettingsReader(settings),
            snapshotStore = snapshotStore(
                snapshot = MDBListLibrarySnapshotStore.Snapshot(
                    rows = listOf(cachedRow),
                    tabs = emptyList(),
                    selectedListKey = MDBListLibraryService.WATCHLIST_KEY,
                    updatedAtMs = 123L
                )
            ),
            profileManager = profileManager(),
            rateLimitGuard = guard
        )

        val result = runCatching { service.refreshNow(force = true) }

        assertTrue(result.isFailure)
        assertEquals(listOf(cachedRow), service.observeAllItems().first())
        coVerify(exactly = 0) { api.getMyLists(any(), any(), any()) }
        coVerify(exactly = 0) { api.getWatchlistItems(any(), any(), any(), any()) }
    }

    private fun flowSettingsReader(settings: MutableStateFlow<MDBListSettings>): MDBListSettingsReader =
        object : MDBListSettingsReader {
            override val settings = settings
        }

    private fun snapshotStore(
        snapshot: MDBListLibrarySnapshotStore.Snapshot? = null
    ): MDBListLibrarySnapshotStore {
        val store = mockk<MDBListLibrarySnapshotStore>(relaxed = true)
        every { store.read(any()) } returns snapshot
        return store
    }

    private fun libraryEntry(id: String, name: String, listKey: String): LibraryEntry {
        return LibraryEntry(
            id = id,
            type = "movie",
            name = name,
            poster = null,
            posterShape = PosterShape.POSTER,
            background = null,
            logo = null,
            description = null,
            releaseInfo = null,
            imdbRating = null,
            genres = emptyList(),
            addonBaseUrl = null,
            listKeys = setOf(listKey),
            imdbId = id
        )
    }

    private fun profileManager(): ProfileManager {
        val manager = mockk<ProfileManager>()
        every { manager.activeProfileId } returns MutableStateFlow(1)
        return manager
    }

    private fun rateLimitGuard(blocked: Boolean = false): MDBListRateLimitGuard {
        val guard = mockk<MDBListRateLimitGuard>(relaxed = true)
        coEvery { guard.throwIfBlocked() } answers {
            if (blocked) throw com.nexio.tv.data.integration.mdblist.MDBListDailyLimitException()
        }
        coEvery { guard.noteResponse(any()) } returns null
        coEvery { guard.isBlocked() } returns blocked
        return guard
    }
}
