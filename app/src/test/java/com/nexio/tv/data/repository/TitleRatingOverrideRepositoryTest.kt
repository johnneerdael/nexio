package com.nexio.tv.data.repository

import com.nexio.tv.domain.model.ContentType
import com.nexio.tv.domain.model.MetaPreview
import com.nexio.tv.domain.model.PosterShape
import com.nexio.tv.domain.model.TitleRatingSource
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

class TitleRatingOverrideRepositoryTest {
    @Test
    fun `custom imdb rating wins over mdblist and tmdb`() = runTest {
        val custom = mockk<CustomImdbTitleRatingsRepository>()
        val mdb = mockk<MDBListRepository>()
        val repository = TitleRatingOverrideRepository(custom, mdb)
        val preview = preview(rating = 8.1f, source = TitleRatingSource.TMDB)

        coEvery {
            custom.getTitleRating("tt0944947", "tt0944947", ContentType.SERIES, "series")
        } returns 9.2

        val enriched = repository.enrichPreview(preview)

        assertEquals(9.2f, enriched.imdbRating ?: 0f, 0.0f)
        assertEquals(TitleRatingSource.IMDB, enriched.ratingSource)
        coVerify(exactly = 0) { mdb.enrichPreview(any()) }
    }

    @Test
    fun `mdblist imdb rating wins when custom imdb is unavailable`() = runTest {
        val custom = mockk<CustomImdbTitleRatingsRepository>()
        val mdb = mockk<MDBListRepository>()
        val repository = TitleRatingOverrideRepository(custom, mdb)
        val preview = preview(rating = 8.1f, source = TitleRatingSource.TMDB)

        coEvery {
            custom.getTitleRating("tt0944947", "tt0944947", ContentType.SERIES, "series")
        } returns null
        coEvery { mdb.enrichPreview(preview) } returns preview.copy(imdbRating = 8.9f, ratingSource = TitleRatingSource.IMDB)

        val enriched = repository.enrichPreview(preview)

        assertEquals(8.9f, enriched.imdbRating ?: 0f, 0.0f)
        assertEquals(TitleRatingSource.IMDB, enriched.ratingSource)
    }

    private fun preview(rating: Float, source: TitleRatingSource): MetaPreview =
        MetaPreview(
            id = "tt0944947",
            type = ContentType.SERIES,
            name = "Game of Thrones",
            poster = null,
            posterShape = PosterShape.POSTER,
            background = null,
            logo = null,
            description = null,
            releaseInfo = "2011",
            imdbRating = rating,
            ratingSource = source,
            genres = emptyList()
        )
}
