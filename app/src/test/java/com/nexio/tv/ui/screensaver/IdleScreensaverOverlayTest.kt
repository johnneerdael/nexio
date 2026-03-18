package com.nexio.tv.ui.screensaver

import org.junit.Assert.assertEquals
import org.junit.Test

class IdleScreensaverOverlayTest {

    @Test
    fun `buildScreensaverPrimaryMetaSegments uses a single divider between genres and ratings`() {
        val slide = buildSlide(
            genres = listOf("Action", "Drama", "Thriller"),
            imdbRating = 7.8f,
            tomatoesRating = 88.0
        )

        val segments = buildScreensaverPrimaryMetaSegments(slide)

        assertEquals(
            listOf(
                ScreensaverPrimaryMetaSegment.Genres("Action • Drama • Thriller"),
                ScreensaverPrimaryMetaSegment.Divider,
                ScreensaverPrimaryMetaSegment.Rating(ScreensaverPrimaryMetaSegment.Rating.Kind.IMDB, "7.8"),
                ScreensaverPrimaryMetaSegment.Rating(ScreensaverPrimaryMetaSegment.Rating.Kind.TOMATOES, "88")
            ),
            segments
        )
    }

    @Test
    fun `buildScreensaverPrimaryMetaSegments omits dividers when only ratings are present`() {
        val slide = buildSlide(
            genres = emptyList(),
            imdbRating = 7.8f,
            tomatoesRating = 88.0
        )

        val segments = buildScreensaverPrimaryMetaSegments(slide)

        assertEquals(
            listOf(
                ScreensaverPrimaryMetaSegment.Rating(ScreensaverPrimaryMetaSegment.Rating.Kind.IMDB, "7.8"),
                ScreensaverPrimaryMetaSegment.Rating(ScreensaverPrimaryMetaSegment.Rating.Kind.TOMATOES, "88")
            ),
            segments
        )
    }

    private fun buildSlide(
        genres: List<String>,
        imdbRating: Float?,
        tomatoesRating: Double?
    ): IdleScreensaverSlide {
        return IdleScreensaverSlide(
            itemId = "tt123",
            itemType = "movie",
            addonBaseUrl = "https://api.example.com",
            title = "Example",
            backgroundUrl = "https://image.example.com/background.jpg",
            logoUrl = null,
            genres = genres,
            description = null,
            releaseInfo = null,
            runtime = null,
            imdbRating = imdbRating,
            tomatoesRating = tomatoesRating
        )
    }
}
