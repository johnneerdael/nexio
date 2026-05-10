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
import com.nexio.tv.data.repository.ContinueWatchingRecord
import com.nexio.tv.domain.model.ContentType
import com.nexio.tv.domain.model.HydrationState
import com.nexio.tv.domain.model.ProviderIds
import com.nexio.tv.domain.model.ResolvedDisplayFields
import com.nexio.tv.domain.model.ResolvedDisplayItem
import com.nexio.tv.domain.model.TitleRating
import com.nexio.tv.domain.model.TitleRatingSource
import com.nexio.tv.domain.model.TrackingProvider
import com.nexio.tv.domain.model.TrailerDisplayState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test

class ContinueWatchingResolvedDisplayItemTest {

    @Test
    fun `from maps display fields, artwork, and rating from resolved`() {
        val poster = artworkRef("poster:1", ArtworkType.POSTER)
        val backdrop = artworkRef("backdrop:1", ArtworkType.BACKDROP)
        val logo = artworkRef("logo:1", ArtworkType.LOGO)
        val rating = TitleRating(8.5, TitleRatingSource.IMDB)
        val resolved = resolvedItem(
            artwork = ArtworkBundle(poster = poster, backdrop = backdrop, logo = logo),
            rating = rating
        )
        val record = continueWatchingRecord()

        val item = ContinueWatchingResolvedDisplayItem.from(resolved, record)

        assertEquals("movie:tmdb:550", item.itemKey)
        assertEquals("tmdb:550", item.contentId)
        assertEquals("Fight Club", item.title)
        assertSame(poster, item.posterRef)
        assertSame(backdrop, item.backdropRef)
        assertSame(logo, item.logoRef)
        assertSame(rating, item.rating)
        assertSame(record, item.record)
    }

    @Test
    fun `from threads record through unchanged`() {
        val record = continueWatchingRecord()
        val item = ContinueWatchingResolvedDisplayItem.from(
            resolvedItem(artwork = ArtworkBundle(), rating = null),
            record
        )

        assertSame(record, item.record)
    }

    @Test
    fun `null artwork and rating from resolved propagate as null`() {
        val item = ContinueWatchingResolvedDisplayItem.from(
            resolvedItem(artwork = ArtworkBundle(), rating = null),
            continueWatchingRecord()
        )

        assertNull(item.posterRef)
        assertNull(item.backdropRef)
        assertNull(item.logoRef)
        assertNull(item.rating)
    }

    @Test
    fun `getters read from underlying record`() {
        val record = continueWatchingRecord(
            positionMs = 1_234L,
            durationMs = 5_678L,
            updatedAt = 9_999L
        )
        val item = ContinueWatchingResolvedDisplayItem.from(
            resolvedItem(artwork = ArtworkBundle(), rating = null),
            record
        )

        assertEquals(1_234L, item.positionMs)
        assertEquals(5_678L, item.durationMs)
        assertEquals(9_999L, item.lastPlayedAtMs)
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
        val recordA = continueWatchingRecord()
        val recordB = continueWatchingRecord()

        val a = ContinueWatchingResolvedDisplayItem.from(resolvedA, recordA)
        val b = ContinueWatchingResolvedDisplayItem.from(resolvedB, recordB)

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

    private fun continueWatchingRecord(
        positionMs: Long = 1_000L,
        durationMs: Long = 5_000L,
        updatedAt: Long = 1_700_000_000_000L
    ): ContinueWatchingRecord = ContinueWatchingRecord(
        profileId = 1,
        parentId = "tmdb:550",
        contentId = "tmdb:550",
        provider = TrackingProvider.TRAKT,
        routingVersion = 4,
        positionMs = positionMs,
        durationMs = durationMs,
        episodeContext = null,
        clickTimeDisplayMetadata = null,
        source = ContinueWatchingRecord.Source.LOCAL,
        updatedAt = updatedAt
    )
}
