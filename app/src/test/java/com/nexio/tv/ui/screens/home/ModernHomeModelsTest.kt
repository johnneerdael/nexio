package com.nexio.tv.ui.screens.home

import com.nexio.tv.domain.model.ContentType
import com.nexio.tv.domain.model.HomeDisplayMetadata
import com.nexio.tv.domain.model.MetaPreview
import com.nexio.tv.domain.model.PosterShape
import com.nexio.tv.domain.model.WatchProgress
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
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
                tomatoesRating = 96.0,
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
        assertEquals("96", built.heroPreview.tomatoesText)
        assertEquals("displayBackdrop", built.imageUrl)
        assertEquals("tt123", built.metaPreview?.id)
        assertEquals(96.0, built.metaPreview?.tomatoesRating ?: 0.0, 0.0)
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
                    tomatoesRating = 91.0,
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
        assertEquals(91.0, preview.tomatoesRating ?: 0.0, 0.0)
        assertEquals(listOf("Comedy"), preview.genres)
        assertEquals(ContentType.SERIES, preview.type)
    }

    @Test
    fun `heroPreviewContentKey changes when tomatoes text changes for the active item`() {
        val withoutTomatoes = buildModernCarouselItem(tomatoesText = null)
        val withTomatoes = buildModernCarouselItem(tomatoesText = "88")

        assertNotEquals(
            heroPreviewContentKey(withoutTomatoes),
            heroPreviewContentKey(withTomatoes)
        )
    }

    @Test
    fun `resolveDisplayedHeroPreview prefers live preview updates for the same focused item`() {
        val stalePreview = buildModernCarouselItem(tomatoesText = null).heroPreview
        val livePreview = buildModernCarouselItem(tomatoesText = "88").heroPreview

        val resolved = resolveDisplayedHeroPreview(
            displayedHeroItemKey = "item_1",
            activeHeroItemKey = "item_1",
            displayedHeroPreview = stalePreview,
            liveActiveHeroPreview = livePreview
        )

        assertEquals("88", resolved?.tomatoesText)
    }

    @Test
    fun `applyTomatoesToContinueWatchingItem updates persisted display metadata`() {
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
                tomatoesRating = null
            )
        )

        val updated = applyTomatoesToContinueWatchingItem(item, 88.0)

        assertEquals(88.0, updated.displayMetadata().tomatoesRating ?: 0.0, 0.0)
    }

    private fun buildModernCarouselItem(tomatoesText: String?): ModernCarouselItem {
        return ModernCarouselItem(
            key = "item_1",
            title = "Paradise",
            subtitle = "2025",
            imageUrl = "background",
            heroPreview = HeroPreview(
                title = "Paradise",
                logo = "logo",
                description = "desc",
                contentTypeText = "Movie",
                yearText = "2025",
                imdbText = "7.8",
                tomatoesText = tomatoesText,
                genres = listOf("Action"),
                poster = "poster",
                backdrop = "background",
                imageUrl = "background"
            ),
            payload = ModernPayload.Catalog(
                focusKey = "focus",
                itemId = "tt123",
                itemType = "movie",
                addonBaseUrl = "https://api.example.com",
                trailerTitle = "Paradise",
                trailerReleaseInfo = "2025",
                trailerApiType = "movie"
            ),
            metaPreview = MetaPreview(
                id = "tt123",
                type = ContentType.MOVIE,
                name = "Paradise",
                poster = "poster",
                posterShape = PosterShape.POSTER,
                background = "background",
                logo = "logo",
                description = "desc",
                releaseInfo = "2025",
                runtime = null,
                imdbRating = 7.8f,
                tomatoesRating = tomatoesText?.toDouble(),
                genres = listOf("Action")
            )
        )
    }
}
