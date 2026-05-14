package com.nexio.tv.data.repository

import com.nexio.tv.core.integration.IntegrationProvider
import com.nexio.tv.core.integration.credentialHash as integrationCredentialHash
import com.nexio.tv.domain.model.TrackingProvider
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.first

interface TrackingAccountScopeProvider {
    suspend fun accountScopedSession(
        provider: TrackingProvider,
        profileId: Int
    ): TrackingAuthSession
}

@Singleton
class DefaultTrackingAccountScopeProvider @Inject constructor(
    private val traktAuthService: TraktAuthService,
    private val simklAuthService: SimklAuthService,
    private val mdbListSettingsReader: MDBListSettingsReader
) : TrackingAccountScopeProvider {
    override suspend fun accountScopedSession(
        provider: TrackingProvider,
        profileId: Int
    ): TrackingAuthSession {
        val base = TrackingAuthSession(provider = provider, profileId = profileId)
        return when (provider) {
            TrackingProvider.TRAKT -> traktAuthService.mutationAccountScopedSession(base)
            TrackingProvider.SIMKL -> simklAuthService.mutationAccountScopedSession(base)
            TrackingProvider.MDBLIST -> {
                val apiKey = mdbListSettingsReader.settings.first().apiKey.trim()
                require(apiKey.isNotBlank()) {
                    "MDBList mutation envelopes require a configured API key"
                }
                base.copy(
                    credentialHash = integrationCredentialHash(IntegrationProvider.MDBLIST, apiKey)
                )
            }
        }
    }
}
