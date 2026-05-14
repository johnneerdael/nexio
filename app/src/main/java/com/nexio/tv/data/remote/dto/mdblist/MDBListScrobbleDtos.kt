package com.nexio.tv.data.remote.dto.mdblist

import com.squareup.moshi.Json

data class MDBListScrobbleRequestDto(
    @Json(name = "movie") val movie: MDBListScrobbleMovieDto? = null,
    @Json(name = "show") val show: MDBListScrobbleShowDto? = null,
    @Json(name = "progress") val progress: Double,
    @Json(name = "app_version") val appVersion: String = "Nexio",
)

data class MDBListScrobbleMovieDto(
    @Json(name = "ids") val ids: MDBListScrobbleIdsDto,
)

data class MDBListScrobbleShowDto(
    @Json(name = "ids") val ids: MDBListScrobbleIdsDto,
    @Json(name = "season") val season: MDBListScrobbleSeasonDto,
)

data class MDBListScrobbleSeasonDto(
    @Json(name = "number") val number: Int,
    @Json(name = "episode") val episode: MDBListScrobbleEpisodeDto,
)

data class MDBListScrobbleEpisodeDto(
    @Json(name = "number") val number: Int,
)

data class MDBListScrobbleIdsDto(
    @Json(name = "tmdb") val tmdb: Int? = null,
    @Json(name = "imdb") val imdb: String? = null,
    @Json(name = "tvdb") val tvdb: Int? = null,
)
