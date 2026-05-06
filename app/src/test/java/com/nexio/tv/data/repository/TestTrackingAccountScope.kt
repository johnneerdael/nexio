package com.nexio.tv.data.repository

import com.nexio.tv.domain.model.TrackingProvider

fun testTraktSession(
    profileId: Int = 1,
    credentialHash: String = "trakt-test-credential"
): TrackingAuthSession {
    return TrackingAuthSession(
        provider = TrackingProvider.TRAKT,
        profileId = profileId,
        credentialHash = credentialHash
    )
}

fun testSimklSession(
    profileId: Int = 1,
    credentialHash: String = "simkl-test-credential"
): TrackingAuthSession {
    return TrackingAuthSession(
        provider = TrackingProvider.SIMKL,
        profileId = profileId,
        credentialHash = credentialHash
    )
}

class TestTrackingAccountScopeProvider : TrackingAccountScopeProvider {
    override suspend fun accountScopedSession(
        provider: TrackingProvider,
        profileId: Int
    ): TrackingAuthSession {
        return when (provider) {
            TrackingProvider.TRAKT -> testTraktSession(profileId)
            TrackingProvider.SIMKL -> testSimklSession(profileId)
        }
    }
}
