package com.nexio.tv.data.remote.api

import com.squareup.moshi.Json
import com.squareup.moshi.JsonClass
import retrofit2.Response
import retrofit2.http.GET
import retrofit2.http.Query

interface OmdbApi {
    @GET("/")
    suspend fun getSeason(
        @Query("apikey") apiKey: String,
        @Query("i") seriesImdbId: String,
        @Query("season") season: Int
    ): Response<OmdbSeasonResponse>
}

@JsonClass(generateAdapter = true)
data class OmdbSeasonResponse(
    @Json(name = "Response") val response: String? = null,
    @Json(name = "Error") val error: String? = null,
    @Json(name = "Episodes") val episodes: List<OmdbSeasonEpisodeDto> = emptyList()
)

@JsonClass(generateAdapter = true)
data class OmdbSeasonEpisodeDto(
    @Json(name = "Episode") val episode: String? = null,
    @Json(name = "imdbRating") val imdbRating: String? = null,
    @Json(name = "imdbID") val imdbId: String? = null
)
