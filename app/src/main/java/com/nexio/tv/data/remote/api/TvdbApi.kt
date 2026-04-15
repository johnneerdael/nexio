package com.nexio.tv.data.remote.api

import com.squareup.moshi.Json
import com.squareup.moshi.JsonClass
import retrofit2.Response
import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.Header
import retrofit2.http.POST
import retrofit2.http.Path
import retrofit2.http.Query

interface TvdbApi {

    @POST("login")
    suspend fun login(
        @Body request: TvdbLoginRequest
    ): Response<TvdbLoginResponse>

    @GET("search")
    suspend fun search(
        @Header("Authorization") authorization: String,
        @Query("remote_id") remoteId: String? = null,
        @Query("query") query: String? = null,
        @Query("type") type: String = "series"
    ): Response<TvdbSearchResponse>

    @GET("search/remoteid/{remoteId}")
    suspend fun searchByRemoteId(
        @Header("Authorization") authorization: String,
        @Path("remoteId") remoteId: String
    ): Response<TvdbRemoteIdSearchResponse>

    @GET("series/{id}")
    suspend fun getSeriesBase(
        @Header("Authorization") authorization: String,
        @Path("id") id: Int
    ): Response<TvdbSeriesBaseResponse>

    @GET("series/{id}/extended")
    suspend fun getSeriesExtended(
        @Header("Authorization") authorization: String,
        @Path("id") id: Int,
        @Query("meta") meta: String? = null,
        @Query("short") short: Boolean? = null
    ): Response<TvdbSeriesExtendedResponse>
}

@JsonClass(generateAdapter = true)
data class TvdbLoginRequest(
    @Json(name = "apikey") val apikey: String,
    @Json(name = "pin") val pin: String? = null
)

@JsonClass(generateAdapter = true)
data class TvdbLoginResponse(
    @Json(name = "status") val status: String? = null,
    @Json(name = "data") val data: Data? = null
) {
    @JsonClass(generateAdapter = true)
    data class Data(
        @Json(name = "token") val token: String? = null
    )
}

@JsonClass(generateAdapter = true)
data class TvdbSearchResponse(
    @Json(name = "status") val status: String? = null,
    @Json(name = "data") val data: List<TvdbSearchResult> = emptyList()
)

@JsonClass(generateAdapter = true)
data class TvdbSearchResult(
    @Json(name = "id") val id: String? = null,
    @Json(name = "tvdb_id") val tvdbId: String? = null,
    @Json(name = "name") val name: String? = null,
    @Json(name = "title") val title: String? = null,
    @Json(name = "type") val type: String? = null,
    @Json(name = "year") val year: String? = null,
    @Json(name = "first_air_time") val firstAirTime: String? = null,
    @Json(name = "remote_ids") val remoteIds: List<TvdbRemoteId> = emptyList()
)

@JsonClass(generateAdapter = true)
data class TvdbRemoteIdSearchResponse(
    @Json(name = "status") val status: String? = null,
    @Json(name = "data") val data: List<TvdbRemoteIdSearchResult> = emptyList()
)

@JsonClass(generateAdapter = true)
data class TvdbRemoteIdSearchResult(
    @Json(name = "series") val series: TvdbSeriesBaseRecord? = null
)

@JsonClass(generateAdapter = true)
data class TvdbSeriesBaseResponse(
    @Json(name = "status") val status: String? = null,
    @Json(name = "data") val data: TvdbSeriesBaseRecord? = null
)

@JsonClass(generateAdapter = true)
data class TvdbSeriesExtendedResponse(
    @Json(name = "status") val status: String? = null,
    @Json(name = "data") val data: TvdbSeriesExtendedRecord? = null
)

@JsonClass(generateAdapter = true)
data class TvdbSeriesBaseRecord(
    @Json(name = "id") val id: Int? = null,
    @Json(name = "name") val name: String? = null,
    @Json(name = "slug") val slug: String? = null,
    @Json(name = "image") val image: String? = null,
    @Json(name = "firstAired") val firstAired: String? = null,
    @Json(name = "lastAired") val lastAired: String? = null
)

@JsonClass(generateAdapter = true)
data class TvdbSeriesExtendedRecord(
    @Json(name = "id") val id: Int? = null,
    @Json(name = "name") val name: String? = null,
    @Json(name = "slug") val slug: String? = null,
    @Json(name = "image") val image: String? = null,
    @Json(name = "firstAired") val firstAired: String? = null,
    @Json(name = "lastAired") val lastAired: String? = null,
    @Json(name = "airsTime") val airsTime: String? = null,
    @Json(name = "remoteIds") val remoteIds: List<TvdbRemoteId> = emptyList()
)

@JsonClass(generateAdapter = true)
data class TvdbRemoteId(
    @Json(name = "id") val id: String? = null,
    @Json(name = "type") val type: Int? = null,
    @Json(name = "sourceName") val sourceName: String? = null
)
