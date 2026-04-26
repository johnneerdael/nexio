package com.nexio.tv.core.integration

import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class IntegrationPlaybackGate @Inject constructor() {
    @Volatile
    private var playbackActive: Boolean = false

    fun setPlaybackActive(active: Boolean) {
        playbackActive = active
    }

    fun isPlaybackActive(): Boolean = playbackActive

    fun isBlocked(policy: IntegrationProviderPolicy, workClass: IntegrationWorkClass): Boolean {
        if (!playbackActive) return false
        if (policy.allowDuringPlayback) return false

        return workClass !in setOf(
            IntegrationWorkClass.PLAYBACK_CRITICAL,
            IntegrationWorkClass.PLAYBACK_RESOLUTION,
            IntegrationWorkClass.SCROBBLE,
            IntegrationWorkClass.MUTATION_OUTBOX
        )
    }
}
