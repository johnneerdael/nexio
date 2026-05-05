package com.nexio.tv.core.anime.projection

import com.nexio.tv.domain.model.ProviderIds
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AnimeWorkIdentityTest {

    @Test
    fun `groupKey prefers tvdb when present and series`() {
        val key = AnimeWorkGroupKey.preferred(
            tvdbId = "305074",
            imdbId = "tt5626028",
            tmdbId = "65930",
            sourceKitsuId = "13881"
        )
        assertEquals("anime-work:tvdb:305074", key.value)
    }

    @Test
    fun `groupKey falls through to imdb then kitsu`() {
        val imdbKey = AnimeWorkGroupKey.preferred(tvdbId = null, imdbId = "tt5626028", tmdbId = null, sourceKitsuId = "13881")
        val kitsuOnly = AnimeWorkGroupKey.preferred(tvdbId = null, imdbId = null, tmdbId = null, sourceKitsuId = "12")
        assertEquals("anime-work:imdb:tt5626028", imdbKey.value)
        assertEquals("anime-work:kitsu:12", kitsuOnly.value)
    }

    @Test
    fun `identity carries confidence and member set`() {
        val identity = AnimeWorkIdentity(
            groupKey = AnimeWorkGroupKey("anime-work:tvdb:305074"),
            primaryKitsuId = "11469",
            memberKitsuIds = setOf("11469", "12268", "13881"),
            providerIds = ProviderIds(tvdb = "305074", imdb = "tt5626028", tmdb = "65930"),
            confidence = AnimeGroupingConfidence.HIGH,
            evidence = listOf("kitsu.tvdb=305074", "kitsu.imdb=tt5626028"),
        )
        assertTrue("13881" in identity.memberKitsuIds)
        assertEquals(AnimeGroupingConfidence.HIGH, identity.confidence)
    }
}
