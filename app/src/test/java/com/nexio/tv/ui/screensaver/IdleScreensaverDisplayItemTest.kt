package com.nexio.tv.ui.screensaver

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
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class IdleScreensaverDisplayItemTest {
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

        val screensaver = IdleScreensaverDisplayItem.from(resolved)

        assertSame(backdrop, screensaver.backgroundRef)
        assertSame(logo, screensaver.logoRef)
        assertEquals("Fight Club", screensaver.title)
        assertEquals(1999, screensaver.year)
        assertEquals(8.5, screensaver.rating!!.value, 0.001)
        assertNotNull(screensaver.trailer.selectedPlaybackRef)
    }

    @Test
    fun `from ResolvedDisplayItem falls back to poster when backdrop is null`() {
        val poster = artworkRef("poster:2", ArtworkType.POSTER)
        val resolved = resolvedItem(
            artwork = ArtworkBundle(poster = poster, backdrop = null, logo = null),
            rating = null,
            trailer = TrailerDisplayState()
        )

        val screensaver = IdleScreensaverDisplayItem.from(resolved)

        assertSame(poster, screensaver.backgroundRef)
        assertNull(screensaver.logoRef)
        assertNull(screensaver.rating)
    }

    @Test
    fun `isScreensaverDisplayable true when title and at least one of backdrop or poster present`() {
        val backdrop = artworkRef("backdrop:1", ArtworkType.BACKDROP)
        val poster = artworkRef("poster:1", ArtworkType.POSTER)
        // backdrop + title
        assertTrue(
            resolvedItem(
                artwork = ArtworkBundle(backdrop = backdrop),
                rating = null,
                trailer = TrailerDisplayState()
            ).isScreensaverDisplayable()
        )
        // poster only + title
        assertTrue(
            resolvedItem(
                artwork = ArtworkBundle(poster = poster),
                rating = null,
                trailer = TrailerDisplayState()
            ).isScreensaverDisplayable()
        )
    }

    @Test
    fun `isScreensaverDisplayable false when title is blank or artwork missing`() {
        val backdrop = artworkRef("backdrop:1", ArtworkType.BACKDROP)
        // empty title -> not displayable
        assertFalse(
            resolvedItem(
                artwork = ArtworkBundle(backdrop = backdrop),
                rating = null,
                trailer = TrailerDisplayState(),
                title = ""
            ).isScreensaverDisplayable()
        )
        // no backdrop and no poster -> not displayable
        assertFalse(
            resolvedItem(
                artwork = ArtworkBundle(),
                rating = null,
                trailer = TrailerDisplayState()
            ).isScreensaverDisplayable()
        )
    }

    @Test
    fun `isDisplayable on projection mirrors the resolved-side gate`() {
        val backdrop = artworkRef("backdrop:1", ArtworkType.BACKDROP)
        val displayable = IdleScreensaverDisplayItem.from(
            resolvedItem(
                artwork = ArtworkBundle(backdrop = backdrop),
                rating = null,
                trailer = TrailerDisplayState()
            )
        )
        assertTrue(displayable.isDisplayable())

        val blankTitle = IdleScreensaverDisplayItem.from(
            resolvedItem(
                artwork = ArtworkBundle(backdrop = backdrop),
                rating = null,
                trailer = TrailerDisplayState(),
                title = "  "
            )
        )
        assertFalse(blankTitle.isDisplayable())

        val noArtwork = IdleScreensaverDisplayItem.from(
            resolvedItem(
                artwork = ArtworkBundle(),
                rating = null,
                trailer = TrailerDisplayState()
            )
        )
        assertFalse(noArtwork.isDisplayable())
    }

    @Test
    fun `from ResolvedDisplayItem with empty artwork yields null background and logo`() {
        val resolved = resolvedItem(
            artwork = ArtworkBundle(),
            rating = null,
            trailer = TrailerDisplayState()
        )

        val screensaver = IdleScreensaverDisplayItem.from(resolved)

        assertNull(screensaver.backgroundRef)
        assertNull(screensaver.logoRef)
        assertNull(screensaver.rating)
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
        trailer: TrailerDisplayState,
        title: String = "Fight Club"
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
            title = title,
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
