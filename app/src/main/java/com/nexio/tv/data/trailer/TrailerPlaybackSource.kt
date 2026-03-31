package com.nexio.tv.data.trailer

data class TrailerPlaybackSource(
    val videoUrl: String,
    val audioUrl: String? = null
)

sealed interface TrailerResolutionResult {
    data class Playback(val source: TrailerPlaybackSource) : TrailerResolutionResult
    data class External(val url: String) : TrailerResolutionResult
}

internal data class StreailerTrailerCandidate(
    val youtubeId: String? = null,
    val externalUrl: String? = null
)
