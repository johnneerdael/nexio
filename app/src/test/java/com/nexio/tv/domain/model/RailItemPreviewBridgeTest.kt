package com.nexio.tv.domain.model

import org.junit.Assert.assertEquals
import org.junit.Test

class RailItemPreviewBridgeTest {
    @Test
    fun `row record converts to catalog row and preserves first paint display context`() {
        val preview = RailItemPreview(
            railId = "kitsu_trending_anime",
            railSource = RailSource.BUILT_IN_KITSU,
            sourceProvider = ProviderId.KITSU,
            sourceItemId = "kitsu:7442",
            itemType = ContentType.SERIES,
            stableIds = ProviderIds(kitsu = "7442", tvdb = "79481"),
            display = RailDisplaySeed(
                title = "Fullmetal Alchemist",
                releaseDate = "2003-10-04",
                posterUrl = "https://cdn.example/poster.jpg",
                posterShape = PosterShape.LANDSCAPE,
                backdropUrl = "https://cdn.example/backdrop.jpg",
                rating = RatingSeed(provider = ProviderId.KITSU, value = 8.7),
                ratingText = "8.9"
            ),
            sourcePayloadQuality = SourcePayloadQuality.RICH_PREVIEW,
            sourcePayloadHash = "hash-kitsu-fma",
            generatedAtMs = 1_000L
        )
        val record = RailPreviewCatalogRowRecord(
            addonId = "kitsu",
            addonName = "Kitsu",
            addonBaseUrl = "https://kitsu.io/api/edge",
            catalogId = "kitsu_trending_anime",
            catalogName = "Trending Anime",
            type = ContentType.SERIES,
            previews = listOf(preview)
        )

        val row = record.toCatalogRow()
        val item = row.items.single()

        assertEquals("kitsu", row.addonId)
        assertEquals("kitsu_trending_anime", row.catalogId)
        assertEquals(FirstPaintSource.RAIL_PREVIEW, item.firstPaintSource)
        assertEquals(ProviderId.KITSU, item.firstPaintSourceProvider)
        assertEquals(RailSource.BUILT_IN_KITSU, item.firstPaintRailSource)
        assertEquals("kitsu:7442", item.firstPaintSourceItemId)
        assertEquals(ProviderIds(kitsu = "7442", tvdb = "79481"), item.firstPaintStableIds)
        assertEquals(PosterShape.LANDSCAPE, item.posterShape)
        assertEquals("2003", item.releaseInfo)
        assertEquals(8.9f, item.imdbRating)
        // MetaPreview currently has no typed source payload hash field. The source record
        // remains authoritative for payload provenance until a typed boundary field exists.
        assertEquals("hash-kitsu-fma", record.previews.single().sourcePayloadHash)
    }

    @Test
    fun `legacy meta preview conversion keeps rail provenance and preview hydration state`() {
        val legacy = MetaPreview(
            id = "tmdb:27205",
            type = ContentType.MOVIE,
            name = "Inception",
            poster = "https://cdn.example/inception.jpg",
            posterShape = PosterShape.POSTER,
            background = null,
            logo = null,
            description = "A thief steals corporate secrets through dream-sharing technology.",
            releaseInfo = "2010",
            runtime = "148 min",
            imdbRating = 8.8f,
            ratingSource = TitleRatingSource.IMDB,
            genres = listOf("Science Fiction"),
            firstPaintSource = FirstPaintSource.RAIL_PREVIEW,
            firstPaintSourceProvider = ProviderId.TMDB,
            firstPaintStableIds = ProviderIds(imdb = "tt1375666"),
            firstPaintRailSource = RailSource.BUILT_IN_TMDB,
            firstPaintSourceItemId = "tmdb:27205"
        )

        val preview = legacy.toLegacyRailItemPreview(
            railId = "tmdb_trending_movies",
            sourcePayloadHash = "legacy-hash",
            generatedAtMs = 2_000L
        )

        assertEquals("tmdb_trending_movies", preview.railId)
        assertEquals(RailSource.BUILT_IN_TMDB, preview.railSource)
        assertEquals(ProviderId.TMDB, preview.sourceProvider)
        assertEquals("tmdb:27205", preview.sourceItemId)
        assertEquals("tt1375666", preview.stableIds.imdb)
        assertEquals("27205", preview.stableIds.tmdb)
        assertEquals("Inception", preview.display.title)
        assertEquals(PosterShape.POSTER, preview.display.posterShape)
        assertEquals("8.8", preview.display.ratingText)
        assertEquals("legacy-hash", preview.sourcePayloadHash)
        assertEquals(RailHydrationState.PREVIEW_ONLY, preview.hydrationState)
    }

    @Test
    fun `stable item key scopes raw fallback deterministically`() {
        val rawKey = ProviderIds().bestStableItemKey(
            itemType = ContentType.SERIES,
            sourceItemId = "42",
            rawNamespace = "BUILT_IN_KITSU:KITSU:kitsu_trending_anime"
        )

        assertEquals("series:raw:BUILT_IN_KITSU:KITSU:kitsu_trending_anime:42", rawKey)
    }

    @Test
    fun `legacy conversion only derives strict prefixed and imdb ids`() {
        val legacy = MetaPreview(
            id = "compound:tmdb:27205",
            type = ContentType.MOVIE,
            name = "Inception",
            poster = null,
            posterShape = PosterShape.POSTER,
            background = null,
            logo = null,
            description = null,
            releaseInfo = "2010",
            imdbRating = null,
            genres = emptyList(),
            firstPaintSource = FirstPaintSource.RAIL_PREVIEW,
            firstPaintSourceItemId = "tt-not-digits"
        )

        val preview = legacy.toLegacyRailItemPreview(railId = "legacy_movies")

        assertEquals(null, preview.stableIds.tmdb)
        assertEquals(null, preview.stableIds.imdb)
        assertEquals("legacy:legacy_movies:compound:tmdb:27205", preview.sourcePayloadHash)
    }
}
