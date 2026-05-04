package com.nexio.tv.data.remote

import android.util.Log
import com.nexio.tv.BuildConfig
import com.nexio.tv.core.integration.IntegrationCallResult
import com.nexio.tv.data.integration.imdb.CustomImdbPayload
import com.nexio.tv.data.integration.imdb.CustomImdbRatingsIntegrationProvider
import com.nexio.tv.data.integration.imdb.transport.CustomImdbRatingsRequests
import com.squareup.moshi.JsonDataException
import com.squareup.moshi.Json
import com.squareup.moshi.JsonClass
import com.squareup.moshi.Moshi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import okhttp3.Request
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton

private const val CUSTOM_IMDB_CLIENT_TAG = "CustomImdbClient"

fun normalizeCustomImdbBaseUrl(rawBaseUrl: String): String {
    return rawBaseUrl.trim().trimEnd('/')
}

interface CustomImdbClient {
    suspend fun validate(baseUrl: String, apiKey: String): Boolean

    suspend fun fetchEpisodeRatings(tconst: String): Map<Pair<Int, Int>, Double>

    suspend fun fetchTitleRatings(identifiers: List<String>): Map<String, Double>
}

@Singleton
class OkHttpCustomImdbClient @Inject constructor(
    private val integrationProvider: CustomImdbRatingsIntegrationProvider,
    moshi: Moshi
) : CustomImdbClient {
    private val ratingWithEpisodesAdapter = moshi.adapter(RatingWithEpisodes::class.java)
    private val bulkRatingsRequestAdapter = moshi.adapter(BulkRatingsRequest::class.java)
    private val bulkRatingsResponseAdapter = moshi.adapter(BulkRatingsResponse::class.java)
    internal var delayMs: suspend (Long) -> Unit = { delay(it) }

    internal var baseUrlProvider: () -> String = { BuildConfig.IMDB_API_URL }
    internal var apiKeyProvider: () -> String = { BuildConfig.IMDB_API_KEY }

    override suspend fun validate(baseUrl: String, apiKey: String): Boolean {
        val normalizedBaseUrl = normalizeCustomImdbBaseUrl(baseUrl)
        val trimmedApiKey = apiKey.trim()
        if (normalizedBaseUrl.isBlank() || trimmedApiKey.isBlank()) return false

        val request = CustomImdbRatingsRequests.validateStats(
            baseUrl = normalizedBaseUrl,
            apiKey = trimmedApiKey
        )

        return executeWithRateLimitRetry(
            baseUrl = normalizedBaseUrl,
            request = request,
            onHttpError = { error ->
                Log.w(
                    CUSTOM_IMDB_CLIENT_TAG,
                    "Custom IMDb validation failed with HTTP ${error.statusCode} for $normalizedBaseUrl"
                )
                false
            },
            onNetworkError = { false },
            onMissing = { false }
        ) { response ->
            true
        }
    }

    override suspend fun fetchEpisodeRatings(tconst: String): Map<Pair<Int, Int>, Double> {
        val baseUrl = normalizeCustomImdbBaseUrl(baseUrlProvider())
        val apiKey = apiKeyProvider().trim()
        val normalizedTconst = tconst.trim()
        if (baseUrl.isBlank() || apiKey.isBlank() || normalizedTconst.isBlank()) return emptyMap()

        val request = CustomImdbRatingsRequests.episodeRatings(
            baseUrl = baseUrl,
            apiKey = apiKey,
            tconst = normalizedTconst
        )

        return executeWithRateLimitRetry(
            baseUrl = baseUrl,
            request = request,
            onHttpError = { error ->
                Log.w(
                    CUSTOM_IMDB_CLIENT_TAG,
                    "Custom IMDb ratings request failed with HTTP ${error.statusCode} for $normalizedTconst"
                )
                emptyMap()
            },
            onNetworkError = { emptyMap() },
            onMissing = { emptyMap() }
        ) { response ->
            val parsed = response.body.parseOrNull(ratingWithEpisodesAdapter)
                ?: return@executeWithRateLimitRetry emptyMap()
            parsed.episodes.mapNotNull { episode ->
                val seasonNumber = episode.seasonNumber ?: return@mapNotNull null
                val episodeNumber = episode.episodeNumber ?: return@mapNotNull null
                val averageRating = episode.averageRating?.takeIf(::isValidImdbAverageRating) ?: return@mapNotNull null
                (seasonNumber to episodeNumber) to averageRating
            }.toMap()
        }
    }

    override suspend fun fetchTitleRatings(identifiers: List<String>): Map<String, Double> {
        val baseUrl = normalizeCustomImdbBaseUrl(baseUrlProvider())
        val apiKey = apiKeyProvider().trim()
        val normalizedIdentifiers = identifiers
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .distinct()
        if (baseUrl.isBlank() || apiKey.isBlank() || normalizedIdentifiers.isEmpty()) return emptyMap()

        val bodyJson = bulkRatingsRequestAdapter
            .toJson(BulkRatingsRequest(normalizedIdentifiers))
        val request = CustomImdbRatingsRequests.bulkTitleRatings(
            baseUrl = baseUrl,
            apiKey = apiKey,
            bodyJson = bodyJson
        )

        return executeWithRateLimitRetry(
            baseUrl = baseUrl,
            request = request,
            onHttpError = { error ->
                Log.w(
                    CUSTOM_IMDB_CLIENT_TAG,
                    "Custom IMDb bulk ratings request failed with HTTP ${error.statusCode}"
                )
                emptyMap()
            },
            onNetworkError = { emptyMap() },
            onMissing = { emptyMap() }
        ) { response ->
            val parsed = response.body.parseOrNull(bulkRatingsResponseAdapter)
                ?: return@executeWithRateLimitRetry emptyMap()
            parsed.results.mapNotNull { rating ->
                val tconst = rating.tconst.trim().takeIf { it.isNotBlank() } ?: return@mapNotNull null
                val value = rating.averageRating?.takeIf(::isValidImdbAverageRating) ?: return@mapNotNull null
                tconst to value
            }.toMap()
        }
    }

    private suspend fun <T> executeWithRateLimitRetry(
        baseUrl: String,
        request: Request,
        onHttpError: (IntegrationCallResult.HttpError) -> T,
        onNetworkError: (IntegrationCallResult.NetworkError) -> T,
        onMissing: () -> T,
        onResponse: (CustomImdbPayload) -> T
    ): T {
        return withContext(Dispatchers.IO) {
            var hasRetriedRateLimit = false

            while (true) {
                when (val result = integrationProvider.execute(baseUrl = baseUrl, request = request)) {
                    is IntegrationCallResult.Success -> return@withContext onResponse(result.value)
                    is IntegrationCallResult.HttpError -> {
                        if (result.statusCode == 429 && !hasRetriedRateLimit) {
                            val retryDelayMs = result.retryAfterMs ?: 1_000L
                            hasRetriedRateLimit = true
                            delayMs(retryDelayMs)
                            continue
                        }
                        return@withContext onHttpError(result)
                    }
                    is IntegrationCallResult.NetworkError -> return@withContext onNetworkError(result)
                    IntegrationCallResult.Missing -> return@withContext onMissing()
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

    private fun isValidImdbAverageRating(value: Double): Boolean {
        return value > 0.0 && value <= 10.0
    }
}

private fun <T> String.parseOrNull(adapter: com.squareup.moshi.JsonAdapter<T>): T? {
    return try {
        adapter.fromJson(this)
    } catch (_: IOException) {
        null
    } catch (_: JsonDataException) {
        null
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
