package com.nexio.tv.data.repository

import com.nexio.tv.data.remote.api.MDBListApi
import com.nexio.tv.data.remote.dto.mdblist.MDBListWatchlistItemDto
import com.nexio.tv.data.remote.dto.mdblist.MDBListWatchlistResponseDto
import com.nexio.tv.domain.model.MDBListSettings
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import retrofit2.Response
import org.junit.Test

class MDBListLibraryServiceTest {
    @Test
    fun `refresh clears rows and skips api when mdblist is disabled`() = runTest {
        val api = mockk<MDBListApi>(relaxed = true)
        val settings = MutableStateFlow(MDBListSettings(enabled = false, apiKey = "api-key"))
        val service = MDBListLibraryService(
            api = api,
            settingsReader = flowSettingsReader(settings),
        )

        service.refreshNow(force = true)

        assertEquals(emptyList<Any>(), service.observeAllItems().first())
        coVerify(exactly = 0) { api.getWatchlistItems(any(), any(), any(), any()) }
    }

    @Test
    fun `refresh maps movies and shows to watchlist library entries`() = runTest {
        val api = mockk<MDBListApi>()
        val settings = MutableStateFlow(MDBListSettings(enabled = true, apiKey = "api-key"))
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

    private fun flowSettingsReader(settings: MutableStateFlow<MDBListSettings>): MDBListSettingsReader =
        object : MDBListSettingsReader {
            override val settings = settings
        }
}
