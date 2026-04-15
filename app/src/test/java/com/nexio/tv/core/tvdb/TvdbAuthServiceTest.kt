package com.nexio.tv.core.tvdb

import com.nexio.tv.data.remote.api.TvdbApi
import com.nexio.tv.data.remote.api.TvdbLoginRequest
import com.nexio.tv.data.remote.api.TvdbLoginResponse
import io.mockk.Runs
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.just
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import retrofit2.Response

class TvdbAuthServiceTest {

    @Test
    fun `blank PIN is omitted from login payload`() = runTest {
        val tvdbApi = mockk<TvdbApi>()
        val tokenStore = mockk<TvdbTokenStore>()
        val service = TvdbAuthService(
            tvdbApi = tvdbApi,
            tokenStore = tokenStore,
            nowMillis = { 1_700_000_000_000L }
        )

        coEvery { tokenStore.saveToken(any(), any()) } just Runs
        coEvery { tvdbApi.login(any()) } returns Response.success(loginResponse("blank-pin-token"))

        val result = service.loginAndCacheToken(apiKey = "tvdb-key", pin = "   ")

        assertTrue(result is TvdbAuthResult.Valid)
        assertEquals("Bearer blank-pin-token", (result as TvdbAuthResult.Valid).authorizationHeader)
        coVerify(exactly = 1) {
            tvdbApi.login(TvdbLoginRequest(apikey = "tvdb-key", pin = null))
        }
        coVerify(exactly = 1) {
            tokenStore.saveToken(
                token = "blank-pin-token",
                expiresAtEpochMillis = match { it > 1_700_000_000_000L }
            )
        }
    }

    @Test
    fun `non blank PIN is sent and token plus expiry metadata are persisted`() = runTest {
        val tvdbApi = mockk<TvdbApi>()
        val tokenStore = mockk<TvdbTokenStore>()
        val service = TvdbAuthService(
            tvdbApi = tvdbApi,
            tokenStore = tokenStore,
            nowMillis = { 1_700_000_000_000L }
        )

        coEvery { tokenStore.saveToken(any(), any()) } just Runs
        coEvery { tvdbApi.login(any()) } returns Response.success(loginResponse("subscriber-token"))

        val result = service.loginAndCacheToken(apiKey = "tvdb-key", pin = " subscriber-pin ")

        assertTrue(result is TvdbAuthResult.Valid)
        assertEquals("Bearer subscriber-token", (result as TvdbAuthResult.Valid).authorizationHeader)
        coVerify(exactly = 1) {
            tvdbApi.login(TvdbLoginRequest(apikey = "tvdb-key", pin = "subscriber-pin"))
        }
        coVerify(exactly = 1) {
            tokenStore.saveToken(
                token = "subscriber-token",
                expiresAtEpochMillis = match { it > 1_700_000_000_000L }
            )
        }
        assertTrue(result.toString().contains("subscriber-token").not())
    }

    @Test
    fun `401 login returns invalid credentials and does not persist cached token`() = runTest {
        val tvdbApi = mockk<TvdbApi>()
        val tokenStore = mockk<TvdbTokenStore>(relaxed = true)
        val service = TvdbAuthService(
            tvdbApi = tvdbApi,
            tokenStore = tokenStore,
            nowMillis = { 1_700_000_000_000L }
        )
        val errorBody = """{"status":"failure"}"""
            .toResponseBody("application/json".toMediaType())

        coEvery { tvdbApi.login(any()) } returns Response.error(401, errorBody)

        val result = service.loginAndCacheToken(apiKey = "tvdb-key", pin = "subscriber-pin")

        assertEquals(TvdbValidationStatus.InvalidCredentials, result.status)
        coVerify(exactly = 0) { tokenStore.saveToken(any(), any()) }
    }

    private fun loginResponse(token: String): TvdbLoginResponse = TvdbLoginResponse(
        status = "success",
        data = TvdbLoginResponse.Data(token = token)
    )
}
