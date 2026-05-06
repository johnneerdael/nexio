package com.nexio.tv.ui.screens.detail

import org.junit.Assert.assertEquals
import org.junit.Test

class MetaDetailsEpisodeRatingsMergeTest {

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
