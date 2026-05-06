package com.nexio.tv.data.repository

import com.nexio.tv.core.profile.ProfileBoundary
import com.nexio.tv.core.profile.ProfileModeRouter
import com.nexio.tv.data.integration.simkl.SimklAuthIntegrationProvider
import com.nexio.tv.data.local.SimklAuthDataStore
import com.nexio.tv.data.remote.SimklRequestGate
import com.nexio.tv.data.remote.dto.simkl.SimklAccountDto
import com.nexio.tv.data.remote.dto.simkl.SimklPinResponseDto
import com.nexio.tv.data.remote.dto.simkl.SimklPinStatusResponseDto
import com.nexio.tv.data.remote.dto.simkl.SimklUserDto
import com.nexio.tv.data.remote.dto.simkl.SimklUserSettingsResponseDto
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
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import retrofit2.Response

class SimklAuthServiceTest {
    @Test
    fun `poll pin saves token and user to same captured profile after profile switch`() = runTest {
        val simklAuthIntegrationProvider = mockk<SimklAuthIntegrationProvider>()
        val activeProfileId = MutableStateFlow(2)
        val profileManager = testProfileManager(activeProfileId)
        val simklAuthDataStore = SimklAuthDataStore(
            factory = profileDataStoreFactoryForTest(),
            profileManager = profileManager
        )
        simklAuthDataStore.saveDeviceFlow(
            SimklPinResponseDto(
                result = "OK",
                deviceCode = "device",
                userCode = "user",
                verificationUrl = "https://simkl.com/pin",
                expiresIn = 600,
                interval = 5
            ),
            profileId = 2
        )
        coEvery {
            simklAuthIntegrationProvider.getPinStatus("user")
        } answers {
            activeProfileId.value = 3
            Response.success(
                SimklPinStatusResponseDto(
                    result = "OK",
                    accessToken = "access"
                )
            )
        }
        coEvery {
            simklAuthIntegrationProvider.getUserSettings(any(), any())
        } returns Response.success(
            SimklUserSettingsResponseDto(
                user = SimklUserDto(name = "profile-two-user"),
                account = SimklAccountDto(id = 2L, type = "standard")
            )
        )

        val service = spyk(
            SimklAuthService(
                simklAuthIntegrationProvider = simklAuthIntegrationProvider,
                simklAuthDataStore = simklAuthDataStore,
                requestGate = SimklRequestGate(),
                profileManager = profileManager,
                profileModeRouter = ProfileModeRouter(),
                profileBoundary = ProfileBoundary(profileManager, languageTagProvider = { "en" })
            )
        )
        every { service.hasRequiredCredentials() } returns true

        service.pollPin()

        assertEquals("profile-two-user", simklAuthDataStore.stateForProfile(2).first().username)
        assertNull(simklAuthDataStore.stateForProfile(3).first().username)
    }

    @Test
    fun `mutation account scope stays stable after first user settings hydration`() = runTest {
        val simklAuthIntegrationProvider = mockk<SimklAuthIntegrationProvider>()
        val profileManager = testProfileManager()
        val simklAuthDataStore = SimklAuthDataStore(
            factory = profileDataStoreFactoryForTest(),
            profileManager = profileManager
        )
        simklAuthDataStore.saveAccessToken(
            accessToken = "access",
            clearAccountIdentity = true
        )
        coEvery {
            simklAuthIntegrationProvider.getUserSettings(any(), any())
        } returns Response.success(
            SimklUserSettingsResponseDto(
                user = SimklUserDto(name = "profile-two-user"),
                account = SimklAccountDto(id = 2L, type = "standard")
            )
        )

        val service = spyk(
            SimklAuthService(
                simklAuthIntegrationProvider = simklAuthIntegrationProvider,
                simklAuthDataStore = simklAuthDataStore,
                requestGate = SimklRequestGate(),
                profileManager = profileManager,
                profileModeRouter = ProfileModeRouter(),
                profileBoundary = ProfileBoundary(profileManager, languageTagProvider = { "en" })
            )
        )

        val first = service.mutationAccountScopedSession(TrackingAuthSession(TrackingProvider.SIMKL, 1))
        val second = service.mutationAccountScopedSession(TrackingAuthSession(TrackingProvider.SIMKL, 1))

        assertEquals(first.credentialHash, second.credentialHash)
        assertEquals(2L, simklAuthDataStore.stateForProfile(1).first().accountId)
    }
}
