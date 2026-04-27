package com.nexio.tv.core.profile

import com.nexio.tv.core.integration.ProfileBoundaryException
import com.nexio.tv.core.integration.ProfileBoundaryViolation
import com.nexio.tv.core.playback.PlaybackSessionRegistry
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

class FakeProfileManager(
    initialProfileId: Int = 1,
    private val playbackSessionRegistry: PlaybackSessionRegistry = PlaybackSessionRegistry()
) {
    private val _activeProfileId = MutableStateFlow(initialProfileId)
    val activeProfileId: StateFlow<Int> = _activeProfileId

    val isPrimaryProfileActive: Boolean get() = _activeProfileId.value == 1

    private val profileIds = mutableListOf<Int>()

    fun seedProfiles(ids: List<Int>) {
        profileIds.clear()
        profileIds.addAll(ids)
    }

    fun switchTo(profileId: Int) {
        _activeProfileId.value = profileId
    }

    suspend fun setActiveProfile(id: Int) {
        if (profileIds.isNotEmpty() && id !in profileIds) return
        if (_activeProfileId.value == id) return
        val owner = playbackSessionRegistry.activeOwner()
        if (owner != null) {
            throw ProfileBoundaryException(
                ProfileBoundaryViolation.PROFILE_SWITCH_BLOCKED_BY_ACTIVE_PLAYBACK,
                "Cannot switch profile while playback owned by profile=${owner.ownerProfileId} session=${owner.ownerSessionId} is active"
            )
        }
        _activeProfileId.value = id
    }
}
