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
        assertEquals(null, item.imdbRating)
        assertEquals(null, item.ratingSource)
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

    @Test
    fun `rail preview rejects title rating above ten`() {
        val preview = RailItemPreview(
            railId = "tmdb_trending_series",
            railSource = RailSource.BUILT_IN_TMDB,
            sourceProvider = ProviderId.TMDB,
            sourceItemId = "tmdb:94997",
            itemType = ContentType.SERIES,
            stableIds = ProviderIds(tmdb = "94997"),
            display = RailDisplaySeed(
                title = "House of the Dragon",
                rating = RatingSeed(provider = ProviderId.TMDB, value = 1767427.0)
            ),
            sourcePayloadQuality = SourcePayloadQuality.RICH_PREVIEW,
            sourcePayloadHash = "hash-hotd",
            generatedAtMs = 1_000L
        )

        val item = preview.toMetaPreview()

        assertEquals(null, item.imdbRating)
        assertEquals(null, item.ratingSource)
        assertEquals(1767427.0, preview.display.toPreviewRating().rejected?.rawValue ?: 0.0, 0.0)
        assertEquals("rating.value", preview.display.toPreviewRating().rejected?.rawField)
        assertEquals("OUT_OF_RANGE_TITLE_RATING", preview.display.toPreviewRating().rejected?.reason)
    }

    @Test
    fun `rail preview rejects popularity-like rating text`() {
        val preview = RailItemPreview(
            railId = "tmdb_trending_series",
            railSource = RailSource.BUILT_IN_TMDB,
            sourceProvider = ProviderId.TMDB,
            sourceItemId = "tmdb:94997",
            itemType = ContentType.SERIES,
            stableIds = ProviderIds(tmdb = "94997"),
            display = RailDisplaySeed(
                title = "House of the Dragon",
                ratingText = "1767427"
            ),
            sourcePayloadQuality = SourcePayloadQuality.RICH_PREVIEW,
            sourcePayloadHash = "hash-hotd",
            generatedAtMs = 1_000L
        )

        val item = preview.toMetaPreview()

        assertEquals(null, item.imdbRating)
        assertEquals(null, item.ratingSource)
        assertEquals("ratingText", preview.display.toPreviewRating().rejected?.rawField)
    }

    @Test
    fun `legacy rail preview rating text uses locale safe formatter`() {
        val legacy = MetaPreview(
            id = "tmdb:27205",
            type = ContentType.MOVIE,
            name = "Inception",
            poster = null,
            posterShape = PosterShape.POSTER,
            background = null,
            logo = null,
            description = null,
            releaseInfo = "2010",
            imdbRating = 8.3f,
            ratingSource = TitleRatingSource.TMDB,
            genres = emptyList()
        )

        val preview = legacy.toLegacyRailItemPreview(railId = "tmdb_trending_movies")

        assertEquals("8.3", preview.display.ratingText)
        assertEquals(8.3, preview.display.rating?.value ?: 0.0, 0.0)
        assertEquals(ProviderId.TMDB, preview.display.rating?.provider)
    }

    @Test
    fun `valid rating text without provider uses trusted fallback source provider`() {
        val preview = RailItemPreview(
            railId = "tmdb_trending_series",
            railSource = RailSource.BUILT_IN_TMDB,
            sourceProvider = ProviderId.TMDB,
            sourceItemId = "tmdb:94997",
            itemType = ContentType.SERIES,
            stableIds = ProviderIds(tmdb = "94997"),
            display = RailDisplaySeed(
                title = "House of the Dragon",
                ratingText = "8.3"
            ),
            sourcePayloadQuality = SourcePayloadQuality.RICH_PREVIEW,
            sourcePayloadHash = "hash-hotd",
            generatedAtMs = 1_000L
        )

        val item = preview.toMetaPreview()

        assertEquals(8.3f, item.imdbRating ?: 0f, 0f)
        assertEquals(TitleRatingSource.TMDB, item.ratingSource)
        assertEquals(ProviderId.TMDB, preview.display.toPreviewRating(fallbackProvider = ProviderId.TMDB).source)
    }

    @Test
    fun `valid rating text without trusted provider is rejected`() {
        val resolution = RailDisplaySeed(
            title = "Unknown source title",
            ratingText = "8.3"
        ).toPreviewRating(fallbackProvider = null)

        assertEquals(null, resolution.rating)
        assertEquals(null, resolution.source)
        assertEquals("MISSING_RATING_SOURCE", resolution.rejected?.reason)
        assertEquals("ratingText", resolution.rejected?.rawField)
    }

    @Test
    fun `valid rating text with untrusted rating provider is rejected`() {
        val preview = RailItemPreview(
            railId = "kitsu_trending_anime",
            railSource = RailSource.BUILT_IN_KITSU,
            sourceProvider = ProviderId.KITSU,
            sourceItemId = "kitsu:7442",
            itemType = ContentType.SERIES,
            stableIds = ProviderIds(kitsu = "7442"),
            display = RailDisplaySeed(
                title = "Fullmetal Alchemist",
                rating = RatingSeed(provider = ProviderId.KITSU, value = 8.7),
                ratingText = "8.7"
            ),
            sourcePayloadQuality = SourcePayloadQuality.RICH_PREVIEW,
            sourcePayloadHash = "hash-kitsu-fma",
            generatedAtMs = 1_000L
        )

        val resolution = preview.display.toPreviewRating(fallbackProvider = preview.sourceProvider)
        val item = preview.toMetaPreview()

        assertEquals(null, resolution.rating)
        assertEquals(null, resolution.source)
        assertEquals("MISSING_RATING_SOURCE", resolution.rejected?.reason)
        assertEquals(null, item.imdbRating)
        assertEquals(null, item.ratingSource)
    }
}
