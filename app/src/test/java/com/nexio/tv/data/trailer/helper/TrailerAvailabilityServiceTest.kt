package com.nexio.tv.data.trailer.helper

import android.util.Log
import com.nexio.tv.data.local.YouTubeTrailerAuthDataStore
import com.nexio.tv.data.local.YouTubeTrailerAuthSettings
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.slot
import io.mockk.unmockkStatic
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.After
import org.junit.Before
import org.junit.Test

class TrailerAvailabilityServiceTest {

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

    @Test
    fun `signed out does not invoke helper`() = runTest {
        val authDataStore = mockk<YouTubeTrailerAuthDataStore>(relaxed = true)
        val tokenStore = mockk<YouTubeTrailerTokenStore>(relaxed = true)
        val authService = mockk<YouTubeDeviceCodeAuthService>(relaxed = true)
        val bundledHelper = mockk<BundledTrailerHelper>(relaxed = true)
        every { authDataStore.settings } returns flowOf(YouTubeTrailerAuthSettings(isSignedIn = false))

        val service = TrailerAvailabilityService(
            authDataStore = authDataStore,
            tokenStore = tokenStore,
            authService = authService,
            bundledTrailerHelper = bundledHelper,
            helperCache = cache()
        )

        val resolved = service.resolveAuthenticatedYouTubePlayback("https://www.youtube.com/watch?v=testvideo01")

        assertNull(resolved)
        coVerify(exactly = 0) { bundledHelper.resolve(any()) }
    }

    @Test
    fun `signed in helper failure caches miss and avoids repeated helper calls`() = runTest {
        val authDataStore = mockk<YouTubeTrailerAuthDataStore>(relaxed = true)
        val tokenStore = mockk<YouTubeTrailerTokenStore>()
        val authService = mockk<YouTubeDeviceCodeAuthService>()
        val bundledHelper = mockk<BundledTrailerHelper>()
        val savedSession = slot<YouTubeTrailerTokenSession>()
        every { authDataStore.settings } returns flowOf(YouTubeTrailerAuthSettings(isSignedIn = true))
        every {
            tokenStore.state
        } returns flowOf(
            YouTubeTrailerTokenState(
                accessToken = "access-token",
                refreshToken = "refresh-token",
                tokenType = "Bearer",
                authUser = "0"
            )
        )
        coEvery { bundledHelper.resolve(any()) } returns
            TrailerHelperResult.Failure(TrailerHelperFailureReason.ProcessFailed)

        val service = TrailerAvailabilityService(
            authDataStore = authDataStore,
            tokenStore = tokenStore,
            authService = authService,
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
        val authDataStore = mockk<YouTubeTrailerAuthDataStore>(relaxed = true)
        val tokenStore = mockk<YouTubeTrailerTokenStore>(relaxed = true)
        val authService = mockk<YouTubeDeviceCodeAuthService>(relaxed = true)
        val bundledHelper = mockk<BundledTrailerHelper>(relaxed = true)
        val requestSlot = slot<TrailerHelperRequest>()
        every { authDataStore.settings } returns flowOf(YouTubeTrailerAuthSettings(isSignedIn = true))
        every {
            tokenStore.state
        } returns flowOf(
            YouTubeTrailerTokenState(
                accessToken = "access-token",
                refreshToken = "refresh-token",
                tokenType = "Bearer",
                delegatedPageId = "page-42",
                authUser = "0"
            )
        )
        coEvery { bundledHelper.resolve(capture(requestSlot)) } returns
            TrailerHelperResult.Playback(
                TrailerHelperPlaybackResult(
                    videoUrl = "https://video.example/stream.m3u8",
                    audioUrl = "https://audio.example/stream.m4a",
                    expiresAtEpochMs = 1_900_000_000_000L
                )
            )

        val service = TrailerAvailabilityService(
            authDataStore = authDataStore,
            tokenStore = tokenStore,
            authService = authService,
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
        assertEquals("Bearer access-token", requestSlot.captured.authorizationHeader)
        assertEquals("page-42", requestSlot.captured.pageId)
        assertEquals("0", requestSlot.captured.authUser)
        coVerify(exactly = 1) { bundledHelper.resolve(any()) }
    }

    @Test
    fun `expiring access token is refreshed before helper invocation`() = runTest {
        val authDataStore = mockk<YouTubeTrailerAuthDataStore>(relaxed = true)
        val tokenStore = mockk<YouTubeTrailerTokenStore>(relaxed = true)
        val authService = mockk<YouTubeDeviceCodeAuthService>()
        val bundledHelper = mockk<BundledTrailerHelper>()
        val savedSession = slot<YouTubeTrailerTokenSession>()
        every { authDataStore.settings } returns flowOf(YouTubeTrailerAuthSettings(isSignedIn = true))
        every {
            tokenStore.state
        } returnsMany listOf(
            flowOf(
                YouTubeTrailerTokenState(
                    accessToken = "stale-token",
                    refreshToken = "refresh-token",
                    tokenType = "Bearer",
                    expiresInSeconds = 1,
                    createdAtEpochMs = System.currentTimeMillis() - 10_000L
                )
            ),
            flowOf(
                YouTubeTrailerTokenState(
                    accessToken = "fresh-token",
                    refreshToken = "refresh-token",
                    tokenType = "Bearer",
                    expiresInSeconds = 3600,
                    createdAtEpochMs = System.currentTimeMillis()
                )
            )
        )
        coEvery { authService.refreshAccessToken("refresh-token") } returns Result.success(
            YouTubeTrailerTokenSession(
                accessToken = "fresh-token",
                refreshToken = null,
                tokenType = "Bearer",
                expiresInSeconds = 3600
            )
        )
        coEvery { bundledHelper.resolve(any()) } returns TrailerHelperResult.Failure(
            TrailerHelperFailureReason.ProcessFailed
        )

        val service = TrailerAvailabilityService(
            authDataStore = authDataStore,
            tokenStore = tokenStore,
            authService = authService,
            bundledTrailerHelper = bundledHelper,
            helperCache = cache()
        )

        assertNull(service.resolveAuthenticatedYouTubePlayback("https://www.youtube.com/watch?v=testvideo04"))

        coVerify(exactly = 1) { authService.refreshAccessToken("refresh-token") }
        coVerify(exactly = 1) { tokenStore.saveSession(capture(savedSession)) }
        assertEquals("fresh-token", savedSession.captured.accessToken)
        assertEquals("refresh-token", savedSession.captured.refreshToken)
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
