package com.nexio.tv.ui.screens.profile

import com.nexio.tv.core.integration.IntegrationProvider
import com.nexio.tv.core.integration.ProfileBoundaryException
import com.nexio.tv.core.integration.ProfileBoundaryViolation
import com.nexio.tv.core.integration.ProviderAccountRef
import com.nexio.tv.core.playback.PlaybackOwnerContext
import com.nexio.tv.core.playback.PlaybackSessionRegistry
import com.nexio.tv.core.profile.ProfileManager
import com.nexio.tv.core.sync.ProfileSyncService
import com.nexio.tv.domain.model.UserProfile
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Before
import org.junit.Test

/**
 * Verifies that ProfileSelectionViewModel.selectProfile catches the
 * ProfileBoundaryException(PROFILE_SWITCH_BLOCKED_BY_ACTIVE_PLAYBACK) thrown by
 * ProfileManager.setActiveProfile when playback is active, and surfaces it as
 * an event on switchBlockedByPlayback without crashing the coroutine.
 *
 * This mirrors the runtime contract enforced by FakeProfileManager /
 * ProfileManager when PlaybackSessionRegistry has an active owner.
 */
class ProfileSelectionViewModelSwitchDuringPlaybackTest {
    @Before fun setMainDispatcher() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
    }

    @After fun resetMainDispatcher() {
        Dispatchers.resetMain()
    }

    @Test
    fun `selectProfile during active playback emits switchBlockedByPlayback without crashing`() = runTest {
        // Simulate the registry+owner situation that would cause the real
        // ProfileManager to refuse the switch.
        val registry = PlaybackSessionRegistry()
        registry.register(
            PlaybackOwnerContext(
                ownerProfileId = 1,
                ownerSessionId = "session-1",
                traktAccount = ProviderAccountRef(IntegrationProvider.TRAKT, "h", null),
                simklAccount = null,
                startedAtEpochMs = 1L
            )
        )

        val activeProfileId = MutableStateFlow(1)
        val profiles = MutableStateFlow(
            listOf(
                UserProfile(id = 1, name = "Default", avatarColorHex = "#1E88E5"),
                UserProfile(id = 2, name = "Kids", avatarColorHex = "#EF5350")
            )
        )
        val profileManager = mockk<ProfileManager> {
            every { this@mockk.activeProfileId } returns activeProfileId
            every { this@mockk.profiles } returns profiles
            coEvery { setActiveProfile(any()) } answers {
                val target = firstArg<Int>()
                if (registry.activeOwner() != null && activeProfileId.value != target) {
                    throw ProfileBoundaryException(
                        ProfileBoundaryViolation.PROFILE_SWITCH_BLOCKED_BY_ACTIVE_PLAYBACK,
                        "blocked by active playback"
                    )
                }
                activeProfileId.value = target
            }
        }
        val syncService = mockk<ProfileSyncService>(relaxed = true)
        val vm = ProfileSelectionViewModel(profileManager = profileManager, profileSyncService = syncService)

        // Subscribe before triggering — SharedFlow without replay won't redeliver to late collectors.
        val received = CompletableDeferred<Unit>()
        val collector = launch {
            vm.switchBlockedByPlayback.collect {
                if (!received.isCompleted) received.complete(Unit)
            }
        }
        runCurrent()

        vm.selectProfile(profileId = 2)  // must not crash
        advanceUntilIdle()

        // VM must surface the rejection as a blocked event.
        assert(received.isCompleted) { "expected switchBlockedByPlayback to fire" }
        // Confirm the active profile did NOT change.
        assert(activeProfileId.value == 1) {
            "active profile must not change on rejected switch, got=${activeProfileId.value}"
        }
        collector.cancel()
    }
}
