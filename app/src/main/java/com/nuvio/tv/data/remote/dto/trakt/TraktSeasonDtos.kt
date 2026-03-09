package com.nuvio.tv.data.remote.dto.trakt

import com.squareup.moshi.Json
import com.squareup.moshi.JsonClass

@JsonClass(generateAdapter = true)
data class TraktSeasonSummaryDto(
    @Json(name = "number") val number: Int? = null,
    @Json(name = "ids") val ids: TraktIdsDto? = null,
    @Json(name = "episode_count") val episodeCount: Int? = null,
    @Json(name = "aired_episodes") val airedEpisodes: Int? = null,
    @Json(name = "title") val title: String? = null,
    @Json(name = "episodes") val episodes: List<TraktSeasonEpisodeDto>? = null
)

@JsonClass(generateAdapter = true)
data class TraktSeasonEpisodeDto(
    @Json(name = "season") val season: Int? = null,
    @Json(name = "number") val number: Int? = null,
    @Json(name = "title") val title: String? = null,
    @Json(name = "ids") val ids: TraktIdsDto? = null,
    @Json(name = "first_aired") val firstAired: String? = null,
    @Json(name = "runtime") val runtime: Int? = null
)
