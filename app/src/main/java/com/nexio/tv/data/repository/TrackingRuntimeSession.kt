package com.nexio.tv.data.repository

import com.nexio.tv.domain.model.TrackingProvider

data class TrackingRuntimeSession(
    val provider: TrackingProvider,
    val profileId: Int,
    val generation: Long = 0L
) {
    init {
        require(profileId in 1..4) {
            "Tracking runtime profile id must be 1-4; was $profileId"
        }
    }

    val authSession: TrackingAuthSession
        get() = TrackingAuthSession(provider = provider, profileId = profileId)
}
