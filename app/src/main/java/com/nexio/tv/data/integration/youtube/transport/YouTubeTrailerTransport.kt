package com.nexio.tv.data.integration.youtube.transport

interface YouTubeTrailerTransport {
    fun execute(call: YouTubeTrailerTransportCall): YouTubeTrailerTransportResponse

    fun probe(url: String, headers: Map<String, String> = emptyMap()): Boolean
}

data class YouTubeTrailerTransportCall(
    val url: String,
    val method: String = "GET",
    val headers: Map<String, String> = emptyMap(),
    val body: String? = null
)

data class YouTubeTrailerTransportResponse(
    val statusCode: Int,
    val isSuccessful: Boolean,
    val statusText: String,
    val url: String,
    val body: String
)
