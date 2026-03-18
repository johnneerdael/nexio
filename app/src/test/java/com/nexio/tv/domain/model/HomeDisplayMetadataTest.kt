package com.nexio.tv.domain.model

import org.junit.Assert.assertEquals
import org.junit.Test

class HomeDisplayMetadataTest {

    @Test
    fun `toHomeDisplayMetadata and applyTo preserve tomatoes rating`() {
        val preview = MetaPreview(
            id = "tt123",
            type = ContentType.MOVIE,
            name = "Movie",
            poster = "poster",
            posterShape = PosterShape.POSTER,
            background = "background",
            logo = "logo",
            description = "description",
            releaseInfo = "2025",
            runtime = "120",
            imdbRating = 8.3f,
            tomatoesRating = 93.0,
            genres = listOf("Drama")
        )

        val displayMetadata = preview.toHomeDisplayMetadata()
        val roundTripped = displayMetadata.applyTo(
            preview.copy(tomatoesRating = null)
        )

        assertEquals(93.0, displayMetadata.tomatoesRating ?: 0.0, 0.0)
        assertEquals(93.0, roundTripped.tomatoesRating ?: 0.0, 0.0)
    }
}
