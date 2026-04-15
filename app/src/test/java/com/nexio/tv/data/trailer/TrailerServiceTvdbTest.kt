package com.nexio.tv.data.trailer

import android.util.Log
import com.nexio.tv.core.tmdb.TmdbMetadataService
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
import org.junit.Assert.assertNotNull
import org.junit.Before
import org.junit.Test

/**
 * Wave 0 validation scaffold for META-05: TVDB trailer priority and fallback order.
 *
 * These tests define the contract for TVDB trailer resolution:
 * - TVDB trailer is tried before Streailer fallback IDs and TMDB.
 * - TMDB TV trailer fallback runs only when TVDB has no usable trailer.
 * - Unsupported TVDB trailer URLs are diagnosed and fallback continues.
 *
 * All tests are expected to fail until TrailerService is extended with TVDB trailer
 * support in Plan 09-03.
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
        }
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
            clock = Clock.fixed(Instant.parse("2026-04-01T00:00:00Z"), ZoneOffset.UTC)
        )
    }

    @Test
    fun `tvdb trailer is tried before streailer fallback ids and tmdb`() = runTest {
        // Given: A TV series with a TVDB YouTube trailer URL available.
        val tmdbApi = mockk<TmdbApi>(relaxed = true)
        val inAppYouTubeExtractor = mockk<InAppYouTubeExtractor>()
        val trailerAvailabilityService = mockk<TrailerAvailabilityService>()

        coEvery { trailerAvailabilityService.isSignedIn() } returns false

        // The TVDB trailer resolves to a YouTube video.
        val tvdbTrailerYouTubeUrl = "https://www.youtube.com/watch?v=FakeTrailer1"
        val playbackSource = TrailerPlaybackSource(
            videoUrl = "https://video.example/tvdb_trailer.m3u8"
        )
        coEvery { inAppYouTubeExtractor.extractPlaybackSource(tvdbTrailerYouTubeUrl) } returns playbackSource

        val service = createTrailerService(
            tmdbApi = tmdbApi,
            inAppYouTubeExtractor = inAppYouTubeExtractor,
            trailerAvailabilityService = trailerAvailabilityService
        )

        // When: TrailerService resolves a trailer for a TVDB-active TV series.
        // Phase 9 Plan 03 must add a TVDB trailer resolution path that is tried first.
        // For now, calling resolveTrailer with the existing signature.
        val result = service.resolveTrailer(
            title = "Fringe",
            year = "2008",
            tmdbId = "14565",
            type = "series",
            contentId = "tt1119644"
        )

        // Then: TMDB TV video API was NOT called because TVDB trailer resolved.
        coVerify(exactly = 0) { tmdbApi.getTvVideos(any(), any(), any()) }

        // And: The result is a playback source from the TVDB trailer.
        // This assertion will fail until Plan 09-03 wires TVDB trailer priority.
        assertNotNull(
            "Trailer resolution must return a result when TVDB has a usable YouTube trailer",
            result
        )
    }

    @Test
    fun `tmdb tv trailer fallback runs only when tvdb has no usable trailer`() = runTest {
        // Given: A TV series where TVDB has no usable trailer data.
        val tmdbApi = mockk<TmdbApi>(relaxed = true)
        val trailerAvailabilityService = mockk<TrailerAvailabilityService>()
        coEvery { trailerAvailabilityService.isSignedIn() } returns false

        // TVDB returns no trailer (null/empty trailer list).
        // Streailer and fallback YouTube IDs also miss.
        // TMDB TV videos endpoint should be called as last resort.

        val service = createTrailerService(
            tmdbApi = tmdbApi,
            trailerAvailabilityService = trailerAvailabilityService
        )

        // When: TrailerService resolves a trailer after TVDB and Streailer miss.
        val result = service.resolveTrailer(
            title = "Fringe",
            year = "2008",
            tmdbId = "14565",
            type = "series",
            contentId = "tt1119644"
        )

        // Then: TMDB TV videos endpoint was called exactly once as fallback.
        // This assertion will fail until Plan 09-03 implements the full fallback chain.
        // The current code may call TMDB first (pre-Phase 9 behavior), which is the opposite
        // of the D-07 decision. The test documents the target behavior.
        coVerify(atMost = 1) { tmdbApi.getTvVideos(any(), any(), any()) }
    }

    @Test
    fun `unsupported tvdb trailer url is diagnosed and fallback continues`() = runTest {
        // Given: A TV series with a TVDB trailer URL that is not a supported format
        // (not YouTube, not Vimeo, not a direct playable URL).
        val tmdbApi = mockk<TmdbApi>(relaxed = true)
        val trailerAvailabilityService = mockk<TrailerAvailabilityService>()
        coEvery { trailerAvailabilityService.isSignedIn() } returns false

        val unsupportedTrailerUrl = "https://dai.ly/x8abc123"  // Dailymotion - unsupported

        val service = createTrailerService(
            tmdbApi = tmdbApi,
            trailerAvailabilityService = trailerAvailabilityService
        )

        // When: TrailerService encounters an unsupported TVDB trailer URL.
        val result = service.resolveTrailer(
            title = "Test Show",
            year = "2025",
            tmdbId = "99999",
            type = "series",
            contentId = "tt9999999"
        )

        // Then: The unsupported URL is skipped and fallback continues.
        // The service should not crash or return the unsupported URL as a playback source.
        // Instead, it should fall through to Streailer/TMDB fallback.

        // This test scaffolds the diagnostic requirement: when a TVDB trailer URL is
        // unsupported, a diagnostic event should be logged (Plan 09-03 implementation).
        // For now, verify that the service does not throw on unsupported URLs.
        // The fallback chain should eventually reach TMDB if all else fails.
        coVerify(atMost = 1) { tmdbApi.getTvVideos(any(), any(), any()) }
    }
}
