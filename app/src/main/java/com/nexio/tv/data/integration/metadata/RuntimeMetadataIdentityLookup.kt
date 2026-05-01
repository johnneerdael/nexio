package com.nexio.tv.data.integration.metadata

import com.nexio.tv.core.metadata.router.MetadataIdentityResolver
import com.nexio.tv.core.metadata.router.StableIdBundleResolver
import com.nexio.tv.data.integration.tmdb.TmdbIntegrationProvider
import com.nexio.tv.data.integration.tvdb.TvdbIntegrationProvider
import javax.inject.Inject

class RuntimeMetadataIdentityLookup @Inject constructor(
    private val tmdbProvider: TmdbIntegrationProvider,
    private val tvdbProvider: TvdbIntegrationProvider
) : MetadataIdentityResolver.Lookup, StableIdBundleResolver.Lookup {
    override suspend fun tmdbMovieToImdb(tmdbId: String): String? =
        tmdbProvider.findImdbIdByTmdbId(MetadataProviderTargetIds.tmdbInt(tmdbId) ?: return null, "movie")

    override suspend fun imdbToTmdbMovie(imdbId: String): String? {
        val normalizedImdbId = imdbId.trim().takeIf { it.isNotEmpty() } ?: return null
        return tmdbProvider.findTmdbIdByImdbId(normalizedImdbId, "movie")?.toString()
    }

    override suspend fun tmdbTvToImdb(tmdbId: String): String? =
        tmdbProvider.findImdbIdByTmdbId(MetadataProviderTargetIds.tmdbInt(tmdbId) ?: return null, "tv")

    override suspend fun imdbToTvdbSeries(imdbId: String): String? =
        tvdbProvider.searchByRemoteId(imdbId)
            ?.data
            .orEmpty()
            .firstNotNullOfOrNull { result -> result.series?.id }
            ?.toString()

    override suspend fun tvdbSeriesToImdb(tvdbId: String): String? =
        tvdbProvider.fetchSeriesExtended(MetadataProviderTargetIds.tvdbInt(tvdbId) ?: return null)
            ?.remoteIds
            ?.firstOrNull { it.sourceName.equals("IMDB", ignoreCase = true) }
            ?.id
            ?.takeIf { it.isNotBlank() }

    override suspend fun tmdbToTvdb(tmdbId: String): String? {
        val imdbId = tmdbTvToImdb(tmdbId)?.takeIf { it.isNotBlank() } ?: return null
        return imdbToTvdbSeries(imdbId)
    }

    override suspend fun imdbToTvdb(imdbId: String): String? =
        imdbToTvdbSeries(imdbId)

    override suspend fun tvdbToTmdb(tvdbId: String): String? {
        val imdbId = tvdbSeriesToImdb(tvdbId)?.takeIf { it.isNotBlank() } ?: return null
        return imdbToTmdbMovie(imdbId)
    }
}
