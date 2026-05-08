package com.nexio.tv.ui.screens.home

import com.nexio.tv.domain.model.CatalogRow
import com.nexio.tv.domain.model.ContentType
import com.nexio.tv.domain.model.HomeDisplayMetadata
import com.nexio.tv.domain.model.HydratedHomeFieldTrace
import com.nexio.tv.domain.model.HydratedHomeOverlay
import com.nexio.tv.domain.model.MetaPreview
import com.nexio.tv.domain.model.PosterShape
import com.nexio.tv.domain.model.ProviderId
import com.nexio.tv.domain.model.TitleRatingSource
import com.nexio.tv.domain.model.TmdbSettings
import com.nexio.tv.domain.model.hydratedHomeDisplayHash
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class HomeReactiveHydrationPipelineTest {
    @Test
    fun `overlay snapshot composition updates visible card and hero without row reorder`() {
        val first = preview("tmdb:550", "Preview")
        val second = preview("tmdb:551", "Second")
        val displayRow = row(listOf(first, second))
        val fullRow = row(listOf(first, second, preview("tmdb:552", "Third")))
        val overlay = overlay(
            itemKey = "movie:tmdb:550",
            fields = HomeDisplayMetadata(title = "Canonical", poster = "poster.jpg")
        )

        val composed = composeHydratedHomeOverlaySnapshot(
            displayRows = listOf(displayRow),
            fullRows = listOf(fullRow),
            heroItems = listOf(first, second),
            overlaysByItemKey = mapOf("movie:tmdb:550" to overlay)
        )

        assertEquals(listOf("tmdb:550", "tmdb:551"), composed.displayRows.single().items.map { it.id })
        assertEquals(listOf("tmdb:550", "tmdb:551", "tmdb:552"), composed.fullRows.single().items.map { it.id })
        assertEquals(listOf("tmdb:550", "tmdb:551"), composed.heroItems.map { it.id })
        assertEquals("Canonical", composed.displayRows.single().items.first().name)
        assertEquals("poster.jpg", composed.displayRows.single().items.first().poster)
        assertEquals("Canonical", composed.fullRows.single().items.first().name)
        assertEquals("Canonical", composed.heroItems.first().name)
        assertEquals("Second", composed.displayRows.single().items.last().name)
    }

    @Test
    fun `overlay observation keys are deduped across display and full rows`() {
        val first = preview("tmdb:550", "Preview")
        val second = preview("tmdb:551", "Second")
        val keys = hydratedHomeOverlayItemKeysForRows(
            listOf(
                row(listOf(first, second)),
                row(listOf(first))
            )
        )

        assertEquals(setOf("movie:tmdb:550", "movie:tmdb:551"), keys)
    }

    @Test
    fun `unchanged overlay map does not request a republish`() {
        val overlay = overlay(
            itemKey = "movie:tmdb:550",
            fields = HomeDisplayMetadata(title = "Canonical")
        )
        val current = mapOf("movie:tmdb:550" to overlay)

        assertFalse(shouldPublishHydratedHomeOverlays(current, current.toMap()))
        assertTrue(shouldPublishHydratedHomeOverlays(emptyMap(), current))
    }

    @Test
    fun `unchanged overlay map ignoring timestamps does not request a republish`() {
        val baseOverlay = overlay(
            itemKey = "movie:tmdb:550",
            fields = HomeDisplayMetadata(title = "Fight Club")
        )
        val current = mapOf("movie:tmdb:550" to baseOverlay)
        val nextSameContent = mapOf(
            "movie:tmdb:550" to baseOverlay.copy(
                updatedAtMs = 5_000L,
                staleAtMs = 6_000L,
                expiresAtMs = 7_000L
            )
        )

        assertFalse(shouldPublishHydratedHomeOverlays(current, nextSameContent))
    }

    @Test
    fun `snapshot composition preserves hero identity when overlay display is unchanged`() {
        val item = preview("tmdb:550", "Preview")
        val heroItems = listOf(item)
        val overlay = overlay(
            itemKey = "movie:tmdb:550",
            fields = HomeDisplayMetadata(title = "Preview")
        )

        val composed = composeHydratedHomeOverlaySnapshot(
            displayRows = emptyList(),
            fullRows = emptyList(),
            heroItems = heroItems,
            overlaysByItemKey = mapOf("movie:tmdb:550" to overlay)
        )

        assertSame(heroItems, composed.heroItems)
        assertSame(item, composed.heroItems.single())
    }

    @Test
    fun `overlay snapshot composition respects disabled hero field groups`() {
        val item = preview("tmdb:550", "Preview").copy(
            logo = "preview-logo",
            background = "preview-backdrop",
            description = "Preview description",
            genres = listOf("Preview"),
            releaseInfo = "2024",
            runtime = "90m",
            imdbRating = 6.5f,
            ratingSource = TitleRatingSource.IMDB
        )
        val overlay = overlay(
            itemKey = "movie:tmdb:550",
            fields = HomeDisplayMetadata(
                title = "Canonical",
                logo = "canonical-logo",
                backdrop = "canonical-backdrop",
                description = "Canonical description",
                genres = listOf("Canonical"),
                releaseInfo = "2025",
                runtime = "110m",
                imdbRating = 8.4f,
                ratingSource = TitleRatingSource.TMDB,
                poster = "canonical-poster"
            )
        )

        val composed = composeHydratedHomeOverlaySnapshot(
            displayRows = listOf(row(listOf(item))),
            fullRows = listOf(row(listOf(item))),
            heroItems = listOf(item),
            overlaysByItemKey = mapOf("movie:tmdb:550" to overlay),
            heroTmdbSettings = TmdbSettings(
                useArtwork = false,
                useBasicInfo = false,
                useDetails = false
            )
        )

        assertEquals("Canonical", composed.displayRows.single().items.single().name)
        assertEquals("canonical-poster", composed.displayRows.single().items.single().poster)
        assertEquals("Canonical", composed.fullRows.single().items.single().name)

        val hero = composed.heroItems.single()
        assertEquals("Preview", hero.name)
        assertEquals("preview-logo", hero.logo)
        assertEquals("preview-backdrop", hero.background)
        assertEquals("Preview description", hero.description)
        assertEquals(listOf("Preview"), hero.genres)
        assertEquals("2024", hero.releaseInfo)
        assertEquals("90m", hero.runtime)
        assertEquals(6.5f, hero.imdbRating ?: 0f, 0f)
        assertEquals(TitleRatingSource.IMDB, hero.ratingSource)
        assertEquals(null, hero.poster)
    }

    @Test
    fun `overlay snapshot composition applies enabled hero field groups`() {
        val item = preview("tmdb:550", "Preview").copy(
            logo = "preview-logo",
            background = "preview-backdrop",
            description = "Preview description",
            releaseInfo = "2024"
        )
        val overlay = overlay(
            itemKey = "movie:tmdb:550",
            fields = HomeDisplayMetadata(
                title = "Canonical",
                logo = "canonical-logo",
                backdrop = "canonical-backdrop",
                description = "Canonical description",
                releaseInfo = "2025"
            )
        )

        val composed = composeHydratedHomeOverlaySnapshot(
            displayRows = listOf(row(listOf(item))),
            fullRows = listOf(row(listOf(item))),
            heroItems = listOf(item),
            overlaysByItemKey = mapOf("movie:tmdb:550" to overlay),
            heroTmdbSettings = TmdbSettings(
                useArtwork = true,
                useBasicInfo = false,
                useDetails = false
            )
        )

        assertEquals("Canonical", composed.displayRows.single().items.single().name)

        val hero = composed.heroItems.single()
        assertEquals("Preview", hero.name)
        assertEquals("canonical-logo", hero.logo)
        assertEquals("canonical-backdrop", hero.background)
        assertEquals("Preview description", hero.description)
        assertEquals("2024", hero.releaseInfo)
    }

    private fun preview(id: String, title: String) = MetaPreview(
        id = id,
        type = ContentType.MOVIE,
        rawType = "movie",
        name = title,
        poster = null,
        posterShape = PosterShape.POSTER,
        background = null,
        logo = null,
        description = null,
        releaseInfo = null,
        imdbRating = null,
        genres = emptyList()
    )

    private fun row(items: List<MetaPreview>) = CatalogRow(
        addonId = "addon",
        addonName = "Addon",
        addonBaseUrl = "https://addon.example",
        catalogId = "popular",
        catalogName = "Popular",
        type = ContentType.MOVIE,
        items = items,
        hasMore = false
    )

    private fun overlay(
        itemKey: String,
        fields: HomeDisplayMetadata
    ) = HydratedHomeOverlay(
        overlayKey = "canonical:TMDB:550:type:MOVIE:lang:en:policy:1",
        itemKey = itemKey,
        canonicalProvider = ProviderId.TMDB,
        canonicalId = "550",
        imdbId = "tt0137523",
        contentType = ContentType.MOVIE,
        languageTag = "en",
        fields = fields,
        fieldTrace = listOf(HydratedHomeFieldTrace("TITLE", "TMDB", "PRIMARY")),
        displayHash = fields.hydratedHomeDisplayHash(),
        updatedAtMs = 1L,
        staleAtMs = 2L,
        expiresAtMs = 3L
    )
}
