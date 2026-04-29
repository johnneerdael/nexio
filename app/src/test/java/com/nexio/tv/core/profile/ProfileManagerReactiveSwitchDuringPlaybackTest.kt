package com.nexio.tv.core.profile

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * F-F-04 pin: when playback is active, a reactive profile-switch is enqueued in
 * `pendingActiveProfileId` rather than dropped. When playback transitions to idle,
 * the pending switch drains and the active profile updates.
 */
class ProfileManagerReactiveSwitchDuringPlaybackTest {

    @Test
    fun `incoming switch during playback is enqueued, not applied`() {
        val policy = ProfileSwitchDeferralPolicy(initialProfileId = 1)

        val applied = policy.onIncomingSwitch(targetProfileId = 2, hasActivePlayback = true)

        assertEquals("policy must NOT apply switch during playback", false, applied)
        assertEquals("pending must hold target", 2, policy.pendingActiveProfileId)
        assertEquals("active stays at 1", 1, policy.activeProfileId)
    }

    @Test
    fun `playback idle transition drains the pending switch`() {
        val policy = ProfileSwitchDeferralPolicy(initialProfileId = 1)
        policy.onIncomingSwitch(targetProfileId = 2, hasActivePlayback = true)

        val drainedTo = policy.onPlaybackIdle()

        assertEquals("drain returns the previously-pending id", 2, drainedTo)
        assertEquals("active updated", 2, policy.activeProfileId)
        assertNull("pending cleared", policy.pendingActiveProfileId)
    }

    @Test
    fun `incoming switch with no playback applies immediately`() {
        val policy = ProfileSwitchDeferralPolicy(initialProfileId = 1)

        val applied = policy.onIncomingSwitch(targetProfileId = 2, hasActivePlayback = false)

        assertEquals("apply immediately when no playback", true, applied)
        assertEquals("active updated", 2, policy.activeProfileId)
        assertNull(policy.pendingActiveProfileId)
    }

    @Test
    fun `playback idle with no pending is a no-op`() {
        val policy = ProfileSwitchDeferralPolicy(initialProfileId = 1)
        val drainedTo = policy.onPlaybackIdle()

        assertNull("no pending to drain", drainedTo)
        assertEquals("active unchanged", 1, policy.activeProfileId)
    }
}
