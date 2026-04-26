package com.nexio.tv.data.integration.supabase.transport

import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.assertEquals
import org.junit.Test

class TvLoginExchangeTransportTest {

    @Test
    fun `exchange preserves request details and decodes tokens`() = runBlocking {
        val server = MockWebServer()
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setBody(
                    """
                    {
                      "access_token": "new-access-token",
                      "refresh_token": "new-refresh-token",
                      "token_type": "bearer",
                      "expires_in": 3600
                    }
                    """.trimIndent()
                )
        )
        server.start()

        val transport = TvLoginExchangeTransport.forTest(
            okHttpClient = OkHttpClient(),
            supabaseUrl = server.url("/").toString().trimEnd('/'),
            supabaseAnonKey = "anon-key"
        )

        val result = transport.exchange(
            token = "current-access-token",
            code = "ABCD1234",
            deviceNonce = "device-nonce"
        )

        val request = server.takeRequest()
        assertEquals("/functions/v1/tv-logins-exchange", request.path)
        assertEquals("POST", request.method)
        assertEquals("anon-key", request.getHeader("apikey"))
        assertEquals("Bearer current-access-token", request.getHeader("Authorization"))
        assertEquals(
            """{"code":"ABCD1234","device_nonce":"device-nonce"}""",
            request.body.readUtf8()
        )
        assertEquals("new-access-token", result.accessToken)
        assertEquals("new-refresh-token", result.refreshToken)

        server.shutdown()
    }

    @Test
    fun `exchange accepts code, deviceNonce, currentAccessToken parameter order`() = runBlocking {
        val server = MockWebServer()
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setBody(
                    """
                    {
                      "access_token": "new-access-token",
                      "refresh_token": "new-refresh-token",
                      "token_type": "bearer",
                      "expires_in": 3600
                    }
                    """.trimIndent()
                )
        )
        server.start()

        val transport = TvLoginExchangeTransport.forTest(
            okHttpClient = OkHttpClient(),
            supabaseUrl = server.url("/").toString(),
            supabaseAnonKey = "anon-key"
        )

        val result = transport.exchange(
            code = "ABCD1234",
            deviceNonce = "device-nonce",
            currentAccessToken = "current-access-token"
        )

        val request = server.takeRequest()
        assertEquals("/functions/v1/tv-logins-exchange", request.path)
        assertEquals("new-access-token", result.accessToken)
        assertEquals("new-refresh-token", result.refreshToken)

        server.shutdown()
    }

    @Test
    fun `exchange preserves non-2xx error message`() = runBlocking {
        val server = MockWebServer()
        server.enqueue(
            MockResponse()
                .setResponseCode(403)
                .setBody("""{"error":"forbidden"}""")
        )
        server.start()

        val transport = TvLoginExchangeTransport.forTest(
            okHttpClient = OkHttpClient(),
            supabaseUrl = server.url("/").toString().trimEnd('/'),
            supabaseAnonKey = "anon-key"
        )

        val error = try {
            transport.exchange(
                token = "current-access-token",
                code = "ABCD1234",
                deviceNonce = "device-nonce"
            )
            null
        } catch (exception: IllegalStateException) {
            exception
        }

        assertEquals(
            """TV login exchange failed (403): {"error":"forbidden"}""",
            error?.message
        )

        server.shutdown()
    }
}
