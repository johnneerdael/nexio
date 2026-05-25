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

internal fun isTmdbIdLookupCandidate(rawId: String?): Boolean {
    val trimmed = rawId?.trim()?.takeIf { it.isNotBlank() } ?: return false
    extractCanonicalImdbId(trimmed)?.let { return true }

    val lower = trimmed.lowercase()
    return when {
        lower.startsWith("tmdb:") -> lower.substringAfter(':').substringBefore(':').all(Char::isDigit)
        lower.startsWith("movie:") || lower.startsWith("series:") -> {
            val nested = lower.substringAfter(':').substringBefore(':')
            nested.all(Char::isDigit) || nested.startsWith("tt")
        }
        lower.substringBefore(':') in setOf("tvdb", "kitsu", "simkl", "trakt", "mal", "anilist", "anidb") -> false
        else -> lower.substringBefore(':').substringBefore('/').all(Char::isDigit)
    }
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

    suspend fun getTitleRatingsByImdbIds(
        imdbIds: List<String>,
        cacheOnly: Boolean = false
    ): Map<String, Double> {
        val cleanIds = imdbIds.mapNotNull(::extractCanonicalImdbId).distinct()
        if (cleanIds.isEmpty()) return emptyMap()

        val now = nowMsProvider()
        val out = linkedMapOf<String, Double>()
        val missing = mutableListOf<String>()

        for (i in cleanIds.indices) {
            val imdbId = cleanIds[i]
            val cached = cache[imdbId]?.takeIf { it.expiresAtMs > now }
            if (cached != null) {
                cached.rating?.let { out[imdbId] = it }
            } else {
                missing += imdbId
            }
        }

        if (cacheOnly || missing.isEmpty()) return out

        val fetched = runCatching {
            customImdbClient.fetchTitleRatings(missing)
        }.getOrElse { error ->
            Log.w(CUSTOM_IMDB_TITLE_TAG, "Failed custom IMDb title rating batch: ${error.message}", error)
            emptyMap()
        }

        val expiresAt = nowMsProvider() + CUSTOM_IMDB_TITLE_RATINGS_TTL_MS
        for (i in missing.indices) {
            val imdbId = missing[i]
            val rating = fetched[imdbId]
            if (rating != null) {
                cache[imdbId] = CacheEntry(
                    rating = rating,
                    expiresAtMs = expiresAt
                )
                out[imdbId] = rating
            } else {
                cache.remove(imdbId)
            }
        }

        return out
    }

    private suspend fun getTitleRatingForResolvedImdbId(imdbId: String): Double? =
        getTitleRatingsByImdbIds(listOf(imdbId), cacheOnly = false)[imdbId]

    private suspend fun resolveImdbId(
        contentId: String,
        fallbackItemId: String,
        contentType: ContentType,
        fallbackItemType: String
    ): String? {
        extractImdbId(contentId)?.let { return it }
        extractImdbId(fallbackItemId)?.let { return it }

        val tmdbType = normalizeMediaType(contentType, fallbackItemType)
        var tmdbId: String? = null
        for (candidate in listOf(contentId, fallbackItemId).distinct()) {
            if (!isTmdbIdLookupCandidate(candidate)) continue
            tmdbId = tmdbService.ensureTmdbId(candidate, tmdbType)
            if (tmdbId != null) break
        }
        tmdbId ?: return null

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
