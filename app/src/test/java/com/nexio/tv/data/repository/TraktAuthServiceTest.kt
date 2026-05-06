package com.nexio.tv.data.repository

import com.nexio.tv.core.profile.ProfileBoundary
import com.nexio.tv.core.profile.ProfileModeRouter
import com.nexio.tv.data.integration.trakt.TraktIntegrationProvider
import com.nexio.tv.data.local.TraktAuthDataStore
import com.nexio.tv.data.remote.dto.trakt.TraktDeviceCodeResponseDto
import com.nexio.tv.data.remote.dto.trakt.TraktIdsDto
import com.nexio.tv.data.remote.dto.trakt.TraktTokenResponseDto
import com.nexio.tv.data.remote.dto.trakt.TraktUserDto
import com.nexio.tv.data.remote.dto.trakt.TraktUserSettingsResponseDto
import com.nexio.tv.domain.model.TrackingProvider
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
import org.junit.Assert.assertNull
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import retrofit2.Response

class TraktAuthServiceTest {

    @Test
    fun `refresh token 400 clears auth state`() = runTest {
        val traktIntegrationProvider = mockk<TraktIntegrationProvider>()
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
            traktIntegrationProvider.refreshToken(any(), any())
        } returns Response.error(
            400,
            "{}".toResponseBody("application/json".toMediaType())
        )

        val service = spyk(
            TraktAuthService(
                traktIntegrationProvider = lazyProvider(traktIntegrationProvider),
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
        val traktIntegrationProvider = mockk<TraktIntegrationProvider>()
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
            traktIntegrationProvider.refreshToken(any(), any())
        } returns Response.error(
            400,
            "{}".toResponseBody("application/json".toMediaType())
        )

        val service = spyk(
            TraktAuthService(
                traktIntegrationProvider = lazyProvider(traktIntegrationProvider),
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

    @Test
    fun `poll token saves token and user to same captured profile after profile switch`() = runTest {
        val traktIntegrationProvider = mockk<TraktIntegrationProvider>()
        val activeProfileId = MutableStateFlow(2)
        val profileManager = testProfileManager(activeProfileId)
        val traktAuthDataStore = TraktAuthDataStore(
            factory = profileDataStoreFactoryForTest(),
            profileManager = profileManager
        )
        traktAuthDataStore.saveDeviceFlow(
            TraktDeviceCodeResponseDto(
                deviceCode = "device",
                userCode = "user",
                verificationUrl = "https://trakt.tv/activate",
                expiresIn = 600,
                interval = 5
            ),
            profileId = 2
        )
        coEvery {
            traktIntegrationProvider.requestDeviceToken(any(), any())
        } answers {
            activeProfileId.value = 3
            Response.success(
                TraktTokenResponseDto(
                    accessToken = "access",
                    tokenType = "Bearer",
                    expiresIn = 3600,
                    refreshToken = "refresh",
                    createdAt = System.currentTimeMillis() / 1000L
                )
            )
        }
        coEvery {
            traktIntegrationProvider.getUserSettings(any())
        } returns Response.success(
            TraktUserSettingsResponseDto(
                user = TraktUserDto(
                    username = "profile-two-user",
                    ids = TraktIdsDto(slug = "profile-two")
                )
            )
        )

        val service = spyk(
            TraktAuthService(
                traktIntegrationProvider = lazyProvider(traktIntegrationProvider),
                traktAuthDataStore = traktAuthDataStore,
                requestGate = com.nexio.tv.data.remote.TraktRequestGate(),
                profileManager = profileManager,
                profileModeRouter = ProfileModeRouter(),
                profileBoundary = ProfileBoundary(profileManager, languageTagProvider = { "en" })
            )
        )
        every { service.hasRequiredCredentials() } returns true

        service.pollDeviceToken()

        assertEquals("profile-two-user", traktAuthDataStore.stateForProfile(2).first().username)
        assertNull(traktAuthDataStore.stateForProfile(3).first().username)
    }

    @Test
    fun `mutation account scope stays stable after first user settings hydration`() = runTest {
        val traktIntegrationProvider = mockk<TraktIntegrationProvider>()
        val profileManager = testProfileManager()
        val traktAuthDataStore = TraktAuthDataStore(
            factory = profileDataStoreFactoryForTest(),
            profileManager = profileManager
        )
        traktAuthDataStore.saveToken(
            TraktTokenResponseDto(
                accessToken = "access",
                tokenType = "Bearer",
                expiresIn = 3600,
                refreshToken = "refresh",
                createdAt = System.currentTimeMillis() / 1000L
            ),
            clearAccountIdentity = true
        )
        coEvery {
            traktIntegrationProvider.getUserSettings(any())
        } returns Response.success(
            TraktUserSettingsResponseDto(
                user = TraktUserDto(
                    username = "profile-two-user",
                    ids = TraktIdsDto(slug = "profile-two")
                )
            )
        )
        val service = spyk(
            TraktAuthService(
                traktIntegrationProvider = lazyProvider(traktIntegrationProvider),
                traktAuthDataStore = traktAuthDataStore,
                requestGate = com.nexio.tv.data.remote.TraktRequestGate(),
                profileManager = profileManager,
                profileModeRouter = ProfileModeRouter(),
                profileBoundary = ProfileBoundary(profileManager, languageTagProvider = { "en" })
            )
        )

        val first = service.mutationAccountScopedSession(TrackingAuthSession(TrackingProvider.TRAKT, 1))
        val second = service.mutationAccountScopedSession(TrackingAuthSession(TrackingProvider.TRAKT, 1))

        assertEquals(first.credentialHash, second.credentialHash)
        assertEquals("profile-two", traktAuthDataStore.stateForProfile(1).first().userSlug)
    }

    private fun lazyProvider(
        traktIntegrationProvider: TraktIntegrationProvider
    ): dagger.Lazy<TraktIntegrationProvider> =
        object : dagger.Lazy<TraktIntegrationProvider> {
            override fun get(): TraktIntegrationProvider = traktIntegrationProvider
        }
}
