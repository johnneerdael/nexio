package com.nexio.tv.data.repository

import com.nexio.tv.domain.model.TrackingProvider
import javax.inject.Inject
import javax.inject.Singleton

interface TrackingAccountScopeProvider {
    suspend fun accountScopedSession(
        provider: TrackingProvider,
        profileId: Int
    ): TrackingAuthSession
}

@Singleton
class DefaultTrackingAccountScopeProvider @Inject constructor(
    private val traktAuthService: TraktAuthService,
    private val simklAuthService: SimklAuthService
) : TrackingAccountScopeProvider {
    override suspend fun accountScopedSession(
        provider: TrackingProvider,
        profileId: Int
    ): TrackingAuthSession {
        val base = TrackingAuthSession(provider = provider, profileId = profileId)
        return when (provider) {
            TrackingProvider.TRAKT -> traktAuthService.mutationAccountScopedSession(base)
            TrackingProvider.SIMKL -> simklAuthService.mutationAccountScopedSession(base)
        }
    }
}
