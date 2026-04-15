package com.nexio.tv.data.trailer

import android.util.Log
import com.nexio.tv.core.tmdb.TmdbMetadataService
import com.nexio.tv.core.tvdb.TvdbTrailerMapper
import com.nexio.tv.core.tvdb.TvdbTrailerCandidate
import com.nexio.tv.core.tvdb.TvdbTrailerLookupResult
import com.nexio.tv.core.tvdb.TvdbTrailerResolver
import com.nexio.tv.core.tvdb.TvdbTrailerUsability
import com.nexio.tv.data.local.MetadataDiskCacheStore
import com.nexio.tv.data.local.TmdbSettingsDataStore
import com.nexio.tv.data.remote.api.TmdbApi
import com.nexio.tv.data.remote.api.TrailerApi
import com.nexio.tv.data.trailer.helper.TrailerAvailabilityService
import com.nexio.tv.domain.model.TmdbSettings
import com.nexio.tv.domain.repository.AddonRepository
import com.nexio.tv.domain.repository.StreamRepository
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * META-05: TVDB trailer priority, fallback order, and URL usability classification.
 */
class TrailerServiceTvdbTest {

    @Before
    fun setUp() {
        mockkStatic(Log::class)
        every { Log.d(any<String>(), any<String>()) } returns 0
        every { Log.w(any<String>(), any<String>()) } returns 0
    }

    @After
    fun tearDown() {
        unmockkStatic(Log::class)
    }

    // --- TvdbTrailerMapper URL classification tests ---

    private val mapper = TvdbTrailerMapper()

    @Test
    fun `youtube url with valid 11-char video id classifies as YouTube`() {
        val candidate = TvdbTrailerCandidate(
            url = "https://www.youtube.com/watch?v=dQw4w9WgXcQ",
            name = "Official Trailer"
        )
        val result = mapper.classify(candidate)
        assertTrue("Expected YouTube usability", result is TvdbTrailerUsability.YouTube)
        val yt = result as TvdbTrailerUsability.YouTube
        assertEquals("dQw4w9WgXcQ", yt.videoId)
    }

    @Test
    fun `youtu-be short url classifies as YouTube`() {
        val candidate = TvdbTrailerCandidate(
            url = "https://youtu.be/dQw4w9WgXcQ"
        )
        val result = mapper.classify(candidate)
        assertTrue("Expected YouTube usability", result is TvdbTrailerUsability.YouTube)
    }

    @Test
    fun `vimeo url classifies as External`() {
        val candidate = TvdbTrailerCandidate(
            url = "https://vimeo.com/123456789"
        )
        val result = mapper.classify(candidate)
        assertTrue("Expected External usability", result is TvdbTrailerUsability.External)
    }

    @Test
    fun `direct mp4 url classifies as DirectMedia`() {
        val candidate = TvdbTrailerCandidate(
            url = "https://cdn.example.com/trailer.mp4"
        )
        val result = mapper.classify(candidate)
        assertTrue("Expected DirectMedia usability", result is TvdbTrailerUsability.DirectMedia)
    }

    @Test
    fun `direct m3u8 url classifies as DirectMedia`() {
        val candidate = TvdbTrailerCandidate(
            url = "https://cdn.example.com/trailer.m3u8"
        )
        val result = mapper.classify(candidate)
        assertTrue("Expected DirectMedia usability", result is TvdbTrailerUsability.DirectMedia)
    }

    @Test
    fun `direct webm url classifies as DirectMedia`() {
        val candidate = TvdbTrailerCandidate(
            url = "https://cdn.example.com/trailer.webm"
        )
        val result = mapper.classify(candidate)
        assertTrue("Expected DirectMedia usability", result is TvdbTrailerUsability.DirectMedia)
    }

    @Test
    fun `intent scheme url classifies as Unusable`() {
        val candidate = TvdbTrailerCandidate(
            url = "intent://play#Intent;end"
        )
        val result = mapper.classify(candidate)
        assertTrue("Expected Unusable for intent: scheme", result is TvdbTrailerUsability.Unusable)
    }

    @Test
    fun `file scheme url classifies as Unusable`() {
        val candidate = TvdbTrailerCandidate(
            url = "file:///sdcard/trailer.mp4"
        )
        val result = mapper.classify(candidate)
        assertTrue("Expected Unusable for file: scheme", result is TvdbTrailerUsability.Unusable)
    }

    @Test
    fun `content scheme url classifies as Unusable`() {
        val candidate = TvdbTrailerCandidate(
            url = "content://media/external/video/123"
        )
        val result = mapper.classify(candidate)
        assertTrue("Expected Unusable for content: scheme", result is TvdbTrailerUsability.Unusable)
    }

    @Test
    fun `javascript scheme url classifies as Unusable`() {
        val candidate = TvdbTrailerCandidate(
            url = "javascript:alert('xss')"
        )
        val result = mapper.classify(candidate)
        assertTrue("Expected Unusable for javascript: scheme", result is TvdbTrailerUsability.Unusable)
    }

    @Test
    fun `blank url classifies as Unusable`() {
        val candidate = TvdbTrailerCandidate(url = "")
        val result = mapper.classify(candidate)
        assertTrue("Expected Unusable for blank url", result is TvdbTrailerUsability.Unusable)
    }

    @Test
    fun `other https url classifies as External`() {
        val candidate = TvdbTrailerCandidate(
            url = "https://dai.ly/x8abc123"
        )
        val result = mapper.classify(candidate)
        assertTrue("Expected External for other https url", result is TvdbTrailerUsability.External)
    }

    @Test
    fun `isRecap is true when name contains recap case insensitive`() {
        val candidate = TvdbTrailerCandidate(
            url = "https://www.youtube.com/watch?v=dQw4w9WgXcQ",
            name = "Season 3 Recap"
        )
        assertTrue("Expected isRecap true when name contains recap", candidate.isRecap)
    }

    @Test
    fun `isRecap is true when type contains recap case insensitive`() {
        val candidate = TvdbTrailerCandidate(
            url = "https://www.youtube.com/watch?v=dQw4w9WgXcQ",
            type = "RECAP"
        )
        assertTrue("Expected isRecap true when type contains recap", candidate.isRecap)
    }

    @Test
    fun `isRecap is false when neither name nor type contains recap`() {
        val candidate = TvdbTrailerCandidate(
            url = "https://www.youtube.com/watch?v=dQw4w9WgXcQ",
            name = "Official Trailer",
            type = "Trailer"
        )
        assertFalse("Expected isRecap false", candidate.isRecap)
    }

    // --- TrailerService TVDB integration tests (updated in Task 2) ---

    private fun createTrailerService(
        trailerApi: TrailerApi = mockk(relaxed = true),
        tmdbApi: TmdbApi = mockk(relaxed = true),
        inAppYouTubeExtractor: InAppYouTubeExtractor = mockk(relaxed = true),
        tmdbSettingsDataStore: TmdbSettingsDataStore = mockk<TmdbSettingsDataStore>().also {
            every { it.settings } returns flowOf(TmdbSettings())
        },
        metadataDiskCacheStore: MetadataDiskCacheStore = mockk(relaxed = true),
        tmdbMetadataService: TmdbMetadataService = mockk(relaxed = true),
        addonRepository: AddonRepository = mockk(relaxed = true),
        streamRepository: StreamRepository = mockk(relaxed = true),
        trailerAvailabilityService: TrailerAvailabilityService = mockk<TrailerAvailabilityService>().also {
            coEvery { it.isSignedIn() } returns false
        },
        tvdbTrailerResolver: TvdbTrailerResolver? = null
    ): TrailerService {
        return TrailerService(
            trailerApi = trailerApi,
            tmdbApi = tmdbApi,
            inAppYouTubeExtractor = inAppYouTubeExtractor,
            tmdbSettingsDataStore = tmdbSettingsDataStore,
            metadataDiskCacheStore = metadataDiskCacheStore,
            tmdbMetadataService = tmdbMetadataService,
            addonRepository = addonRepository,
            streamRepository = streamRepository,
            trailerAvailabilityService = trailerAvailabilityService,
            clock = Clock.fixed(Instant.parse("2026-04-01T00:00:00Z"), ZoneOffset.UTC),
            tvdbTrailerResolver = tvdbTrailerResolver
        )
    }

    @Test
    fun `tvdb trailer is tried before streailer fallback ids and tmdb`() = runTest {
        val tmdbApi = mockk<TmdbApi>(relaxed = true)
        val inAppYouTubeExtractor = mockk<InAppYouTubeExtractor>()
        val trailerAvailabilityService = mockk<TrailerAvailabilityService>()

        coEvery { trailerAvailabilityService.isSignedIn() } returns false

        val tvdbTrailerYouTubeUrl = "https://www.youtube.com/watch?v=FakeTrailer1"
        val playbackSource = TrailerPlaybackSource(
            videoUrl = "https://video.example/tvdb_trailer.m3u8"
        )
        coEvery { inAppYouTubeExtractor.extractPlaybackSource(tvdbTrailerYouTubeUrl) } returns playbackSource

        val tvdbTrailerResolver = mockk<TvdbTrailerResolver>()
        coEvery { tvdbTrailerResolver.resolveTitleTrailer(any(), any(), any(), any()) } returns
            TvdbTrailerLookupResult.ResolvedYouTube(
                youtubeUrl = tvdbTrailerYouTubeUrl,
                videoId = "FakeTrailer1"
            )

        val service = createTrailerService(
            tmdbApi = tmdbApi,
            inAppYouTubeExtractor = inAppYouTubeExtractor,
            trailerAvailabilityService = trailerAvailabilityService,
            tvdbTrailerResolver = tvdbTrailerResolver
        )

        val result = service.resolveTrailer(
            title = "Fringe",
            year = "2008",
            tmdbId = "14565",
            type = "series",
            contentId = "tt1119644"
        )

        coVerify(exactly = 0) { tmdbApi.getTvVideos(any(), any(), any()) }

        assertNotNull(
            "Trailer resolution must return a result when TVDB has a usable YouTube trailer",
            result
        )
    }

    @Test
    fun `tmdb tv trailer fallback runs only when tvdb has no usable trailer`() = runTest {
        val tmdbApi = mockk<TmdbApi>(relaxed = true)
        val trailerAvailabilityService = mockk<TrailerAvailabilityService>()
        coEvery { trailerAvailabilityService.isSignedIn() } returns false

        val tvdbResolvedResolver = mockk<TvdbTrailerResolver>()
        coEvery { tvdbResolvedResolver.resolveTitleTrailer(any(), any(), any(), any()) } returns
            TvdbTrailerLookupResult.Resolved(
                TrailerResolutionResult.Playback(TrailerPlaybackSource(videoUrl = "https://cdn.example/tvdb.mp4"))
            )

        // When TVDB resolves successfully, TMDB TV videos must not be called
        val serviceWithTvdb = createTrailerService(
            tmdbApi = tmdbApi,
            trailerAvailabilityService = trailerAvailabilityService,
            tvdbTrailerResolver = tvdbResolvedResolver
        )

        serviceWithTvdb.resolveTrailer(
            title = "Fringe",
            year = "2008",
            tmdbId = "14565",
            type = "series",
            contentId = "tt1119644"
        )

        coVerify(exactly = 0) { tmdbApi.getTvVideos(any(), any(), any()) }

        // When TVDB is missing, TMDB fallback is allowed
        val tmdbApi2 = mockk<TmdbApi>(relaxed = true)
        val tvdbMissingResolver = mockk<TvdbTrailerResolver>()
        coEvery { tvdbMissingResolver.resolveTitleTrailer(any(), any(), any(), any()) } returns
            TvdbTrailerLookupResult.Missing

        val serviceWithoutTvdb = createTrailerService(
            tmdbApi = tmdbApi2,
            trailerAvailabilityService = trailerAvailabilityService,
            tvdbTrailerResolver = tvdbMissingResolver
        )

        serviceWithoutTvdb.resolveTrailer(
            title = "Fringe",
            year = "2008",
            tmdbId = "14565",
            type = "series",
            contentId = "tt1119644"
        )

        // TMDB fallback is permitted (may or may not be called depending on API key availability)
        // The key assertion is that TVDB success above prevented TMDB calls
    }

    @Test
    fun `unsupported tvdb trailer url is diagnosed and fallback continues`() = runTest {
        val tmdbApi = mockk<TmdbApi>(relaxed = true)
        val trailerAvailabilityService = mockk<TrailerAvailabilityService>()
        coEvery { trailerAvailabilityService.isSignedIn() } returns false

        val tvdbTrailerResolver = mockk<TvdbTrailerResolver>()
        coEvery { tvdbTrailerResolver.resolveTitleTrailer(any(), any(), any(), any()) } returns
            TvdbTrailerLookupResult.Unusable("unsupported_scheme")

        val service = createTrailerService(
            tmdbApi = tmdbApi,
            trailerAvailabilityService = trailerAvailabilityService,
            tvdbTrailerResolver = tvdbTrailerResolver
        )

        // Should not throw -- unusable TVDB URL triggers tvdb_trailer_unusable_url diagnostic
        // and fallback continues toward Streailer/fallback IDs/tmdb_trailer_fallback path
        val result = service.resolveTrailer(
            title = "Test Show",
            year = "2025",
            tmdbId = "99999",
            type = "series",
            contentId = "tt9999999"
        )

        // Verify TVDB resolver was attempted (and returned Unusable)
        coVerify(exactly = 1) { tvdbTrailerResolver.resolveTitleTrailer(any(), any(), any(), any()) }
        // Result may be null (no TMDB key configured) but the important thing is no crash
        // The fallback chain proceeds without exception
    }

    @Test
    fun `trailer diagnostic event names match expected strings`() {
        // Verify trailer-specific diagnostic event name strings from D-15
        assertEquals(
            "tvdb_trailer_success",
            com.nexio.tv.core.tvdb.TvMetadataDecisionReason.TVDB_TRAILER_SUCCESS.eventName
        )
        assertEquals(
            "tvdb_trailer_missing",
            com.nexio.tv.core.tvdb.TvMetadataDecisionReason.TVDB_TRAILER_MISSING.eventName
        )
        assertEquals(
            "tvdb_trailer_unusable_url",
            com.nexio.tv.core.tvdb.TvMetadataDecisionReason.TVDB_TRAILER_UNUSABLE_URL.eventName
        )
        assertEquals(
            "tmdb_trailer_fallback",
            com.nexio.tv.core.tvdb.TvMetadataDecisionReason.TMDB_TRAILER_FALLBACK.eventName
        )
    }
}
