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

    @GET("series/{id}/translations/{language}")
    suspend fun getSeriesTranslation(
        @Header("Authorization") authorization: String,
        @Path("id") id: Int,
        @Path("language") language: String
    ): Response<TvdbTranslationResponse>

    @GET("series/{id}/episodes/{seasonType}/{language}")
    suspend fun getSeriesEpisodesTranslated(
        @Header("Authorization") authorization: String,
        @Path("id") id: Int,
        @Path("seasonType") seasonType: String = "default",
        @Path("language") language: String,
        @Query("page") page: Int = 0,
        @Query("season") season: Int? = null,
        @Query("episodeNumber") episodeNumber: Int? = null,
        @Query("airDate") airDate: String? = null
    ): Response<TvdbSeriesEpisodesResponse>

    @GET("updates")
    suspend fun getUpdates(
        @Header("Authorization") authorization: String,
        @Query("since") since: Long,
        @Query("type") type: String? = null,
        @Query("page") page: Int? = null
    ): Response<TvdbUpdatesResponse>

    @GET("artwork/types")
    suspend fun getArtworkTypes(
        @Header("Authorization") authorization: String
    ): Response<TvdbReferenceResponse<TvdbArtworkTypeRecord>>

    @GET("artwork/statuses")
    suspend fun getArtworkStatuses(
        @Header("Authorization") authorization: String
    ): Response<TvdbReferenceResponse<TvdbArtworkStatusRecord>>

    @GET("genres")
    suspend fun getGenres(
        @Header("Authorization") authorization: String
    ): Response<TvdbReferenceResponse<TvdbGenreReferenceRecord>>

    @GET("languages")
    suspend fun getLanguages(
        @Header("Authorization") authorization: String
    ): Response<TvdbReferenceResponse<TvdbLanguageRecord>>

    @GET("series/statuses")
    suspend fun getSeriesStatuses(
        @Header("Authorization") authorization: String
    ): Response<TvdbReferenceResponse<TvdbSeriesStatusRecord>>

    @GET("content/ratings")
    suspend fun getContentRatings(
        @Header("Authorization") authorization: String
    ): Response<TvdbReferenceResponse<TvdbContentRatingRecord>>

    @GET("seasons/types")
    suspend fun getSeasonTypes(
        @Header("Authorization") authorization: String
    ): Response<TvdbReferenceResponse<TvdbSeasonTypeReferenceRecord>>

    @GET("sources/types")
    suspend fun getSourceTypes(
        @Header("Authorization") authorization: String
    ): Response<TvdbReferenceResponse<TvdbSourceTypeRecord>>

    @GET("entities")
    suspend fun getEntityTypes(
        @Header("Authorization") authorization: String
    ): Response<TvdbReferenceResponse<TvdbEntityTypeRecord>>

    @GET("companies/types")
    suspend fun getCompanyTypes(
        @Header("Authorization") authorization: String
    ): Response<TvdbReferenceResponse<TvdbCompanyTypeRecord>>

    @GET("people/{id}/extended")
    suspend fun getPersonExtended(
        @Header("Authorization") authorization: String,
        @Path("id") id: Int
    ): Response<TvdbPersonExtendedResponse>
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
    @Json(name = "data") val data: List<TvdbSearchResult>? = emptyList()
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
    @Json(name = "remote_ids") val remoteIds: List<TvdbRemoteId>? = emptyList()
)

@JsonClass(generateAdapter = true)
data class TvdbRemoteIdSearchResponse(
    @Json(name = "status") val status: String? = null,
    @Json(name = "data") val data: List<TvdbRemoteIdSearchResult>? = emptyList()
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
    @Json(name = "aliases") val aliases: List<TvdbAlias>? = emptyList(),
    @Json(name = "artworks") val artworks: List<TvdbArtworkRecord>? = emptyList(),
    @Json(name = "averageRuntime") val averageRuntime: Int? = null,
    @Json(name = "contentRatings") val contentRatings: List<TvdbContentRating>? = emptyList(),
    @Json(name = "country") val country: String? = null,
    @Json(name = "episodes") val episodes: List<TvdbEpisodeRecord>? = emptyList(),
    @Json(name = "firstAired") val firstAired: String? = null,
    @Json(name = "genres") val genres: List<TvdbGenreRecord>? = emptyList(),
    @Json(name = "lastAired") val lastAired: String? = null,
    @Json(name = "originalCountry") val originalCountry: String? = null,
    @Json(name = "originalLanguage") val originalLanguage: String? = null,
    @Json(name = "originalNetwork") val originalNetwork: TvdbCompanyRecord? = null,
    @Json(name = "overview") val overview: String? = null,
    @Json(name = "latestNetwork") val latestNetwork: TvdbCompanyRecord? = null,
    @Json(name = "remoteIds") val remoteIds: List<TvdbRemoteId>? = emptyList(),
    @Json(name = "score") val score: Double? = null,
    @Json(name = "status") val status: TvdbStatusRecord? = null,
    @Json(name = "translations") val translations: TvdbTranslations? = null,
    @Json(name = "defaultSeasonType") val defaultSeasonType: Int? = null,
    @Json(name = "seasonTypes") val seasonTypes: List<TvdbSeasonTypeRecord>? = emptyList(),
    @Json(name = "characters") val characters: List<TvdbCharacterRecord>? = emptyList(),
    @Json(name = "companies") val companies: List<TvdbCompanyExtendedRecord>? = emptyList(),
    @Json(name = "trailers") val trailers: List<TvdbTrailerRecord>? = emptyList()
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
    @Json(name = "data") val data: TvdbSeriesEpisodesData? = null
)

@JsonClass(generateAdapter = true)
data class TvdbSeriesEpisodesData(
    @Json(name = "series") val series: TvdbSeriesBaseRecord? = null,
    @Json(name = "episodes") val episodes: List<TvdbEpisodeRecord> = emptyList()
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
    @Json(name = "nameTranslations") val nameTranslations: List<String>? = emptyList(),
    @Json(name = "overviewTranslations") val overviewTranslations: List<String>? = emptyList()
)

@JsonClass(generateAdapter = true)
data class TvdbTranslationResponse(
    @Json(name = "status") val status: String? = null,
    @Json(name = "data") val data: TvdbTranslationRecord? = null
)

@JsonClass(generateAdapter = true)
data class TvdbTranslationRecord(
    @Json(name = "aliases") val aliases: List<String>? = emptyList(),
    @Json(name = "isAlias") val isAlias: Boolean? = null,
    @Json(name = "isPrimary") val isPrimary: Boolean? = null,
    @Json(name = "language") val language: String? = null,
    @Json(name = "name") val name: String? = null,
    @Json(name = "overview") val overview: String? = null,
    @Json(name = "tagline") val tagline: String? = null
)

@JsonClass(generateAdapter = true)
data class TvdbSeasonTypeRecord(
    @Json(name = "id") val id: Int? = null,
    @Json(name = "name") val name: String? = null,
    @Json(name = "type") val type: String? = null,
    @Json(name = "alternateName") val alternateName: String? = null
)

@JsonClass(generateAdapter = true)
data class TvdbCharacterRecord(
    @Json(name = "id") val id: Int? = null,
    @Json(name = "name") val name: String? = null,
    @Json(name = "personName") val personName: String? = null,
    @Json(name = "personImgURL") val personImgURL: String? = null,
    @Json(name = "peopleId") val peopleId: Int? = null,
    @Json(name = "peopleType") val peopleType: String? = null,
    @Json(name = "seriesId") val seriesId: Int? = null,
    @Json(name = "movieId") val movieId: Int? = null,
    @Json(name = "series") val series: TvdbLinkedEntity? = null,
    @Json(name = "movie") val movie: TvdbLinkedEntity? = null,
    @Json(name = "sort") val sort: Int? = null,
    @Json(name = "type") val type: Int? = null,
    @Json(name = "url") val url: String? = null,
    @Json(name = "nameTranslations") val nameTranslations: List<String>? = emptyList(),
    @Json(name = "isFeatured") val isFeatured: Boolean? = null
)

@JsonClass(generateAdapter = true)
data class TvdbLinkedEntity(
    @Json(name = "image") val image: String? = null,
    @Json(name = "name") val name: String? = null,
    @Json(name = "year") val year: String? = null
)

@JsonClass(generateAdapter = true)
data class TvdbPersonExtendedResponse(
    @Json(name = "status") val status: String? = null,
    @Json(name = "data") val data: TvdbPersonExtendedRecord? = null
)

@JsonClass(generateAdapter = true)
data class TvdbPersonExtendedRecord(
    @Json(name = "id") val id: Int? = null,
    @Json(name = "name") val name: String? = null,
    @Json(name = "image") val image: String? = null,
    @Json(name = "biographies") val biographies: List<TvdbBiography>? = emptyList(),
    @Json(name = "birth") val birth: String? = null,
    @Json(name = "death") val death: String? = null,
    @Json(name = "birthPlace") val birthPlace: String? = null,
    @Json(name = "characters") val characters: List<TvdbCharacterRecord>? = emptyList()
)

@JsonClass(generateAdapter = true)
data class TvdbBiography(
    @Json(name = "biography") val biography: String? = null,
    @Json(name = "language") val language: String? = null
)

@JsonClass(generateAdapter = true)
data class TvdbCompanyExtendedRecord(
    @Json(name = "id") val id: Int? = null,
    @Json(name = "name") val name: String? = null,
    @Json(name = "slug") val slug: String? = null,
    @Json(name = "country") val country: String? = null,
    @Json(name = "primaryCompanyType") val primaryCompanyType: Int? = null,
    @Json(name = "activeDate") val activeDate: String? = null,
    @Json(name = "inactiveDate") val inactiveDate: String? = null
)

@JsonClass(generateAdapter = true)
data class TvdbTrailerRecord(
    @Json(name = "id") val id: Int? = null,
    @Json(name = "language") val language: String? = null,
    @Json(name = "name") val name: String? = null,
    @Json(name = "url") val url: String? = null,
    @Json(name = "runtime") val runtime: Int? = null
)

@JsonClass(generateAdapter = true)
data class TvdbUpdatesResponse(
    @Json(name = "status") val status: String? = null,
    @Json(name = "data") val data: List<TvdbEntityUpdate> = emptyList(),
    @Json(name = "links") val links: TvdbLinks? = null
)

@JsonClass(generateAdapter = true)
data class TvdbEntityUpdate(
    @Json(name = "entityType") val entityType: String?,
    @Json(name = "method") val method: String? = null,
    @Json(name = "methodInt") val methodInt: Int?,
    @Json(name = "recordId") val recordId: Int?,
    @Json(name = "timeStamp") val timeStamp: Long?,
    @Json(name = "seriesId") val seriesId: Int? = null,
    @Json(name = "mergeToId") val mergeToId: Int? = null,
    @Json(name = "mergeToEntityType") val mergeToEntityType: String? = null
)

@JsonClass(generateAdapter = true)
data class TvdbLinks(
    @Json(name = "prev") val prev: String? = null,
    @Json(name = "self") val self: String? = null,
    @Json(name = "next") val next: String? = null,
    @Json(name = "total_items") val totalItems: Int? = null,
    @Json(name = "page_size") val pageSize: Int? = null
)

// -- Reference data response wrapper and DTOs --

@JsonClass(generateAdapter = true)
data class TvdbReferenceResponse<T>(
    @Json(name = "status") val status: String? = null,
    @Json(name = "data") val data: List<T> = emptyList()
)

@JsonClass(generateAdapter = true)
data class TvdbArtworkTypeRecord(
    @Json(name = "id") val id: Int? = null,
    @Json(name = "name") val name: String? = null,
    @Json(name = "recordType") val recordType: String? = null,
    @Json(name = "slug") val slug: String? = null,
    @Json(name = "imageFormat") val imageFormat: String? = null,
    @Json(name = "width") val width: Int? = null,
    @Json(name = "height") val height: Int? = null,
    @Json(name = "thumbWidth") val thumbWidth: Int? = null,
    @Json(name = "thumbHeight") val thumbHeight: Int? = null
)

@JsonClass(generateAdapter = true)
data class TvdbArtworkStatusRecord(
    @Json(name = "id") val id: Int? = null,
    @Json(name = "name") val name: String? = null
)

@JsonClass(generateAdapter = true)
data class TvdbGenreReferenceRecord(
    @Json(name = "id") val id: Int? = null,
    @Json(name = "name") val name: String? = null,
    @Json(name = "slug") val slug: String? = null
)

@JsonClass(generateAdapter = true)
data class TvdbLanguageRecord(
    @Json(name = "id") val id: String? = null,
    @Json(name = "name") val name: String? = null,
    @Json(name = "nativeName") val nativeName: String? = null,
    @Json(name = "shortCode") val shortCode: String? = null
)

@JsonClass(generateAdapter = true)
data class TvdbSeriesStatusRecord(
    @Json(name = "id") val id: Int? = null,
    @Json(name = "name") val name: String? = null,
    @Json(name = "recordType") val recordType: String? = null,
    @Json(name = "keepUpdated") val keepUpdated: Boolean? = null
)

@JsonClass(generateAdapter = true)
data class TvdbContentRatingRecord(
    @Json(name = "id") val id: Int? = null,
    @Json(name = "name") val name: String? = null,
    @Json(name = "description") val description: String? = null,
    @Json(name = "country") val country: String? = null,
    @Json(name = "contentType") val contentType: String? = null,
    @Json(name = "order") val order: Int? = null,
    @Json(name = "fullName") val fullName: String? = null
)

@JsonClass(generateAdapter = true)
data class TvdbSeasonTypeReferenceRecord(
    @Json(name = "id") val id: Int? = null,
    @Json(name = "name") val name: String? = null,
    @Json(name = "type") val type: String? = null,
    @Json(name = "alternateName") val alternateName: String? = null
)

@JsonClass(generateAdapter = true)
data class TvdbSourceTypeRecord(
    @Json(name = "id") val id: Int? = null,
    @Json(name = "name") val name: String? = null,
    @Json(name = "slug") val slug: String? = null,
    @Json(name = "prefix") val prefix: String? = null,
    @Json(name = "postfix") val postfix: String? = null,
    @Json(name = "sort") val sort: Int? = null
)

@JsonClass(generateAdapter = true)
data class TvdbEntityTypeRecord(
    @Json(name = "id") val id: Int? = null,
    @Json(name = "name") val name: String? = null,
    @Json(name = "hasSpecials") val hasSpecials: Boolean? = null
)

@JsonClass(generateAdapter = true)
data class TvdbCompanyTypeRecord(
    @Json(name = "companyTypeId") val companyTypeId: Int? = null,
    @Json(name = "companyTypeName") val companyTypeName: String? = null
)
