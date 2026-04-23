package com.nexio.tv.ui.screens.settings

import com.nexio.tv.core.auth.AuthManager
import com.nexio.tv.core.auth.hasLiveFullAccountSyncSession
import com.nexio.tv.core.profile.ProfileManager
import com.nexio.tv.core.sync.ProfileSettingsSyncService
import com.nexio.tv.core.sync.ProfileSyncService
import com.nexio.tv.domain.model.AuthState
import com.nexio.tv.domain.model.UserProfile
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.coVerifyOrder
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class SettingsViewModelSyncTest {
    private val dispatcher = StandardTestDispatcher()

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `sync now pulls remote profiles before pushing local metadata`() = runTest(dispatcher) {
        val activeProfileId = MutableStateFlow(3)
        val authManager = authManager(authState = AuthState.FullAccount("user-1", "user@example.com"))
        val profileSyncService = mockk<ProfileSyncService>()
        val profileSettingsSyncService = mockk<ProfileSettingsSyncService>()
        val profileManager = profileManager(activeProfileId)

        coEvery { profileSyncService.pullFromRemote() } answers {
            activeProfileId.value = 1
            Result.success(emptyList())
        }
        coEvery { profileSyncService.pushToRemote() } returns Result.success(Unit)
        coEvery { profileSettingsSyncService.pushBlobForProfile(1) } returns Result.success(Unit)

        val viewModel = SettingsViewModel(
            authManager = authManager,
            profileSyncService = profileSyncService,
            profileSettingsSyncService = profileSettingsSyncService,
            profileManager = profileManager
        )

        viewModel.triggerSyncNow()
        advanceUntilIdle()

        coVerifyOrder {
            profileSyncService.pullFromRemote()
            profileSyncService.pushToRemote()
            profileSettingsSyncService.pushBlobForProfile(1)
        }
    }

    @Test
    fun `sync now does not re-push stale profile metadata when remote pull fails`() = runTest(dispatcher) {
        val activeProfileId = MutableStateFlow(3)
        val authManager = authManager(authState = AuthState.FullAccount("user-1", "user@example.com"))
        val profileSyncService = mockk<ProfileSyncService>()
        val profileSettingsSyncService = mockk<ProfileSettingsSyncService>()
        val profileManager = profileManager(activeProfileId)

        coEvery { profileSyncService.pullFromRemote() } returns Result.failure(IllegalStateException("offline"))
        coEvery { profileSettingsSyncService.pushBlobForProfile(3) } returns Result.success(Unit)

        val viewModel = SettingsViewModel(
            authManager = authManager,
            profileSyncService = profileSyncService,
            profileSettingsSyncService = profileSettingsSyncService,
            profileManager = profileManager
        )

        viewModel.triggerSyncNow()
        advanceUntilIdle()

        coVerify(exactly = 0) { profileSyncService.pushToRemote() }
        coVerify(exactly = 1) { profileSettingsSyncService.pushBlobForProfile(3) }
    }

    @Test
    fun `sync now is a no-op without a full account session`() = runTest(dispatcher) {
        val activeProfileId = MutableStateFlow(3)
        val authManager = authManager(authState = AuthState.SessionLost)
        val profileSyncService = mockk<ProfileSyncService>(relaxed = true)
        val profileSettingsSyncService = mockk<ProfileSettingsSyncService>(relaxed = true)
        val profileManager = profileManager(activeProfileId)

        val viewModel = SettingsViewModel(
            authManager = authManager,
            profileSyncService = profileSyncService,
            profileSettingsSyncService = profileSettingsSyncService,
            profileManager = profileManager
        )

        viewModel.triggerSyncNow()
        advanceUntilIdle()

        coVerify(exactly = 0) { profileSyncService.pullFromRemote() }
        coVerify(exactly = 0) { profileSyncService.pushToRemote() }
        coVerify(exactly = 0) { profileSettingsSyncService.pushBlobForProfile(any()) }
    }

    @Test
    fun `sync now is a no-op without a live session user id`() = runTest(dispatcher) {
        val activeProfileId = MutableStateFlow(3)
        val authManager = authManager(
            authState = AuthState.FullAccount("user-1", "user@example.com"),
            currentSessionUserId = null
        )
        val profileSyncService = mockk<ProfileSyncService>(relaxed = true)
        val profileSettingsSyncService = mockk<ProfileSettingsSyncService>(relaxed = true)
        val profileManager = profileManager(activeProfileId)

        val viewModel = SettingsViewModel(
            authManager = authManager,
            profileSyncService = profileSyncService,
            profileSettingsSyncService = profileSettingsSyncService,
            profileManager = profileManager
        )

        viewModel.triggerSyncNow()
        advanceUntilIdle()

        coVerify(exactly = 0) { profileSyncService.pullFromRemote() }
        coVerify(exactly = 0) { profileSyncService.pushToRemote() }
        coVerify(exactly = 0) { profileSettingsSyncService.pushBlobForProfile(any()) }
    }

    @Test
    fun `confirm delete profile enables remote sync for a live full account session`() = runTest(dispatcher) {
        val authManager = authManager(authState = AuthState.FullAccount("user-1", "user@example.com"))
        val profileSyncService = mockk<ProfileSyncService>(relaxed = true)
        val profileSettingsSyncService = mockk<ProfileSettingsSyncService>(relaxed = true)
        val profileManager = profileManager(MutableStateFlow(3))
        val profile = UserProfile(id = 3, name = "Profile 3", avatarColorHex = "#8E24AA")
        coEvery { profileManager.deleteProfile(profile.id, syncRemoteDelete = true) } returns true

        val viewModel = SettingsViewModel(
            authManager = authManager,
            profileSyncService = profileSyncService,
            profileSettingsSyncService = profileSettingsSyncService,
            profileManager = profileManager
        )

        viewModel.requestDeleteProfile(profile)
        viewModel.confirmDeleteProfile()
        advanceUntilIdle()

        coVerify(exactly = 1) { profileManager.deleteProfile(profile.id, syncRemoteDelete = true) }
    }

    @Test
    fun `confirm delete profile skips remote sync without a live full account session`() = runTest(dispatcher) {
        val authManager = authManager(authState = AuthState.SessionLost)
        val profileSyncService = mockk<ProfileSyncService>(relaxed = true)
        val profileSettingsSyncService = mockk<ProfileSettingsSyncService>(relaxed = true)
        val profileManager = profileManager(MutableStateFlow(3))
        val profile = UserProfile(id = 3, name = "Profile 3", avatarColorHex = "#8E24AA")
        coEvery { profileManager.deleteProfile(profile.id, syncRemoteDelete = false) } returns true

        val viewModel = SettingsViewModel(
            authManager = authManager,
            profileSyncService = profileSyncService,
            profileSettingsSyncService = profileSettingsSyncService,
            profileManager = profileManager
        )

        viewModel.requestDeleteProfile(profile)
        viewModel.confirmDeleteProfile()
        advanceUntilIdle()

        coVerify(exactly = 1) { profileManager.deleteProfile(profile.id, syncRemoteDelete = false) }
    }

    private fun authManager(
        authState: AuthState,
        currentSessionUserId: String? = (authState as? AuthState.FullAccount)?.userId
    ): AuthManager {
        return mockk {
            every { this@mockk.authState } returns MutableStateFlow(authState)
            every { this@mockk.isAuthenticated } returns (authState is AuthState.FullAccount)
            every { this@mockk.sessionUserId } returns MutableStateFlow(currentSessionUserId)
            every { this@mockk.currentSessionUserId } returns currentSessionUserId
            every {
                this@mockk.hasSyncSession
            } returns hasLiveFullAccountSyncSession(authState, currentSessionUserId)
        }
    }

    private fun profileManager(activeProfileId: MutableStateFlow<Int>): ProfileManager {
        return mockk {
            every { this@mockk.activeProfileId } returns activeProfileId
            every { this@mockk.profiles } returns MutableStateFlow(
                listOf(
                    UserProfile(id = 1, name = "Default", avatarColorHex = "#1E88E5"),
                    UserProfile(id = 3, name = "Profile 3", avatarColorHex = "#8E24AA")
                )
            )
        }
    }
}
