package com.nexio.tv.data.repository

import com.nexio.tv.core.metadata.router.CanonicalStableIds
import com.nexio.tv.core.metadata.router.SidecarStableIds
import com.nexio.tv.core.metadata.router.SourceStableIds
import com.nexio.tv.core.metadata.router.StableIdBundle
import com.nexio.tv.domain.model.ContentType
import com.nexio.tv.domain.model.Meta
import com.nexio.tv.domain.model.MetaCompany
import com.nexio.tv.domain.model.MetaLink
import com.nexio.tv.domain.model.MetaPreview
import com.nexio.tv.domain.model.PosterShape
import com.nexio.tv.domain.model.ProviderIds
import com.nexio.tv.domain.model.TitleRatingSource
import com.nexio.tv.domain.model.Video
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

    @Test
    fun `enrich preview uses stable bundle imdb before inferring from tmdb id`() = runTest {
        val custom = mockk<CustomImdbTitleRatingsRepository>()
        val mdb = mockk<MDBListRepository>()
        val repository = TitleRatingOverrideRepository(custom, mdb)
        val preview = preview(id = "tmdb:1399", rating = 8.1f, source = TitleRatingSource.TMDB)
        val stableIds = stableIdBundle(imdbId = "tt0944947")

        coEvery { custom.getTitleRatingByImdbId("tt0944947") } returns 8.8

        val enriched = repository.enrichPreview(preview, stableIds)

        assertEquals(8.8f, enriched.imdbRating ?: 0f, 0.0f)
        assertEquals(TitleRatingSource.IMDB, enriched.ratingSource)
        coVerify(exactly = 1) { custom.getTitleRatingByImdbId("tt0944947") }
        coVerify(exactly = 0) { custom.getTitleRating(any(), any(), any(), any()) }
        coVerify(exactly = 0) { mdb.enrichPreview(any(), any()) }
    }

    @Test
    fun `enrich preview falls back to custom inference when stable bundle imdb misses`() = runTest {
        val custom = mockk<CustomImdbTitleRatingsRepository>()
        val mdb = mockk<MDBListRepository>()
        val repository = TitleRatingOverrideRepository(custom, mdb)
        val preview = preview(id = "tmdb:1399", rating = 8.1f, source = TitleRatingSource.TMDB)
        val stableIds = stableIdBundle(imdbId = "tt0944947")

        coEvery { custom.getTitleRatingByImdbId("tt0944947") } returns null
        coEvery {
            custom.getTitleRating("tmdb:1399", "tmdb:1399", ContentType.SERIES, "series")
        } returns 8.4

        val enriched = repository.enrichPreview(preview, stableIds)

        assertEquals(8.4f, enriched.imdbRating ?: 0f, 0.0f)
        assertEquals(TitleRatingSource.IMDB, enriched.ratingSource)
        coVerify(exactly = 1) {
            custom.getTitleRating("tmdb:1399", "tmdb:1399", ContentType.SERIES, "series")
        }
        coVerify(exactly = 0) { mdb.enrichPreview(any(), any()) }
    }

    @Test
    fun `enrich meta uses stable bundle imdb before fallback inference`() = runTest {
        val custom = mockk<CustomImdbTitleRatingsRepository>()
        val mdb = mockk<MDBListRepository>()
        val repository = TitleRatingOverrideRepository(custom, mdb)
        val meta = meta(id = "tmdb:1399", rating = 8.1f, source = TitleRatingSource.TMDB)
        val stableIds = stableIdBundle(imdbId = "tt0944947")

        coEvery { custom.getTitleRatingByImdbId("tt0944947") } returns 8.8

        val enriched = repository.enrichMeta(meta, "tmdb:1399", "series", stableIds)

        assertEquals(8.8f, enriched.imdbRating ?: 0f, 0.0f)
        assertEquals(TitleRatingSource.IMDB, enriched.ratingSource)
        coVerify(exactly = 1) { custom.getTitleRatingByImdbId("tt0944947") }
        coVerify(exactly = 0) { custom.getTitleRating(any(), any(), any(), any()) }
        coVerify(exactly = 0) { mdb.getRatingsForMeta(any(), any(), any(), any()) }
    }

    private fun preview(
        id: String = "tt0944947",
        rating: Float,
        source: TitleRatingSource
    ): MetaPreview =
        MetaPreview(
            id = id,
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

    private fun meta(id: String, rating: Float, source: TitleRatingSource): Meta =
        Meta(
            id = id,
            type = ContentType.SERIES,
            rawType = "series",
            name = "Game of Thrones",
            poster = null,
            posterShape = PosterShape.POSTER,
            background = null,
            logo = null,
            description = null,
            releaseInfo = "2011",
            imdbRating = rating,
            ratingSource = source,
            genres = emptyList(),
            runtime = null,
            director = emptyList(),
            writer = emptyList(),
            cast = emptyList(),
            castMembers = emptyList(),
            videos = emptyList<Video>(),
            productionCompanies = emptyList<MetaCompany>(),
            networks = emptyList<MetaCompany>(),
            ageRating = null,
            country = null,
            awards = null,
            language = null,
            links = emptyList<MetaLink>(),
            trailerYtIds = emptyList()
        )

    private fun stableIdBundle(imdbId: String): StableIdBundle =
        StableIdBundle(
            itemKey = "tmdb:1399",
            itemType = ContentType.SERIES,
            canonical = CanonicalStableIds(tvdbSeriesId = "121361"),
            sidecars = SidecarStableIds(imdbId = imdbId),
            source = SourceStableIds(
                sourceProvider = null,
                sourceItemId = null,
                railId = null,
                observedIds = ProviderIds()
            ),
            evidence = emptyList(),
            resolvedAtMs = 1L
        )
}
