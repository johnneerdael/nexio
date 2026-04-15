package com.nexio.tv.data.repository

import com.nexio.tv.core.profile.ProfileBoundary
import com.nexio.tv.core.profile.ProfileModeRouter
import com.nexio.tv.data.local.TraktAuthDataStore
import com.nexio.tv.data.remote.api.TraktApi
import com.nexio.tv.data.remote.dto.trakt.TraktTokenResponseDto
import com.nexio.tv.testutil.profileDataStoreFactoryForTest
import com.nexio.tv.testutil.testProfileManager
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.mockk.spyk
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import retrofit2.Response

class TraktAuthServiceTest {

    @Test
    fun `refresh token 400 clears auth state`() = runTest {
        val traktApi = mockk<TraktApi>()
        val profileManager = testProfileManager()
        val traktAuthDataStore = TraktAuthDataStore(
            factory = profileDataStoreFactoryForTest(),
            profileManager = profileManager
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
                requestGate = com.nexio.tv.data.remote.TraktRequestGate(),
                profileManager = profileManager,
                profileModeRouter = ProfileModeRouter(),
                profileBoundary = ProfileBoundary(profileManager, languageTagProvider = { "en" })
            )
        )
        every { service.hasRequiredCredentials() } returns true

        val refreshed = service.refreshTokenIfNeeded(force = true)

        assertFalse(refreshed)
        assertFalse(traktAuthDataStore.state.first().isAuthenticated)
    }

    @Test
    fun `refresh token 400 clears only routed secondary profile auth state`() = runTest {
        val traktApi = mockk<TraktApi>()
        val activeProfileId = MutableStateFlow(2)
        val profileManager = testProfileManager(activeProfileId)
        val traktAuthDataStore = TraktAuthDataStore(
            factory = profileDataStoreFactoryForTest(),
            profileManager = profileManager
        )
        val profileOneToken = TraktTokenResponseDto(
            accessToken = "profile-one-access",
            tokenType = "Bearer",
            expiresIn = 1,
            refreshToken = "profile-one-refresh",
            createdAt = 0L
        )
        val profileTwoToken = TraktTokenResponseDto(
            accessToken = "profile-two-access",
            tokenType = "Bearer",
            expiresIn = 1,
            refreshToken = "profile-two-refresh",
            createdAt = 0L
        )
        traktAuthDataStore.saveToken(profileOneToken, profileId = 1)
        traktAuthDataStore.saveToken(profileTwoToken, profileId = 2)
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
                requestGate = com.nexio.tv.data.remote.TraktRequestGate(),
                profileManager = profileManager,
                profileModeRouter = ProfileModeRouter(),
                profileBoundary = ProfileBoundary(profileManager, languageTagProvider = { "en" })
            )
        )
        every { service.hasRequiredCredentials() } returns true

        val refreshed = service.refreshTokenIfNeeded(force = true)

        assertFalse(refreshed)
        assertTrue(traktAuthDataStore.stateForProfile(1).first().isAuthenticated)
        assertFalse(traktAuthDataStore.stateForProfile(2).first().isAuthenticated)
    }
}
