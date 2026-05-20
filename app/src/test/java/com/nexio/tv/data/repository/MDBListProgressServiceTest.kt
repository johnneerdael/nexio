package com.nexio.tv.data.repository

import com.nexio.tv.core.profile.ProfileManager
import com.nexio.tv.data.local.MDBListProgressSyncState
import com.nexio.tv.data.local.MDBListProgressSyncStateStore
import com.nexio.tv.data.integration.mdblist.MDBListProgressService
import com.nexio.tv.data.remote.api.MDBListApi
import com.nexio.tv.data.remote.dto.mdblist.MDBListPlaybackResponseDto
import com.nexio.tv.data.remote.dto.mdblist.MDBListSyncIdsDto
import com.nexio.tv.data.remote.dto.mdblist.MDBListWatchedResponseDto
import com.nexio.tv.data.remote.dto.mdblist.MDBListWatchedSyncRequestDto
import com.nexio.tv.domain.model.MDBListSettings
import com.nexio.tv.domain.model.WatchProgress
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test
import retrofit2.Response

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

        val movie = request.movies!!.single()
        assertEquals("tt0137523", movie.ids.imdb)
        assertEquals(550, movie.ids.tmdb)
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

    @Test
    fun `observeAllProgress maps mdblist movie playback rows`() = runTest {
        val api = mockk<MDBListApi>()
        val settings = MutableStateFlow(MDBListSettings(enabled = true, apiKey = "mdb-key"))
        coEvery { api.getPlayback(apiKey = "mdb-key") } returns Response.success(
            MDBListPlaybackResponseDto(
                movies = listOf(
                    MDBListPlaybackResponseDto.MoviePlayback(
                        id = 44,
                        progress = 50.0,
                        pausedAt = "2026-05-14T10:15:00Z",
                        movie = MDBListPlaybackResponseDto.Movie(
                            title = "Fight Club",
                            year = 1999,
                            ids = MDBListSyncIdsDto(imdb = "tt0137523", tmdb = 550)
                        )
                    )
                )
            )
        )
        coEvery { api.getWatched(apiKey = "mdb-key", limit = 1000, offset = 0) } returns Response.success(
            MDBListWatchedResponseDto()
        )
        val service = MDBListProgressService(api, flowSettingsReader(settings), profileManager(), syncStateStore())

        service.refreshNowImmediate()

        val progress = service.observeAllProgress().first().single()
        assertEquals("tt0137523", progress.contentId)
        assertEquals("movie", progress.contentType)
        assertEquals("Fight Club", progress.name)
        assertEquals(50f, progress.progressPercent)
        assertEquals(WatchProgress.SOURCE_MDBLIST_PLAYBACK, progress.source)
    }

    @Test
    fun `observeAllProgress maps mdblist episode playback rows using show identity`() = runTest {
        val api = mockk<MDBListApi>()
        val settings = MutableStateFlow(MDBListSettings(enabled = true, apiKey = "mdb-key"))
        coEvery { api.getPlayback(apiKey = "mdb-key") } returns Response.success(
            MDBListPlaybackResponseDto(
                episodes = listOf(
                    MDBListPlaybackResponseDto.EpisodePlayback(
                        id = 55,
                        progress = 68.5,
                        pausedAt = "2026-05-14T10:20:00Z",
                        episode = MDBListPlaybackResponseDto.Episode(
                            season = 1,
                            number = 3,
                            name = "Tabula Rasa",
                            show = MDBListPlaybackResponseDto.Show(
                                title = "Lost",
                                year = 2004,
                                ids = MDBListSyncIdsDto(imdb = "tt0411008", tmdb = 4607, tvdb = 73739)
                            )
                        )
                    )
                )
            )
        )
        coEvery { api.getWatched(apiKey = "mdb-key", limit = 1000, offset = 0) } returns Response.success(
            MDBListWatchedResponseDto()
        )
        val service = MDBListProgressService(api, flowSettingsReader(settings), profileManager(), syncStateStore())

        service.refreshNowImmediate()

        val progress = service.observeAllProgress().first().single()
        assertEquals("tt0411008", progress.contentId)
        assertEquals("series", progress.contentType)
        assertEquals(1, progress.season)
        assertEquals(3, progress.episode)
        assertEquals("Tabula Rasa", progress.episodeTitle)
        assertEquals(WatchProgress.SOURCE_MDBLIST_PLAYBACK, progress.source)
    }

    @Test
    fun `follow up watched refresh uses persisted since cursor`() = runTest {
        val api = mockk<MDBListApi>()
        val settings = MutableStateFlow(MDBListSettings(enabled = true, apiKey = "mdb-key"))
        var state = MDBListProgressSyncState(lastWatchedSyncAt = "2026-05-20T20:00:00Z")
        val syncStateStore = mockk<MDBListProgressSyncStateStore> {
            every { read(1) } answers { state }
            every { write(any(), 1) } answers {
                state = firstArg()
            }
            every { clear(1) } answers {
                state = MDBListProgressSyncState()
            }
        }
        coEvery { api.getPlayback(apiKey = "mdb-key") } returns Response.success(MDBListPlaybackResponseDto())
        coEvery {
            api.getWatched(
                apiKey = "mdb-key",
                limit = 1000,
                offset = 0,
                since = "2026-05-20T20:00:00Z"
            )
        } returns Response.success(MDBListWatchedResponseDto())
        val service = MDBListProgressService(
            api,
            flowSettingsReader(settings),
            profileManager(),
            syncStateStore
        )

        service.refreshNowImmediate()

        coVerify(exactly = 1) {
            api.getWatched(
                apiKey = "mdb-key",
                limit = 1000,
                offset = 0,
                since = "2026-05-20T20:00:00Z"
            )
        }
    }

    private fun flowSettingsReader(settings: MutableStateFlow<MDBListSettings>): MDBListSettingsReader =
        object : MDBListSettingsReader {
            override val settings = settings
        }

    private fun profileManager(activeProfileId: MutableStateFlow<Int> = MutableStateFlow(1)): ProfileManager {
        val manager = mockk<ProfileManager>()
        every { manager.activeProfileId } returns activeProfileId
        return manager
    }

    private fun syncStateStore(
        initial: MDBListProgressSyncState = MDBListProgressSyncState()
    ): MDBListProgressSyncStateStore {
        var state = initial
        return mockk {
            every { read(any()) } answers { state }
            every { write(any(), any()) } answers { state = firstArg() }
            every { clear(any()) } answers { state = MDBListProgressSyncState() }
        }
    }
}
