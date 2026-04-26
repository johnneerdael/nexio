package com.nexio.tv.data.integration.subtitles.transport

import java.io.IOException
import kotlin.coroutines.cancellation.CancellationException
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SubtitleTranslationTransportTest {
    @Test
    fun `execute returns status body success and retry-after header`() = runTest {
        val server = MockWebServer()
        server.enqueue(
            MockResponse()
                .setResponseCode(429)
                .setHeader("Retry-After", "3")
                .setBody("""{"error":"slow down"}""")
        )
        server.start()

        try {
            val transport = SubtitleTranslationTransport(OkHttpClient())
            val request = Request.Builder()
                .url(server.url("/v1/chat/completions"))
                .post("{}".toRequestBody())
                .build()

            val result = transport.execute(request)

            assertEquals(429, result.statusCode)
            assertEquals("""{"error":"slow down"}""", result.body)
            assertFalse(result.isSuccessful)
            assertEquals("3", result.retryAfterHeader)
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun `execute preserves null body on successful response`() = runTest {
        val server = MockWebServer()
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
        )
        server.start()

        try {
            val transport = SubtitleTranslationTransport(OkHttpClient())
            val request = Request.Builder()
                .url(server.url("/v1/messages"))
                .post("{}".toRequestBody())
                .build()

            val result = transport.execute(request)

            assertEquals(200, result.statusCode)
            assertTrue(result.isSuccessful)
            assertNull(result.body)
            assertNull(result.retryAfterHeader)
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun `execute propagates okhttp network failures`() = runTest {
        val transport = SubtitleTranslationTransport(OkHttpClient())
        val request = Request.Builder()
            .url("http://127.0.0.1:1/unreachable")
            .post("{}".toRequestBody())
            .build()

        try {
            transport.execute(request)
            org.junit.Assert.fail("Expected IOException")
        } catch (exception: IOException) {
            assertTrue(exception.message.orEmpty().isNotBlank())
        }
    }

    @Test
    fun `execute parses retry-after seconds to ms`() = runTest {
        val server = MockWebServer()
        server.enqueue(
            MockResponse()
                .setResponseCode(429)
                .setHeader("Retry-After", "2")
                .setBody("{}")
        )
        server.start()

        try {
            val transport = SubtitleTranslationTransport(OkHttpClient())
            val request = Request.Builder()
                .url(server.url("/v1/messages"))
                .post("{}".toRequestBody())
                .build()

            val result = transport.execute(request)

            assertEquals(2_000L, result.retryAfterHeaderMs)
            assertEquals("2", result.retryAfterHeader)
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun `execute rethrows cancellation exception`() = runTest {
        val client = mockk<OkHttpClient>(relaxed = true)
        val call = mockk<okhttp3.Call>()
        val request = Request.Builder()
            .url("https://example.test/retry-after")
            .post("{}".toRequestBody())
            .build()
        val cancellation = CancellationException("cancelled")

        every { client.newCall(any()) } returns call
        every { call.execute() } throws cancellation

        val transport = SubtitleTranslationTransport(client)

        try {
            transport.execute(request)
        } catch (actual: CancellationException) {
            assertEquals(cancellation.message, actual.message)
            return@runTest
        }

        throw AssertionError("Expected CancellationException")
    }
}
