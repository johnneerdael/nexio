package com.nexio.tv.data.trailer.potoken

interface PoTokenProvider {
    suspend fun getWebClientPoToken(
        videoId: String,
        webClientName: String,
        webClientId: String,
        webClientVersion: String,
        webClientScreen: String? = null,
        embedUrl: String? = null
    ): PoTokenResult?
}
