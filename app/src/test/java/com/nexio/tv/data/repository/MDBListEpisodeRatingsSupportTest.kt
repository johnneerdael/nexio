package com.nexio.tv.data.repository

import com.nexio.tv.data.remote.dto.mdblist.MDBListRatingItemDto
import com.nexio.tv.domain.model.ContentType
import com.nexio.tv.domain.model.Meta
import com.nexio.tv.domain.model.MetaCompany
import com.nexio.tv.domain.model.MetaLink
import com.nexio.tv.domain.model.PosterShape
import com.nexio.tv.domain.model.Video
import org.junit.Assert.assertEquals
import org.junit.Test

class MDBListEpisodeRatingsSupportTest {

    @Test
    fun `mapEpisodeRatings matches returned tmdb ids back to season episodes`() {
        val episodeIdsByKey = mapOf(
            (1 to 1) to 62085,
            (1 to 2) to 62086,
            (1 to 3) to 62087
        )

        val result = mapEpisodeRatings(
            ratingItems = listOf(
                MDBListRatingItemDto(id = 62085, rating = 7.1),
                MDBListRatingItemDto(id = 62087, rating = 5.4),
                MDBListRatingItemDto(id = 99999, rating = 9.9),
                MDBListRatingItemDto(id = 62086, rating = null)
            ),
            episodeIdsByKey = episodeIdsByKey
        )

        assertEquals(
            mapOf(
                (1 to 1) to 7.1,
                (1 to 3) to 5.4
            ),
            result
        )
    }

    @Test
    fun `episodeRatingsCacheTtl uses long ttl only for complete non-empty seasons`() {
        assertEquals(EPISODE_RATINGS_COMPLETE_TTL_MS, episodeRatingsCacheTtlMs(expectedCount = 3, actualCount = 3))
        assertEquals(EPISODE_RATINGS_RETRY_TTL_MS, episodeRatingsCacheTtlMs(expectedCount = 3, actualCount = 2))
        assertEquals(EPISODE_RATINGS_RETRY_TTL_MS, episodeRatingsCacheTtlMs(expectedCount = 3, actualCount = 0))
        assertEquals(EPISODE_RATINGS_RETRY_TTL_MS, episodeRatingsCacheTtlMs(expectedCount = 0, actualCount = 0))
    }

    @Test
    fun `episodeRatingsCacheNamespace falls back to tmdb or raw ids when imdb is unavailable`() {
        val metaWithoutImdb = stubMeta(id = "tmdb:12345", type = ContentType.SERIES)
        val metaWithRawFallback = stubMeta(id = "catalog:nexio:show-abc", type = ContentType.SERIES)

        assertEquals("tmdb:12345", episodeRatingsCacheNamespace(metaWithoutImdb, fallbackItemId = "ignored"))
        assertEquals("catalog:nexio:show-abc", episodeRatingsCacheNamespace(metaWithRawFallback, fallbackItemId = "fallback"))
        assertEquals("tmdb:999", episodeRatingsCacheNamespace(stubMeta(id = "", type = ContentType.SERIES), fallbackItemId = "tmdb:999"))
    }

    private fun stubMeta(id: String, type: ContentType): Meta {
        return Meta(
            id = id,
            type = type,
            rawType = type.toApiString(),
            name = "Stub",
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
    }
}
