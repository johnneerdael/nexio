package com.nexio.tv.data.integration.trailer

import android.util.Log
import com.nexio.tv.BuildConfig
import com.nexio.tv.core.metadata.MetadataApiKeyResolver
import com.nexio.tv.data.integration.tmdb.TmdbIntegrationProvider
import com.nexio.tv.data.local.MetadataDiskCacheStore
import com.nexio.tv.data.local.TmdbSettingsDataStore
import com.nexio.tv.data.remote.api.TmdbVideoResult
import com.nexio.tv.data.trailer.isTmdbVideoLanguageEligible
import java.time.Clock
import java.time.LocalDate
import javax.inject.Inject
import kotlinx.coroutines.flow.first

private const val TAG = "TrailerTmdbProvider"
private const val TMDB_TRAILER_CACHE_PROVIDER = "trailer"
private const val TMDB_TRAILER_FALLBACK_LANGUAGE = "en-US"

private fun trailerDebugLog(message: String) {
    if (!BuildConfig.DEBUG) return
    runCatching { Log.d(TAG, message) }
}

private fun trailerWarnLog(message: String) {
    if (!BuildConfig.DEBUG) return
    runCatching { Log.w(TAG, message) }
}

@Suppress("LongParameterList")
class TrailerTmdbProvider @Inject constructor(
    private val tmdbIntegrationProvider: TmdbIntegrationProvider,
    private val metadataDiskCacheStore: MetadataDiskCacheStore,
    private val metadataApiKeyResolver: MetadataApiKeyResolver? = null,
    private val tmdbSettingsDataStore: TmdbSettingsDataStore
) {
    suspend fun resolveLatestAiredSeasonNumber(
        tmdbId: Int,
        apiKey: String,
        clock: Clock
    ): Int? {
        return try {
            tmdbIntegrationProvider.fetchTvDetails(
                tvId = tmdbId,
                normalizedLanguage = TMDB_TRAILER_FALLBACK_LANGUAGE
            )?.seasons.orEmpty()
                .asSequence()
                .filter { season ->
                    val num = season.seasonNumber ?: return@filter false
                    if (num <= 0) return@filter false
                    val count = season.episodeCount ?: return@filter false
                    if (count <= 0) return@filter false
                    val airDate = season.airDate?.takeIf { it.isNotBlank() } ?: return@filter false
                    runCatching { LocalDate.parse(airDate) <= LocalDate.now(clock.zone) }.getOrElse { false }
                }
                .maxByOrNull { it.seasonNumber ?: 0 }
                ?.seasonNumber
        } catch (_: Exception) {
            null
        }
    }

    suspend fun getTmdbApiKey(): String? {
        metadataApiKeyResolver?.tmdbCredential()?.let { credential ->
            if (credential.missing) {
                trailerDebugLog("TMDB trailer lookup skipped: api_key_missing")
                return null
            }
            return credential.apiKey
        }
        val apiKey = tmdbSettingsDataStore.settings.first().apiKey.trim()
        return apiKey.takeIf { it.isNotBlank() }
    }

    suspend fun fetchMovieVideos(
        tmdbId: Int,
        preferredLanguage: String,
        apiKey: String,
        originalLanguage: String? = null
    ): List<TmdbVideoResult> {
        val localized = fetchMovieVideosOnce(tmdbId, preferredLanguage, apiKey)
        if (preferredLanguage.equals(TMDB_TRAILER_FALLBACK_LANGUAGE, ignoreCase = true)) {
            return localized
        }
        if (hasTrailerOrTeaserCandidate(localized, originalLanguage)) {
            return localized
        }
        return fetchMovieVideosOnce(tmdbId, TMDB_TRAILER_FALLBACK_LANGUAGE, apiKey)
    }

    suspend fun fetchTvVideos(
        tmdbId: Int,
        preferredLanguage: String,
        apiKey: String,
        originalLanguage: String? = null
    ): List<TmdbVideoResult> {
        val localized = fetchTvVideosOnce(tmdbId, preferredLanguage, apiKey)
        if (preferredLanguage.equals(TMDB_TRAILER_FALLBACK_LANGUAGE, ignoreCase = true)) {
            return localized
        }
        if (hasTrailerOrTeaserCandidate(localized, originalLanguage)) {
            return localized
        }
        return fetchTvVideosOnce(tmdbId, TMDB_TRAILER_FALLBACK_LANGUAGE, apiKey)
    }

    suspend fun fetchSeasonVideos(
        tmdbId: Int,
        seasonNumber: Int,
        preferredLanguage: String,
        apiKey: String
    ): List<TmdbVideoResult> {
        val localized = fetchSeasonVideosOnce(tmdbId, seasonNumber, preferredLanguage, apiKey)
        if (localized.isNotEmpty() || preferredLanguage.equals(TMDB_TRAILER_FALLBACK_LANGUAGE, ignoreCase = true)) {
            return localized
        }
        return fetchSeasonVideosOnce(tmdbId, seasonNumber, TMDB_TRAILER_FALLBACK_LANGUAGE, apiKey)
    }

    private suspend fun fetchMovieVideosOnce(
        tmdbId: Int,
        language: String,
        apiKey: String
    ): List<TmdbVideoResult> {
        metadataDiskCacheStore.readTmdbTitleVideos(
            tmdbId = tmdbId,
            mediaType = "movie",
            languageTag = language,
            providerToken = TMDB_TRAILER_CACHE_PROVIDER
        )?.let { videos ->
            trailerDebugLog("TMDB movie videos cache hit tmdbId=$tmdbId language=$language count=${videos.size}")
            return videos
        }

        return try {
            val results = filterCacheableTmdbTrailerVideos(
                tmdbIntegrationProvider.fetchMovieVideos(tmdbId, language)?.results.orEmpty()
            )
            trailerDebugLog("TMDB movie videos fetch tmdbId=$tmdbId language=$language count=${results.size}")
            metadataDiskCacheStore.writeTmdbTitleVideos(
                tmdbId = tmdbId,
                mediaType = "movie",
                languageTag = language,
                providerToken = TMDB_TRAILER_CACHE_PROVIDER,
                videos = results
            )
            results
        } catch (error: Exception) {
            trailerWarnLog("TMDB movie videos fetch exception tmdbId=$tmdbId language=$language: ${error.message}")
            emptyList()
        }
    }

    private suspend fun fetchTvVideosOnce(
        tmdbId: Int,
        language: String,
        apiKey: String
    ): List<TmdbVideoResult> {
        metadataDiskCacheStore.readTmdbTitleVideos(
            tmdbId = tmdbId,
            mediaType = "tv",
            languageTag = language,
            providerToken = TMDB_TRAILER_CACHE_PROVIDER
        )?.let { videos ->
            trailerDebugLog("TMDB tv videos cache hit tmdbId=$tmdbId language=$language count=${videos.size}")
            return videos
        }

        return try {
            val results = filterCacheableTmdbTrailerVideos(
                tmdbIntegrationProvider.fetchTvVideos(tmdbId, language)?.results.orEmpty()
            )
            trailerDebugLog("TMDB tv videos fetch tmdbId=$tmdbId language=$language count=${results.size}")
            metadataDiskCacheStore.writeTmdbTitleVideos(
                tmdbId = tmdbId,
                mediaType = "tv",
                languageTag = language,
                providerToken = TMDB_TRAILER_CACHE_PROVIDER,
                videos = results
            )
            results
        } catch (error: Exception) {
            trailerWarnLog("TMDB tv videos fetch exception tmdbId=$tmdbId language=$language: ${error.message}")
            emptyList()
        }
    }

    private suspend fun fetchSeasonVideosOnce(
        tmdbId: Int,
        seasonNumber: Int,
        language: String,
        apiKey: String
    ): List<TmdbVideoResult> {
        metadataDiskCacheStore.readTmdbSeasonVideos(
            tmdbId = tmdbId,
            seasonNumber = seasonNumber,
            languageTag = language,
            providerToken = TMDB_TRAILER_CACHE_PROVIDER
        )?.let { videos ->
            return videos
        }

        return try {
            val results = filterCacheableTmdbTrailerVideos(
                tmdbIntegrationProvider.fetchSeasonVideos(tmdbId, seasonNumber, language)?.results.orEmpty()
            )
            metadataDiskCacheStore.writeTmdbSeasonVideos(
                tmdbId = tmdbId,
                seasonNumber = seasonNumber,
                languageTag = language,
                providerToken = TMDB_TRAILER_CACHE_PROVIDER,
                videos = results
            )
            results
        } catch (_: Exception) {
            emptyList()
        }
    }

    private fun hasTrailerOrTeaserCandidate(
        results: List<TmdbVideoResult>,
        originalLanguage: String?
    ): Boolean {
        return results.any {
            (it.site ?: "").equals("YouTube", ignoreCase = true) &&
                !it.key.isNullOrBlank() &&
                isTmdbVideoLanguageEligible(it.iso6391, originalLanguage) &&
                when (it.type?.trim()?.lowercase()) {
                    "trailer", "teaser" -> true
                    else -> false
                }
        }
    }

    private fun filterCacheableTmdbTrailerVideos(results: List<TmdbVideoResult>): List<TmdbVideoResult> {
        return results
            .asSequence()
            .filter { (it.site ?: "").equals("YouTube", ignoreCase = true) }
            .filter { !it.key.isNullOrBlank() }
            .filter { isCacheableTmdbTrailerType(it.type) }
            .toList()
    }

    private fun isCacheableTmdbTrailerType(type: String?): Boolean {
        return when (type?.trim()?.lowercase()) {
            "trailer", "teaser", "recap" -> true
            else -> false
        }
    }
}

private fun normalizeTmdbTrailerLanguage(language: String?): String {
    val normalized = language
        ?.trim()
        ?.replace('_', '-')
        ?.takeIf { it.isNotBlank() }
        ?: return TMDB_TRAILER_FALLBACK_LANGUAGE

    if (normalized.contains('-')) {
        val parts = normalized.split("-", limit = 2)
        val locale = parts[0].lowercase()
        val region = parts.getOrNull(1)?.uppercase()?.takeIf { it.isNotBlank() }
        return if (region != null) "$locale-$region" else locale
    }

    if (normalized.equals("en", ignoreCase = true)) return TMDB_TRAILER_FALLBACK_LANGUAGE
    return normalized.lowercase()
}
