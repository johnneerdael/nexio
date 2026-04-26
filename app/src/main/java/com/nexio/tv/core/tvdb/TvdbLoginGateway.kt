package com.nexio.tv.core.tvdb

interface TvdbLoginGateway {
    suspend fun requestToken(
        apiKey: String,
        pin: String,
        nowMillis: () -> Long
    ): TvdbAuthResult
}
