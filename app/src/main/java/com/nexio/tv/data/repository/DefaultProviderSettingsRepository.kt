package com.nexio.tv.data.repository

import com.nexio.tv.data.integration.mdblist.MDBListIntegrationProvider
import com.nexio.tv.data.integration.omdb.OmdbIntegrationProvider
import com.nexio.tv.data.integration.posters.RpdbIntegrationProvider
import com.nexio.tv.data.integration.posters.TopPostersIntegrationProvider
import com.nexio.tv.data.integration.skip.AnimeSkipIntegrationProvider
import com.nexio.tv.data.integration.tmdb.TmdbIntegrationProvider
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class DefaultProviderSettingsRepository @Inject constructor(
    private val omdbProvider: OmdbIntegrationProvider,
    private val mdbListProvider: MDBListIntegrationProvider,
    private val tmdbProvider: TmdbIntegrationProvider,
    private val animeSkipProvider: AnimeSkipIntegrationProvider,
    private val rpdbProvider: RpdbIntegrationProvider,
    private val topPostersProvider: TopPostersIntegrationProvider
) : ProviderSettingsRepository {
    override suspend fun validateOmdbApiKey(apiKey: String): Boolean =
        omdbProvider.validateApiKey(apiKey)

    override suspend fun validateMDBListApiKey(apiKey: String): Boolean =
        mdbListProvider.validateApiKey(apiKey)

    override suspend fun validateTmdbApiKey(apiKey: String): Boolean =
        tmdbProvider.validateApiKey(apiKey)

    override suspend fun validateAnimeSkipClientId(clientId: String): Boolean =
        animeSkipProvider.validateClientId(clientId)

    override suspend fun validateRpdbApiKey(apiKey: String): Boolean =
        rpdbProvider.validateApiKey(apiKey)

    override suspend fun validateTopPostersApiKey(apiKey: String): Boolean =
        topPostersProvider.validateApiKey(apiKey)
}
