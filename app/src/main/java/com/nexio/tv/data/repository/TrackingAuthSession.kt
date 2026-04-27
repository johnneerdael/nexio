package com.nexio.tv.data.repository

import com.nexio.tv.domain.model.TrackingProvider

data class TrackingAuthSession(
    val provider: TrackingProvider,
    val profileId: Int,
    val credentialHash: String? = null,
    val accountIdHash: String? = null
) {
    fun toRuntimeSession(generation: Long = 0L): TrackingRuntimeSession {
        return TrackingRuntimeSession(
            provider = provider,
            profileId = profileId,
            generation = generation
        )
    }
}
