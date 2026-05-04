package com.nexio.tv.data.integration.supabase.transport

import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.assertEquals
import org.junit.Test

class DurableDeviceCredentialActivationTransportTest {
    @Test
    fun `activate preserves request details and decodes success`() = runBlocking {
        val server = MockWebServer()
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setBody("""{"activated":true}""")
        )
        server.start()

        val transport = DurableDeviceCredentialActivationTransport.forTest(
            okHttpClient = OkHttpClient(),
            supabaseUrl = server.url("/").toString().trimEnd('/'),
            supabaseAnonKey = "anon-key"
        )

        val result = transport.activate(
            token = "current-access-token",
            devicePublicId = "device-public-id",
            deviceSecret = "device-secret"
        )

        val request = server.takeRequest()
        assertEquals("/functions/v1/device-credential-activate", request.path)
        assertEquals("POST", request.method)
        assertEquals("anon-key", request.getHeader("apikey"))
        assertEquals("Bearer current-access-token", request.getHeader("Authorization"))
        assertEquals(
            """{"device_public_id":"device-public-id","device_secret":"device-secret"}""",
            request.body.readUtf8()
        )
        assertEquals(true, result.activated)

        server.shutdown()
    }

    @Test
    fun `activate preserves non-2xx error message`() = runBlocking {
        val server = MockWebServer()
        server.enqueue(
            MockResponse()
                .setResponseCode(403)
                .setBody("""{"error":"forbidden"}""")
        )
        server.start()

        val transport = DurableDeviceCredentialActivationTransport.forTest(
            okHttpClient = OkHttpClient(),
            supabaseUrl = server.url("/").toString().trimEnd('/'),
            supabaseAnonKey = "anon-key"
        )

        val error = try {
            transport.activate(
                token = "current-access-token",
                devicePublicId = "device-public-id",
                deviceSecret = "device-secret"
            )
            null
        } catch (exception: IllegalStateException) {
            exception
        }

        assertEquals(
            """Device credential activation failed (403): {"error":"forbidden"}""",
            error?.message
        )

        server.shutdown()
    }
}
