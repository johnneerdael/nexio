package com.nuvio.tv.data.repository

import com.nuvio.tv.data.remote.api.TraktApi
import com.nuvio.tv.data.remote.dto.trakt.TraktIdsDto
import io.mockk.mockk
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test

class TraktScrobbleServiceTest {

    private val traktApi: TraktApi = mockk()
    private val authService: TraktAuthService = mockk()
    private val service = TraktScrobbleService(traktApi, authService)

    @Test
    fun `buildRequestBody builds movie payload with ids`() {
        val item = TraktScrobbleItem.Movie(
            title = "Interstellar",
            year = 2014,
            ids = TraktIdsDto(imdb = "tt0816692", tmdb = 157336)
        )

        val request = service.buildRequestBody(item, clampedProgress = 44.5f)

        assertEquals(44.5f, request.progress)
        assertNotNull(request.movie)
        assertEquals("Interstellar", request.movie?.title)
        assertEquals("tt0816692", request.movie?.ids?.imdb)
        assertEquals(null, request.episode)
        assertEquals(null, request.show)
    }

    @Test
    fun `buildRequestBody builds episode payload with show and episode`() {
        val item = TraktScrobbleItem.Episode(
            showTitle = "Severance",
            showYear = 2022,
            showIds = TraktIdsDto(imdb = "tt11280740", tmdb = 95396),
            season = 2,
            number = 3,
            episodeTitle = "Who Is Alive?"
        )

        val request = service.buildRequestBody(item, clampedProgress = 81.2f)

        assertEquals(81.2f, request.progress)
        assertNotNull(request.show)
        assertNotNull(request.episode)
        assertEquals("tt11280740", request.show?.ids?.imdb)
        assertEquals(2, request.episode?.season)
        assertEquals(3, request.episode?.number)
        assertEquals(null, request.movie)
    }
}

