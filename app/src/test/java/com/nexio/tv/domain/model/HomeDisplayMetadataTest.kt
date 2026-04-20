package com.nexio.tv.domain.model

import com.google.gson.Gson
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

    @Test
    fun `toHomeDisplayMetadata and applyTo preserve poster provider tag`() {
        val preview = MetaPreview(
            id = "tt123",
            type = ContentType.MOVIE,
            name = "Movie",
            poster = "https://api.ratingposterdb.com/key/imdb/poster-default/tt123.jpg",
            posterShape = PosterShape.POSTER,
            background = "background",
            logo = "logo",
            description = "description",
            releaseInfo = "2025",
            runtime = "120",
            imdbRating = 8.3f,
            tomatoesRating = 93.0,
            genres = listOf("Drama"),
            posterProviderTag = "rpdb"
        )

        val displayMetadata = preview.toHomeDisplayMetadata()
        val roundTripped = displayMetadata.applyTo(
            preview.copy(posterProviderTag = null)
        )

        assertEquals("rpdb", displayMetadata.posterProviderTag)
        assertEquals("rpdb", roundTripped.posterProviderTag)
    }

    @Test
    fun `toHomeDisplayMetadata and applyTo preserve rating source`() {
        val preview = MetaPreview(
            id = "tmdb:1399",
            type = ContentType.SERIES,
            name = "Game of Thrones",
            poster = null,
            posterShape = PosterShape.POSTER,
            background = null,
            logo = null,
            description = null,
            releaseInfo = "2011",
            imdbRating = 8.9f,
            ratingSource = TitleRatingSource.TMDB,
            genres = emptyList()
        )

        val displayMetadata = preview.toHomeDisplayMetadata()
        val roundTripped = displayMetadata.applyTo(
            preview.copy(imdbRating = null, ratingSource = TitleRatingSource.IMDB)
        )

        assertEquals(TitleRatingSource.TMDB, displayMetadata.ratingSource)
        assertEquals(TitleRatingSource.TMDB, roundTripped.ratingSource)
    }

    @Test
    fun `legacy preview without rating source can be hashed and converted`() {
        val preview = Gson().fromJson(
            """
            {
              "id": "tt123",
              "type": "MOVIE",
              "rawType": "movie",
              "name": "Movie",
              "poster": null,
              "posterShape": "POSTER",
              "background": null,
              "logo": null,
              "description": null,
              "releaseInfo": "2025",
              "runtime": null,
              "imdbRating": 8.3,
              "tomatoesRating": null,
              "genres": [],
              "trailerYtIds": [],
              "language": null,
              "posterProviderTag": null
            }
            """.trimIndent(),
            MetaPreview::class.java
        )

        preview.hashCode()
        val displayMetadata = preview.toHomeDisplayMetadata()

        assertEquals(TitleRatingSource.IMDB, displayMetadata.ratingSource)
    }
}
