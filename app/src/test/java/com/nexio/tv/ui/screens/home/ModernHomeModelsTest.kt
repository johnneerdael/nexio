package com.nexio.tv.ui.screens.home

import com.nexio.tv.domain.model.ContentType
import com.nexio.tv.domain.model.HomeDisplayMetadata
import com.nexio.tv.domain.model.WatchProgress
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ModernHomeModelsTest {

    @Test
    fun `buildContinueWatchingItem prefers shared display metadata for in progress items`() {
        val item = ContinueWatchingItem.InProgress(
            progress = WatchProgress(
                contentId = "tt123",
                contentType = "movie",
                name = "Raw Title",
                poster = "rawPoster",
                backdrop = "rawBackdrop",
                logo = "rawLogo",
                videoId = "tt123",
                season = null,
                episode = null,
                episodeTitle = null,
                position = 100L,
                duration = 1_000L,
                lastWatched = 1L
            ),
            displayMetadata = HomeDisplayMetadata(
                title = "Display Title",
                logo = "displayLogo",
                description = "Display Description",
                genres = listOf("Drama"),
                releaseInfo = "2025",
                imdbRating = 8.5f,
                poster = "displayPoster",
                backdrop = "displayBackdrop"
            )
        )

        val built = buildContinueWatchingItem(
            item = item,
            useLandscapePosters = true,
            airsDateTemplate = "Airs %s",
            upcomingLabel = "Upcoming"
        )

        assertEquals("Display Title", built.title)
        assertEquals("Display Title", built.heroPreview.title)
        assertEquals("displayLogo", built.heroPreview.logo)
        assertEquals("Display Description", built.heroPreview.description)
        assertEquals("8.5", built.heroPreview.imdbText)
        assertEquals("displayBackdrop", built.imageUrl)
        assertTrue(built.heroPreview.genres.contains("Drama"))
    }

    @Test
    fun `nextUpToMetaPreview uses persisted display metadata`() {
        val nextUp = ContinueWatchingItem.NextUp(
            NextUpInfo(
                contentId = "tt456",
                contentType = "series",
                name = "Raw Show",
                poster = "rawPoster",
                backdrop = "rawBackdrop",
                logo = "rawLogo",
                displayMetadata = HomeDisplayMetadata(
                    title = "Localized Show",
                    logo = "displayLogo",
                    description = "Localized overview",
                    genres = listOf("Comedy"),
                    releaseInfo = "2024",
                    imdbRating = 8.2f,
                    poster = "displayPoster",
                    backdrop = "displayBackdrop"
                ),
                videoId = "tt456:1:2",
                season = 1,
                episode = 2,
                episodeTitle = "Episode 2",
                thumbnail = null,
                lastWatched = 1L
            )
        )

        val preview = nextUpToMetaPreview(nextUp)

        assertTrue(preview.name.startsWith("Localized Show"))
        assertEquals("displayPoster", preview.poster)
        assertEquals("displayBackdrop", preview.background)
        assertEquals("displayLogo", preview.logo)
        assertEquals("Localized overview", preview.description)
        assertEquals("2024", preview.releaseInfo)
        assertEquals(8.2f, preview.imdbRating)
        assertEquals(listOf("Comedy"), preview.genres)
        assertEquals(ContentType.SERIES, preview.type)
    }
}
