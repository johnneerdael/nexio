package com.nexio.tv.data.integration.imdb.transport

import java.util.concurrent.TimeUnit
import okhttp3.OkHttpClient
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test

class ImdbSearchWebSocketTransportTest {

    @Test
    fun `open builds websocket request with api key header`() {
        val server = MockWebServer()
        server.enqueue(
            MockResponse().withWebSocketUpgrade(
                object : WebSocketListener() {
                    override fun onOpen(webSocket: WebSocket, response: Response) {
                        webSocket.close(1000, "done")
                    }
                }
            )
        )
        server.start()

        val transport = OkHttpImdbSearchWebSocketTransport(OkHttpClient())
        val socket = transport.open(
            wsUrl = server.url("/socket").toString().replaceFirst("http", "ws"),
            apiKey = "test-key",
            listener = object : WebSocketListener() {}
        )

        val request = server.takeRequest(1, TimeUnit.SECONDS)

        assertNotNull(socket)
        assertNotNull(request)
        assertEquals("/socket", request?.path)
        assertEquals("test-key", request?.getHeader("X-API-Key"))

        server.shutdown()
    }
}
