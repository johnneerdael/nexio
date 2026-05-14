package com.nexio.tv.data.repository

import com.nexio.tv.data.integration.mdblist.MDBListLibraryService
import com.nexio.tv.data.local.MDBListLibrarySnapshotStore
import com.nexio.tv.core.profile.ProfileManager
import com.nexio.tv.data.remote.api.MDBListApi
import com.nexio.tv.data.remote.dto.mdblist.MDBListListItemsResponseDto
import com.nexio.tv.data.remote.dto.mdblist.MDBListUserListDto
import com.nexio.tv.data.remote.dto.mdblist.MDBListWatchlistItemDto
import com.nexio.tv.data.remote.dto.mdblist.MDBListWatchlistResponseDto
import com.nexio.tv.domain.model.MDBListSettings
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
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
            profileManager = profileManager()
        )

        service.refreshNow(force = true)

        assertEquals(emptyList<Any>(), service.observeAllItems().first())
        coVerify(exactly = 0) { api.getWatchlistItems(any(), any(), any(), any()) }
    }

    @Test
    fun `refresh maps movies and shows to watchlist library entries`() = runTest {
        val api = mockk<MDBListApi>()
        val settings = MutableStateFlow(MDBListSettings(enabled = true, apiKey = "api-key"))
        coEvery { api.getMyLists(apiKey = "api-key", sort = "ranked", unified = true) } returns Response.success(emptyList())
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
            profileManager = profileManager()
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
        coEvery { api.getMyLists(apiKey = "mdb-key", sort = "ranked", unified = true) } returns Response.success(
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
            profileManager = profileManager()
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
        coEvery { api.getMyLists(apiKey = "mdb-key", sort = "ranked", unified = true) } returns Response.success(
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
            profileManager = profileManager()
        )
        service.refreshNow(force = true, selectedListKey = "mdblist:list:10")

        val item = service.observeAllItems().first().single()
        assertEquals("Arrival", item.name)
        assertEquals(setOf("mdblist:list:10"), item.listKeys)
    }

    private fun flowSettingsReader(settings: MutableStateFlow<MDBListSettings>): MDBListSettingsReader =
        object : MDBListSettingsReader {
            override val settings = settings
        }

    private fun snapshotStore(): MDBListLibrarySnapshotStore {
        val store = mockk<MDBListLibrarySnapshotStore>(relaxed = true)
        every { store.read(any()) } returns null
        return store
    }

    private fun profileManager(): ProfileManager {
        val manager = mockk<ProfileManager>()
        every { manager.activeProfileId } returns MutableStateFlow(1)
        return manager
    }
}
