package com.nexio.tv.domain.model

import com.nexio.tv.core.artwork.ArtworkBundle
import com.nexio.tv.core.artwork.ArtworkDecisionKey
import com.nexio.tv.core.artwork.ArtworkDisplayHints
import com.nexio.tv.core.artwork.ArtworkDisplayRef
import com.nexio.tv.core.artwork.ArtworkProviderId
import com.nexio.tv.core.artwork.ArtworkSourceRole
import com.nexio.tv.core.artwork.ArtworkTrace
import com.nexio.tv.core.artwork.ArtworkType
import com.nexio.tv.core.metadata.router.MetadataMediaKind
import com.nexio.tv.core.metadata.router.resolver.TrailerPlaybackRef
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class ResolvedDisplaySignatureTest {
    private val featureSignature = DisplayFeatureSignature(
        languageTag = "en-US",
        artworkSettingsSignature = "artwork-settings:v1",
        ratingProviderPolicy = "imdb-first",
        displayPolicyVersion = 2
    )

    @Test
    fun `timestamps and cache ttl fields do not change visible display signature`() {
        val base = resolvedItem(
            updatedAtMs = 100L,
            trailer = TrailerDisplayState(lastResolvedAtMs = 200L),
            slots = slots(updatedAtMs = 300L, expiresAtMs = 400L)
        )
        val restamped = base.copy(
            updatedAtMs = 9_000L,
            trailer = base.trailer.copy(lastResolvedAtMs = 9_100L),
            slots = slots(updatedAtMs = 9_200L, expiresAtMs = 9_300L)
        )

        assertEquals(
            base.visibleDisplaySignature(featureSignature),
            restamped.visibleDisplaySignature(featureSignature)
        )
    }

    @Test
    fun `trailer playback resolution state does not change visible display signature`() {
        val base = resolvedItem(
            trailer = TrailerDisplayState(
                fallbackTrailerYtIds = listOf("yt-1"),
                selectedPlaybackRef = null,
                availabilityReason = "available",
                surface = "home",
                resolverSource = "candidate_cache",
                lastResolvedAtMs = 100L
            )
        )
        val resolvedPlayback = base.copy(
            trailer = TrailerDisplayState(
                fallbackTrailerYtIds = listOf("yt-2"),
                selectedPlaybackRef = TrailerPlaybackRef.YouTubeId("yt-2"),
                availabilityReason = "resolved",
                surface = "screensaver",
                resolverSource = "youtubei",
                lastResolvedAtMs = 200L
            )
        )

        assertEquals(
            base.visibleDisplaySignature(featureSignature),
            resolvedPlayback.visibleDisplaySignature(featureSignature)
        )
    }

    @Test
    fun `artwork trace changes do not change visible display signature`() {
        val base = resolvedItem()
        val traceChanged = base.copy(
            artwork = base.artwork.copy(
                poster = artworkRef(
                    key = "poster-1",
                    imageType = ArtworkType.POSTER,
                    trace = ArtworkTrace(selectedProvider = "different-trace")
                )
            ),
            slots = slots(
                trace = listOf("different-slot-trace")
            )
        )

        assertEquals(
            base.visibleDisplaySignature(featureSignature),
            traceChanged.visibleDisplaySignature(featureSignature)
        )
    }

    @Test
    fun `visible display field changes change visible display signature`() {
        val base = resolvedItem()

        assertVisibleChanges(base.copy(display = base.display.copy(title = "Different")))
        assertVisibleChanges(base.copy(rating = TitleRating(7.1, TitleRatingSource.TMDB)))
        assertVisibleChanges(base.copy(artwork = base.artwork.copy(poster = artworkRef("poster-2", ArtworkType.POSTER))))
        assertVisibleChanges(base.copy(artwork = base.artwork.copy(backdrop = artworkRef("backdrop-2", ArtworkType.BACKDROP))))
        assertVisibleChanges(base.copy(artwork = base.artwork.copy(logo = artworkRef("logo-2", ArtworkType.LOGO))))
        assertVisibleChanges(base.copy(display = base.display.copy(overview = "Different overview")))
        assertVisibleChanges(base.copy(display = base.display.copy(genres = listOf("Drama"))))
        assertVisibleChanges(base.copy(display = base.display.copy(releaseDate = "2000-01-01")))
        assertVisibleChanges(base.copy(display = base.display.copy(runtimeText = "91 min")))
        assertVisibleChanges(base.copy(display = base.display.copy(tomatoesRating = 91.0)))
    }

    @Test
    fun `stable id strengthening changes identity signature but not visible display signature`() {
        val base = resolvedItem(stableIds = ProviderIds(tmdb = "1"))
        val strengthened = base.copy(
            stableIds = base.stableIds.copy(imdb = "tt0000001"),
            canonicalProvider = "imdb",
            canonicalId = "tt0000001",
            imdbId = "tt0000001"
        )

        assertEquals(
            base.visibleDisplaySignature(featureSignature),
            strengthened.visibleDisplaySignature(featureSignature)
        )
        assertNotEquals(base.identitySignature(), strengthened.identitySignature())
    }

    @Test
    fun `feature signature participates in visible display signature`() {
        val item = resolvedItem()
        val base = item.visibleDisplaySignature(featureSignature)

        assertNotEquals(base, item.visibleDisplaySignature(featureSignature.copy(languageTag = "nl-NL")))
        assertNotEquals(base, item.visibleDisplaySignature(featureSignature.copy(artworkSettingsSignature = "artwork-settings:v2")))
        assertNotEquals(base, item.visibleDisplaySignature(featureSignature.copy(ratingProviderPolicy = "tmdb-first")))
        assertNotEquals(base, item.visibleDisplaySignature(featureSignature.copy(displayPolicyVersion = 3)))
    }

    @Test
    fun `slot visible signature ignores timestamps and ttl`() {
        val first = slots(updatedAtMs = 1L, expiresAtMs = 2L)
        val second = slots(updatedAtMs = 10L, expiresAtMs = 20L)

        assertEquals(first.visibleSlotSignature(), second.visibleSlotSignature())
    }

    private fun assertVisibleChanges(changed: ResolvedDisplayItem) {
        assertNotEquals(
            resolvedItem().visibleDisplaySignature(featureSignature),
            changed.visibleDisplaySignature(featureSignature)
        )
    }

    private fun resolvedItem(
        updatedAtMs: Long = 100L,
        stableIds: ProviderIds = ProviderIds(tmdb = "1"),
        trailer: TrailerDisplayState = TrailerDisplayState(
            fallbackTrailerYtIds = listOf("yt-1"),
            selectedPlaybackRef = null,
            availabilityReason = "available",
            surface = "home",
            resolverSource = "cache",
            lastResolvedAtMs = 200L
        ),
        slots: ResolvedDisplayFieldSlots? = slots()
    ): ResolvedDisplayItem = ResolvedDisplayItem(
        itemKey = "movie:tmdb:1",
        contentId = "movie:tmdb:1",
        parentId = "movie:tmdb:1",
        itemType = ContentType.MOVIE,
        mediaKind = MetadataMediaKind.MOVIE,
        canonicalProvider = "tmdb",
        canonicalId = "1",
        imdbId = null,
        stableIds = stableIds,
        display = ResolvedDisplayFields(
            title = "The Movie",
            originalTitle = "The Original Movie",
            year = 1999,
            releaseDate = "1999-03-31",
            overview = "A display description.",
            genres = listOf("Action", "Sci-Fi"),
            runtimeText = "90 min",
            tomatoesRating = 90.0
        ),
        artwork = ArtworkBundle(
            poster = artworkRef("poster-1", ArtworkType.POSTER),
            backdrop = artworkRef("backdrop-1", ArtworkType.BACKDROP),
            logo = artworkRef("logo-1", ArtworkType.LOGO)
        ),
        rating = TitleRating(8.7, TitleRatingSource.IMDB),
        trailer = trailer,
        hydrationState = HydrationState.CANONICAL_READY,
        sourceTrace = listOf(HydratedHomeFieldTrace("title", "tmdb", "PRIMARY")),
        updatedAtMs = updatedAtMs,
        slots = slots,
        preferredArtworkProviders = mapOf(ArtworkType.POSTER to ArtworkProviderId.RailPreview)
    )

    private fun slots(
        updatedAtMs: Long = 300L,
        expiresAtMs: Long? = 400L,
        trace: List<String> = listOf("trace")
    ): ResolvedDisplayFieldSlots = ResolvedDisplayFieldSlots(
        title = slot("The Movie", updatedAtMs, expiresAtMs, trace),
        originalTitle = slot("The Original Movie", updatedAtMs, expiresAtMs, trace),
        overview = slot("A display description.", updatedAtMs, expiresAtMs, trace),
        genres = slot(listOf("Action", "Sci-Fi"), updatedAtMs, expiresAtMs, trace),
        releaseInfo = slot("1999-03-31", updatedAtMs, expiresAtMs, trace),
        runtime = slot("90 min", updatedAtMs, expiresAtMs, trace),
        rating = slot(TitleRating(8.7, TitleRatingSource.IMDB), updatedAtMs, expiresAtMs, trace),
        poster = slot(artworkRef("poster-1", ArtworkType.POSTER), updatedAtMs, expiresAtMs, trace),
        backdrop = slot(artworkRef("backdrop-1", ArtworkType.BACKDROP), updatedAtMs, expiresAtMs, trace),
        logo = slot(artworkRef("logo-1", ArtworkType.LOGO), updatedAtMs, expiresAtMs, trace),
        thumbnail = slot(null, updatedAtMs, expiresAtMs, trace),
        posterProviderTag = slot("rail-preview", updatedAtMs, expiresAtMs, trace)
    )

    private fun <T> slot(
        value: T?,
        updatedAtMs: Long,
        expiresAtMs: Long?,
        trace: List<String>
    ): ResolvedSlot<T> = ResolvedSlot(
        value = value,
        rank = DisplaySourceRank.RESOLVED,
        provider = "tmdb",
        role = "PRIMARY",
        updatedAtMs = updatedAtMs,
        expiresAtMs = expiresAtMs,
        trace = trace
    )

    private fun artworkRef(
        key: String,
        imageType: ArtworkType,
        trace: ArtworkTrace = ArtworkTrace.empty()
    ): ArtworkDisplayRef.RuntimeAsset =
        ArtworkDisplayRef.RuntimeAsset(
            decisionKey = ArtworkDecisionKey("decision-$key"),
            assetKey = null,
            imageType = imageType,
            selectedProvider = ArtworkProviderId.RailPreview,
            sourceRole = ArtworkSourceRole.RAIL_PREVIEW,
            trace = trace,
            displayHints = ArtworkDisplayHints()
        )
}
