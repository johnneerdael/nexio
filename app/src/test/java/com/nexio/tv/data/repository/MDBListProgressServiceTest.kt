package com.nexio.tv.data.repository

import com.nexio.tv.data.remote.dto.mdblist.MDBListPlaybackResponseDto
import com.nexio.tv.data.remote.dto.mdblist.MDBListSyncIdsDto
import com.nexio.tv.data.remote.dto.mdblist.MDBListWatchedSyncRequestDto
import org.junit.Assert.assertEquals
import org.junit.Test

class MDBListProgressServiceTest {
    @Test
    fun `watched request supports movie ids`() {
        val request = MDBListWatchedSyncRequestDto(
            movies = listOf(
                MDBListWatchedSyncRequestDto.Movie(
                    title = "Fight Club",
                    year = 1999,
                    ids = MDBListSyncIdsDto(imdb = "tt0137523", tmdb = 550)
                )
            )
        )

        assertEquals("tt0137523", request.movies!!.single().ids.imdb)
        assertEquals(550, request.movies!!.single().ids.tmdb)
    }

    @Test
    fun `playback response supports episode coordinates`() {
        val response = MDBListPlaybackResponseDto(
            episodes = listOf(
                MDBListPlaybackResponseDto.EpisodePlayback(
                    id = 12345,
                    progress = 42.75,
                    pausedAt = "2026-05-14T10:15:00Z",
                    episode = MDBListPlaybackResponseDto.Episode(
                        season = 2,
                        number = 3,
                        name = "Episode Three",
                        ids = MDBListSyncIdsDto(tmdb = 62085),
                        show = MDBListPlaybackResponseDto.Show(
                            title = "Breaking Bad",
                            year = 2008,
                            ids = MDBListSyncIdsDto(imdb = "tt0903747", tmdb = 1396, mdblist = "8plj")
                        )
                    )
                )
            )
        )

        val episode = response.episodes!!.single().episode!!
        assertEquals(2, episode.season)
        assertEquals(3, episode.number)
        assertEquals("tt0903747", episode.show!!.ids.imdb)
    }
}
