package com.nexio.tv.data.remote.dto.trakt

import com.squareup.moshi.Json
import com.squareup.moshi.JsonClass

@JsonClass(generateAdapter = true)
data class TraktCheckinRequestDto(
    @Json(name = "movie") val movie: TraktMovieDto? = null,
    @Json(name = "show") val show: TraktShowDto? = null,
    @Json(name = "episode") val episode: TraktEpisodeDto? = null,
    @Json(name = "app_version") val appVersion: String? = null,
    @Json(name = "app_date") val appDate: String? = null,
    @Json(name = "message") val message: String? = null
)

@JsonClass(generateAdapter = true)
data class TraktCheckinResponseDto(
    @Json(name = "id") val id: Long? = null,
    @Json(name = "watched_at") val watchedAt: String? = null
)
