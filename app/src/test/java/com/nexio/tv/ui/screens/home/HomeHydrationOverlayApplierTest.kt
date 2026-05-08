package com.nexio.tv.ui.screens.home

import com.nexio.tv.domain.model.CatalogRow
import com.nexio.tv.domain.model.ContentType
import com.nexio.tv.domain.model.HomeDisplayMetadata
import com.nexio.tv.domain.model.HydratedHomeFieldTrace
import com.nexio.tv.domain.model.HydratedHomeOverlay
import com.nexio.tv.domain.model.MetaPreview
import com.nexio.tv.domain.model.PosterShape
import com.nexio.tv.domain.model.applyTo
import com.nexio.tv.domain.model.ProviderId
import com.nexio.tv.domain.model.ProviderIds
import com.nexio.tv.domain.model.TitleRatingSource
import com.nexio.tv.domain.model.hydratedHomeDisplayHash
import com.nexio.tv.domain.model.toHomeDisplayMetadata
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Test

class HomeHydrationOverlayApplierTest {
    @Test
    fun `overlay replaces display fields on matching item without changing row order`() {
        val first = preview("tmdb:550", "Preview title")
        val second = preview("tmdb:551", "Second")
        val row = row(listOf(first, second))
        val overlay = overlay(
            itemKey = "movie:tmdb:550",
            fields = HomeDisplayMetadata(
                title = "Canonical title",
                poster = "poster.jpg",
                backdrop = "background.jpg",
                logo = "logo.png",
                description = "Canonical overview",
                genres = listOf("Drama"),
                releaseInfo = "1999",
                runtime = "139 min",
                imdbRating = 8.8f,
                ratingSource = TitleRatingSource.IMDB,
                tomatoesRating = 79.0,
                posterProviderTag = "tmdb"
            )
        )

        val updated = row.applyHydratedHomeOverlays(mapOf("movie:tmdb:550" to overlay))

        assertEquals(listOf("tmdb:550", "tmdb:551"), updated.items.map { it.id })
        assertEquals("Canonical title", updated.items.first().name)
        assertEquals("poster.jpg", updated.items.first().poster)
        assertEquals("background.jpg", updated.items.first().background)
        assertEquals("logo.png", updated.items.first().logo)
        assertEquals("Canonical overview", updated.items.first().description)
        assertEquals(listOf("Drama"), updated.items.first().genres)
        assertEquals("1999", updated.items.first().releaseInfo)
        assertEquals("139 min", updated.items.first().runtime)
        assertEquals(8.8f, updated.items.first().imdbRating ?: 0f, 0f)
        assertEquals(TitleRatingSource.IMDB, updated.items.first().ratingSource)
        assertEquals(79.0, updated.items.first().tomatoesRating ?: 0.0, 0.0)
        assertEquals("tmdb", updated.items.first().posterProviderTag)
        assertEquals("Second", updated.items.last().name)
    }

    @Test
    fun `row and list instances are reused when overlay display does not change items`() {
        val item = preview("tmdb:550", "Same title")
        val row = row(listOf(item))
        val rows = listOf(row)
        val overlay = overlay(
            itemKey = "movie:tmdb:550",
            fields = item.toHomeDisplayMetadata()
        )

        val updatedRow = row.applyHydratedHomeOverlays(mapOf("movie:tmdb:550" to overlay))
        val updatedRows = rows.applyHydratedHomeOverlays(mapOf("movie:tmdb:550" to overlay))

        assertSame(row, updatedRow)
        assertSame(rows, updatedRows)
    }

    @Test
    fun `overlays apply by item key without provider-specific rendering branches`() {
        val first = preview("tmdb:550", "Preview")
        val second = preview("imdb:tt0137523", "Preview")
        val rows = listOf(row(listOf(first, second)))
        val tmdbOverlay = overlay(
            itemKey = "movie:tmdb:550",
            canonicalProvider = ProviderId.TMDB,
            fields = HomeDisplayMetadata(title = "TMDB Canonical")
        )
        val imdbOverlay = overlay(
            itemKey = "movie:imdb:tt0137523",
            canonicalProvider = ProviderId.IMDB,
            fields = HomeDisplayMetadata(title = "IMDb Canonical")
        )

        val updated = rows.applyHydratedHomeOverlays(
            mapOf(
                "movie:tmdb:550" to tmdbOverlay,
                "movie:imdb:tt0137523" to imdbOverlay
            )
        )

        assertEquals(listOf("TMDB Canonical", "IMDb Canonical"), updated.single().items.map { it.name })
        val applierSource = File("app/src/main/java/com/nexio/tv/ui/screens/home/HomeHydrationOverlayApplier.kt").readText()
        assertFalse(applierSource.contains("ProviderId."))
    }

    @Test
    fun `rowsForResolvedDisplaySurface returns final overlay applied rows`() {
        val item = preview("tmdb:550", "Preview title")
        val overlay = overlay(
            itemKey = "movie:tmdb:550",
            fields = HomeDisplayMetadata(
                title = "Resolved title",
                poster = "resolved-poster",
                backdrop = "resolved-backdrop",
                imdbRating = 8.8f,
                ratingSource = TitleRatingSource.IMDB
            )
        )

        val publishedRows = rowsForResolvedDisplaySurface(
            rows = listOf(row(listOf(item))),
            overlaysByItemKey = mapOf("movie:tmdb:550" to overlay)
        )

        assertEquals("Resolved title", publishedRows.single().items.single().name)
        assertEquals("resolved-poster", publishedRows.single().items.single().poster)
        assertEquals(8.8f, publishedRows.single().items.single().imdbRating ?: 0f, 0f)
    }

    @Test
    fun `applyHydratedHomeOverlays applies overlay stored under canonical alias to trakt-keyed series row`() {
        val item = MetaPreview(
            id = "trakt:171028",
            type = ContentType.SERIES,
            rawType = "series",
            name = "First-paint title",
            poster = null,
            posterShape = PosterShape.POSTER,
            background = null,
            logo = null,
            description = null,
            releaseInfo = null,
            imdbRating = null,
            genres = emptyList(),
            firstPaintStableIds = ProviderIds(
                trakt = "171028",
                tvdb = "355567",
                imdb = "tt1190634"
            )
        )
        val row = CatalogRow(
            addonId = "trakt-addon",
            addonName = "Trakt",
            addonBaseUrl = "https://trakt.example",
            catalogId = "trending",
            catalogName = "Trending",
            type = ContentType.SERIES,
            items = listOf(item),
            hasMore = false
        )
        val overlay = HydratedHomeOverlay(
            overlayKey = "canonical:TVDB:355567:type:SERIES:lang:en:policy:1",
            itemKey = "series:trakt:171028",
            canonicalProvider = ProviderId.TVDB,
            canonicalId = "355567",
            imdbId = "tt1190634",
            contentType = ContentType.SERIES,
            languageTag = "en",
            fields = HomeDisplayMetadata(title = "The Boys"),
            fieldTrace = listOf(HydratedHomeFieldTrace("TITLE", "TVDB", "PRIMARY")),
            displayHash = HomeDisplayMetadata(title = "The Boys").hydratedHomeDisplayHash(),
            updatedAtMs = 1L,
            staleAtMs = 2L,
            expiresAtMs = 3L
        )
        // Overlay stored ONLY under the canonical TVDB alias key, mimicking the store-flow path
        // that returns Map<aliasKey, overlay> after the read scope expanded the row's lookup.
        val overlaysByAlias = mapOf("series:tvdb:355567" to overlay)

        val updated = row.applyHydratedHomeOverlays(overlaysByAlias)

        assertEquals("The Boys", updated.items.single().name)
    }

    @Test
    fun `applyTo preserves base nexio-artwork poster against bare URL overlay poster`() {
        val basePremiumRef = "nexio-artwork://decision/artwork-decision:poster:canonical:tmdb:series-76479:provider:RPDB:premium:true:settings:abc:credential:def:imageLang:en:policy:1"
        val item = preview(id = "trakt:171028", title = "First-paint title").copy(
            poster = basePremiumRef,
            posterProviderTag = "rpdb"
        )
        val overlayMetadata = HomeDisplayMetadata(
            title = "The Boys",
            poster = "https://image.tmdb.org/t/p/w500/abc.jpg",
            posterProviderTag = "tmdb"
        )

        val applied = overlayMetadata.applyTo(item)

        assertEquals(basePremiumRef, applied.poster)
        assertEquals("rpdb", applied.posterProviderTag)
        assertEquals("The Boys", applied.name)
    }

    @Test
    fun `applyTo prefers overlay poster when overlay carries a nexio-artwork ref`() {
        val overlayPremiumRef = "nexio-artwork://decision/artwork-decision:poster:canonical:tmdb:series-76479:provider:RPDB:premium:true:settings:def:credential:abc:imageLang:en:policy:1"
        val item = preview(id = "trakt:171028", title = "First-paint title").copy(
            poster = "https://image.tmdb.org/t/p/w500/abc.jpg"
        )
        val overlayMetadata = HomeDisplayMetadata(
            poster = overlayPremiumRef,
            posterProviderTag = "rpdb"
        )

        val applied = overlayMetadata.applyTo(item)

        assertEquals(overlayPremiumRef, applied.poster)
        assertEquals("rpdb", applied.posterProviderTag)
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
        canonicalProvider: ProviderId = ProviderId.TMDB,
        fields: HomeDisplayMetadata
    ) = HydratedHomeOverlay(
        overlayKey = "canonical:${canonicalProvider.name}:550:type:MOVIE:lang:en:policy:1",
        itemKey = itemKey,
        canonicalProvider = canonicalProvider,
        canonicalId = "550",
        imdbId = "tt0137523",
        contentType = ContentType.MOVIE,
        languageTag = "en",
        fields = fields,
        fieldTrace = listOf(HydratedHomeFieldTrace("TITLE", canonicalProvider.name, "PRIMARY")),
        displayHash = fields.hydratedHomeDisplayHash(),
        updatedAtMs = 1L,
        staleAtMs = 2L,
        expiresAtMs = 3L
    )
}
