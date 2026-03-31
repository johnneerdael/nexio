package com.nexio.tv.data.trailer.helper

import com.nexio.tv.data.local.YouTubeTrailerAuthDataStore
import com.nexio.tv.data.local.YouTubeTrailerAuthSettings
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class YouTubeTrailerAuthManagerTest {

    @Test
    fun `startDeviceFlow persists device session details`() = runTest {
        val authDataStore = mockk<YouTubeTrailerAuthDataStore>(relaxed = true)
        val tokenStore = mockk<YouTubeTrailerTokenStore>(relaxed = true)
        val service = mockk<YouTubeDeviceCodeAuthService>()
        val session = YouTubeDeviceCodeSession(
            deviceCode = "device-code",
            userCode = "ABCD-EFGH",
            verificationUrl = "https://example.com/verify",
            verificationUrlComplete = "https://example.com/verify?code=ABCD-EFGH",
            expiresInSeconds = 900,
            intervalSeconds = 5
        )
        coEvery { service.startDeviceFlow() } returns Result.success(session)
        every { authDataStore.settings } returns flowOf(YouTubeTrailerAuthSettings())

        val manager = YouTubeTrailerAuthManager(
            authDataStore = authDataStore,
            tokenStore = tokenStore,
            authService = service
        )

        val result = manager.startDeviceFlow()

        assertTrue(result.isSuccess)
        assertEquals(session, result.getOrNull())
        coVerify(exactly = 1) { authDataStore.saveDeviceFlow(session) }
        coVerify(exactly = 1) {
            authDataStore.updateSessionStatusMessage(any())
        }
    }

    @Test
    fun `pollDeviceToken stores approved token session and marks signed in`() = runTest {
        val authDataStore = mockk<YouTubeTrailerAuthDataStore>(relaxed = true)
        val tokenStore = mockk<YouTubeTrailerTokenStore>(relaxed = true)
        val service = mockk<YouTubeDeviceCodeAuthService>()
        val authSettings = YouTubeTrailerAuthSettings(
            isSignedIn = false,
            deviceCode = "device-code",
            userCode = "ABCD-EFGH",
            verificationUrl = "https://example.com/verify",
            verificationUrlComplete = "https://example.com/verify?code=ABCD-EFGH",
            deviceCodeExpiresAtEpochMs = 1_900_000_000_000L,
            pollIntervalSeconds = 5,
            statusMessage = "Waiting for approval"
        )
        val tokenSession = YouTubeTrailerTokenSession(
            accessToken = "access-token",
            refreshToken = "refresh-token",
            tokenType = "Bearer",
            expiresInSeconds = 3_600,
            delegatedPageId = "page-42",
            authUser = "0"
        )

        every { authDataStore.settings } returns flowOf(authSettings)
        coEvery { service.pollDeviceToken("device-code") } returns YouTubeTrailerTokenPollResult.Approved(
            tokenSession
        )

        val manager = YouTubeTrailerAuthManager(
            authDataStore = authDataStore,
            tokenStore = tokenStore,
            authService = service
        )

        val result = manager.pollDeviceToken()

        assertTrue(result is YouTubeTrailerTokenPollResult.Approved)
        coVerify(exactly = 1) { tokenStore.saveSession(tokenSession) }
        coVerify(exactly = 1) { authDataStore.clearDeviceFlow() }
        coVerify(exactly = 1) {
            authDataStore.markSignedIn(any())
        }
    }

    @Test
    fun `refreshSession refreshes the active access token when signed in`() = runTest {
        val authDataStore = mockk<YouTubeTrailerAuthDataStore>(relaxed = true)
        val tokenStore = mockk<YouTubeTrailerTokenStore>(relaxed = true)
        val service = mockk<YouTubeDeviceCodeAuthService>()
        every { authDataStore.settings } returns flowOf(
            YouTubeTrailerAuthSettings(
                isSignedIn = true,
                statusMessage = "Signed in"
            )
        )
        every { tokenStore.state } returns flowOf(
            YouTubeTrailerTokenState(
                accessToken = "access-token",
                refreshToken = "refresh-token",
                tokenType = "Bearer",
                expiresInSeconds = 3_600,
                createdAtEpochMs = 1_900_000_000_000L
            )
        )
        coEvery { service.refreshAccessToken("refresh-token") } returns Result.success(
            YouTubeTrailerTokenSession(
                accessToken = "new-access-token",
                refreshToken = "new-refresh-token",
                tokenType = "Bearer",
                expiresInSeconds = 3_600
            )
        )

        val manager = YouTubeTrailerAuthManager(
            authDataStore = authDataStore,
            tokenStore = tokenStore,
            authService = service
        )

        manager.refreshSession()

        coVerify(exactly = 1) { tokenStore.saveSession(any()) }
        coVerify(exactly = 1) { authDataStore.markSignedIn("Session refreshed") }
    }
}
