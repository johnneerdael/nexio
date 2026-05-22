package com.nexio.tv.data.integration.metadata

import com.nexio.tv.core.metadata.router.MetadataIdentityResolver
import com.nexio.tv.core.metadata.router.StableIdBundleResolver
import com.nexio.tv.core.tvdb.TvdbRemoteIdSource
import com.nexio.tv.core.tvdb.normalizeTvdbRemoteIdSource
import com.nexio.tv.data.integration.tmdb.TmdbIntegrationProvider
import com.nexio.tv.data.integration.tvdb.TvdbIntegrationProvider
import com.nexio.tv.data.remote.api.TvdbSearchResult
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

    override suspend fun imdbToTmdb(imdbId: String, mediaType: String): String? {
        val normalizedImdbId = imdbId.trim().takeIf { it.isNotEmpty() } ?: return null
        val normalizedType = when (mediaType.trim().lowercase()) {
            "tv", "series", "show" -> "tv"
            else -> "movie"
        }
        return tmdbProvider.findTmdbIdByImdbId(normalizedImdbId, normalizedType)?.toString()
    }

    override suspend fun tmdbTvToImdb(tmdbId: String): String? =
        tmdbProvider.findImdbIdByTmdbId(MetadataProviderTargetIds.tmdbInt(tmdbId) ?: return null, "tv")

    override suspend fun tmdbTvToTvdb(tmdbId: String): String? =
        resolveTmdbTvToTvdb(tmdbId)

    override suspend fun imdbToTvdbSeries(imdbId: String): String? {
        val normalizedImdbId = imdbId.trim().takeIf { it.isNotEmpty() } ?: return null
        return tvdbProvider.searchByRemoteId(normalizedImdbId)
            ?.data
            .orEmpty()
            .firstNotNullOfOrNull { result -> result.series?.id }
            ?.toString()
    }

    override suspend fun tvdbSeriesToImdb(tvdbId: String): String? =
        tvdbProvider.fetchSeriesExtended(MetadataProviderTargetIds.tvdbInt(tvdbId) ?: return null)
            ?.remoteIds
            ?.firstOrNull { normalizeTvdbRemoteIdSource(it.sourceName) == TvdbRemoteIdSource.IMDB }
            ?.id
            ?.takeIf { it.isNotBlank() }

    override suspend fun tmdbToTvdb(tmdbId: String): String? {
        resolveTmdbTvToTvdb(tmdbId)?.let { tvdbId -> return tvdbId }
        val imdbId = tmdbTvToImdb(tmdbId)?.takeIf { it.isNotBlank() } ?: return null
        return imdbToTvdbSeries(imdbId)
    }

    override suspend fun imdbToTvdb(imdbId: String): String? =
        imdbToTvdbSeries(imdbId)

    override suspend fun tvdbToTmdb(tvdbId: String): String? {
        val imdbId = tvdbSeriesToImdb(tvdbId)?.takeIf { it.isNotBlank() } ?: return null
        return imdbToTmdbMovie(imdbId)
    }

    private suspend fun resolveTmdbTvToTvdb(tmdbId: String): String? {
        val tmdbInt = MetadataProviderTargetIds.tmdbInt(tmdbId) ?: return null
        val directTvdbId = tmdbProvider.findTvdbIdByTmdbTvId(tmdbInt)
        if (directTvdbId != null && tvdbProvider.fetchSeriesExtended(directTvdbId) != null) {
            return directTvdbId.toString()
        }

        return findCanonicalTvdbSeriesByTmdbTitle(tmdbInt)
    }

    private suspend fun findCanonicalTvdbSeriesByTmdbTitle(tmdbId: Int): String? {
        val title = tmdbProvider.fetchTvCore(
            tvId = tmdbId,
            normalizedLanguage = "en-US",
            activePosterProvider = null
        )?.localizedTitle?.trim()?.takeIf { it.isNotEmpty() } ?: return null

        return tvdbProvider.searchSeriesByQuery(title)
            ?.data
            .orEmpty()
            .firstNotNullOfOrNull { result ->
                result.canonicalTvdbIdIfExactAlias(title)
            }
    }

    private fun TvdbSearchResult.canonicalTvdbIdIfExactAlias(sourceTitle: String): String? {
        val normalizedTitle = sourceTitle.normalizedIdentityTitle()
        if (normalizedTitle.isEmpty()) return null
        val exactNameMatch = name.normalizedIdentityTitle() == normalizedTitle ||
            this.title.normalizedIdentityTitle() == normalizedTitle
        val exactAliasMatch = aliases.orEmpty().any { alias ->
            alias.normalizedIdentityTitle() == normalizedTitle
        }
        if (!exactNameMatch && !exactAliasMatch) return null
        val rawId = id?.trim()
        return tvdbId?.trim()?.takeIf { it.isNotEmpty() }
            ?: rawId?.substringAfter("series-", missingDelimiterValue = rawId)
                ?.takeIf { it.all(Char::isDigit) }
    }

    private fun String?.normalizedIdentityTitle(): String =
        this
            ?.trim()
            ?.lowercase()
            ?.replace(Regex("[^\\p{L}\\p{Nd}]+"), " ")
            ?.replace(Regex("\\s+"), " ")
            ?.trim()
            .orEmpty()
}
