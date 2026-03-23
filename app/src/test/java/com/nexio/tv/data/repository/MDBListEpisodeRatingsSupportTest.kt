package com.nexio.tv.data.repository

import com.nexio.tv.data.remote.dto.mdblist.MDBListRatingItemDto
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
}
