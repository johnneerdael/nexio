package com.nexio.tv.ui.screens.home

import android.content.Context
import com.nexio.tv.domain.model.ContentType
import com.nexio.tv.domain.model.CatalogRow
import com.nexio.tv.domain.model.HomeDisplayMetadata
import com.nexio.tv.domain.model.MetaPreview
import com.nexio.tv.domain.model.PosterShape
import com.nexio.tv.domain.model.WatchProgress
import io.mockk.every
import io.mockk.mockk
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

    @Test
    fun `buildModernHomePresentation creates row lookup by global row index`() {
        val presentation = buildModernHomePresentation(
            input = ModernHomePresentationInput(
                catalogRows = listOf(
                    CatalogRow(
                        addonId = "cinemeta",
                        addonName = "Cinemeta",
                        addonBaseUrl = "https://cinemeta.example",
                        catalogId = "popular",
                        catalogName = "Popular",
                        type = ContentType.MOVIE,
                        items = listOf(buildPreview("tt123", "Movie 1"))
                    ),
                    CatalogRow(
                        addonId = "cinemeta",
                        addonName = "Cinemeta",
                        addonBaseUrl = "https://cinemeta.example",
                        catalogId = "series",
                        catalogName = "Series",
                        type = ContentType.SERIES,
                        items = listOf(buildPreview("tt456", "Show 1"))
                    )
                ),
                continueWatchingItems = emptyList(),
                useLandscapePosters = false,
                showCatalogTypeSuffix = true,
                localeTag = "en"
            ),
            cache = ModernCarouselRowBuildCache(),
            context = mockHomeContext()
        )

        assertEquals(2, presentation.rows.size)
        assertEquals("cinemeta_movie_popular", presentation.lookups.rowKeyByGlobalRowIndex[0])
        assertEquals("cinemeta_series_series", presentation.lookups.rowKeyByGlobalRowIndex[1])
    }

    @Test
    fun `buildModernHomePresentation warm start limits catalog rows but keeps continue watching`() {
        val presentation = buildModernHomePresentation(
            input = ModernHomePresentationInput(
                catalogRows = listOf(
                    buildCatalogRow("popular", buildPreview("tt123", "Movie 1")),
                    buildCatalogRow("trending", buildPreview("tt124", "Movie 2")),
                    buildCatalogRow("top", buildPreview("tt125", "Movie 3"))
                ),
                continueWatchingItems = listOf(sampleInProgressItem()),
                useLandscapePosters = true,
                showCatalogTypeSuffix = true,
                localeTag = "en"
            ),
            cache = ModernCarouselRowBuildCache(),
            context = mockHomeContext(),
            maxCatalogRows = 2
        )

        assertEquals(3, presentation.rows.size)
        assertEquals("continue_watching", presentation.rows.first().key)
        assertEquals(listOf("continue_watching", "cinemeta_movie_popular", "cinemeta_movie_trending"), presentation.rows.map { it.key })
    }

    @Test
    fun `resolveSavedModernHomeFocusResolution defers until saved row exists`() {
        val resolution = resolveSavedModernHomeFocusResolution(
            hasSavedFocus = true,
            focusedRowIndex = 4,
            focusedItemIndex = 1,
            hasContinueWatching = true,
            preparedCatalogRowCount = 1,
            availableCatalogRowCount = 2,
            rowKeyByGlobalRowIndex = mapOf(0 to "cinemeta_movie_popular"),
            rows = listOf(
                HeroCarouselRow(
                    key = "continue_watching",
                    title = "Continue Watching",
                    globalRowIndex = -1,
                    items = listOf(buildModernCarouselItem("95%"))
                ),
                HeroCarouselRow(
                    key = "cinemeta_movie_popular",
                    title = "Popular",
                    globalRowIndex = 0,
                    items = listOf(buildModernCarouselItem("95%"))
                )
            )
        )

        assertEquals(SavedModernHomeFocusResolution.DeferUntilTargetExists, resolution)
    }

    @Test
    fun `resolveSavedModernHomeFocusResolution restores saved row once it exists`() {
        val resolution = resolveSavedModernHomeFocusResolution(
            hasSavedFocus = true,
            focusedRowIndex = 2,
            focusedItemIndex = 3,
            hasContinueWatching = false,
            preparedCatalogRowCount = 2,
            availableCatalogRowCount = 2,
            rowKeyByGlobalRowIndex = mapOf(2 to "cinemeta_movie_top"),
            rows = listOf(
                HeroCarouselRow(
                    key = "cinemeta_movie_popular",
                    title = "Popular",
                    globalRowIndex = 0,
                    items = listOf(buildModernCarouselItem("95%"))
                ),
                HeroCarouselRow(
                    key = "cinemeta_movie_top",
                    title = "Top",
                    globalRowIndex = 2,
                    items = listOf(
                        buildModernCarouselItem("95%"),
                        buildModernCarouselItem("94%")
                    )
                )
            )
        )

        assertEquals(
            SavedModernHomeFocusResolution.Restore(
                rowKey = "cinemeta_movie_top",
                itemIndex = 1
            ),
            resolution
        )
    }

    @Test
    fun `resolveSavedModernHomeFocusResolution falls back to first prepared row when target row is gone`() {
        val resolution = resolveSavedModernHomeFocusResolution(
            hasSavedFocus = true,
            focusedRowIndex = 4,
            focusedItemIndex = 1,
            hasContinueWatching = false,
            preparedCatalogRowCount = 2,
            availableCatalogRowCount = 2,
            rowKeyByGlobalRowIndex = mapOf(
                0 to "cinemeta_movie_popular",
                1 to "cinemeta_movie_top"
            ),
            rows = listOf(
                HeroCarouselRow(
                    key = "cinemeta_movie_popular",
                    title = "Popular",
                    globalRowIndex = 0,
                    items = listOf(buildModernCarouselItem("95%"))
                ),
                HeroCarouselRow(
                    key = "cinemeta_movie_top",
                    title = "Top",
                    globalRowIndex = 1,
                    items = listOf(buildModernCarouselItem("94%"))
                )
            )
        )

        assertEquals(SavedModernHomeFocusResolution.FallbackToFirstPreparedRow, resolution)
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

    private fun buildPreview(id: String, title: String): MetaPreview {
        return MetaPreview(
            id = id,
            type = ContentType.MOVIE,
            name = title,
            poster = "poster-$id",
            posterShape = PosterShape.POSTER,
            background = "background-$id",
            logo = "logo-$id",
            description = "$title description",
            releaseInfo = "2025",
            runtime = null,
            imdbRating = 7.5f,
            tomatoesRating = null,
            genres = listOf("Action")
        )
    }

    private fun buildCatalogRow(catalogId: String, item: MetaPreview): CatalogRow {
        return CatalogRow(
            addonId = "cinemeta",
            addonName = "Cinemeta",
            addonBaseUrl = "https://cinemeta.example",
            catalogId = catalogId,
            catalogName = catalogId.replaceFirstChar { it.uppercase() },
            type = ContentType.MOVIE,
            items = listOf(item)
        )
    }

    private fun sampleInProgressItem(): ContinueWatchingItem.InProgress {
        return ContinueWatchingItem.InProgress(
            progress = WatchProgress(
                contentId = "tt999",
                contentType = "movie",
                name = "Resume Item",
                poster = "resume-poster",
                backdrop = "resume-backdrop",
                logo = "resume-logo",
                videoId = "tt999",
                season = null,
                episode = null,
                episodeTitle = null,
                position = 120L,
                duration = 600L,
                lastWatched = 10L
            )
        )
    }

    private fun mockHomeContext(): Context {
        val context = mockk<Context>()
        every { context.getString(com.nexio.tv.R.string.continue_watching) } returns "Continue Watching"
        every { context.getString(com.nexio.tv.R.string.cw_airs_date) } returns "Airs %s"
        every { context.getString(com.nexio.tv.R.string.cw_upcoming) } returns "Upcoming"
        every { context.getString(com.nexio.tv.R.string.type_movie) } returns "Movie"
        every { context.getString(com.nexio.tv.R.string.type_series) } returns "Series"
        return context
    }
}
