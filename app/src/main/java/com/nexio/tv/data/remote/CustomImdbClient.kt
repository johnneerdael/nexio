package com.nexio.tv.data.remote

import com.squareup.moshi.Json
import com.squareup.moshi.JsonClass
import com.squareup.moshi.Moshi
import kotlinx.coroutines.delay
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton

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
}

@Singleton
class OkHttpCustomImdbClient @Inject constructor(
    private val okHttpClient: OkHttpClient,
    private val moshi: Moshi
) : CustomImdbClient {
    private val ratingWithEpisodesAdapter = moshi.adapter(RatingWithEpisodes::class.java)
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

    private suspend fun <T> executeWithRateLimitRetry(
        request: Request,
        onFailure: (IOException) -> T,
        onResponse: (Response) -> T
    ): T {
        var hasRetriedRateLimit = false

        while (true) {
            try {
                var retryDelayMs: Long? = null

                okHttpClient.newCall(request).execute().use { response ->
                    if (response.code == 429 && !hasRetriedRateLimit) {
                        retryDelayMs = parseRetryAfterDelayMs(response.header("Retry-After"))
                        return@use
                    }

                    return onResponse(response)
                }

                if (retryDelayMs != null) {
                    hasRetriedRateLimit = true
                    delayMs(retryDelayMs)
                    continue
                }

                error("Unreachable response state for custom IMDb request.")
            } catch (error: IOException) {
                return onFailure(error)
            }
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
