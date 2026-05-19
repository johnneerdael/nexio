package com.nexio.tv.ui.screens.detail

import com.nexio.tv.domain.model.TitleRating
import com.nexio.tv.domain.model.TitleRatingSource
import com.nexio.tv.domain.model.MDBListRatings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class HeroSectionRatingGroupingTest {

    @Test
    fun `uses resolved imdb rating as grouped row imdb rating`() {
        val rating = resolveGroupedHeroImdbRating(
            resolvedRating = TitleRating(value = 7.8, source = TitleRatingSource.IMDB),
            fallbackRating = 6.2f,
            fallbackSource = TitleRatingSource.IMDB
        )

        assertEquals(7.8, rating ?: 0.0, 0.0)
    }

    @Test
    fun `falls back to meta imdb rating for grouped row`() {
        val rating = resolveGroupedHeroImdbRating(
            resolvedRating = null,
            fallbackRating = 7.3f,
            fallbackSource = TitleRatingSource.IMDB
        )

        assertEquals(7.3, rating ?: 0.0, 0.0001)
    }

    @Test
    fun `does not group non imdb title ratings`() {
        val rating = resolveGroupedHeroImdbRating(
            resolvedRating = TitleRating(value = 8.1, source = TitleRatingSource.TMDB),
            fallbackRating = 7.3f,
            fallbackSource = TitleRatingSource.IMDB
        )

        assertNull(rating)
    }

    @Test
    fun `rating row groups imdb first and mal second`() {
        val items = resolveHeroRatingsRowItems(
            ratings = MDBListRatings(mal = 8.7, tmdb = 8.2),
            imdbRating = 7.8
        )

        assertEquals(listOf("imdb", "mal", "tmdb"), items.map { it.provider })
    }
}
