package com.nexio.tv.data.repository

import com.nexio.tv.data.local.TraktAuthDataStore
import com.nexio.tv.data.local.TraktAuthState
import com.nexio.tv.data.remote.api.TraktApi
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.spyk
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertFalse
import org.junit.Test
import retrofit2.Response

class TraktAuthServiceTest {

    @Test
    fun `refresh token 400 clears auth state`() = runTest {
        val traktApi = mockk<TraktApi>()
        val traktAuthDataStore = mockk<TraktAuthDataStore>(relaxed = true)
        every {
            traktAuthDataStore.state
        } returns flowOf(
            TraktAuthState(
                accessToken = "access",
                refreshToken = "refresh",
                createdAt = 0L,
                expiresIn = 1
            )
        )
        coEvery {
            traktApi.refreshToken(any())
        } returns Response.error(
            400,
            "{}".toResponseBody("application/json".toMediaType())
        )

        val service = spyk(
            TraktAuthService(
                traktApi = traktApi,
                traktAuthDataStore = traktAuthDataStore,
                requestGate = com.nexio.tv.data.remote.TraktRequestGate()
            )
        )
        every { service.hasRequiredCredentials() } returns true

        val refreshed = service.refreshTokenIfNeeded(force = true)

        assertFalse(refreshed)
        coVerify(exactly = 1) { traktAuthDataStore.clearAuth() }
    }
}
