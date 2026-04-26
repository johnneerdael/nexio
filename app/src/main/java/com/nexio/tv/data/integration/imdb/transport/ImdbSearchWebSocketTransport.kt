package com.nexio.tv.data.integration.imdb.transport

import java.util.concurrent.TimeUnit
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.WebSocket
import okhttp3.WebSocketListener

interface ImdbSearchWebSocketTransport {
    fun open(
        wsUrl: String,
        apiKey: String,
        listener: WebSocketListener
    ): WebSocket
}

class OkHttpImdbSearchWebSocketTransport(
    okHttpClient: OkHttpClient
) : ImdbSearchWebSocketTransport {
    private val webSocketClient: OkHttpClient = okHttpClient.newBuilder()
        .pingInterval(20, TimeUnit.SECONDS)
        .build()

    override fun open(
        wsUrl: String,
        apiKey: String,
        listener: WebSocketListener
    ): WebSocket {
        val request = Request.Builder()
            .url(wsUrl)
            .header("X-API-Key", apiKey)
            .build()

        return webSocketClient.newWebSocket(request, listener)
    }
}
