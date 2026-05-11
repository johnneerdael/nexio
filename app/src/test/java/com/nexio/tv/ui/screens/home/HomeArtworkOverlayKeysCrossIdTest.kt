package com.nexio.tv.ui.screens.home

import com.nexio.tv.core.metadata.router.AnimeIdScheme
import com.nexio.tv.core.metadata.router.IdMapping
import com.nexio.tv.core.metadata.router.IdMappingSource
import com.nexio.tv.core.metadata.router.InMemoryIdMappingStore
import com.nexio.tv.core.metadata.router.MetadataPrimaryProvider
import com.nexio.tv.core.metadata.router.ParsedMetadataId
import com.nexio.tv.domain.model.ProviderIds
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertTrue
import org.junit.Test

class HomeArtworkOverlayKeysCrossIdTest {

    @Test
    fun `tmdb-only row with cached imdb gets imdb-form alias`() = runTest {
        val store = InMemoryIdMappingStore(
            initialMappings = listOf(
                IdMapping(
                    sourceId = ParsedMetadataId(AnimeIdScheme.TMDB, "movie:202555", "tmdb:movie:202555"),
                    provider = MetadataPrimaryProvider.IMDB,
                    providerId = "tt12345",
                    source = IdMappingSource.PROVIDER_LOOKUP,
                    evidence = "tmdbMovieToImdb"
                )
            )
        )

        val aliases = HomeArtworkOverlayKeys.aliasesForEnriched(
            rowItemKey = "movie:tmdb:202555",
            contentId = "tmdb:202555",
            itemType = "movie",
            providerIds = ProviderIds(tmdb = "202555"),
            canonicalProvider = null,
            canonicalId = null,
            idMappingStore = store
        )

        assertTrue(
            "Expected imdb-form alias in $aliases",
            aliases.contains("movie:imdb:tt12345") || aliases.contains("movie:tt12345")
        )
    }

    @Test
    fun `tmdb-only row without cached imdb yields only tmdb aliases`() = runTest {
        val emptyStore = InMemoryIdMappingStore()

        val aliases = HomeArtworkOverlayKeys.aliasesForEnriched(
            rowItemKey = "movie:tmdb:202555",
            contentId = "tmdb:202555",
            itemType = "movie",
            providerIds = ProviderIds(tmdb = "202555"),
            canonicalProvider = null,
            canonicalId = null,
            idMappingStore = emptyStore
        )

        assertTrue("Expected tmdb alias in $aliases", aliases.contains("movie:tmdb:202555"))
        assertTrue(
            "Expected no imdb-form alias (cache miss)",
            aliases.none { it.contains(":imdb:") || it.matches(Regex(".*:tt\\d+$")) }
        )
    }
}
