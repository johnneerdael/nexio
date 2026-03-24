package com.nexio.tv.data.remote

import com.squareup.moshi.Json
import com.squareup.moshi.JsonClass
import com.squareup.moshi.Moshi
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
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

    override suspend fun validate(baseUrl: String, apiKey: String): Boolean {
        val normalizedBaseUrl = normalizeCustomImdbBaseUrl(baseUrl)
        if (normalizedBaseUrl.isBlank() || apiKey.isBlank()) return false

        return runCatching {
            val request = Request.Builder()
                .url(buildCustomImdbUrl(normalizedBaseUrl, "meta/stats"))
                .header("X-API-Key", apiKey.trim())
                .get()
                .build()

            okHttpClient.newCall(request).execute().use { response ->
                response.isSuccessful
            }
        }.getOrDefault(false)
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

        okHttpClient.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                return emptyMap()
            }

            val payload = response.body?.string().orEmpty()
            val parsed = ratingWithEpisodesAdapter.fromJson(payload) ?: return emptyMap()
            return parsed.episodes.mapNotNull { episode ->
                val seasonNumber = episode.seasonNumber ?: return@mapNotNull null
                val episodeNumber = episode.episodeNumber ?: return@mapNotNull null
                val averageRating = episode.averageRating?.takeIf { it > 0.0 } ?: return@mapNotNull null
                (seasonNumber to episodeNumber) to averageRating
            }.toMap()
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
