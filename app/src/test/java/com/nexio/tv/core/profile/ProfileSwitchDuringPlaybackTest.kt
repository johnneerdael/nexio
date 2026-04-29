package com.nexio.tv.core.profile

import com.nexio.tv.core.integration.ProfileBoundaryException
import com.nexio.tv.core.integration.ProfileBoundaryViolation
import com.nexio.tv.core.playback.PlaybackOwnerContext
import com.nexio.tv.core.playback.PlaybackSessionRegistry
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class ProfileSwitchDuringPlaybackTest {
    @Test
    fun `setActiveProfile rejects switch while playback session active`() = runTest {
        val registry = PlaybackSessionRegistry()
        val manager = FakeProfileManager(initialProfileId = 1, playbackSessionRegistry = registry)
        manager.seedProfiles(listOf(1, 2))

        registry.register(
            PlaybackOwnerContext(
                ownerProfileId = 1,
                ownerSessionId = "session-1",
                startedAtEpochMs = 1L
            )
        )

        val ex = assertThrows(ProfileBoundaryException::class.java) {
            runBlocking { manager.setActiveProfile(2) }
        }
        assertEquals(ProfileBoundaryViolation.PROFILE_SWITCH_BLOCKED_BY_ACTIVE_PLAYBACK, ex.violation)
        assertEquals(1, manager.activeProfileId.value)
    }

    @Test
    fun `setActiveProfile succeeds once playback session ends`() = runTest {
        val registry = PlaybackSessionRegistry()
        val manager = FakeProfileManager(initialProfileId = 1, playbackSessionRegistry = registry)
        manager.seedProfiles(listOf(1, 2))

        val token = registry.register(
            PlaybackOwnerContext(
                ownerProfileId = 1,
                ownerSessionId = "session-1",
                startedAtEpochMs = 1L
            )
        )
        registry.unregister(token)

        manager.setActiveProfile(2)
        assertEquals(2, manager.activeProfileId.value)
    }
}
