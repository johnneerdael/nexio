package com.nexio.tv.data.trailer

import android.util.Log
import com.nexio.tv.core.metadata.router.resolver.TrailerPlaybackRef
import com.nexio.tv.core.tmdb.TmdbMetadataService
import com.nexio.tv.data.integration.trailer.TrailerBackendProvider
import com.nexio.tv.data.integration.trailer.TrailerTmdbProvider
import com.nexio.tv.domain.model.TmdbSettings
import com.nexio.tv.domain.repository.AddonRepository
import com.nexio.tv.domain.repository.StreamRepository
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
import java.util.concurrent.CancellationException
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset

class TrailerServiceBackendBoundaryTest {

    @Before
    fun setUp() {
        mockkStatic(Log::class)
        every { Log.d(any<String>(), any<String>()) } returns 0
        every { Log.w(any<String>(), any<String>()) } returns 0
        every { Log.e(any<String>(), any<String>(), any()) } returns 0
    }

    @After
    fun tearDown() {
        unmockkStatic(Log::class)
    }

    private fun buildService(
        inAppYouTubeExtractor: InAppYouTubeExtractor,
        trailerBackendProvider: TrailerBackendProvider
    ): TrailerService {
        return TrailerService(
            trailerBackendProvider = trailerBackendProvider,
            inAppYouTubeExtractor = inAppYouTubeExtractor,
            tmdbMetadataService = mockk<TmdbMetadataService>(relaxed = true),
            trailerTmdbProvider = mockk<TrailerTmdbProvider>().also {
                coEvery { it.getTmdbApiKey() } returns "tmdb-key"
            },
            addonRepository = mockk<AddonRepository>(relaxed = true),
            streamRepository = mockk<StreamRepository>(relaxed = true),
            clock = Clock.fixed(Instant.parse("2026-04-01T00:00:00Z"), ZoneOffset.UTC),
        )
    }

    @Test
    fun `youtube trailer falls back to backend provider when extractor misses`() = runTest {
        val extractor = mockk<InAppYouTubeExtractor>()
        val backendProvider = mockk<TrailerBackendProvider>()
        val backendSource = TrailerPlaybackSource(videoUrl = "https://backend.example/trailer.m3u8")
        val youtubeUrl = "https://www.youtube.com/watch?v=abc12345678"

        coEvery { extractor.extractPlaybackSource(youtubeUrl, any()) } returns null
        coEvery {
            backendProvider.resolveYouTubePlaybackSource(youtubeUrl, "Demo", "2026")
        } returns backendSource

        val service = buildService(
            inAppYouTubeExtractor = extractor,
            trailerBackendProvider = backendProvider
        )

        val result = service.getTrailerPlaybackSourceFromYouTubeUrl(
            youtubeUrl = youtubeUrl,
            title = "Demo",
            year = "2026"
        )

        assertEquals(backendSource.videoUrl, result?.videoUrl)
        coVerify(exactly = 1) {
            backendProvider.resolveYouTubePlaybackSource(youtubeUrl, "Demo", "2026")
        }
    }

    @Test
    fun `youtube trailer does not use backend when local extractor succeeds`() = runTest {
        val extractor = mockk<InAppYouTubeExtractor>()
        val backendProvider = mockk<TrailerBackendProvider>(relaxed = true)
        val extractorSource = TrailerPlaybackSource(videoUrl = "https://local.example/trailer.mp4")

        coEvery { extractor.extractPlaybackSource(any(), any()) } returns extractorSource

        val service = buildService(
            inAppYouTubeExtractor = extractor,
            trailerBackendProvider = backendProvider
        )

        val result = service.getTrailerPlaybackSourceFromYouTubeUrl(
            youtubeUrl = "https://www.youtube.com/watch?v=abc12345678",
            title = "Demo",
            year = "2026"
        )

        assertEquals(extractorSource.videoUrl, result?.videoUrl)
        coVerify(exactly = 0) { backendProvider.resolveYouTubePlaybackSource(any(), any(), any()) }
    }

    @Test
    fun `youtube trailer does not cache progressive googlevideo fallback over later adaptive source`() = runTest {
        val extractor = mockk<InAppYouTubeExtractor>()
        val backendProvider = mockk<TrailerBackendProvider>(relaxed = true)
        val progressiveFallback = TrailerPlaybackSource(
            videoUrl = "https://rr1---sn.example.googlevideo.com/videoplayback?itag=18"
        )
        val adaptiveSource = TrailerPlaybackSource(
            videoUrl = "data:application/dash+xml;base64,VIDEO",
            audioUrl = "data:application/dash+xml;base64,AUDIO",
            signingClientKey = "android"
        )
        val youtubeUrl = "https://www.youtube.com/watch?v=abc12345678"

        coEvery { extractor.extractPlaybackSource(youtubeUrl, any()) } returnsMany listOf(
            progressiveFallback,
            adaptiveSource
        )

        val service = buildService(
            inAppYouTubeExtractor = extractor,
            trailerBackendProvider = backendProvider
        )

        val first = service.getTrailerPlaybackSourceFromYouTubeUrl(
            youtubeUrl = youtubeUrl,
            title = "Demo",
            year = "2026"
        )
        val second = service.getTrailerPlaybackSourceFromYouTubeUrl(
            youtubeUrl = youtubeUrl,
            title = "Demo",
            year = "2026"
        )

        assertEquals(progressiveFallback.videoUrl, first?.videoUrl)
        assertEquals(adaptiveSource.videoUrl, second?.videoUrl)
        assertEquals(adaptiveSource.audioUrl, second?.audioUrl)
        coVerify(exactly = 2) { extractor.extractPlaybackSource(youtubeUrl, any()) }
    }

    @Test
    fun `youtube trailer does not cache adaptive playback source over later codec selection`() = runTest {
        val extractor = mockk<InAppYouTubeExtractor>()
        val backendProvider = mockk<TrailerBackendProvider>(relaxed = true)
        val av1AdaptiveSource = TrailerPlaybackSource(
            videoUrl = "data:application/dash+xml;base64,AV1",
            audioUrl = "data:application/dash+xml;base64,AUDIO",
            signingClientKey = "android"
        )
        val vp9AdaptiveSource = TrailerPlaybackSource(
            videoUrl = "data:application/dash+xml;base64,VP9",
            audioUrl = "data:application/dash+xml;base64,AUDIO",
            signingClientKey = "android"
        )
        val youtubeUrl = "https://www.youtube.com/watch?v=abc12345678"

        coEvery { extractor.extractPlaybackSource(youtubeUrl, any()) } returnsMany listOf(
            av1AdaptiveSource,
            vp9AdaptiveSource
        )

        val service = buildService(
            inAppYouTubeExtractor = extractor,
            trailerBackendProvider = backendProvider
        )

        val first = service.getTrailerPlaybackSourceFromYouTubeUrl(
            youtubeUrl = youtubeUrl,
            title = "Demo",
            year = "2026"
        )
        val second = service.getTrailerPlaybackSourceFromYouTubeUrl(
            youtubeUrl = youtubeUrl,
            title = "Demo",
            year = "2026"
        )

        assertEquals(av1AdaptiveSource.videoUrl, first?.videoUrl)
        assertEquals(vp9AdaptiveSource.videoUrl, second?.videoUrl)
        coVerify(exactly = 2) { extractor.extractPlaybackSource(youtubeUrl, any()) }
    }

    @Test
    fun `youtube trailer returns null when backend provider misses`() = runTest {
        val extractor = mockk<InAppYouTubeExtractor>()
        val backendProvider = mockk<TrailerBackendProvider>()

        coEvery { extractor.extractPlaybackSource(any(), any()) } returns null
        coEvery { backendProvider.resolveYouTubePlaybackSource(any(), any(), any()) } returns null

        val service = buildService(
            inAppYouTubeExtractor = extractor,
            trailerBackendProvider = backendProvider
        )

        val result = service.getTrailerPlaybackSourceFromYouTubeUrl(
            youtubeUrl = "https://www.youtube.com/watch?v=abc12345678",
            title = "Demo",
            year = "2026"
        )

        assertNull(result)
    }

    @Test
    fun `typed youtube playback ref falls back to external url when extraction misses`() = runTest {
        val extractor = mockk<InAppYouTubeExtractor>()
        val backendProvider = mockk<TrailerBackendProvider>()

        coEvery { extractor.extractPlaybackSource(any(), any()) } returns null
        coEvery { backendProvider.resolveYouTubePlaybackSource(any(), any(), any()) } returns null

        val service = buildService(
            inAppYouTubeExtractor = extractor,
            trailerBackendProvider = backendProvider
        )

        val result = service.resolvePlaybackSource(
            ref = TrailerPlaybackRef.YouTubeId("abc12345678"),
            title = "Demo",
            year = "2026"
        )

        assertTrue(result is TrailerResolutionResult.External)
        assertEquals(
            "https://www.youtube.com/watch?v=abc12345678",
            (result as TrailerResolutionResult.External).url
        )
    }

    @Test
    fun `youtube trailer rethrows backend cancellation`() = runTest {
        val extractor = mockk<InAppYouTubeExtractor>()
        val backendProvider = mockk<TrailerBackendProvider>()
        val cancellation = CancellationException("backend request cancelled")

        coEvery { extractor.extractPlaybackSource(any(), any()) } returns null
        coEvery { backendProvider.resolveYouTubePlaybackSource(any(), any(), any()) } throws cancellation

        val service = buildService(
            inAppYouTubeExtractor = extractor,
            trailerBackendProvider = backendProvider
        )

        try {
            service.getTrailerPlaybackSourceFromYouTubeUrl(
                youtubeUrl = "https://www.youtube.com/watch?v=abc12345678",
                title = "Demo",
                year = "2026"
            )
            fail("Expected CancellationException to be rethrown")
        } catch (_: CancellationException) {
            // Expected.
        }
    }
}
