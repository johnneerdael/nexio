package com.nexio.tv.data.remote

import com.squareup.moshi.Json
import com.squareup.moshi.JsonClass
import com.squareup.moshi.Moshi
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import javax.inject.Inject
import javax.inject.Singleton

private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()

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
        identifiers: List<String>
    ): Map<String, Map<Pair<Int, Int>, Double>>
}

@Singleton
class OkHttpCustomImdbClient @Inject constructor(
    private val okHttpClient: OkHttpClient,
    private val moshi: Moshi
) : CustomImdbClient {
    private val bulkLookupRequestAdapter = moshi.adapter(BulkLookupRequest::class.java)
    private val bulkEpisodeRatingsResponseAdapter = moshi.adapter(BulkEpisodeRatingsResponse::class.java)

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
        identifiers: List<String>
    ): Map<String, Map<Pair<Int, Int>, Double>> {
        val normalizedBaseUrl = normalizeCustomImdbBaseUrl(baseUrl)
        if (normalizedBaseUrl.isBlank() || apiKey.isBlank() || identifiers.isEmpty()) return emptyMap()

        val body = bulkLookupRequestAdapter.toJson(BulkLookupRequest(identifiers = identifiers.distinct()))
            .toRequestBody(JSON_MEDIA_TYPE)

        val request = Request.Builder()
            .url(buildCustomImdbUrl(normalizedBaseUrl, "series/bulk/episode-ratings"))
            .header("X-API-Key", apiKey.trim())
            .post(body)
            .build()

        okHttpClient.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                return emptyMap()
            }

            val payload = response.body?.string().orEmpty()
            val parsed = bulkEpisodeRatingsResponseAdapter.fromJson(payload) ?: return emptyMap()
            return parsed.items.associate { item ->
                item.identifier to item.episodes.mapNotNull { episode ->
                    val seasonNumber = episode.seasonNumber ?: return@mapNotNull null
                    val episodeNumber = episode.episodeNumber ?: return@mapNotNull null
                    val averageRating = episode.averageRating?.takeIf { it > 0.0 } ?: return@mapNotNull null
                    (seasonNumber to episodeNumber) to averageRating
                }.toMap()
            }
        }
    }
}

@JsonClass(generateAdapter = true)
data class BulkLookupRequest(
    val identifiers: List<String> = emptyList()
)

@JsonClass(generateAdapter = true)
data class BulkEpisodeRatingsResponse(
    val items: List<BulkEpisodeRatingsItem> = emptyList()
)

@JsonClass(generateAdapter = true)
data class BulkEpisodeRatingsItem(
    val identifier: String,
    val episodes: List<CustomImdbEpisodeRatingDto> = emptyList()
)

@JsonClass(generateAdapter = true)
data class CustomImdbEpisodeRatingDto(
    @Json(name = "seasonNumber") val seasonNumber: Int? = null,
    @Json(name = "episodeNumber") val episodeNumber: Int? = null,
    @Json(name = "averageRating") val averageRating: Double? = null
)
