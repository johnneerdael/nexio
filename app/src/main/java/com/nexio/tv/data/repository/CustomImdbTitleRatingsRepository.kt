package com.nexio.tv.data.repository

import android.util.Log
import com.nexio.tv.core.tmdb.TmdbService
import com.nexio.tv.data.remote.CustomImdbClient
import com.nexio.tv.domain.model.ContentType
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

private const val CUSTOM_IMDB_TITLE_TAG = "CustomImdbTitleRatings"
internal const val CUSTOM_IMDB_TITLE_RATINGS_TTL_MS = 7L * 24L * 60L * 60L * 1000L
private val CANONICAL_IMDB_ID_REGEX = Regex("tt\\d+")

internal fun extractCanonicalImdbId(rawId: String?): String? {
    if (rawId.isNullOrBlank()) return null
    return CANONICAL_IMDB_ID_REGEX.find(rawId)?.value
}

@Singleton
class CustomImdbTitleRatingsRepository @Inject constructor(
    private val customImdbClient: CustomImdbClient,
    private val tmdbService: TmdbService
) {
    private data class CacheEntry(
        val rating: Double?,
        val expiresAtMs: Long
    )

    private val cache = ConcurrentHashMap<String, CacheEntry>()
    internal var nowMsProvider: () -> Long = { System.currentTimeMillis() }

    suspend fun getTitleRating(
        contentId: String,
        fallbackItemId: String,
        contentType: ContentType,
        fallbackItemType: String
    ): Double? {
        val imdbId = resolveImdbId(contentId, fallbackItemId, contentType, fallbackItemType) ?: return null
        return getTitleRatingForResolvedImdbId(imdbId)
    }

    suspend fun getTitleRatingByImdbId(imdbId: String): Double? {
        val clean = extractCanonicalImdbId(imdbId) ?: return null
        return getTitleRatingForResolvedImdbId(clean)
    }

    private suspend fun getTitleRatingForResolvedImdbId(imdbId: String): Double? {
        val cacheKey = imdbId
        val now = nowMsProvider()
        cache[cacheKey]?.takeIf { it.expiresAtMs > now }?.let { return it.rating }

        val rating = runCatching {
            customImdbClient.fetchTitleRatings(listOf(imdbId))[imdbId]
        }.getOrElse { error ->
            Log.w(CUSTOM_IMDB_TITLE_TAG, "Failed custom IMDb title rating for $imdbId: ${error.message}", error)
            null
        }

        cache[cacheKey] = CacheEntry(
            rating = rating,
            expiresAtMs = nowMsProvider() + CUSTOM_IMDB_TITLE_RATINGS_TTL_MS
        )
        return rating
    }

    private suspend fun resolveImdbId(
        contentId: String,
        fallbackItemId: String,
        contentType: ContentType,
        fallbackItemType: String
    ): String? {
        extractImdbId(contentId)?.let { return it }
        extractImdbId(fallbackItemId)?.let { return it }

        val tmdbType = normalizeMediaType(contentType, fallbackItemType)
        val tmdbId = tmdbService.ensureTmdbId(contentId, tmdbType)
            ?: tmdbService.ensureTmdbId(fallbackItemId, tmdbType)
            ?: return null

        return tmdbId.toIntOrNull()?.let { tmdbService.tmdbToImdb(it, tmdbType) }
    }

    private fun normalizeMediaType(contentType: ContentType, fallbackItemType: String): String {
        return when (contentType) {
            ContentType.SERIES, ContentType.TV -> "series"
            ContentType.MOVIE -> "movie"
            else -> fallbackItemType.ifBlank { contentType.toApiString() }
        }
    }

    private fun extractImdbId(rawId: String?): String? {
        return extractCanonicalImdbId(rawId)
    }
}
