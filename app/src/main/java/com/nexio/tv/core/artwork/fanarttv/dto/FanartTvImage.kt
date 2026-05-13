package com.nexio.tv.core.artwork.fanarttv.dto

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class FanartTvImage(
    @SerialName("id") val id: String? = null,
    @SerialName("url") val url: String? = null,
    @SerialName("lang") val lang: String? = null,
    @SerialName("likes") val likes: String? = null
)
