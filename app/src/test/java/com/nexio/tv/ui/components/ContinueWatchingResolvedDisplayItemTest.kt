package com.nexio.tv.ui.components

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
import com.nexio.tv.domain.model.WatchProgress
import com.nexio.tv.ui.screens.home.ContinueWatchingItem
import com.nexio.tv.ui.screens.home.NextUpInfo
import com.nexio.tv.ui.screens.home.displayMetadata
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class ContinueWatchingResolvedDisplayItemTest {

    @Test
    fun `fromInProgress maps display fields, artwork, and rating from resolved`() {
        val poster = artworkRef("poster:1", ArtworkType.POSTER)
        val backdrop = artworkRef("backdrop:1", ArtworkType.BACKDROP)
        val logo = artworkRef("logo:1", ArtworkType.LOGO)
        val rating = TitleRating(8.5, TitleRatingSource.IMDB)
        val resolved = resolvedItem(
            artwork = ArtworkBundle(poster = poster, backdrop = backdrop, logo = logo),
            rating = rating
        )
        val source = inProgressItem()

        val item = ContinueWatchingResolvedDisplayItem.fromInProgress(resolved, source)

        assertEquals("movie:tmdb:550", item.itemKey)
        assertEquals("tmdb:550", item.contentId)
        assertEquals("Fight Club", item.title)
        assertSame(poster, item.posterRef)
        assertSame(backdrop, item.backdropRef)
        assertSame(logo, item.logoRef)
        assertSame(rating, item.rating)
        assertSame(source.progress, item.source.progress)
        assertEquals("Fight Club", item.source.displayMetadata().title)
        assertEquals("An office worker meets a strange soap salesman.", item.source.displayMetadata().description)
        assertEquals("tt0137523", item.source.displayMetadata().imdbId)
    }

    @Test
    fun `fromInProgress preserves progress and exposes progress getter`() {
        val source = inProgressItem()
        val item = ContinueWatchingResolvedDisplayItem.fromInProgress(
            resolvedItem(artwork = ArtworkBundle(), rating = null),
            source
        )

        assertSame(source.progress, item.progress)
    }

    @Test
    fun `fromInProgress with null artwork and rating from resolved propagates as null`() {
        val item = ContinueWatchingResolvedDisplayItem.fromInProgress(
            resolvedItem(artwork = ArtworkBundle(), rating = null),
            inProgressItem()
        )

        assertNull(item.posterRef)
        assertNull(item.backdropRef)
        assertNull(item.logoRef)
        assertNull(item.rating)
    }

    @Test
    fun `fromNextUp maps display fields, artwork, and rating from resolved`() {
        val poster = artworkRef("poster:1", ArtworkType.POSTER)
        val backdrop = artworkRef("backdrop:1", ArtworkType.BACKDROP)
        val logo = artworkRef("logo:1", ArtworkType.LOGO)
        val rating = TitleRating(7.2, TitleRatingSource.TMDB)
        val resolved = resolvedItem(
            artwork = ArtworkBundle(poster = poster, backdrop = backdrop, logo = logo),
            rating = rating
        )
        val source = nextUpItem()

        val item = ContinueWatchingResolvedDisplayItem.fromNextUp(resolved, source)

        assertEquals("movie:tmdb:550", item.itemKey)
        assertEquals("tmdb:550", item.contentId)
        assertEquals("Fight Club", item.title)
        assertSame(poster, item.posterRef)
        assertSame(backdrop, item.backdropRef)
        assertSame(logo, item.logoRef)
        assertSame(rating, item.rating)
        assertEquals(source.info.videoId, item.source.info.videoId)
        assertEquals(source.info.season, item.source.info.season)
        assertEquals(source.info.episode, item.source.info.episode)
        assertEquals("Fight Club", item.source.displayMetadata().title)
        assertEquals("tt0137523", item.source.displayMetadata().imdbId)
    }

    @Test
    fun `fromNextUp preserves episode coordinates and exposes info getter`() {
        val source = nextUpItem()
        val item = ContinueWatchingResolvedDisplayItem.fromNextUp(
            resolvedItem(artwork = ArtworkBundle(), rating = null),
            source
        )

        assertEquals(source.info.videoId, item.info.videoId)
        assertEquals(source.info.season, item.info.season)
        assertEquals(source.info.episode, item.info.episode)
    }

    @Test
    fun `fromInProgress and fromNextUp produce sealed class instances`() {
        val resolved = resolvedItem(artwork = ArtworkBundle(), rating = null)
        val ip = ContinueWatchingResolvedDisplayItem.fromInProgress(resolved, inProgressItem())
        val nu = ContinueWatchingResolvedDisplayItem.fromNextUp(resolved, nextUpItem())

        val ipBase: ContinueWatchingResolvedDisplayItem = ip
        val nuBase: ContinueWatchingResolvedDisplayItem = nu

        assertTrue(ipBase is ContinueWatchingResolvedDisplayItem.InProgress)
        assertTrue(nuBase is ContinueWatchingResolvedDisplayItem.NextUp)
    }

    @Test
    fun `content-equal InProgress inputs produce equal projections via data-class equality`() {
        val poster = artworkRef("poster:1", ArtworkType.POSTER)
        val backdrop = artworkRef("backdrop:1", ArtworkType.BACKDROP)
        val logo = artworkRef("logo:1", ArtworkType.LOGO)
        val rating = TitleRating(8.5, TitleRatingSource.IMDB)
        val artwork = ArtworkBundle(poster = poster, backdrop = backdrop, logo = logo)

        val resolvedA = resolvedItem(artwork = artwork, rating = rating)
        val resolvedB = resolvedItem(artwork = artwork, rating = rating)
        val sourceA = inProgressItem()
        val sourceB = inProgressItem()

        val a = ContinueWatchingResolvedDisplayItem.fromInProgress(resolvedA, sourceA)
        val b = ContinueWatchingResolvedDisplayItem.fromInProgress(resolvedB, sourceB)

        assertNotSame(a, b)
        assertEquals(a, b)
        assertEquals(a.hashCode(), b.hashCode())
    }

    @Test
    fun `content-equal NextUp inputs produce equal projections via data-class equality`() {
        val poster = artworkRef("poster:1", ArtworkType.POSTER)
        val backdrop = artworkRef("backdrop:1", ArtworkType.BACKDROP)
        val logo = artworkRef("logo:1", ArtworkType.LOGO)
        val rating = TitleRating(7.2, TitleRatingSource.TMDB)
        val artwork = ArtworkBundle(poster = poster, backdrop = backdrop, logo = logo)

        val resolvedA = resolvedItem(artwork = artwork, rating = rating)
        val resolvedB = resolvedItem(artwork = artwork, rating = rating)
        val sourceA = nextUpItem()
        val sourceB = nextUpItem()

        val a = ContinueWatchingResolvedDisplayItem.fromNextUp(resolvedA, sourceA)
        val b = ContinueWatchingResolvedDisplayItem.fromNextUp(resolvedB, sourceB)

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

    private fun inProgressItem(
        contentId: String = "tmdb:550",
        positionMs: Long = 1_000L,
        durationMs: Long = 5_000L,
        lastWatched: Long = 1_700_000_000_000L
    ): ContinueWatchingItem.InProgress = ContinueWatchingItem.InProgress(
        progress = WatchProgress(
            contentId = contentId,
            contentType = "movie",
            name = "Fight Club",
            poster = null,
            backdrop = null,
            logo = null,
            videoId = contentId,
            season = null,
            episode = null,
            episodeTitle = null,
            position = positionMs,
            duration = durationMs,
            lastWatched = lastWatched
        )
    )

    private fun nextUpItem(
        contentId: String = "tmdb:550",
        season: Int = 1,
        episode: Int = 2,
        lastWatched: Long = 1_700_000_000_000L
    ): ContinueWatchingItem.NextUp = ContinueWatchingItem.NextUp(
        info = NextUpInfo(
            contentId = contentId,
            contentType = "series",
            name = "Fight Club",
            poster = null,
            backdrop = null,
            logo = null,
            videoId = "$contentId:$season:$episode",
            season = season,
            episode = episode,
            episodeTitle = "Episode $episode",
            thumbnail = null,
            lastWatched = lastWatched
        )
    )
}
