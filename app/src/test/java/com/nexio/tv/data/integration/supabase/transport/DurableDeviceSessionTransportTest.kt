package com.nexio.tv.data.integration.supabase.transport

import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.assertEquals
import org.junit.Test

class DurableDeviceSessionTransportTest {
    @Test
    fun `exchangeSession posts durable credential and decodes owner session`() = runBlocking {
        val server = MockWebServer()
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setBody(
                    """
                    {
                      "access_token": "owner-access",
                      "refresh_token": "owner-refresh",
                      "token_type": "bearer",
                      "expires_in": 3600
                    }
                    """.trimIndent()
                )
        )
        server.start()

        val transport = DurableDeviceSessionTransport.forTest(
            okHttpClient = OkHttpClient(),
            supabaseUrl = server.url("/").toString().trimEnd('/'),
            supabaseAnonKey = "anon-key"
        )

        val result = transport.exchangeSession(
            devicePublicId = "device-public-id",
            deviceSecret = "device-secret"
        )

        val request = server.takeRequest()
        assertEquals("/functions/v1/device-session-exchange", request.path)
        assertEquals("POST", request.method)
        assertEquals("anon-key", request.getHeader("apikey"))
        assertEquals(
            """{"device_public_id":"device-public-id","device_secret":"device-secret"}""",
            request.body.readUtf8()
        )
        assertEquals("owner-access", result.accessToken)
        assertEquals("owner-refresh", result.refreshToken)

        server.shutdown()
    }

    @Test
    fun `exchangeSession preserves non-2xx error body`() = runBlocking {
        val server = MockWebServer()
        server.enqueue(
            MockResponse()
                .setResponseCode(401)
                .setBody("""{"error":"Invalid durable device credential"}""")
        )
        server.start()

        val transport = DurableDeviceSessionTransport.forTest(
            okHttpClient = OkHttpClient(),
            supabaseUrl = server.url("/").toString().trimEnd('/'),
            supabaseAnonKey = "anon-key"
        )

        val error = try {
            transport.exchangeSession(
                devicePublicId = "device-public-id",
                deviceSecret = "device-secret"
            )
            null
        } catch (exception: DurableDeviceSessionExchangeException) {
            exception
        }

        assertEquals(401, error?.statusCode)
        assertEquals("""{"error":"Invalid durable device credential"}""", error?.responseBody)

        server.shutdown()
    }
}
