package com.nexio.tv.domain.model

import org.junit.Assert.assertEquals
import org.junit.Test

class HomeDisplayMetadataApplyToPreviewTest {

    @Test
    fun `applyToPreview overlays title onto base MetaPreview`() {
        val base = makePreview(name = "Base")
        val overlay = HomeDisplayMetadata(title = "Overlay")
        val result = overlay.applyToPreview(base)
        assertEquals("Overlay", result.name)
    }

    @Test
    fun `applyToPreview keeps base name when overlay title is null`() {
        val base = makePreview(name = "Base")
        val overlay = HomeDisplayMetadata(title = null)
        val result = overlay.applyToPreview(base)
        assertEquals("Base", result.name)
    }

    @Test
    fun `applyToPreview overlays description, releaseInfo, runtime, genres`() {
        val base = makePreview(description = "BD", releaseInfo = "BR", genres = emptyList())
        val overlay = HomeDisplayMetadata(
            description = "OD",
            releaseInfo = "OR",
            runtime = "120 min",
            genres = listOf("Action")
        )
        val result = overlay.applyToPreview(base)
        assertEquals("OD", result.description)
        assertEquals("OR", result.releaseInfo)
        assertEquals("120 min", result.runtime)
        assertEquals(listOf("Action"), result.genres)
    }

    @Test
    fun `applyToPreview takes overlay imdbRating + ratingSource when present`() {
        val base = makePreview(imdbRating = 7.0f, ratingSource = TitleRatingSource.IMDB)
        val overlay = HomeDisplayMetadata(imdbRating = 8.5f, ratingSource = TitleRatingSource.TMDB)
        val result = overlay.applyToPreview(base)
        assertEquals(8.5f, result.imdbRating)
        assertEquals(TitleRatingSource.TMDB, result.ratingSource)
    }

    @Test
    fun `applyToPreview falls back to base imdbRating + ratingSource when overlay rating is null`() {
        val base = makePreview(imdbRating = 7.0f, ratingSource = TitleRatingSource.IMDB)
        val overlay = HomeDisplayMetadata(imdbRating = null)
        val result = overlay.applyToPreview(base)
        assertEquals(7.0f, result.imdbRating)
        assertEquals(TitleRatingSource.IMDB, result.ratingSource)
    }

    @Test
    fun `applyToPreview produces same result as deprecated applyTo`() {
        // Behavioral parity: applyToPreview must match the existing applyTo body.
        val base = makePreview(name = "Base", description = "BD", imdbRating = 7.0f)
        val overlay = HomeDisplayMetadata(
            title = "Overlay",
            description = "OD",
            imdbRating = 8.5f,
            ratingSource = TitleRatingSource.TMDB
        )
        @Suppress("DEPRECATION")
        val deprecated = overlay.applyTo(base)
        val replacement = overlay.applyToPreview(base)
        assertEquals(deprecated, replacement)
    }

    private fun makePreview(
        name: String = "x",
        description: String? = null,
        releaseInfo: String? = null,
        imdbRating: Float? = null,
        ratingSource: TitleRatingSource? = null,
        genres: List<String> = emptyList()
    ): MetaPreview = MetaPreview(
        id = "tt1",
        type = ContentType.MOVIE,
        name = name,
        poster = null,
        posterShape = PosterShape.POSTER,
        background = null,
        logo = null,
        description = description,
        releaseInfo = releaseInfo,
        imdbRating = imdbRating,
        ratingSource = ratingSource,
        genres = genres
    )
}
