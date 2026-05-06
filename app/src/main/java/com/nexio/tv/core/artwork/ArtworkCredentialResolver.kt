package com.nexio.tv.core.artwork

import com.nexio.tv.core.integration.IntegrationProvider
import com.nexio.tv.data.local.PosterRatingsSettingsDataStore
import com.nexio.tv.domain.model.ArtworkProviderSettings
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import javax.inject.Inject
import javax.inject.Singleton

interface ArtworkCredentialResolver {
    suspend fun apiKeyFor(
        provider: IntegrationProvider,
        credentialHash: String?
    ): String?
}

interface ArtworkProviderSettingsSource {
    val settings: Flow<ArtworkProviderSettings>
}

@Singleton
class PosterRatingsArtworkProviderSettingsSource @Inject constructor(
    private val settingsDataStore: PosterRatingsSettingsDataStore
) : ArtworkProviderSettingsSource {
    override val settings = settingsDataStore.settings
}

@Singleton
class PosterRatingsArtworkCredentialResolver @Inject constructor(
    private val settingsSource: ArtworkProviderSettingsSource
) : ArtworkCredentialResolver {
    override suspend fun apiKeyFor(
        provider: IntegrationProvider,
        credentialHash: String?
    ): String? {
        if (credentialHash.isNullOrBlank()) return null
        val settings = settingsSource.settings.first()
        val candidate = when (provider) {
            IntegrationProvider.RPDB -> settings.rpdbApiKey.trim()
            IntegrationProvider.TOP_POSTERS -> settings.topPostersApiKey.trim()
            else -> return null
        }
        if (candidate.isBlank()) return null
        return candidate.takeIf {
            ArtworkCredentialHash.hashCredential(it) == credentialHash
        }
    }
}
