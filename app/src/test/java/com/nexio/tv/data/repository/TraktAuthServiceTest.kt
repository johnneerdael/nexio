package com.nexio.tv.data.repository

import com.nexio.tv.data.local.TraktAuthDataStore
import com.nexio.tv.data.remote.api.TraktApi
import com.nexio.tv.data.remote.dto.trakt.TraktTokenResponseDto
import com.nexio.tv.testutil.profileDataStoreFactoryForTest
import com.nexio.tv.testutil.testProfileManager
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.mockk.spyk
import kotlinx.coroutines.flow.first
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
        val traktAuthDataStore = TraktAuthDataStore(
            factory = profileDataStoreFactoryForTest(),
            profileManager = testProfileManager()
        )
        traktAuthDataStore.saveToken(
            TraktTokenResponseDto(
                accessToken = "access",
                tokenType = "Bearer",
                expiresIn = 1,
                refreshToken = "refresh",
                createdAt = 0L
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
        assertFalse(traktAuthDataStore.state.first().isAuthenticated)
    }
}
