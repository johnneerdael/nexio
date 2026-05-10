package com.nexio.tv.ui.screens.detail

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
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test

class MetaDetailsResolvedFieldsTest {

    @Test
    fun `from maps every display field, artwork, and rating from resolved`() {
        val poster = artworkRef("poster:1", ArtworkType.POSTER)
        val backdrop = artworkRef("backdrop:1", ArtworkType.BACKDROP)
        val logo = artworkRef("logo:1", ArtworkType.LOGO)
        val rating = TitleRating(8.5, TitleRatingSource.IMDB)
        val resolved = resolvedItem(
            artwork = ArtworkBundle(poster = poster, backdrop = backdrop, logo = logo),
            rating = rating
        )

        val item = MetaDetailsResolvedFields.from(resolved)

        assertEquals("Fight Club", item.title)
        assertEquals("An office worker meets a strange soap salesman.", item.overview)
        assertEquals(listOf("Drama"), item.genres)
        assertEquals(1999, item.year)
        assertEquals("139 min", item.runtimeText)
        assertSame(poster, item.posterRef)
        assertSame(backdrop, item.backdropRef)
        assertSame(logo, item.logoRef)
        assertSame(rating, item.rating)
    }

    @Test
    fun `from with null artwork and rating from resolved propagates as null`() {
        val item = MetaDetailsResolvedFields.from(
            resolvedItem(artwork = ArtworkBundle(), rating = null)
        )

        assertNull(item.posterRef)
        assertNull(item.backdropRef)
        assertNull(item.logoRef)
        assertNull(item.rating)
    }

    @Test
    fun `content-equal inputs produce equal projections via data-class equality`() {
        val poster = artworkRef("poster:1", ArtworkType.POSTER)
        val backdrop = artworkRef("backdrop:1", ArtworkType.BACKDROP)
        val logo = artworkRef("logo:1", ArtworkType.LOGO)
        val rating = TitleRating(8.5, TitleRatingSource.IMDB)
        val artwork = ArtworkBundle(poster = poster, backdrop = backdrop, logo = logo)

        val resolvedA = resolvedItem(artwork = artwork, rating = rating)
        val resolvedB = resolvedItem(artwork = artwork, rating = rating)

        val a = MetaDetailsResolvedFields.from(resolvedA)
        val b = MetaDetailsResolvedFields.from(resolvedB)

        assertNotSame(a, b)
        assertEquals(a, b)
        assertEquals(a.hashCode(), b.hashCode())
    }

    private fun artworkRef(decisionKey: String, type: ArtworkType): ArtworkDisplayRef =
        ArtworkDisplayRef.RuntimeAsset(
            decisionKey = ArtworkDecisionKey(decisionKey),
            assetKey = null,
            imageType = type,
            selectedProvider = ArtworkProviderId.RuntimeProvider(IntegrationProvider.TVDB),
            sourceRole = ArtworkSourceRole.PRIMARY,
            trace = ArtworkTrace.empty(),
            displayHints = ArtworkDisplayHints()
        )

    private fun resolvedItem(
        artwork: ArtworkBundle,
        rating: TitleRating?
    ): ResolvedDisplayItem = ResolvedDisplayItem(
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
        artwork = artwork,
        rating = rating,
        trailer = TrailerDisplayState(),
        hydrationState = HydrationState.CANONICAL_READY,
        sourceTrace = emptyList(),
        updatedAtMs = 1_000L
    )
}
