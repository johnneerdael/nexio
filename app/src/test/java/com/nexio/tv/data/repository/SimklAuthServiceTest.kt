package com.nexio.tv.data.repository

import com.nexio.tv.core.profile.ProfileBoundary
import com.nexio.tv.core.profile.ProfileModeRouter
import com.nexio.tv.data.local.SimklAuthDataStore
import com.nexio.tv.data.remote.SimklRequestGate
import com.nexio.tv.data.remote.api.SimklApi
import com.nexio.tv.data.remote.dto.simkl.SimklAccountDto
import com.nexio.tv.data.remote.dto.simkl.SimklPinResponseDto
import com.nexio.tv.data.remote.dto.simkl.SimklPinStatusResponseDto
import com.nexio.tv.data.remote.dto.simkl.SimklUserDto
import com.nexio.tv.data.remote.dto.simkl.SimklUserSettingsResponseDto
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
        val simklApi = mockk<SimklApi>()
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
            simklApi.getPinStatus("user")
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
            simklApi.getUserSettings(any())
        } returns Response.success(
            SimklUserSettingsResponseDto(
                user = SimklUserDto(name = "profile-two-user"),
                account = SimklAccountDto(id = 2L, type = "standard")
            )
        )

        val service = spyk(
            SimklAuthService(
                simklApi = simklApi,
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
}
