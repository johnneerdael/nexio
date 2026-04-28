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

        assertEquals("trakt:show:1", meta.id)
        assertEquals(ContentType.SERIES, meta.type)
        assertEquals("Breaking Bad", meta.name)
        assertEquals("2008", meta.releaseInfo)
        assertNull(meta.poster)
    }

    @Test
    fun `rail preview omits unsupported rating provider from meta rating`() {
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

        assertNull(meta.imdbRating)
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

        assertEquals("TMDB 27205", meta.name)
        assertNull(meta.poster)
        assertNull(meta.background)
        assertNull(meta.logo)
        assertNull(meta.description)
        assertNull(meta.imdbRating)
        assertNull(meta.ratingSource)
        assertEquals(emptyList<String>(), meta.trailerYtIds)
    }
}
