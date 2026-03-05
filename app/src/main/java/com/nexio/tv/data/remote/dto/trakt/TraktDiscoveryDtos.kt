package com.nexio.tv.data.remote.dto.trakt

import com.squareup.moshi.Json
import com.squareup.moshi.JsonClass

@JsonClass(generateAdapter = true)
data class TraktRecommendationItemDto(
    @Json(name = "movie") val movie: TraktMovieDto? = null,
    @Json(name = "show") val show: TraktShowDto? = null
)

@JsonClass(generateAdapter = true)
data class TraktCalendarEpisodeItemDto(
    @Json(name = "first_aired") val firstAired: String? = null,
    @Json(name = "show") val show: TraktShowDto? = null,
    @Json(name = "episode") val episode: TraktEpisodeDto? = null
)

@JsonClass(generateAdapter = true)
data class TraktTrendingMovieItemDto(
    @Json(name = "watchers") val watchers: Int? = null,
    @Json(name = "movie") val movie: TraktMovieDto? = null
)

@JsonClass(generateAdapter = true)
data class TraktTrendingShowItemDto(
    @Json(name = "watchers") val watchers: Int? = null,
    @Json(name = "show") val show: TraktShowDto? = null
)

@JsonClass(generateAdapter = true)
data class TraktPopularListItemDto(
    @Json(name = "like_count") val likeCount: Int? = null,
    @Json(name = "comment_count") val commentCount: Int? = null,
    @Json(name = "list") val list: TraktListSummaryDto? = null,
    @Json(name = "user") val user: TraktUserDto? = null
)
