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
        @Query("meta") meta: String? = "translations",
        @Query("short") short: Boolean? = false
    ): Response<TvdbSeriesExtendedResponse>

    @GET("series/{id}/episodes/{seasonType}")
    suspend fun getSeriesEpisodes(
        @Header("Authorization") authorization: String,
        @Path("id") id: Int,
        @Path("seasonType") seasonType: String = "default",
        @Query("page") page: Int = 0,
        @Query("season") season: Int? = null,
        @Query("episodeNumber") episodeNumber: Int? = null,
        @Query("airDate") airDate: String? = null
    ): Response<TvdbSeriesEpisodesResponse>
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
    @Json(name = "airsDays") val airsDays: TvdbAirsDays? = null,
    @Json(name = "airsTime") val airsTime: String? = null,
    @Json(name = "aliases") val aliases: List<TvdbAlias> = emptyList(),
    @Json(name = "artworks") val artworks: List<TvdbArtworkRecord> = emptyList(),
    @Json(name = "averageRuntime") val averageRuntime: Int? = null,
    @Json(name = "contentRatings") val contentRatings: List<TvdbContentRating> = emptyList(),
    @Json(name = "country") val country: String? = null,
    @Json(name = "episodes") val episodes: List<TvdbEpisodeRecord> = emptyList(),
    @Json(name = "firstAired") val firstAired: String? = null,
    @Json(name = "genres") val genres: List<TvdbGenreRecord> = emptyList(),
    @Json(name = "lastAired") val lastAired: String? = null,
    @Json(name = "originalCountry") val originalCountry: String? = null,
    @Json(name = "originalLanguage") val originalLanguage: String? = null,
    @Json(name = "originalNetwork") val originalNetwork: TvdbCompanyRecord? = null,
    @Json(name = "overview") val overview: String? = null,
    @Json(name = "latestNetwork") val latestNetwork: TvdbCompanyRecord? = null,
    @Json(name = "remoteIds") val remoteIds: List<TvdbRemoteId> = emptyList(),
    @Json(name = "score") val score: Double? = null,
    @Json(name = "status") val status: TvdbStatusRecord? = null,
    @Json(name = "translations") val translations: TvdbTranslations? = null,
    @Json(name = "defaultSeasonType") val defaultSeasonType: Int? = null,
    @Json(name = "seasonTypes") val seasonTypes: List<TvdbSeasonTypeRecord> = emptyList()
)

@JsonClass(generateAdapter = true)
data class TvdbRemoteId(
    @Json(name = "id") val id: String? = null,
    @Json(name = "type") val type: Int? = null,
    @Json(name = "sourceName") val sourceName: String? = null
)

@JsonClass(generateAdapter = true)
data class TvdbSeriesEpisodesResponse(
    @Json(name = "status") val status: String? = null,
    @Json(name = "data") val data: List<TvdbEpisodeRecord> = emptyList()
)

@JsonClass(generateAdapter = true)
data class TvdbAirsDays(
    @Json(name = "monday") val monday: Boolean? = null,
    @Json(name = "tuesday") val tuesday: Boolean? = null,
    @Json(name = "wednesday") val wednesday: Boolean? = null,
    @Json(name = "thursday") val thursday: Boolean? = null,
    @Json(name = "friday") val friday: Boolean? = null,
    @Json(name = "saturday") val saturday: Boolean? = null,
    @Json(name = "sunday") val sunday: Boolean? = null
)

@JsonClass(generateAdapter = true)
data class TvdbAlias(
    @Json(name = "language") val language: String? = null,
    @Json(name = "name") val name: String? = null
)

@JsonClass(generateAdapter = true)
data class TvdbArtworkRecord(
    @Json(name = "id") val id: Int? = null,
    @Json(name = "image") val image: String? = null,
    @Json(name = "language") val language: String? = null,
    @Json(name = "score") val score: Double? = null,
    @Json(name = "thumbnail") val thumbnail: String? = null,
    @Json(name = "type") val type: Int? = null
)

@JsonClass(generateAdapter = true)
data class TvdbEpisodeRecord(
    @Json(name = "absoluteNumber") val absoluteNumber: Int? = null,
    @Json(name = "aired") val aired: String? = null,
    @Json(name = "airsAfterSeason") val airsAfterSeason: Int? = null,
    @Json(name = "airsBeforeEpisode") val airsBeforeEpisode: Int? = null,
    @Json(name = "airsBeforeSeason") val airsBeforeSeason: Int? = null,
    @Json(name = "finaleType") val finaleType: String? = null,
    @Json(name = "id") val id: Int? = null,
    @Json(name = "image") val image: String? = null,
    @Json(name = "linkedMovie") val linkedMovie: Int? = null,
    @Json(name = "name") val name: String? = null,
    @Json(name = "number") val number: Int? = null,
    @Json(name = "overview") val overview: String? = null,
    @Json(name = "runtime") val runtime: Int? = null,
    @Json(name = "seasonNumber") val seasonNumber: Int? = null
)

@JsonClass(generateAdapter = true)
data class TvdbContentRating(
    @Json(name = "country") val country: String? = null,
    @Json(name = "name") val name: String? = null,
    @Json(name = "description") val description: String? = null
)

@JsonClass(generateAdapter = true)
data class TvdbCompanyRecord(
    @Json(name = "id") val id: Int? = null,
    @Json(name = "name") val name: String? = null,
    @Json(name = "slug") val slug: String? = null
)

@JsonClass(generateAdapter = true)
data class TvdbGenreRecord(
    @Json(name = "id") val id: Int? = null,
    @Json(name = "name") val name: String? = null,
    @Json(name = "slug") val slug: String? = null
)

@JsonClass(generateAdapter = true)
data class TvdbStatusRecord(
    @Json(name = "id") val id: Int? = null,
    @Json(name = "name") val name: String? = null,
    @Json(name = "recordType") val recordType: String? = null
)

@JsonClass(generateAdapter = true)
data class TvdbTranslations(
    @Json(name = "nameTranslations") val nameTranslations: List<String> = emptyList(),
    @Json(name = "overviewTranslations") val overviewTranslations: List<String> = emptyList()
)

@JsonClass(generateAdapter = true)
data class TvdbSeasonTypeRecord(
    @Json(name = "id") val id: Int? = null,
    @Json(name = "name") val name: String? = null,
    @Json(name = "type") val type: String? = null,
    @Json(name = "alternateName") val alternateName: String? = null
)
