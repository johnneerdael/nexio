package com.nexio.tv.data.integration.metadata

import com.nexio.tv.core.metadata.router.MetadataIdentityResolver
import com.nexio.tv.data.integration.tmdb.TmdbIntegrationProvider
import com.nexio.tv.data.integration.tvdb.TvdbIntegrationProvider
import javax.inject.Inject

class RuntimeMetadataIdentityLookup @Inject constructor(
    private val tmdbProvider: TmdbIntegrationProvider,
    private val tvdbProvider: TvdbIntegrationProvider
) : MetadataIdentityResolver.Lookup {
    override suspend fun tmdbToTvdb(tmdbId: String): String? {
        val imdbId = tmdbProvider.findImdbIdByTmdbId(MetadataProviderTargetIds.tmdbInt(tmdbId) ?: return null, "tv") ?: return null
        return tvdbProvider.searchByRemoteId(imdbId)
            ?.data
            ?.firstOrNull()
            ?.series
            ?.id
            ?.toString()
    }

    override suspend fun imdbToTvdb(imdbId: String): String? {
        return tvdbProvider.searchByRemoteId(imdbId)
            ?.data
            .orEmpty()
            .firstNotNullOfOrNull { result -> result.series?.id }
            ?.toString()
    }

    override suspend fun tvdbToTmdb(tvdbId: String): String? {
        val series = tvdbProvider.fetchSeriesExtended(MetadataProviderTargetIds.tvdbInt(tvdbId) ?: return null)
        val imdbId = series
            ?.remoteIds
            ?.firstOrNull { it.sourceName.equals("IMDB", ignoreCase = true) }
            ?.id
            ?: return null
        return tmdbProvider.findTmdbIdByImdbId(imdbId, "movie")?.toString()
    }
}
