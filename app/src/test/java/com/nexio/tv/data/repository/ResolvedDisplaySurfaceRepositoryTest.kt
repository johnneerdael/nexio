package com.nexio.tv.data.repository

import com.nexio.tv.core.artwork.ArtworkBundle
import com.nexio.tv.core.metadata.router.MetadataMediaKind
import com.nexio.tv.domain.model.ContentType
import com.nexio.tv.domain.model.HydrationState
import com.nexio.tv.domain.model.ProviderIds
import com.nexio.tv.domain.model.ResolvedDisplayFields
import com.nexio.tv.domain.model.ResolvedDisplayItem
import com.nexio.tv.domain.model.TitleRating
import com.nexio.tv.domain.model.TitleRatingSource
import com.nexio.tv.domain.model.TrailerDisplayState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ResolvedDisplaySurfaceRepositoryTest {
    @Test
    fun `resolved display item carries canonical display fields artwork rating stable ids and trailer state`() {
        val item = ResolvedDisplayItem(
            itemKey = "movie:tmdb:550",
            contentId = "tmdb:550",
            parentId = "tmdb:550",
            itemType = ContentType.MOVIE,
            mediaKind = MetadataMediaKind.MOVIE,
            canonicalProvider = "TMDB",
            canonicalId = "550",
            imdbId = "tt0137523",
            stableIds = ProviderIds(tmdb = "550", imdb = "tt0137523"),
            display = ResolvedDisplayFields(
                title = "Fight Club",
                originalTitle = null,
                year = 1999,
                releaseDate = "1999",
                overview = "An insomniac office worker...",
                genres = listOf("Drama"),
                runtimeText = "139m"
            ),
            artwork = ArtworkBundle(),
            rating = TitleRating(value = 8.8, source = TitleRatingSource.IMDB),
            trailer = TrailerDisplayState(fallbackTrailerYtIds = emptyList()),
            hydrationState = HydrationState.CANONICAL_READY,
            sourceTrace = emptyList(),
            updatedAtMs = 123L
        )

        assertEquals("movie:tmdb:550", item.itemKey)
        assertEquals("Fight Club", item.display.title)
        assertEquals("tt0137523", item.stableIds.imdb)
        assertEquals(8.8, item.rating?.value ?: 0.0, 0.0)
        assertTrue(item.trailer.fallbackTrailerYtIds.isEmpty())
    }
}
