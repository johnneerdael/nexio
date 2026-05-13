package com.nexio.tv.core.artwork.fanarttv.dto

import com.squareup.moshi.Json
import com.squareup.moshi.JsonClass

@JsonClass(generateAdapter = true)
data class FanartTvImage(
    @Json(name = "id") val id: String? = null,
    @Json(name = "url") val url: String? = null,
    @Json(name = "lang") val lang: String? = null,
    @Json(name = "likes") val likes: String? = null
)
