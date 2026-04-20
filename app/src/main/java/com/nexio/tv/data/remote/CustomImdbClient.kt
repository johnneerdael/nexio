package com.nexio.tv.data.remote

import android.util.Log
import com.squareup.moshi.Json
import com.squareup.moshi.JsonClass
import com.squareup.moshi.Moshi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton

private const val CUSTOM_IMDB_CLIENT_TAG = "CustomImdbClient"

fun normalizeCustomImdbBaseUrl(rawBaseUrl: String): String {
    return rawBaseUrl.trim().trimEnd('/')
}

private fun buildCustomImdbUrl(baseUrl: String, pathAfterVersion: String): String {
    val normalizedBaseUrl = normalizeCustomImdbBaseUrl(baseUrl)
    val normalizedPath = pathAfterVersion.trimStart('/')
    val hasVersionPath = normalizedBaseUrl.lowercase().endsWith("/v1")

    return if (hasVersionPath) {
        "$normalizedBaseUrl/$normalizedPath"
    } else {
        "$normalizedBaseUrl/v1/$normalizedPath"
    }
}

interface CustomImdbClient {
    suspend fun validate(baseUrl: String, apiKey: String): Boolean

    suspend fun fetchEpisodeRatings(
        baseUrl: String,
        apiKey: String,
        tconst: String
    ): Map<Pair<Int, Int>, Double>

    suspend fun fetchTitleRatings(
        baseUrl: String,
        apiKey: String,
        identifiers: List<String>
    ): Map<String, Double>
}

@Singleton
class OkHttpCustomImdbClient @Inject constructor(
    private val okHttpClient: OkHttpClient,
    private val moshi: Moshi
) : CustomImdbClient {
    private val ratingWithEpisodesAdapter = moshi.adapter(RatingWithEpisodes::class.java)
    private val bulkRatingsRequestAdapter = moshi.adapter(BulkRatingsRequest::class.java)
    private val bulkRatingsResponseAdapter = moshi.adapter(BulkRatingsResponse::class.java)
    internal var delayMs: suspend (Long) -> Unit = { delay(it) }

    override suspend fun validate(baseUrl: String, apiKey: String): Boolean {
        val normalizedBaseUrl = normalizeCustomImdbBaseUrl(baseUrl)
        if (normalizedBaseUrl.isBlank() || apiKey.isBlank()) return false

        val request = Request.Builder()
            .url(buildCustomImdbUrl(normalizedBaseUrl, "meta/stats"))
            .header("X-API-Key", apiKey.trim())
            .get()
            .build()

        return executeWithRateLimitRetry(
            request = request,
            onFailure = { false }
        ) { response ->
            if (!response.isSuccessful) {
                Log.w(
                    CUSTOM_IMDB_CLIENT_TAG,
                    "Custom IMDb validation failed with HTTP ${response.code} for $normalizedBaseUrl"
                )
            }
            response.isSuccessful
        }
    }

    override suspend fun fetchEpisodeRatings(
        baseUrl: String,
        apiKey: String,
        tconst: String
    ): Map<Pair<Int, Int>, Double> {
        val normalizedBaseUrl = normalizeCustomImdbBaseUrl(baseUrl)
        val normalizedTconst = tconst.trim()
        if (normalizedBaseUrl.isBlank() || apiKey.isBlank() || normalizedTconst.isBlank()) return emptyMap()

        val endpoint = buildCustomImdbUrl(normalizedBaseUrl, "ratings/$normalizedTconst")
            .toHttpUrl()
            .newBuilder()
            .addQueryParameter("episodes", "true")
            .build()
        val request = Request.Builder()
            .url(endpoint)
            .header("X-API-Key", apiKey.trim())
            .get()
            .build()

        return executeWithRateLimitRetry(
            request = request,
            onFailure = { emptyMap() }
        ) { response ->
            if (!response.isSuccessful) {
                Log.w(
                    CUSTOM_IMDB_CLIENT_TAG,
                    "Custom IMDb ratings request failed with HTTP ${response.code} for $normalizedTconst"
                )
                return@executeWithRateLimitRetry emptyMap()
            }

            val payload = response.body?.string().orEmpty()
            val parsed = ratingWithEpisodesAdapter.fromJson(payload) ?: return@executeWithRateLimitRetry emptyMap()
            parsed.episodes.mapNotNull { episode ->
                val seasonNumber = episode.seasonNumber ?: return@mapNotNull null
                val episodeNumber = episode.episodeNumber ?: return@mapNotNull null
                val averageRating = episode.averageRating?.takeIf { it > 0.0 } ?: return@mapNotNull null
                (seasonNumber to episodeNumber) to averageRating
            }.toMap()
        }
    }

    override suspend fun fetchTitleRatings(
        baseUrl: String,
        apiKey: String,
        identifiers: List<String>
    ): Map<String, Double> {
        val normalizedBaseUrl = normalizeCustomImdbBaseUrl(baseUrl)
        val normalizedIdentifiers = identifiers
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .distinct()
        if (normalizedBaseUrl.isBlank() || apiKey.isBlank() || normalizedIdentifiers.isEmpty()) return emptyMap()

        val body = bulkRatingsRequestAdapter
            .toJson(BulkRatingsRequest(normalizedIdentifiers))
            .toRequestBody("application/json".toMediaType())
        val request = Request.Builder()
            .url(buildCustomImdbUrl(normalizedBaseUrl, "ratings/bulk"))
            .header("X-API-Key", apiKey.trim())
            .post(body)
            .build()

        return executeWithRateLimitRetry(
            request = request,
            onFailure = { emptyMap() }
        ) { response ->
            if (!response.isSuccessful) {
                Log.w(
                    CUSTOM_IMDB_CLIENT_TAG,
                    "Custom IMDb bulk ratings request failed with HTTP ${response.code}"
                )
                return@executeWithRateLimitRetry emptyMap()
            }

            val payload = response.body?.string().orEmpty()
            val parsed = bulkRatingsResponseAdapter.fromJson(payload) ?: return@executeWithRateLimitRetry emptyMap()
            parsed.results.mapNotNull { rating ->
                val tconst = rating.tconst.trim().takeIf { it.isNotBlank() } ?: return@mapNotNull null
                val value = rating.averageRating?.takeIf { it > 0.0 } ?: return@mapNotNull null
                tconst to value
            }.toMap()
        }
    }

    private suspend fun <T> executeWithRateLimitRetry(
        request: Request,
        onFailure: (IOException) -> T,
        onResponse: (Response) -> T
    ): T {
        return withContext(Dispatchers.IO) {
            var hasRetriedRateLimit = false

            while (true) {
                try {
                    var retryDelayMs: Long? = null

                    okHttpClient.newCall(request).execute().use { response ->
                        if (response.code == 429 && !hasRetriedRateLimit) {
                            retryDelayMs = parseRetryAfterDelayMs(response.header("Retry-After"))
                        } else {
                            return@withContext onResponse(response)
                        }
                    }

                    if (retryDelayMs != null) {
                        hasRetriedRateLimit = true
                        delayMs(retryDelayMs)
                        continue
                    }
                } catch (error: IOException) {
                    return@withContext onFailure(error)
                }
            }

            error("Unreachable response state for custom IMDb request.")
        }
    }

    private fun parseRetryAfterDelayMs(retryAfterHeader: String?): Long {
        val seconds = retryAfterHeader?.trim()?.toLongOrNull()
        return if (seconds != null && seconds >= 0L) {
            seconds * 1_000L
        } else {
            1_000L
        }
    }
}

@JsonClass(generateAdapter = true)
data class RatingDto(
    val tconst: String,
    @Json(name = "averageRating") val averageRating: Double? = null,
    @Json(name = "numVotes") val numVotes: Int? = null
)

@JsonClass(generateAdapter = true)
data class BulkRatingsRequest(
    val identifiers: List<String>
)

@JsonClass(generateAdapter = true)
data class BulkRatingsResponse(
    val results: List<RatingDto> = emptyList(),
    val missing: List<String> = emptyList()
)

@JsonClass(generateAdapter = true)
data class RatingWithEpisodes(
    val requestTconst: String,
    val rating: RatingDto? = null,
    val episodesParentTconst: String? = null,
    val episodes: List<CustomImdbEpisodeRatingDto> = emptyList()
)

@JsonClass(generateAdapter = true)
data class CustomImdbEpisodeRatingDto(
    val tconst: String? = null,
    val parentTconst: String? = null,
    @Json(name = "seasonNumber") val seasonNumber: Int? = null,
    @Json(name = "episodeNumber") val episodeNumber: Int? = null,
    @Json(name = "averageRating") val averageRating: Double? = null,
    @Json(name = "numVotes") val numVotes: Int? = null
)
