package com.nexio.tv.ui.screens.home

import com.nexio.tv.core.artwork.ArtworkBundle
import com.nexio.tv.core.artwork.ArtworkDecisionKey
import com.nexio.tv.core.artwork.ArtworkDisplayHints
import com.nexio.tv.core.artwork.ArtworkDisplayRef
import com.nexio.tv.core.artwork.ArtworkProviderId
import com.nexio.tv.core.artwork.ArtworkSourceRole
import com.nexio.tv.core.artwork.ArtworkTrace
import com.nexio.tv.core.artwork.ArtworkType
import com.nexio.tv.core.integration.IntegrationProvider
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
import org.junit.Assert.assertNotNull
import org.junit.Test

class ModernHomeRowItemTest {
    @Test
    fun `from ResolvedDisplayItem extracts poster ref backdrop ref and rating`() {
        val poster = ArtworkDisplayRef.RuntimeAsset(
            decisionKey = ArtworkDecisionKey("artwork-decision:poster:imdb:tt0137523"),
            assetKey = null,
            imageType = ArtworkType.POSTER,
            selectedProvider = ArtworkProviderId.RuntimeProvider(IntegrationProvider.RPDB),
            sourceRole = ArtworkSourceRole.PREMIUM,
            trace = ArtworkTrace.empty(),
            displayHints = ArtworkDisplayHints()
        )
        val resolved = ResolvedDisplayItem(
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
                overview = "An office worker meets a strange soap salesman.",
                genres = listOf("Drama"),
                runtimeText = "139 min"
            ),
            artwork = ArtworkBundle(poster = poster),
            rating = TitleRating(8.8, TitleRatingSource.IMDB),
            trailer = TrailerDisplayState(),
            hydrationState = HydrationState.CANONICAL_READY,
            sourceTrace = emptyList(),
            updatedAtMs = 1_000L
        )

        val row = ModernHomeRowItem.from(resolved)

        assertEquals("movie:tmdb:550", row.itemKey)
        assertEquals("Fight Club", row.title)
        assertEquals(1999, row.year)
        assertNotNull(row.posterRef)
        assertEquals(true, row.posterRef is ArtworkDisplayRef.RuntimeAsset)
        assertEquals(8.8, row.rating!!.value, 0.001)
    }

    @Test
    fun `from ResolvedDisplayItem with null artwork yields null refs`() {
        val resolved = ResolvedDisplayItem(
            itemKey = "k",
            contentId = "c",
            parentId = "c",
            itemType = ContentType.MOVIE,
            mediaKind = MetadataMediaKind.MOVIE,
            canonicalProvider = null,
            canonicalId = null,
            imdbId = null,
            stableIds = ProviderIds(),
            display = ResolvedDisplayFields(null, null, null, null, null, emptyList(), null),
            artwork = ArtworkBundle(),
            rating = null,
            trailer = TrailerDisplayState(),
            hydrationState = HydrationState.PREVIEW_ONLY,
            sourceTrace = emptyList(),
            updatedAtMs = 0L
        )

        val row = ModernHomeRowItem.from(resolved)

        assertEquals(null, row.posterRef)
        assertEquals(null, row.backdropRef)
        assertEquals(null, row.logoRef)
        assertEquals(null, row.rating)
    }
}
