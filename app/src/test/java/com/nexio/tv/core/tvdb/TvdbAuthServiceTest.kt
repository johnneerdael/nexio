package com.nexio.tv.core.tvdb

import com.nexio.tv.data.local.TvdbSettings
import com.nexio.tv.data.local.TvdbSettingsDataStore
import com.nexio.tv.data.local.TvdbTokenState
import io.mockk.Runs
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.just
import io.mockk.mockk
import io.mockk.every
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TvdbAuthServiceTest {

    @Test
    fun `blank PIN is trimmed before token request`() = runTest {
        val loginGateway = mockk<TvdbLoginGateway>()
        val tokenStore = mockk<TvdbTokenStore>()
        val service = TvdbAuthService(
            loginGateway = loginGateway,
            tokenStore = tokenStore,
            nowMillis = { 1_700_000_000_000L }
        )

        coEvery { tokenStore.saveToken(any(), any()) } just Runs
        coEvery {
            loginGateway.requestToken("tvdb-key", "", any())
        } returns validAuth("blank-pin-token")

        val result = service.loginAndCacheToken(apiKey = "tvdb-key", pin = "   ")

        assertTrue(result is TvdbAuthResult.Valid)
        assertEquals("Bearer blank-pin-token", (result as TvdbAuthResult.Valid).authorizationHeader)
        coVerify(exactly = 1) {
            loginGateway.requestToken("tvdb-key", "", any())
        }
        coVerify(exactly = 1) {
            tokenStore.saveToken(
                token = "blank-pin-token",
                expiresAtEpochMillis = match { it > 1_700_000_000_000L }
            )
        }
    }

    @Test
    fun `non blank PIN is trimmed and token plus expiry metadata are persisted`() = runTest {
        val loginGateway = mockk<TvdbLoginGateway>()
        val tokenStore = mockk<TvdbTokenStore>()
        val service = TvdbAuthService(
            loginGateway = loginGateway,
            tokenStore = tokenStore,
            nowMillis = { 1_700_000_000_000L }
        )

        coEvery { tokenStore.saveToken(any(), any()) } just Runs
        coEvery {
            loginGateway.requestToken("tvdb-key", "subscriber-pin", any())
        } returns validAuth("subscriber-token")

        val result = service.loginAndCacheToken(apiKey = "tvdb-key", pin = " subscriber-pin ")

        assertTrue(result is TvdbAuthResult.Valid)
        assertEquals("Bearer subscriber-token", (result as TvdbAuthResult.Valid).authorizationHeader)
        coVerify(exactly = 1) {
            loginGateway.requestToken("tvdb-key", "subscriber-pin", any())
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
        val loginGateway = mockk<TvdbLoginGateway>()
        val tokenStore = mockk<TvdbTokenStore>(relaxed = true)
        val service = TvdbAuthService(
            loginGateway = loginGateway,
            tokenStore = tokenStore,
            nowMillis = { 1_700_000_000_000L }
        )

        coEvery {
            loginGateway.requestToken("tvdb-key", "subscriber-pin", any())
        } returns TvdbAuthResult.InvalidCredentials("Invalid TVDB credentials")

        val result = service.loginAndCacheToken(apiKey = "tvdb-key", pin = "subscriber-pin")

        assertEquals(TvdbValidationStatus.INVALID, result.status)
        coVerify(exactly = 0) { tokenStore.saveToken(any(), any()) }
    }

    @Test
    fun `HTTP 500 login records fallback active and preserves cached token state`() = runTest {
        val loginGateway = mockk<TvdbLoginGateway>()
        val settingsDataStore = mockk<TvdbSettingsDataStore>(relaxed = true)
        val tokenStore = mockk<TvdbTokenStore>(relaxed = true)
        val service = TvdbAuthService(
            loginGateway = loginGateway,
            settingsDataStore = settingsDataStore,
            tokenStore = tokenStore,
            nowMillis = { 1_700_000_000_000L }
        )

        coEvery {
            loginGateway.requestToken("tvdb-key", "subscriber-pin", any())
        } returns TvdbAuthResult.AuthUnavailable("TVDB login failed with HTTP 500")

        val result = service.validateCredentialsResult(apiKey = "tvdb-key", subscriberPin = "subscriber-pin")

        assertTrue(result is TvdbAuthResult.AuthUnavailable)
        assertEquals(TvdbValidationStatus.FALLBACK_ACTIVE, result.status)
        coVerify(exactly = 0) { tokenStore.clear() }
        coVerify(exactly = 1) {
            settingsDataStore.saveValidationFailure(
                status = TvdbValidationStatus.FALLBACK_ACTIVE,
                lastFailure = "TVDB login failed with HTTP 500"
            )
        }
        coVerify(exactly = 0) {
            settingsDataStore.saveValidationFailure(
                status = TvdbValidationStatus.INVALID,
                lastFailure = any()
            )
        }
    }

    @Test
    fun `IOException during token refresh records fallback active and preserves cached token state`() = runTest {
        val loginGateway = mockk<TvdbLoginGateway>()
        val settingsDataStore = mockk<TvdbSettingsDataStore>(relaxed = true)
        val tokenStore = mockk<TvdbTokenStore>(relaxed = true)
        val service = TvdbAuthService(
            loginGateway = loginGateway,
            settingsDataStore = settingsDataStore,
            tokenStore = tokenStore,
            nowMillis = { 1_700_000_000_000L }
        )
        every { settingsDataStore.settings } returns flowOf(
            TvdbSettings(
                enabled = true,
                apiKey = "tvdb-key",
                subscriberPin = "subscriber-pin",
                validationStatus = TvdbValidationStatus.VALID
            )
        )
        every { tokenStore.tokenState } returns flowOf(
            TvdbTokenState(
                token = "cached-token",
                expiresAtEpochMs = 1_700_000_000_001L,
                credentialFingerprint = "tvdb-key:subscriber-pin".hashCode().toString()
            )
        )
        coEvery {
            loginGateway.requestToken("tvdb-key", "subscriber-pin", any())
        } returns TvdbAuthResult.AuthUnavailable("TVDB login failed: IOException")

        val result = service.bearerToken()

        assertEquals(null, result)
        coVerify(exactly = 0) { tokenStore.clear() }
        coVerify(exactly = 1) {
            settingsDataStore.saveValidationFailure(
                status = TvdbValidationStatus.FALLBACK_ACTIVE,
                lastFailure = "TVDB login failed: IOException"
            )
        }
        coVerify(exactly = 0) {
            settingsDataStore.saveValidationFailure(
                status = TvdbValidationStatus.INVALID,
                lastFailure = any()
            )
        }
    }

    private fun validAuth(token: String): TvdbAuthResult.Valid = TvdbAuthResult.Valid(
        authorizationHeader = "Bearer $token",
        expiresAtEpochMillis = 1_700_000_100_000L
    )
}
