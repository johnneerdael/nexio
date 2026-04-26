package com.nexio.tv.data.integration.tmdb

import javax.inject.Inject
import javax.inject.Provider
import javax.inject.Singleton

interface TmdbExternalIdLookupProvider {
    suspend fun findTmdbIdByImdbId(imdbId: String, mediaType: String): Int?

    suspend fun findImdbIdByTmdbId(tmdbId: Int, mediaType: String): String?
}

@Singleton
class DefaultTmdbExternalIdLookupProvider @Inject constructor(
    private val tmdbIntegrationProvider: Provider<TmdbIntegrationProvider>
) : TmdbExternalIdLookupProvider {

    override suspend fun findTmdbIdByImdbId(imdbId: String, mediaType: String): Int? {
        return tmdbIntegrationProvider.get().findTmdbIdByImdbId(imdbId, mediaType)
    }

    override suspend fun findImdbIdByTmdbId(tmdbId: Int, mediaType: String): String? {
        return tmdbIntegrationProvider.get().findImdbIdByTmdbId(tmdbId, mediaType)
    }
}
