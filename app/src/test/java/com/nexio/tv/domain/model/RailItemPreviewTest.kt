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
}
