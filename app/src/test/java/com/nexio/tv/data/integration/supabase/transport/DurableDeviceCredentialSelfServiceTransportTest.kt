package com.nexio.tv.data.integration.supabase.transport

import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DurableDeviceCredentialSelfServiceTransportTest {
    @Test
    fun `status posts durable credential and decodes active response`() = runBlocking {
        val server = MockWebServer()
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setBody("""{"status":"active","active":true,"revoked":false}""")
        )
        server.start()

        val transport = DurableDeviceCredentialSelfServiceTransport.forTest(
            okHttpClient = OkHttpClient(),
            supabaseUrl = server.url("/").toString().trimEnd('/'),
            supabaseAnonKey = "anon-key"
        )

        val result = transport.status(
            devicePublicId = "device-public-id",
            deviceSecret = "device-secret"
        )

        val request = server.takeRequest()
        assertEquals("/functions/v1/device-credential-self-service", request.path)
        assertEquals("POST", request.method)
        assertEquals("anon-key", request.getHeader("apikey"))
        assertEquals(
            """{"device_public_id":"device-public-id","device_secret":"device-secret","action":"status"}""",
            request.body.readUtf8()
        )
        assertEquals("active", result.status)
        assertTrue(result.active)
        assertFalse(result.revoked)

        server.shutdown()
    }

    @Test
    fun `revoke posts durable credential and decodes revoked response`() = runBlocking {
        val server = MockWebServer()
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setBody("""{"status":"revoked","active":false,"revoked":true}""")
        )
        server.start()

        val transport = DurableDeviceCredentialSelfServiceTransport.forTest(
            okHttpClient = OkHttpClient(),
            supabaseUrl = server.url("/").toString().trimEnd('/'),
            supabaseAnonKey = "anon-key"
        )

        val result = transport.revoke(
            devicePublicId = "device-public-id",
            deviceSecret = "device-secret"
        )

        val request = server.takeRequest()
        assertEquals("/functions/v1/device-credential-self-service", request.path)
        assertEquals(
            """{"device_public_id":"device-public-id","device_secret":"device-secret","action":"revoke"}""",
            request.body.readUtf8()
        )
        assertEquals("revoked", result.status)
        assertTrue(result.revoked)

        server.shutdown()
    }

    @Test
    fun `self service preserves non-2xx error body`() = runBlocking {
        val server = MockWebServer()
        server.enqueue(
            MockResponse()
                .setResponseCode(403)
                .setBody("""{"error":"Invalid durable device credential"}""")
        )
        server.start()

        val transport = DurableDeviceCredentialSelfServiceTransport.forTest(
            okHttpClient = OkHttpClient(),
            supabaseUrl = server.url("/").toString().trimEnd('/'),
            supabaseAnonKey = "anon-key"
        )

        val error = try {
            transport.status(
                devicePublicId = "device-public-id",
                deviceSecret = "device-secret"
            )
            null
        } catch (exception: DurableDeviceCredentialSelfServiceException) {
            exception
        }

        assertEquals(403, error?.statusCode)
        assertEquals("""{"error":"Invalid durable device credential"}""", error?.responseBody)

        server.shutdown()
    }
}
