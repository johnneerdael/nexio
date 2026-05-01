package com.nexio.tv.domain.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class RailItemPreviewTest {
    @Test
    fun `rail preview converts to immediate home card meta preview`() {
        val preview = RailItemPreview(
            railId = "trakt_trending_shows",
            railSource = RailSource.BUILT_IN_TRAKT,
            sourceProvider = ProviderId.TRAKT,
            sourceItemId = "trakt:show:1",
            itemType = ContentType.SERIES,
            stableIds = ProviderIds(
                trakt = "1",
                imdb = "tt0903747",
                tmdb = "1396",
                tvdb = "81189",
                slug = "breaking-bad"
            ),
            display = RailDisplaySeed(
                title = "Breaking Bad",
                year = 2008
            ),
            ranking = RailRankingMetadata(watchers = 541),
            sourcePayloadQuality = SourcePayloadQuality.SPARSE_IDENTITY,
            sourcePayloadHash = "hash-trakt-breaking-bad",
            generatedAtMs = 1_000L
        )

        val meta = preview.toMetaPreview()

        assertEquals("tvdb:81189", meta.id)
        assertEquals(ContentType.SERIES, meta.type)
        assertEquals("Breaking Bad", meta.name)
        assertEquals("2008", meta.releaseInfo)
        assertEquals(FirstPaintSource.RAIL_PREVIEW, meta.firstPaintSource)
        assertEquals(ProviderId.TRAKT, meta.firstPaintSourceProvider)
        assertEquals(preview.stableIds, meta.firstPaintStableIds)
        assertEquals(RailSource.BUILT_IN_TRAKT, meta.firstPaintRailSource)
        assertEquals("trakt:show:1", meta.firstPaintSourceItemId)
        assertNull(meta.poster)
    }

    @Test
    fun `rail preview conversion preserves provenance and stable ids`() {
        val stableIds = ProviderIds(
            trakt = "1",
            imdb = "tt0903747",
            tmdb = "1396",
            tvdb = "81189",
            slug = "breaking-bad"
        )
        val preview = RailItemPreview(
            railId = "trakt_trending_shows",
            railSource = RailSource.BUILT_IN_TRAKT,
            sourceProvider = ProviderId.TRAKT,
            sourceItemId = "trakt:show:1",
            itemType = ContentType.SERIES,
            stableIds = stableIds,
            display = RailDisplaySeed(title = "Breaking Bad", year = 2008),
            sourcePayloadQuality = SourcePayloadQuality.SPARSE_IDENTITY,
            sourcePayloadHash = "hash-trakt-breaking-bad",
            generatedAtMs = 1_000L
        )

        val meta = preview.toMetaPreview()

        assertEquals(FirstPaintSource.RAIL_PREVIEW, meta.firstPaintSource)
        assertEquals(ProviderId.TRAKT, meta.firstPaintSourceProvider)
        assertEquals(stableIds, meta.firstPaintStableIds)
        assertEquals("81189", meta.firstPaintStableIds.tvdb)
        assertEquals("1396", meta.firstPaintStableIds.tmdb)
        assertEquals(RailSource.BUILT_IN_TRAKT, meta.firstPaintRailSource)
        assertEquals("trakt:show:1", meta.firstPaintSourceItemId)
    }

    @Test
    fun `simkl movie preview uses tmdb routeable meta id instead of provider source id`() {
        val preview = RailItemPreview(
            railId = "simkl_trending_movies",
            railSource = RailSource.BUILT_IN_SIMKL_DISCOVERY,
            sourceProvider = ProviderId.SIMKL,
            sourceItemId = "simkl:movie:88",
            itemType = ContentType.MOVIE,
            stableIds = ProviderIds(
                simkl = "88",
                imdb = "tt1375666",
                tmdb = "27205"
            ),
            display = RailDisplaySeed(title = "Inception", year = 2010),
            sourcePayloadQuality = SourcePayloadQuality.RICH_PREVIEW,
            sourcePayloadHash = "hash-simkl-inception",
            generatedAtMs = 1_000L
        )

        val meta = preview.toMetaPreview()

        assertEquals("tmdb:27205", meta.id)
        assertEquals("Inception", meta.name)
        assertEquals("2010", meta.releaseInfo)
    }

    @Test
    fun `movie preview falls back to source item id when tmdb is unavailable`() {
        val preview = RailItemPreview(
            railId = "trakt_recommended_movies",
            railSource = RailSource.BUILT_IN_TRAKT,
            sourceProvider = ProviderId.TRAKT,
            sourceItemId = "trakt:movie:7",
            itemType = ContentType.MOVIE,
            stableIds = ProviderIds(
                trakt = "7",
                imdb = "tt0137523"
            ),
            display = RailDisplaySeed(title = "Fight Club", year = 1999),
            sourcePayloadQuality = SourcePayloadQuality.SPARSE_IDENTITY,
            sourcePayloadHash = "hash-trakt-fight-club",
            generatedAtMs = 1_000L
        )

        val meta = preview.toMetaPreview()

        assertEquals("trakt:movie:7", meta.id)
        assertEquals("Fight Club", meta.name)
    }

    @Test
    fun `non kitsu series preview prefers tvdb over kitsu meta id`() {
        val preview = RailItemPreview(
            railId = "trakt_trending_anime",
            railSource = RailSource.BUILT_IN_TRAKT,
            sourceProvider = ProviderId.TRAKT,
            sourceItemId = "trakt:show:99",
            itemType = ContentType.SERIES,
            stableIds = ProviderIds(
                kitsu = "7442",
                tvdb = "79481"
            ),
            display = RailDisplaySeed(title = "Fullmetal Alchemist", year = 2003),
            sourcePayloadQuality = SourcePayloadQuality.RICH_PREVIEW,
            sourcePayloadHash = "hash-trakt-fma",
            generatedAtMs = 1_000L
        )

        val meta = preview.toMetaPreview()

        assertEquals("tvdb:79481", meta.id)
        assertEquals("Fullmetal Alchemist", meta.name)
    }

    @Test
    fun `mdblist series preview uses tvdb routeable meta id instead of provider source id`() {
        val preview = RailItemPreview(
            railId = "mdblist_top_shows",
            railSource = RailSource.BUILT_IN_MDBLIST,
            sourceProvider = ProviderId.MDBLIST,
            sourceItemId = "mdblist:list:top-shows:item:1",
            itemType = ContentType.SERIES,
            stableIds = ProviderIds(
                tmdb = "1396",
                tvdb = "81189"
            ),
            display = RailDisplaySeed(title = "Breaking Bad", year = 2008),
            sourcePayloadQuality = SourcePayloadQuality.RICH_PREVIEW,
            sourcePayloadHash = "hash-mdblist-breaking-bad",
            generatedAtMs = 1_000L
        )

        val meta = preview.toMetaPreview()

        assertEquals("tvdb:81189", meta.id)
        assertEquals("Breaking Bad", meta.name)
    }

    @Test
    fun `series preview falls back to source item id when no routeable stable id exists`() {
        val preview = RailItemPreview(
            railId = "mdblist_top_shows",
            railSource = RailSource.BUILT_IN_MDBLIST,
            sourceProvider = ProviderId.MDBLIST,
            sourceItemId = "mdblist:list:top-shows:item:2",
            itemType = ContentType.SERIES,
            stableIds = ProviderIds(
                trakt = "2",
                simkl = "22",
                tmdb = "999"
            ),
            display = RailDisplaySeed(title = "Provider Only", year = 2024),
            sourcePayloadQuality = SourcePayloadQuality.SPARSE_IDENTITY,
            sourcePayloadHash = "hash-provider-only",
            generatedAtMs = 1_000L
        )

        val meta = preview.toMetaPreview()

        assertEquals("mdblist:list:top-shows:item:2", meta.id)
        assertEquals("Provider Only", meta.name)
    }

    @Test
    fun `kitsu preview prefers kitsu routeable meta id`() {
        val preview = RailItemPreview(
            railId = "kitsu_trending_anime",
            railSource = RailSource.BUILT_IN_KITSU,
            sourceProvider = ProviderId.KITSU,
            sourceItemId = "kitsu:7442",
            itemType = ContentType.SERIES,
            stableIds = ProviderIds(
                kitsu = "7442",
                tvdb = "79481"
            ),
            display = RailDisplaySeed(title = "Fullmetal Alchemist", year = 2003),
            sourcePayloadQuality = SourcePayloadQuality.RICH_PREVIEW,
            sourcePayloadHash = "hash-kitsu-fma",
            generatedAtMs = 1_000L
        )

        val meta = preview.toMetaPreview()

        assertEquals("kitsu:7442", meta.id)
        assertEquals("Fullmetal Alchemist", meta.name)
    }

    @Test
    fun `best supported routing id prefers kitsu for kitsu rail items`() {
        val preview = railItemPreview(
            railSource = RailSource.BUILT_IN_KITSU,
            sourceProvider = ProviderId.KITSU,
            itemType = ContentType.SERIES,
            stableIds = ProviderIds(kitsu = "42", tvdb = "11"),
            sourceItemId = "kitsu:anime:42"
        )

        assertEquals("kitsu:42", preview.bestSupportedRoutingId())
    }

    @Test
    fun `best supported routing id prefers tvdb over kitsu for non kitsu series items`() {
        val preview = railItemPreview(
            railSource = RailSource.BUILT_IN_TRAKT,
            sourceProvider = ProviderId.TRAKT,
            itemType = ContentType.SERIES,
            stableIds = ProviderIds(kitsu = "42", tvdb = "11"),
            sourceItemId = "trakt:show:1"
        )

        assertEquals("tvdb:11", preview.bestSupportedRoutingId())
    }

    @Test
    fun `best supported routing id uses movie tmdb id`() {
        val preview = railItemPreview(
            itemType = ContentType.MOVIE,
            stableIds = ProviderIds(tmdb = "99", tvdb = "11", imdb = "tt99"),
            sourceItemId = "source:movie:1"
        )

        assertEquals("tmdb:99", preview.bestSupportedRoutingId())
    }

    @Test
    fun `best supported routing id falls back to source item id when stable ids are empty`() {
        val preview = railItemPreview(
            itemType = ContentType.MOVIE,
            stableIds = ProviderIds(),
            sourceItemId = "source:movie:empty-stable-ids"
        )

        assertEquals("source:movie:empty-stable-ids", preview.bestSupportedRoutingId())
    }

    @Test
    fun `best supported routing id does not use imdb for movie without tmdb`() {
        val preview = railItemPreview(
            itemType = ContentType.MOVIE,
            stableIds = ProviderIds(imdb = "tt99"),
            sourceItemId = "source:movie:imdb-only"
        )

        assertEquals("source:movie:imdb-only", preview.bestSupportedRoutingId())
    }

    @Test
    fun `best supported routing id uses series tvdb id`() {
        val preview = railItemPreview(
            itemType = ContentType.SERIES,
            stableIds = ProviderIds(tmdb = "99", tvdb = "11", imdb = "tt99"),
            sourceItemId = "source:series:1"
        )

        assertEquals("tvdb:11", preview.bestSupportedRoutingId())
    }

    @Test
    fun `best supported routing id falls back to source item id when only imdb is available`() {
        val preview = railItemPreview(
            itemType = ContentType.SERIES,
            stableIds = ProviderIds(imdb = "tt99"),
            sourceItemId = "source:series:1"
        )

        assertEquals("source:series:1", preview.bestSupportedRoutingId())
    }

    @Test
    fun `rail preview preserves unsupported rating provider value without legacy badge source`() {
        val preview = RailItemPreview(
            railId = "mdblist_top_shows",
            railSource = RailSource.BUILT_IN_MDBLIST,
            sourceProvider = ProviderId.MDBLIST,
            sourceItemId = "mdblist:show:1",
            itemType = ContentType.SERIES,
            stableIds = ProviderIds(tmdb = "1396"),
            display = RailDisplaySeed(
                title = "Breaking Bad",
                rating = RatingSeed(provider = ProviderId.MDBLIST, value = 8.9)
            ),
            sourcePayloadQuality = SourcePayloadQuality.RICH_PREVIEW,
            sourcePayloadHash = "hash-mdblist-breaking-bad",
            generatedAtMs = 1_000L
        )

        val meta = preview.toMetaPreview()

        assertEquals("mdblist:show:1", meta.id)
        assertEquals(8.9f, meta.imdbRating)
        assertNull(meta.ratingSource)
    }

    @Test
    fun `sparse rail preview converts with usable fallback name`() {
        val preview = RailItemPreview(
            railId = "sparse_rail",
            railSource = RailSource.ADDON_CATALOG,
            sourceProvider = ProviderId.ADDON,
            sourceItemId = "addon:item:missing-title",
            itemType = ContentType.MOVIE,
            stableIds = ProviderIds(tmdb = "27205"),
            display = RailDisplaySeed(
                title = null,
                posterUrl = null,
                backdropUrl = null,
                logoUrl = null,
                overview = null,
                rating = null,
                trailerHint = null
            ),
            sourcePayloadQuality = SourcePayloadQuality.ID_ONLY,
            sourcePayloadHash = "hash-sparse-item",
            generatedAtMs = 1_000L
        )

        val meta = preview.toMetaPreview()

        assertEquals("tmdb:27205", meta.id)
        assertEquals("TMDB 27205", meta.name)
        assertNull(meta.poster)
        assertNull(meta.background)
        assertNull(meta.logo)
        assertNull(meta.description)
        assertNull(meta.imdbRating)
        assertNull(meta.ratingSource)
        assertEquals(emptyList<String>(), meta.trailerYtIds)
    }

    private fun railItemPreview(
        railSource: RailSource = RailSource.ADDON_CATALOG,
        sourceProvider: ProviderId = ProviderId.ADDON,
        itemType: ContentType,
        stableIds: ProviderIds,
        sourceItemId: String
    ): RailItemPreview {
        return RailItemPreview(
            railId = "rail",
            railSource = railSource,
            sourceProvider = sourceProvider,
            sourceItemId = sourceItemId,
            itemType = itemType,
            stableIds = stableIds,
            display = RailDisplaySeed(title = "Title"),
            sourcePayloadQuality = SourcePayloadQuality.SPARSE_IDENTITY,
            sourcePayloadHash = "hash",
            generatedAtMs = 1_000L
        )
    }
}
