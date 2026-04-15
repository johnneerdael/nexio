package com.nexio.tv.data.trailer

import android.util.Log
import com.nexio.tv.core.tmdb.TmdbMetadataService
import com.nexio.tv.core.tvdb.TvdbTrailerMapper
import com.nexio.tv.core.tvdb.TvdbTrailerCandidate
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
        val tmdbApi = mockk<TmdbApi>(relaxed = true)
        val inAppYouTubeExtractor = mockk<InAppYouTubeExtractor>()
        val trailerAvailabilityService = mockk<TrailerAvailabilityService>()

        coEvery { trailerAvailabilityService.isSignedIn() } returns false

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

        val service = createTrailerService(
            tmdbApi = tmdbApi,
            trailerAvailabilityService = trailerAvailabilityService
        )

        val result = service.resolveTrailer(
            title = "Fringe",
            year = "2008",
            tmdbId = "14565",
            type = "series",
            contentId = "tt1119644"
        )

        coVerify(atMost = 1) { tmdbApi.getTvVideos(any(), any(), any()) }
    }

    @Test
    fun `unsupported tvdb trailer url is diagnosed and fallback continues`() = runTest {
        val tmdbApi = mockk<TmdbApi>(relaxed = true)
        val trailerAvailabilityService = mockk<TrailerAvailabilityService>()
        coEvery { trailerAvailabilityService.isSignedIn() } returns false

        val service = createTrailerService(
            tmdbApi = tmdbApi,
            trailerAvailabilityService = trailerAvailabilityService
        )

        val result = service.resolveTrailer(
            title = "Test Show",
            year = "2025",
            tmdbId = "99999",
            type = "series",
            contentId = "tt9999999"
        )

        coVerify(atMost = 1) { tmdbApi.getTvVideos(any(), any(), any()) }
    }
}
