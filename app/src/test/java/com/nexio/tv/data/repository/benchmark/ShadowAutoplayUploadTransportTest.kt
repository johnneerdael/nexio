package com.nexio.tv.data.repository.benchmark

import com.nexio.tv.data.integration.collector.transport.ShadowAutoplayUploadTransport
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlinx.coroutines.test.runTest

class ShadowAutoplayUploadTransportTest {

    @Test
    fun `uploadEvent preserves request details`() = runTest {
        val server = MockWebServer()
        server.enqueue(MockResponse().setResponseCode(202))
        server.start()

        val transport = ShadowAutoplayUploadTransport(OkHttpClient())
        val envelope = """{"sentAtMs":1234,"client":{},"payload":{}}"""

        val result = transport.uploadEvent(
            baseUrl = server.url("/").toString().trimEnd('/'),
            token = "write-token",
            envelopeJson = envelope
        )

        val request = server.takeRequest()
        assertEquals("/api/v1/shadow-autoplay-events", request.path)
        assertEquals("POST", request.method)
        assertEquals("Bearer write-token", request.getHeader("Authorization"))
        assertEquals(envelope, request.body.readUtf8())
        assertEquals(202, result.statusCode)
        assertTrue(result.isSuccessful)

        server.shutdown()
    }

    @Test
    fun `uploadEvent returns success state for successful response`() = runTest {
        val server = MockWebServer()
        server.enqueue(MockResponse().setResponseCode(200))
        server.start()

        val transport = ShadowAutoplayUploadTransport(OkHttpClient())

        val result = transport.uploadEvent(
            baseUrl = server.url("/").toString().trimEnd('/'),
            token = "write-token",
            envelopeJson = """{}"""
        )

        assertEquals(200, result.statusCode)
        assertTrue(result.isSuccessful)

        server.shutdown()
    }
}
