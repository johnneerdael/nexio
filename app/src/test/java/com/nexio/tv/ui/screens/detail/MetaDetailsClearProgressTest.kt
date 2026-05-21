package com.nexio.tv.ui.screens.detail

import com.nexio.tv.domain.model.ContentType
import com.nexio.tv.domain.model.Meta
import com.nexio.tv.domain.model.PosterShape
import com.nexio.tv.domain.model.Video
import com.nexio.tv.domain.repository.WatchProgressRepository
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Before
import org.junit.Test

class MetaDetailsClearProgressTest {
    private val dispatcher = StandardTestDispatcher()

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `clear episode progress on series detail clears show-level progress`() = runTest(dispatcher) {
        val video = testVideo(season = 3, episode = 12)
        val meta = testSeriesMeta(id = "tmdb:1668", videos = listOf(video))
        val watchProgressRepository = mockk<WatchProgressRepository>(relaxed = true)
        every { watchProgressRepository.getAllEpisodeProgress(any(), any()) } returns flowOf(emptyMap())
        every { watchProgressRepository.getProgress(any(), any()) } returns flowOf(null)
        every { watchProgressRepository.isWatched(any(), any(), any(), any()) } returns flowOf(false)
        coEvery { watchProgressRepository.clearShowProgress(any(), any()) } returns Unit

        val viewModel = buildMetaDetailsViewModel(
            meta = meta,
            itemId = meta.id,
            itemType = "series",
            watchProgressRepository = watchProgressRepository
        )
        advanceUntilIdle()

        viewModel.onEvent(MetaDetailsEvent.OnClearEpisodeProgress(video))
        advanceUntilIdle()

        coVerify(exactly = 1) {
            watchProgressRepository.clearShowProgress(any(), "tmdb:1668")
        }
        coVerify(exactly = 0) {
            watchProgressRepository.removeFromHistory(any(), any(), any(), any())
        }
    }

    private fun testVideo(season: Int, episode: Int): Video {
        return Video(
            id = "tmdb:1668:$season:$episode",
            title = "The One",
            released = null,
            thumbnail = null,
            season = season,
            episode = episode,
            overview = null,
            runtime = null
        )
    }

    private fun testSeriesMeta(id: String, videos: List<Video>): Meta {
        return Meta(
            id = id,
            type = ContentType.SERIES,
            rawType = "series",
            name = "Friends",
            poster = null,
            posterShape = PosterShape.POSTER,
            background = null,
            logo = null,
            description = null,
            releaseInfo = "1994",
            imdbRating = null,
            genres = emptyList(),
            runtime = null,
            director = emptyList(),
            cast = emptyList(),
            castMembers = emptyList(),
            videos = videos,
            productionCompanies = emptyList(),
            networks = emptyList(),
            country = null,
            awards = null,
            language = null,
            links = emptyList(),
            trailerYtIds = emptyList()
        )
    }
}
