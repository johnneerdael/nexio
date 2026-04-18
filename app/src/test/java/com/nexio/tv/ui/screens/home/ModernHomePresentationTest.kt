package com.nexio.tv.ui.screens.home

import com.nexio.tv.domain.model.CatalogRow
import com.nexio.tv.domain.model.ContentType
import com.nexio.tv.domain.model.HomeDisplayMetadata
import com.nexio.tv.domain.model.MetaPreview
import com.nexio.tv.domain.model.PosterShape
import com.nexio.tv.domain.model.WatchProgress
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class ModernHomePresentationTest {
    @Test
    fun `builds continue watching before catalog rows`() {
        val cache = ModernCarouselRowBuildCache()
        val state = buildModernHomePresentation(
            input = ModernHomePresentationInput(
                catalogRows = listOf(catalogRow("popular", "Popular", ContentType.MOVIE, listOf(meta("movie-1")))),
                continueWatchingItems = listOf(inProgress("tt-cw-1", "Resume Me")),
                useLandscapePosters = false,
                showCatalogTypeSuffix = true,
                continueWatchingTitle = "Continue watching",
                airsDateTemplate = "Airs %s",
                upcomingLabel = "Upcoming"
            ),
            cache = cache
        )

        assertEquals(listOf("continue_watching", "addon_movie_popular"), state.rows.map { it.key })
        assertEquals("Continue watching", state.rows[0].title)
        assertEquals("Popular - Movie", state.rows[1].title)
        assertEquals(setOf("continue_watching", "addon_movie_popular"), state.lookups.activeRowKeys)
        assertTrue(state.lookups.activeCatalogItemIds.contains("movie-1"))
    }

    @Test
    fun `reuses cached row when catalog input is unchanged`() {
        val cache = ModernCarouselRowBuildCache()
        val row = catalogRow("popular", "Popular", ContentType.MOVIE, listOf(meta("movie-1")))
        val input = ModernHomePresentationInput(
            catalogRows = listOf(row),
            continueWatchingItems = emptyList(),
            useLandscapePosters = false,
            showCatalogTypeSuffix = true,
            continueWatchingTitle = "Continue watching",
            airsDateTemplate = "Airs %s",
            upcomingLabel = "Upcoming"
        )

        val first = buildModernHomePresentation(input, cache)
        val second = buildModernHomePresentation(input, cache)

        assertSame(first.rows.single(), second.rows.single())
        assertSame(first.rows.single().items.single(), second.rows.single().items.single())
    }

    @Test
    fun `removes stale catalog cache entries when row disappears`() {
        val cache = ModernCarouselRowBuildCache()
        buildModernHomePresentation(
            input = ModernHomePresentationInput(
                catalogRows = listOf(catalogRow("popular", "Popular", ContentType.MOVIE, listOf(meta("movie-1")))),
                continueWatchingItems = emptyList(),
                useLandscapePosters = false,
                showCatalogTypeSuffix = true,
                continueWatchingTitle = "Continue watching",
                airsDateTemplate = "Airs %s",
                upcomingLabel = "Upcoming"
            ),
            cache = cache
        )
        buildModernHomePresentation(
            input = ModernHomePresentationInput(
                catalogRows = emptyList(),
                continueWatchingItems = emptyList(),
                useLandscapePosters = false,
                showCatalogTypeSuffix = true,
                continueWatchingTitle = "Continue watching",
                airsDateTemplate = "Airs %s",
                upcomingLabel = "Upcoming"
            ),
            cache = cache
        )

        assertTrue(cache.catalogRows.isEmpty())
        assertTrue(cache.catalogItemCache.isEmpty())
    }

    private fun catalogRow(
        catalogId: String,
        catalogName: String,
        type: ContentType,
        items: List<MetaPreview>
    ): CatalogRow = CatalogRow(
        addonId = "addon",
        addonName = "Addon",
        addonBaseUrl = "https://addon.example",
        catalogId = catalogId,
        catalogName = catalogName,
        type = type,
        items = items
    )

    private fun meta(id: String): MetaPreview = MetaPreview(
        id = id,
        type = ContentType.MOVIE,
        name = "Title $id",
        poster = "https://img.example/$id.jpg",
        posterShape = PosterShape.POSTER,
        background = "https://img.example/$id-bg.jpg",
        logo = null,
        description = "Description $id",
        releaseInfo = "2026",
        imdbRating = null,
        genres = emptyList(),
        posterProviderTag = null
    )

    private fun inProgress(id: String, name: String): ContinueWatchingItem.InProgress =
        ContinueWatchingItem.InProgress(
            progress = WatchProgress(
                contentId = id,
                contentType = "series",
                name = name,
                poster = "https://img.example/$id.jpg",
                backdrop = "https://img.example/$id-bg.jpg",
                logo = null,
                videoId = "$id:1:1",
                season = 1,
                episode = 1,
                episodeTitle = "Episode 1",
                position = 10_000L,
                duration = 100_000L,
                lastWatched = 1_000L,
                progressPercent = 10f
            ),
            displayMetadata = HomeDisplayMetadata(title = name)
        )
}
