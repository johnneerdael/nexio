package com.nexio.tv.data.repository

import com.nexio.tv.core.metadata.router.CanonicalStableIds
import com.nexio.tv.core.metadata.router.SidecarStableIds
import com.nexio.tv.core.metadata.router.SourceStableIds
import com.nexio.tv.core.metadata.router.StableIdBundle
import com.nexio.tv.core.metadata.router.resolver.RatingCandidate
import com.nexio.tv.core.metadata.router.resolver.RatingResolver
import com.nexio.tv.core.metadata.router.resolver.SourceRole
import com.nexio.tv.domain.model.ContentType
import com.nexio.tv.domain.model.MDBListRatings
import com.nexio.tv.domain.model.MDBListRatingsResult
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
    fun `preview candidate source emits custom imdb and mdblist without selecting a winner`() = runTest {
        val custom = mockk<CustomImdbTitleRatingsRepository>()
        val mdb = mockk<MDBListRepository>()
        val repository = TitleRatingOverrideRepository(custom, mdb)
        val preview = preview(id = "tt0944947", rating = 8.1f, source = TitleRatingSource.TMDB)

        coEvery {
            custom.getTitleRating("tt0944947", "tt0944947", ContentType.SERIES, "series")
        } returns 9.2
        coEvery {
            mdb.getRatingsForMeta(any(), "tt0944947", "series", imdbIdOverride = null)
        } returns MDBListRatingsResult(MDBListRatings(imdb = 8.8), hasImdbRating = true)

        val candidates = repository.titleRatingCandidates(preview)

        assertEquals(listOf(SourceRole.CUSTOM_IMDB, SourceRole.MDBLIST), candidates.map { it.sourceRole })
        assertEquals(listOf(9.2, 8.8), candidates.map { it.value })
    }

    @Test
    fun `preview candidate source uses stable bundle imdb before inference and passes it to mdblist`() = runTest {
        val custom = mockk<CustomImdbTitleRatingsRepository>()
        val mdb = mockk<MDBListRepository>()
        val repository = TitleRatingOverrideRepository(custom, mdb)
        val preview = preview(id = "tmdb:1399", rating = 8.1f, source = TitleRatingSource.TMDB)
        val stableIds = stableIdBundle(imdbId = "tt0944947")

        coEvery { custom.getTitleRatingByImdbId("tt0944947") } returns 8.8
        coEvery {
            mdb.getRatingsForMeta(any(), "tmdb:1399", "series", imdbIdOverride = "tt0944947")
        } returns MDBListRatingsResult(MDBListRatings(imdb = 8.7), hasImdbRating = true)

        val candidates = repository.titleRatingCandidates(preview, stableIds)

        assertEquals(listOf(SourceRole.CUSTOM_IMDB, SourceRole.MDBLIST), candidates.map { it.sourceRole })
        assertEquals(listOf(8.8, 8.7), candidates.map { it.value })
        coVerify(exactly = 1) { custom.getTitleRatingByImdbId("tt0944947") }
        coVerify(exactly = 0) { custom.getTitleRating(any(), any(), any(), any()) }
        coVerify(exactly = 1) {
            mdb.getRatingsForMeta(any(), "tmdb:1399", "series", imdbIdOverride = "tt0944947")
        }
    }

    @Test
    fun `meta candidate source accepts provider imdb ids for sidecar lookups`() = runTest {
        val custom = mockk<CustomImdbTitleRatingsRepository>()
        val mdb = mockk<MDBListRepository>()
        val repository = TitleRatingOverrideRepository(custom, mdb)
        val meta = meta(id = "tmdb:1399", rating = 8.1f, source = TitleRatingSource.TMDB)

        coEvery { custom.getTitleRatingByImdbId("tt0944947") } returns null
        coEvery {
            custom.getTitleRating("tmdb:1399", "tt0944947", ContentType.SERIES, "series")
        } returns 8.4
        coEvery {
            mdb.getRatingsForMeta(meta, "tt0944947", "series", imdbIdOverride = "tt0944947")
        } returns MDBListRatingsResult(MDBListRatings(imdb = 8.7), hasImdbRating = true)

        val candidates = repository.titleRatingCandidates(
            meta = meta,
            fallbackItemId = "tt0944947",
            fallbackItemType = "series",
            providerIds = ProviderIds(imdb = "tt0944947")
        )

        assertEquals(listOf(SourceRole.CUSTOM_IMDB, SourceRole.MDBLIST), candidates.map { it.sourceRole })
        assertEquals(listOf(8.4, 8.7), candidates.map { it.value })
    }

    @Test
    fun `resolver selects mdblist candidate over preview fallback`() = runTest {
        val custom = mockk<CustomImdbTitleRatingsRepository>()
        val mdb = mockk<MDBListRepository>()
        val repository = TitleRatingOverrideRepository(custom, mdb)
        val preview = preview(id = "tt0944947", rating = 8.1f, source = TitleRatingSource.TMDB)

        coEvery {
            custom.getTitleRating("tt0944947", "tt0944947", ContentType.SERIES, "series")
        } returns null
        coEvery {
            mdb.getRatingsForMeta(any(), "tt0944947", "series", imdbIdOverride = null)
        } returns MDBListRatingsResult(MDBListRatings(imdb = 8.9), hasImdbRating = true)

        val resolved = RatingResolver.resolveTitleRating(
            repository.titleRatingCandidates(preview) + RatingCandidate(
                value = 8.1,
                sourceRole = SourceRole.PREVIEW_FALLBACK,
                sourceProvider = TitleRatingSource.TMDB.name,
                confidence = com.nexio.tv.core.metadata.router.resolver.Confidence.LOW
            )
        )

        assertEquals(8.9, resolved?.value ?: 0.0, 0.0)
        assertEquals(SourceRole.MDBLIST, resolved?.sourceRole)
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
