package com.nexio.tv.ui.screens.detail

import com.nexio.tv.domain.model.ContentType
import com.nexio.tv.domain.model.Meta
import com.nexio.tv.domain.model.PosterShape
import com.nexio.tv.domain.repository.WatchProgressRepository
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class MetaDetailsEffectiveContentIdTest {
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
    fun `episode progress lookup switches to loaded meta id`() = runTest(dispatcher) {
        val watchProgressRepository = mockk<WatchProgressRepository>(relaxed = true)
        every { watchProgressRepository.getAllEpisodeProgress(any()) } returns flowOf(emptyMap())
        every { watchProgressRepository.getProgress(any()) } returns flowOf(null)

        buildMetaDetailsViewModel(
            meta = seriesMeta("tt0944947"),
            itemId = "tmdb:1399",
            itemType = "series",
            watchProgressRepository = watchProgressRepository
        )
        advanceUntilIdle()

        verify { watchProgressRepository.getAllEpisodeProgress("tt0944947") }
    }

    private fun seriesMeta(id: String): Meta {
        return Meta(
            id = id,
            type = ContentType.SERIES,
            rawType = "series",
            name = "Series",
            poster = null,
            posterShape = PosterShape.POSTER,
            background = null,
            logo = null,
            description = null,
            releaseInfo = null,
            imdbRating = null,
            genres = emptyList(),
            runtime = null,
            director = emptyList(),
            writer = emptyList(),
            cast = emptyList(),
            castMembers = emptyList(),
            videos = emptyList(),
            productionCompanies = emptyList(),
            networks = emptyList(),
            country = null,
            awards = null,
            language = null,
            links = emptyList()
        )
    }
}
