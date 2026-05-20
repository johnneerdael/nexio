package com.nexio.tv.data.repository

import com.nexio.tv.domain.model.ContentType
import com.nexio.tv.domain.model.MDBListRatings
import com.nexio.tv.domain.model.MDBListRatingsResult
import com.nexio.tv.domain.model.Meta
import com.nexio.tv.domain.model.PosterShape
import com.nexio.tv.domain.model.ProviderIds
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

class DetailRatingDisplayRepositoryTest {
    @Test
    fun `resolve passes IMDb identity to MDBList for anime ratings when TMDB id also exists`() = runTest {
        val titleRatingOverrideRepository = mockk<TitleRatingOverrideRepository>()
        val mdbListRepository = mockk<MDBListRepository>()
        val episodeRatingsSelectionRepository = mockk<EpisodeRatingsSelectionRepository>()
        val repository = DetailRatingDisplayRepository(
            titleRatingOverrideRepository = titleRatingOverrideRepository,
            mdbListRepository = mdbListRepository,
            episodeRatingsSelectionRepository = episodeRatingsSelectionRepository
        )
        val fallbackItemId = slot<String>()
        val imdbIdOverride = slot<String>()

        coEvery {
            titleRatingOverrideRepository.titleRatingCandidates(any<Meta>(), any(), any(), any(), any())
        } returns emptyList()
        coEvery {
            episodeRatingsSelectionRepository.episodeRatingCandidates(any(), any(), any(), any())
        } returns emptyList()
        coEvery {
            mdbListRepository.getRatingsForMeta(
                meta = any(),
                fallbackItemId = capture(fallbackItemId),
                fallbackItemType = "anime",
                imdbIdOverride = capture(imdbIdOverride)
            )
        } returns MDBListRatingsResult(
            ratings = MDBListRatings(mal = 8.7, imdb = 8.4),
            hasImdbRating = true
        )

        val result = repository.resolve(
            meta = animeMeta(),
            fallbackItemId = "kitsu:1",
            fallbackItemType = "anime",
            providerIds = ProviderIds(imdb = "tt12343534", tmdb = "94605", kitsu = "1"),
            episodesBySeason = emptyMap()
        )

        assertEquals("imdb:tt12343534", fallbackItemId.captured)
        assertEquals("tt12343534", imdbIdOverride.captured)
        assertEquals(8.7, result.mdbListRatings?.mal ?: 0.0, 0.0)
        coVerify(exactly = 1) {
            mdbListRepository.getRatingsForMeta(
                meta = any(),
                fallbackItemId = "imdb:tt12343534",
                fallbackItemType = "anime",
                imdbIdOverride = "tt12343534"
            )
        }
    }

    private fun animeMeta(): Meta =
        Meta(
            id = "kitsu:1",
            type = ContentType.SERIES,
            rawType = "anime",
            name = "Anime",
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
            videos = emptyList(),
            country = null,
            awards = null,
            language = null,
            links = emptyList()
        )
}
