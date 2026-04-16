package com.nexio.tv.ui.screens.profile

import com.nexio.tv.core.profile.ProfileManager
import com.nexio.tv.core.sync.ProfileSyncService
import com.nexio.tv.data.remote.supabase.SupabaseProfilePinVerifyResult
import com.nexio.tv.domain.model.UserProfile
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ProfileSelectionViewModelTest {
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
    fun `successful unlock emits completion event`() = runTest(dispatcher) {
        val profileManager = profileManager()
        val profileSyncService = mockk<ProfileSyncService>()
        coEvery { profileSyncService.verifyProfilePin(2, "1234") } returns Result.success(
            SupabaseProfilePinVerifyResult(
                unlocked = true,
                retryAfterSeconds = 0,
                pinEnabled = true
            )
        )

        val viewModel = ProfileSelectionViewModel(profileManager, profileSyncService)
        val unlockedProfileId = CompletableDeferred<Int>()
        val collector = launch {
            viewModel.pinUnlockedProfileId.collect { if (!unlockedProfileId.isCompleted) unlockedProfileId.complete(it) }
        }
        runCurrent()

        viewModel.verifyPin(2, "1234")
        advanceUntilIdle()

        coVerify(exactly = 0) { profileManager.setActiveProfile(any()) }
        assertEquals(2, unlockedProfileId.await())
        collector.cancel()
        assertEquals(ProfileSelectionViewModel.PinVerificationState(), viewModel.pinState.value)
    }

    @Test
    fun `successful unlock emits completion even when profile is already active`() = runTest(dispatcher) {
        val profileManager = profileManager(MutableStateFlow(2))
        val profileSyncService = mockk<ProfileSyncService>()
        coEvery { profileSyncService.verifyProfilePin(2, "1234") } returns Result.success(
            SupabaseProfilePinVerifyResult(
                unlocked = true,
                retryAfterSeconds = 0,
                pinEnabled = true
            )
        )

        val viewModel = ProfileSelectionViewModel(profileManager, profileSyncService)
        val unlockedProfileId = CompletableDeferred<Int>()
        val collector = launch {
            viewModel.pinUnlockedProfileId.collect { if (!unlockedProfileId.isCompleted) unlockedProfileId.complete(it) }
        }
        runCurrent()

        viewModel.verifyPin(2, "1234")
        advanceUntilIdle()

        coVerify(exactly = 0) { profileManager.setActiveProfile(any()) }
        assertEquals(2, unlockedProfileId.await())
        collector.cancel()
        assertEquals(ProfileSelectionViewModel.PinVerificationState(), viewModel.pinState.value)
    }

    @Test
    fun `wrong PIN does not switch and sets Wrong PIN`() = runTest(dispatcher) {
        val profileManager = profileManager()
        val profileSyncService = mockk<ProfileSyncService>()
        coEvery { profileSyncService.verifyProfilePin(2, "0000") } returns Result.success(
            SupabaseProfilePinVerifyResult(
                unlocked = false,
                retryAfterSeconds = 0,
                pinEnabled = true
            )
        )

        val viewModel = ProfileSelectionViewModel(profileManager, profileSyncService)
        viewModel.verifyPin(2, "0000")
        advanceUntilIdle()

        coVerify(exactly = 0) { profileManager.setActiveProfile(any()) }
        assertTrue(viewModel.pinState.value.isError)
        assertEquals("Wrong PIN", viewModel.pinState.value.errorMessage)
    }

    @Test
    fun `rate limit exposes retry countdown and does not switch`() = runTest(dispatcher) {
        val profileManager = profileManager()
        val profileSyncService = mockk<ProfileSyncService>()
        coEvery { profileSyncService.verifyProfilePin(2, "1234") } returns Result.success(
            SupabaseProfilePinVerifyResult(
                unlocked = false,
                retryAfterSeconds = 5,
                pinEnabled = true
            )
        )

        val viewModel = ProfileSelectionViewModel(profileManager, profileSyncService)
        viewModel.verifyPin(2, "1234")
        runCurrent()

        coVerify(exactly = 0) { profileManager.setActiveProfile(any()) }
        assertFalse(viewModel.pinState.value.isError)
        assertEquals(5, viewModel.pinState.value.retryAfterSeconds)
    }

    @Test
    fun `duplicate verifyPin calls before dispatch only submit once`() = runTest(dispatcher) {
        val profileManager = profileManager()
        val profileSyncService = mockk<ProfileSyncService>()
        coEvery { profileSyncService.verifyProfilePin(2, "1234") } returns Result.success(
            SupabaseProfilePinVerifyResult(
                unlocked = false,
                retryAfterSeconds = 0,
                pinEnabled = true
            )
        )

        val viewModel = ProfileSelectionViewModel(profileManager, profileSyncService)
        viewModel.verifyPin(2, "1234")
        viewModel.verifyPin(2, "1234")
        runCurrent()

        coVerify(exactly = 1) { profileSyncService.verifyProfilePin(2, "1234") }
    }

    @Test
    fun `rate limited pin blocks additional submits until countdown clears`() = runTest(dispatcher) {
        val profileManager = profileManager()
        val profileSyncService = mockk<ProfileSyncService>()
        coEvery { profileSyncService.verifyProfilePin(2, "1234") } returns Result.success(
            SupabaseProfilePinVerifyResult(
                unlocked = false,
                retryAfterSeconds = 5,
                pinEnabled = true
            )
        )

        val viewModel = ProfileSelectionViewModel(profileManager, profileSyncService)
        viewModel.verifyPin(2, "1234")
        runCurrent()
        viewModel.verifyPin(2, "1234")
        runCurrent()

        coVerify(exactly = 1) { profileSyncService.verifyProfilePin(2, "1234") }
    }

    @Test
    fun `failed RPC sets Unable to verify PIN and does not switch`() = runTest(dispatcher) {
        val profileManager = profileManager()
        val profileSyncService = mockk<ProfileSyncService>()
        coEvery { profileSyncService.verifyProfilePin(2, "1234") } returns Result.failure(
            IllegalStateException("boom")
        )

        val viewModel = ProfileSelectionViewModel(profileManager, profileSyncService)
        viewModel.verifyPin(2, "1234")
        advanceUntilIdle()

        coVerify(exactly = 0) { profileManager.setActiveProfile(any()) }
        assertTrue(viewModel.pinState.value.isError)
        assertEquals("Unable to verify PIN", viewModel.pinState.value.errorMessage)
    }

    private fun profileManager(activeProfileId: MutableStateFlow<Int> = MutableStateFlow(1)): ProfileManager {
        val profiles = MutableStateFlow(
            listOf(
                UserProfile(id = 1, name = "Default", avatarColorHex = "#1E88E5"),
                UserProfile(id = 2, name = "Kids", avatarColorHex = "#EF5350", pinEnabled = true)
            )
        )
        return mockk {
            every { this@mockk.activeProfileId } returns activeProfileId
            every { this@mockk.profiles } returns profiles
            coEvery { this@mockk.setActiveProfile(any()) } answers {
                activeProfileId.value = firstArg()
            }
        }
    }
}
