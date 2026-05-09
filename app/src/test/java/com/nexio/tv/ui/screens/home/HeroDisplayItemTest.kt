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
import com.nexio.tv.core.metadata.router.resolver.TrailerPlaybackRef
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
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test

class HeroDisplayItemTest {
    @Test
    fun `from ResolvedDisplayItem prefers backdrop for backgroundRef`() {
        val backdrop = artworkRef("backdrop:1", ArtworkType.BACKDROP)
        val poster = artworkRef("poster:1", ArtworkType.POSTER)
        val logo = artworkRef("logo:1", ArtworkType.LOGO)
        val resolved = resolvedItem(
            artwork = ArtworkBundle(poster = poster, backdrop = backdrop, logo = logo),
            rating = TitleRating(8.5, TitleRatingSource.IMDB),
            trailer = TrailerDisplayState(
                fallbackTrailerYtIds = listOf("yt-1"),
                selectedPlaybackRef = TrailerPlaybackRef.YouTubeId("yt-1")
            )
        )

        val hero = HeroDisplayItem.from(resolved)

        assertSame(backdrop, hero.backgroundRef)
        assertSame(logo, hero.logoRef)
        assertEquals("Fight Club", hero.title)
        assertEquals(1999, hero.year)
        assertEquals(8.5, hero.rating!!.value, 0.001)
        assertNotNull(hero.trailer.selectedPlaybackRef)
    }

    @Test
    fun `from ResolvedDisplayItem falls back to poster when backdrop is null`() {
        val poster = artworkRef("poster:2", ArtworkType.POSTER)
        val resolved = resolvedItem(
            artwork = ArtworkBundle(poster = poster, backdrop = null, logo = null),
            rating = null,
            trailer = TrailerDisplayState()
        )

        val hero = HeroDisplayItem.from(resolved)

        assertSame(poster, hero.backgroundRef)
        assertNull(hero.logoRef)
        assertNull(hero.rating)
    }

    @Test
    fun `from ResolvedDisplayItem with empty artwork yields null background and logo`() {
        val resolved = resolvedItem(
            artwork = ArtworkBundle(),
            rating = null,
            trailer = TrailerDisplayState()
        )

        val hero = HeroDisplayItem.from(resolved)

        assertNull(hero.backgroundRef)
        assertNull(hero.logoRef)
        assertNull(hero.rating)
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
        rating: TitleRating?,
        trailer: TrailerDisplayState
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
        trailer = trailer,
        hydrationState = HydrationState.CANONICAL_READY,
        sourceTrace = emptyList(),
        updatedAtMs = 1_000L
    )
}
