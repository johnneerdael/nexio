package com.nexio.tv.ui.screens.detail

import org.junit.Assert.assertEquals
import org.junit.Test

class MetaDetailsEpisodeRatingsMergeTest {

    @Test
    fun `resolveEpisodeRatings prefers OMDB values for overlapping episode keys`() {
        val tmdb = mapOf(
            (1 to 1) to 7.2,
            (1 to 2) to 6.8
        )
        val omdb = mapOf(
            (1 to 2) to 8.4,
            (1 to 3) to 8.1
        )

        val resolved = resolveEpisodeRatings(tmdb, omdb)

        assertEquals(EpisodeRating(7.2, EpisodeRatingSource.TMDB), resolved[1 to 1])
        assertEquals(EpisodeRating(8.4, EpisodeRatingSource.OMDB), resolved[1 to 2])
        assertEquals(EpisodeRating(8.1, EpisodeRatingSource.OMDB), resolved[1 to 3])
    }

    @Test
    fun `resolveEpisodeRatingValues strips sources without changing numbers`() {
        val resolved = mapOf(
            (1 to 1) to EpisodeRating(8.3, EpisodeRatingSource.OMDB),
            (1 to 2) to EpisodeRating(6.1, EpisodeRatingSource.TMDB)
        )

        assertEquals(
            mapOf(
                (1 to 1) to 8.3,
                (1 to 2) to 6.1
            ),
            resolveEpisodeRatingValues(resolved)
        )
    }
}
