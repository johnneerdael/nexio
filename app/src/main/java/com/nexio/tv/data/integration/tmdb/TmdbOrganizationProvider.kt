package com.nexio.tv.data.integration.tmdb

import com.nexio.tv.data.remote.api.TmdbCompanyDetailsResponse
import com.nexio.tv.data.remote.api.TmdbDiscoverResponse
import com.nexio.tv.data.remote.api.TmdbNetworkDetailsResponse
import javax.inject.Inject
import javax.inject.Singleton

interface TmdbOrganizationProvider {
    suspend fun getCompanyDetails(entityId: Int): TmdbCompanyDetailsResponse?

    suspend fun discoverMoviesByCompany(entityId: Int, language: String): TmdbDiscoverResponse?

    suspend fun discoverTvByCompany(entityId: Int, language: String): TmdbDiscoverResponse?

    suspend fun getNetworkDetails(entityId: Int): TmdbNetworkDetailsResponse?

    suspend fun discoverTvByNetwork(entityId: Int, language: String): TmdbDiscoverResponse?
}

@Singleton
class DefaultTmdbOrganizationProvider @Inject constructor(
    private val tmdbIntegrationProvider: TmdbIntegrationProvider
) : TmdbOrganizationProvider {

    override suspend fun getCompanyDetails(entityId: Int): TmdbCompanyDetailsResponse? {
        return tmdbIntegrationProvider.fetchCompanyDetails(entityId)
    }

    override suspend fun discoverMoviesByCompany(
        entityId: Int,
        language: String
    ): TmdbDiscoverResponse? {
        return tmdbIntegrationProvider.discoverMoviesByCompany(entityId, language)
    }

    override suspend fun discoverTvByCompany(
        entityId: Int,
        language: String
    ): TmdbDiscoverResponse? {
        return tmdbIntegrationProvider.discoverTvByCompany(entityId, language)
    }

    override suspend fun getNetworkDetails(entityId: Int): TmdbNetworkDetailsResponse? {
        return tmdbIntegrationProvider.fetchNetworkDetails(entityId)
    }

    override suspend fun discoverTvByNetwork(entityId: Int, language: String): TmdbDiscoverResponse? {
        return tmdbIntegrationProvider.discoverTvByNetwork(entityId, language)
    }
}
