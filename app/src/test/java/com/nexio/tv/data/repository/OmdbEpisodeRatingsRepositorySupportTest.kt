package com.nexio.tv.data.repository

import com.nexio.tv.data.remote.api.OmdbSeasonEpisodeDto
import com.nexio.tv.data.remote.api.OmdbSeasonResponse
import org.junit.Assert.assertEquals
import org.junit.Test

class OmdbEpisodeRatingsRepositorySupportTest {

    @Test
    fun `omdb episode ratings ttl is 24h`() {
        assertEquals(24L * 60L * 60L * 1000L, OMDB_EPISODE_RATINGS_TTL_MS)
    }

    @Test
    fun `parseSeasonRatings ignores N A and invalid episode numbers`() {
        val result = parseSeasonRatings(
            season = 1,
            response = OmdbSeasonResponse(
                response = "True",
                episodes = listOf(
                    OmdbSeasonEpisodeDto(episode = "1", imdbRating = "8.3", imdbId = "tt1"),
                    OmdbSeasonEpisodeDto(episode = "2", imdbRating = "N/A", imdbId = "tt2"),
                    OmdbSeasonEpisodeDto(episode = "x", imdbRating = "7.0", imdbId = "tt3")
                )
            )
        )

        assertEquals(mapOf((1 to 1) to 8.3), result)
    }
}
