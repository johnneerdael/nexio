package com.nexio.tv.core.artwork.fanarttv.dto

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class FanartTvDocument(
    @SerialName("name") val name: String? = null,
    @SerialName("tmdb_id") val tmdbId: String? = null,
    @SerialName("thetvdb_id") val tvdbId: String? = null,
    @SerialName("imdb_id") val imdbId: String? = null,

    @SerialName("hdmovielogo") val hdMovieLogo: List<FanartTvImage>? = null,
    @SerialName("moviebackground") val movieBackground: List<FanartTvImage>? = null,
    @SerialName("movieposter") val moviePoster: List<FanartTvImage>? = null,

    @SerialName("hdtvlogo") val hdTvLogo: List<FanartTvImage>? = null,
    @SerialName("showbackground") val showBackground: List<FanartTvImage>? = null,
    @SerialName("tvposter") val tvPoster: List<FanartTvImage>? = null
)
