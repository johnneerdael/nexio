package com.nexio.tv.domain.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class HomeDisplayMetadataCoalesceWithTest {

    @Test
    fun `coalesceWith returns this when fallback is null`() {
        val primary = HomeDisplayMetadata(title = "P")
        val result = primary.coalesceWith(null)
        assertEquals(primary, result)
    }

    @Test
    fun `coalesceWith fills null fields from fallback`() {
        val primary = HomeDisplayMetadata(title = "P")
        val fallback = HomeDisplayMetadata(title = "F", logo = "logo-url", description = "desc")
        val result = primary.coalesceWith(fallback)
        assertEquals("P", result.title)
        assertEquals("logo-url", result.logo)
        assertEquals("desc", result.description)
    }

    @Test
    fun `coalesceWith uses fallback genres when primary genres is empty`() {
        val primary = HomeDisplayMetadata(genres = emptyList())
        val fallback = HomeDisplayMetadata(genres = listOf("Action", "Drama"))
        val result = primary.coalesceWith(fallback)
        assertEquals(listOf("Action", "Drama"), result.genres)
    }

    @Test
    fun `coalesceWith keeps primary genres when non-empty`() {
        val primary = HomeDisplayMetadata(genres = listOf("Comedy"))
        val fallback = HomeDisplayMetadata(genres = listOf("Action", "Drama"))
        val result = primary.coalesceWith(fallback)
        assertEquals(listOf("Comedy"), result.genres)
    }

    @Test
    fun `coalesceWith keeps primary imdbRating + ratingSource when present`() {
        val primary = HomeDisplayMetadata(imdbRating = 8.5f, ratingSource = TitleRatingSource.IMDB)
        val fallback = HomeDisplayMetadata(imdbRating = 7.0f, ratingSource = TitleRatingSource.TMDB)
        val result = primary.coalesceWith(fallback)
        assertEquals(8.5f, result.imdbRating)
        assertEquals(TitleRatingSource.IMDB, result.ratingSource)
    }

    @Test
    fun `coalesceWith uses fallback imdbRating + ratingSource when primary rating is null`() {
        val primary = HomeDisplayMetadata(imdbRating = null)
        val fallback = HomeDisplayMetadata(imdbRating = 7.0f, ratingSource = TitleRatingSource.TMDB)
        val result = primary.coalesceWith(fallback)
        assertEquals(7.0f, result.imdbRating)
        assertEquals(TitleRatingSource.TMDB, result.ratingSource)
    }

    @Test
    fun `coalesceWith preserves null fields when both inputs are null`() {
        val primary = HomeDisplayMetadata()
        val fallback = HomeDisplayMetadata()
        val result = primary.coalesceWith(fallback)
        assertNull(result.title)
        assertNull(result.logo)
        assertNull(result.description)
    }
}
