package com.nexio.tv.data.trailer.helper

import com.nexio.tv.data.local.YouTubeTrailerAuthDataStore
import com.nexio.tv.data.local.YouTubeTrailerAuthSettings
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TrailerAvailabilityServiceTest {

    @Test
    fun `signed out does not invoke helper`() = runTest {
        val authDataStore = mockk<YouTubeTrailerAuthDataStore>()
        val cookieStore = mockk<YouTubeTrailerCookieStore>()
        val bundledHelper = mockk<BundledTrailerHelper>()
        every { authDataStore.settings } returns flowOf(YouTubeTrailerAuthSettings(isSignedIn = false))

        val service = TrailerAvailabilityService(
            authDataStore = authDataStore,
            cookieStore = cookieStore,
            bundledTrailerHelper = bundledHelper,
            helperCache = cache()
        )

        val resolved = service.resolveAuthenticatedYouTubePlayback("https://www.youtube.com/watch?v=testvideo01")

        assertNull(resolved)
        coVerify(exactly = 0) { bundledHelper.resolve(any()) }
    }

    @Test
    fun `signed in helper failure caches miss and avoids repeated helper calls`() = runTest {
        val authDataStore = mockk<YouTubeTrailerAuthDataStore>()
        val cookieStore = mockk<YouTubeTrailerCookieStore>()
        val bundledHelper = mockk<BundledTrailerHelper>()
        every { authDataStore.settings } returns flowOf(YouTubeTrailerAuthSettings(isSignedIn = true))
        coEvery { cookieStore.currentYouTubeCookieHeader() } returns "SID=abc; HSID=def"
        coEvery {
            bundledHelper.resolve(any())
        } returns TrailerHelperResult.Failure(TrailerHelperFailureReason.ProcessFailed)

        val service = TrailerAvailabilityService(
            authDataStore = authDataStore,
            cookieStore = cookieStore,
            bundledTrailerHelper = bundledHelper,
            helperCache = cache()
        )

        val youtubeUrl = "https://www.youtube.com/watch?v=testvideo02"
        assertNull(service.resolveAuthenticatedYouTubePlayback(youtubeUrl))
        assertNull(service.resolveAuthenticatedYouTubePlayback(youtubeUrl))

        coVerify(exactly = 1) { bundledHelper.resolve(any()) }
    }

    @Test
    fun `signed in helper playback is returned and cached`() = runTest {
        val authDataStore = mockk<YouTubeTrailerAuthDataStore>()
        val cookieStore = mockk<YouTubeTrailerCookieStore>()
        val bundledHelper = mockk<BundledTrailerHelper>()
        val requestSlot = slot<TrailerHelperRequest>()
        every { authDataStore.settings } returns flowOf(YouTubeTrailerAuthSettings(isSignedIn = true))
        coEvery { cookieStore.currentYouTubeCookieHeader() } returns "SID=abc; HSID=def"
        coEvery {
            bundledHelper.resolve(capture(requestSlot))
        } returns TrailerHelperResult.Playback(
            TrailerHelperPlaybackResult(
                videoUrl = "https://video.example/stream.m3u8",
                audioUrl = "https://audio.example/stream.m4a",
                expiresAtEpochMs = 1_900_000_000_000L
            )
        )

        val service = TrailerAvailabilityService(
            authDataStore = authDataStore,
            cookieStore = cookieStore,
            bundledTrailerHelper = bundledHelper,
            helperCache = cache()
        )

        val youtubeUrl = "https://www.youtube.com/watch?v=testvideo03"
        val first = service.resolveAuthenticatedYouTubePlayback(youtubeUrl)
        val second = service.resolveAuthenticatedYouTubePlayback(youtubeUrl)

        assertEquals("https://video.example/stream.m3u8", first?.videoUrl)
        assertEquals("https://audio.example/stream.m4a", first?.audioUrl)
        assertEquals(first, second)
        assertEquals(youtubeUrl, requestSlot.captured.youtubeUrl)
        assertEquals("SID=abc; HSID=def", requestSlot.captured.cookieHeader)
        coVerify(exactly = 1) { bundledHelper.resolve(any()) }
    }

    private fun cache(): TrailerHelperCache {
        return TrailerHelperCache(
            clock = Clock.fixed(
                Instant.parse("2026-03-31T12:00:00Z"),
                ZoneOffset.UTC
            )
        )
    }
}
