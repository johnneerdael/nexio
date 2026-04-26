package com.nexio.tv.data.remote.api

import com.squareup.moshi.Json
import com.squareup.moshi.JsonClass
import retrofit2.Response
import retrofit2.http.GET
import retrofit2.http.Header
import retrofit2.http.Path
import retrofit2.http.Query

interface KitsuApi {
    @GET("trending/anime")
    suspend fun getTrendingAnime(
        @Query("page[limit]") limit: Int = 20,
        @Query("page[offset]") offset: Int = 0
    ): Response<KitsuCollectionResponse<KitsuAnimeResource>>

    @GET("anime")
    suspend fun getAnimeCollection(
        @Header("Authorization") authorization: String? = null,
        @Query("sort") sort: String? = null,
        @Query("filter[categories]") category: String? = null,
        @Query("filter[text]") text: String? = null,
        @Query("page[limit]") limit: Int = 20,
        @Query("page[offset]") offset: Int = 0
    ): Response<KitsuCollectionResponse<KitsuAnimeResource>>

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

    @GET("anime/{id}/anime-characters")
    suspend fun getAnimeCharacters(
        @Header("Authorization") authorization: String? = null,
        @Path("id") id: String,
        @Query("include") include: String = "character",
        @Query("page[limit]") limit: Int = 40,
        @Query("page[offset]") offset: Int = 0
    ): Response<KitsuCollectionResponse<KitsuAnimeCharacterResource>>

    @GET("anime/{id}/anime-staff")
    suspend fun getAnimeStaff(
        @Header("Authorization") authorization: String? = null,
        @Path("id") id: String,
        @Query("include") include: String = "person",
        @Query("page[limit]") limit: Int = 40,
        @Query("page[offset]") offset: Int = 0
    ): Response<KitsuCollectionResponse<KitsuAnimeStaffResource>>

    @GET("anime/{id}/anime-productions")
    suspend fun getAnimeProductions(
        @Header("Authorization") authorization: String? = null,
        @Path("id") id: String,
        @Query("include") include: String = "producer",
        @Query("page[limit]") limit: Int = 40,
        @Query("page[offset]") offset: Int = 0
    ): Response<KitsuCollectionResponse<KitsuAnimeProductionResource>>

    @GET("anime/{id}/media-relationships")
    suspend fun getAnimeMediaRelationships(
        @Header("Authorization") authorization: String? = null,
        @Path("id") id: String,
        @Query("include") include: String = "destination",
        @Query("page[limit]") limit: Int = 40,
        @Query("page[offset]") offset: Int = 0
    ): Response<KitsuCollectionResponse<KitsuMediaRelationshipResource>>

    @GET("anime/{id}/installments")
    suspend fun getAnimeInstallments(
        @Header("Authorization") authorization: String? = null,
        @Path("id") id: String,
        @Query("include") include: String = "media",
        @Query("page[limit]") limit: Int = 40,
        @Query("page[offset]") offset: Int = 0
    ): Response<KitsuCollectionResponse<KitsuInstallmentResource>>

    @GET("characters")
    suspend fun getCharacters(
        @Header("Authorization") authorization: String? = null,
        @Query("page[limit]") limit: Int = 40,
        @Query("page[offset]") offset: Int = 0
    ): Response<KitsuCollectionResponse<KitsuIncludedResource>>

    @GET("castings")
    suspend fun getCastings(
        @Header("Authorization") authorization: String? = null,
        @Query("page[limit]") limit: Int = 40,
        @Query("page[offset]") offset: Int = 0
    ): Response<KitsuCollectionResponse<KitsuIncludedResource>>

    @GET("people")
    suspend fun getPeople(
        @Header("Authorization") authorization: String? = null,
        @Query("page[limit]") limit: Int = 40,
        @Query("page[offset]") offset: Int = 0
    ): Response<KitsuCollectionResponse<KitsuIncludedResource>>

    @GET("producers")
    suspend fun getProducers(
        @Header("Authorization") authorization: String? = null,
        @Query("page[limit]") limit: Int = 40,
        @Query("page[offset]") offset: Int = 0
    ): Response<KitsuCollectionResponse<KitsuIncludedResource>>

    @GET("franchises")
    suspend fun getFranchises(
        @Header("Authorization") authorization: String? = null,
        @Query("page[limit]") limit: Int = 40,
        @Query("page[offset]") offset: Int = 0
    ): Response<KitsuCollectionResponse<KitsuIncludedResource>>

    @GET("castings")
    suspend fun getCastingsByMedia(
        @Header("Authorization") authorization: String? = null,
        @Query("filter[mediaId]") mediaId: String,
        @Query("include") include: String = "person,character",
        @Query("page[limit]") limit: Int = 40,
        @Query("page[offset]") offset: Int = 0
    ): Response<KitsuCollectionResponse<KitsuCastingResource>>
}

@JsonClass(generateAdapter = true)
data class KitsuResourceResponse<T>(
    @Json(name = "data") val data: T? = null,
    @Json(name = "included") val included: List<KitsuIncludedResource>? = null
)

@JsonClass(generateAdapter = true)
data class KitsuCollectionResponse<T>(
    @Json(name = "data") val data: List<T>? = emptyList(),
    @Json(name = "included") val included: List<KitsuIncludedResource>? = null,
    @Json(name = "links") val links: KitsuLinks? = null
)

@JsonClass(generateAdapter = true)
data class KitsuAnimeResource(
    @Json(name = "id") val id: String? = null,
    @Json(name = "type") val type: String? = null,
    @Json(name = "attributes") val attributes: KitsuAnimeAttributes? = null
)

@JsonClass(generateAdapter = true)
data class KitsuAnimeCharacterResource(
    @Json(name = "id") val id: String? = null,
    @Json(name = "type") val type: String? = null,
    @Json(name = "attributes") val attributes: KitsuAnimeCharacterAttributes? = null,
    @Json(name = "relationships") val relationships: KitsuAnimeCharacterRelationships? = null
)

@JsonClass(generateAdapter = true)
data class KitsuAnimeStaffResource(
    @Json(name = "id") val id: String? = null,
    @Json(name = "type") val type: String? = null,
    @Json(name = "attributes") val attributes: KitsuAnimeStaffAttributes? = null,
    @Json(name = "relationships") val relationships: KitsuAnimeStaffRelationships? = null
)

@JsonClass(generateAdapter = true)
data class KitsuAnimeProductionResource(
    @Json(name = "id") val id: String? = null,
    @Json(name = "type") val type: String? = null,
    @Json(name = "attributes") val attributes: KitsuAnimeProductionAttributes? = null,
    @Json(name = "relationships") val relationships: KitsuAnimeProductionRelationships? = null
)

@JsonClass(generateAdapter = true)
data class KitsuMediaRelationshipResource(
    @Json(name = "id") val id: String? = null,
    @Json(name = "type") val type: String? = null,
    @Json(name = "attributes") val attributes: KitsuMediaRelationshipAttributes? = null,
    @Json(name = "relationships") val relationships: KitsuMediaRelationshipRelationships? = null
)

@JsonClass(generateAdapter = true)
data class KitsuInstallmentResource(
    @Json(name = "id") val id: String? = null,
    @Json(name = "type") val type: String? = null,
    @Json(name = "attributes") val attributes: KitsuInstallmentAttributes? = null,
    @Json(name = "relationships") val relationships: KitsuInstallmentRelationships? = null
)

@JsonClass(generateAdapter = true)
data class KitsuCastingResource(
    @Json(name = "id") val id: String? = null,
    @Json(name = "type") val type: String? = null,
    @Json(name = "attributes") val attributes: KitsuCastingAttributes? = null,
    @Json(name = "relationships") val relationships: KitsuCastingRelationships? = null
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
data class KitsuAnimeCharacterAttributes(
    @Json(name = "role") val role: String? = null
)

@JsonClass(generateAdapter = true)
data class KitsuAnimeStaffAttributes(
    @Json(name = "role") val role: String? = null
)

@JsonClass(generateAdapter = true)
data class KitsuAnimeProductionAttributes(
    @Json(name = "role") val role: String? = null
)

@JsonClass(generateAdapter = true)
data class KitsuMediaRelationshipAttributes(
    @Json(name = "role") val role: String? = null
)

@JsonClass(generateAdapter = true)
data class KitsuInstallmentAttributes(
    @Json(name = "tag") val tag: Int? = null
)

@JsonClass(generateAdapter = true)
data class KitsuCastingAttributes(
    @Json(name = "role") val role: String? = null,
    @Json(name = "voiceActor") val voiceActor: Boolean? = null,
    @Json(name = "featured") val featured: Boolean? = null,
    @Json(name = "language") val language: String? = null
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
data class KitsuAnimeCharacterRelationships(
    @Json(name = "character") val character: KitsuToOneRelationship? = null
)

@JsonClass(generateAdapter = true)
data class KitsuAnimeStaffRelationships(
    @Json(name = "person") val person: KitsuToOneRelationship? = null
)

@JsonClass(generateAdapter = true)
data class KitsuAnimeProductionRelationships(
    @Json(name = "producer") val producer: KitsuToOneRelationship? = null
)

@JsonClass(generateAdapter = true)
data class KitsuMediaRelationshipRelationships(
    @Json(name = "destination") val destination: KitsuToOneRelationship? = null,
    @Json(name = "source") val source: KitsuToOneRelationship? = null
)

@JsonClass(generateAdapter = true)
data class KitsuInstallmentRelationships(
    @Json(name = "media") val media: KitsuToOneRelationship? = null,
    @Json(name = "franchise") val franchise: KitsuToOneRelationship? = null
)

@JsonClass(generateAdapter = true)
data class KitsuCastingRelationships(
    @Json(name = "person") val person: KitsuToOneRelationship? = null,
    @Json(name = "character") val character: KitsuToOneRelationship? = null
)

@JsonClass(generateAdapter = true)
data class KitsuToOneRelationship(
    @Json(name = "data") val data: KitsuResourceIdentifier? = null
)

@JsonClass(generateAdapter = true)
data class KitsuResourceIdentifier(
    @Json(name = "id") val id: String? = null,
    @Json(name = "type") val type: String? = null
)

@JsonClass(generateAdapter = true)
data class KitsuLinks(
    @Json(name = "next") val next: String? = null
)
