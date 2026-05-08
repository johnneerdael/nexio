package com.nexio.tv.ui.screens.home

import com.nexio.tv.domain.model.ProviderId
import com.nexio.tv.domain.model.ProviderIds
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HomeArtworkOverlayKeysTest {

    @Test
    fun `tv overlay aliases include row key canonical tvdb key and series tv variants`() {
        val aliases = HomeArtworkOverlayKeys.aliasesFor(
            rowItemKey = "series:tmdb:76479",
            contentId = "tmdb:tv:76479",
            itemType = "series",
            providerIds = ProviderIds(tmdb = "76479", tvdb = "355567", imdb = "tt1190634"),
            canonicalProvider = ProviderId.TVDB,
            canonicalId = "355567"
        )

        assertTrue("series:tmdb:76479" in aliases)
        assertTrue("tv:tmdb:76479" in aliases)
        assertTrue("series:tvdb:355567" in aliases)
        assertTrue("tv:tvdb:355567" in aliases)
        assertTrue("series:imdb:tt1190634" in aliases)
        assertTrue("tv:imdb:tt1190634" in aliases)
    }

    @Test
    fun `canonical tvdb overlay is readable by trakt tv row aliases`() {
        val aliases = HomeArtworkOverlayKeys.aliasesFor(
            rowItemKey = "series:trakt:171028",
            contentId = "trakt:171028",
            itemType = "series",
            providerIds = ProviderIds(trakt = "171028", tvdb = "355567", imdb = "tt1190634"),
            canonicalProvider = ProviderId.TVDB,
            canonicalId = "355567"
        )

        assertTrue("series:trakt:171028" in aliases)
        assertTrue("series:tvdb:355567" in aliases)
        assertTrue("series:imdb:tt1190634" in aliases)
    }

    @Test
    fun `movie overlay aliases do not include series or tv variants`() {
        val aliases = HomeArtworkOverlayKeys.aliasesFor(
            rowItemKey = "movie:tmdb:550",
            contentId = "tmdb:movie:550",
            itemType = "movie",
            providerIds = ProviderIds(tmdb = "550", imdb = "tt0137523"),
            canonicalProvider = ProviderId.TMDB,
            canonicalId = "550"
        )

        assertTrue("movie:tmdb:550" in aliases)
        assertTrue("movie:imdb:tt0137523" in aliases)
        assertFalse(aliases.any { it.startsWith("series:") || it.startsWith("tv:") })
    }
}
