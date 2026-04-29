package com.nexio.tv.core.profile

/**
 * F-F-04: pure state machine for deferred profile switches.
 *
 * When a reactive (sibling-device) profile push arrives during active playback, the new
 * profile id is held in `pendingActiveProfileId` rather than applied. When playback ends
 * (`onPlaybackIdle()`), the pending switch drains.
 *
 * This class has no IO — it's a pure decision helper. ProfileManager owns the wiring:
 * forwarding incoming dataStore emissions to `onIncomingSwitch` and forwarding playback-end
 * events to `onPlaybackIdle`, then applying the returned profile id to the StateFlow.
 */
class ProfileSwitchDeferralPolicy(initialProfileId: Int) {
    var activeProfileId: Int = initialProfileId
        private set

    var pendingActiveProfileId: Int? = null
        private set

    /**
     * Called when [dataStore.activeProfileId] emits a new id.
     * Returns true if applied immediately; false if deferred.
     */
    fun onIncomingSwitch(targetProfileId: Int, hasActivePlayback: Boolean): Boolean {
        if (targetProfileId == activeProfileId) {
            pendingActiveProfileId = null
            return false
        }
        if (hasActivePlayback) {
            pendingActiveProfileId = targetProfileId
            return false
        }
        activeProfileId = targetProfileId
        pendingActiveProfileId = null
        return true
    }

    /**
     * Called when [playbackSessionRegistry.isIdle()] becomes true (e.g. unregister of the last
     * active session). Returns the profile id that was drained, or null if no pending switch.
     */
    fun onPlaybackIdle(): Int? {
        val pending = pendingActiveProfileId ?: return null
        if (pending == activeProfileId) {
            pendingActiveProfileId = null
            return null
        }
        activeProfileId = pending
        pendingActiveProfileId = null
        return pending
    }
}
