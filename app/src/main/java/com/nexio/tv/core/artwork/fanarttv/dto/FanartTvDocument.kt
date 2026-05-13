package com.nexio.tv.core.artwork.fanarttv.dto

import com.squareup.moshi.Json
import com.squareup.moshi.JsonClass

@JsonClass(generateAdapter = true)
data class FanartTvDocument(
    @Json(name = "name") val name: String? = null,
    @Json(name = "tmdb_id") val tmdbId: String? = null,
    @Json(name = "thetvdb_id") val tvdbId: String? = null,
    @Json(name = "imdb_id") val imdbId: String? = null,

    @Json(name = "hdmovielogo") val hdMovieLogo: List<FanartTvImage>? = null,
    @Json(name = "moviebackground") val movieBackground: List<FanartTvImage>? = null,
    @Json(name = "movieposter") val moviePoster: List<FanartTvImage>? = null,

    @Json(name = "hdtvlogo") val hdTvLogo: List<FanartTvImage>? = null,
    @Json(name = "showbackground") val showBackground: List<FanartTvImage>? = null,
    @Json(name = "tvposter") val tvPoster: List<FanartTvImage>? = null
)
