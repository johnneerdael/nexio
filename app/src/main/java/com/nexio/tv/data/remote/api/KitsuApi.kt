package com.nexio.tv.data.remote.api

import com.squareup.moshi.Json
import com.squareup.moshi.JsonClass
import retrofit2.Response
import retrofit2.http.GET
import retrofit2.http.Header
import retrofit2.http.Path
import retrofit2.http.Query

interface KitsuApi {
    @GET("anime/{id}")
    suspend fun getAnime(
        @Header("Authorization") authorization: String? = null,
        @Path("id") id: String,
        @Query("include") include: String = "categories,mediaRelationships.destination"
    ): Response<KitsuResourceResponse<KitsuAnimeResource>>

    @GET("anime/{id}/episodes")
    suspend fun getAnimeEpisodes(
        @Header("Authorization") authorization: String? = null,
        @Path("id") id: String,
        @Query("page[limit]") limit: Int = 20,
        @Query("page[offset]") offset: Int = 0
    ): Response<KitsuCollectionResponse<KitsuAnimeResource>>
}

@JsonClass(generateAdapter = true)
data class KitsuResourceResponse<T>(
    @Json(name = "data") val data: T? = null,
    @Json(name = "included") val included: List<KitsuIncludedResource>? = null
)

@JsonClass(generateAdapter = true)
data class KitsuCollectionResponse<T>(
    @Json(name = "data") val data: List<T>? = emptyList(),
    @Json(name = "links") val links: KitsuLinks? = null
)

@JsonClass(generateAdapter = true)
data class KitsuAnimeResource(
    @Json(name = "id") val id: String? = null,
    @Json(name = "type") val type: String? = null,
    @Json(name = "attributes") val attributes: KitsuAnimeAttributes? = null
)

@JsonClass(generateAdapter = true)
data class KitsuAnimeAttributes(
    @Json(name = "canonicalTitle") val canonicalTitle: String? = null,
    @Json(name = "titles") val titles: Map<String, String?>? = null,
    @Json(name = "synopsis") val synopsis: String? = null,
    @Json(name = "description") val description: String? = null,
    @Json(name = "subtype") val subtype: String? = null,
    @Json(name = "status") val status: String? = null,
    @Json(name = "startDate") val startDate: String? = null,
    @Json(name = "endDate") val endDate: String? = null,
    @Json(name = "episodeCount") val episodeCount: Int? = null,
    @Json(name = "episodeLength") val episodeLength: Int? = null,
    @Json(name = "averageRating") val averageRating: String? = null,
    @Json(name = "ageRating") val ageRating: String? = null,
    @Json(name = "posterImage") val posterImage: KitsuImage? = null,
    @Json(name = "coverImage") val coverImage: KitsuImage? = null,
    @Json(name = "youtubeVideoId") val youtubeVideoId: String? = null,
    @Json(name = "number") val number: Int? = null,
    @Json(name = "seasonNumber") val seasonNumber: Int? = null,
    @Json(name = "airdate") val airdate: String? = null,
    @Json(name = "length") val length: Int? = null,
    @Json(name = "thumbnail") val thumbnail: KitsuImage? = null
)

@JsonClass(generateAdapter = true)
data class KitsuImage(
    @Json(name = "tiny") val tiny: String? = null,
    @Json(name = "small") val small: String? = null,
    @Json(name = "medium") val medium: String? = null,
    @Json(name = "large") val large: String? = null,
    @Json(name = "original") val original: String? = null
)

@JsonClass(generateAdapter = true)
data class KitsuIncludedResource(
    @Json(name = "id") val id: String? = null,
    @Json(name = "type") val type: String? = null,
    @Json(name = "attributes") val attributes: Map<String, Any?>? = null
)

@JsonClass(generateAdapter = true)
data class KitsuLinks(
    @Json(name = "next") val next: String? = null
)
