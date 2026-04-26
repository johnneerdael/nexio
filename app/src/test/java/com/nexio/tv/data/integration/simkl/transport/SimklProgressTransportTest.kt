package com.nexio.tv.data.integration.simkl.transport

import com.nexio.tv.data.remote.SimklRequestGate
import io.mockk.every
import io.mockk.mockk
import kotlin.coroutines.cancellation.CancellationException
import kotlin.system.measureTimeMillis
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class SimklProgressTransportTest {

    private lateinit var server: MockWebServer

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    @Test
    fun `executeRequest maps successful response fields`() = runBlocking {
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setBody("""{"all":"2026-04-08T10:00:00Z"}""")
        )

        val transport = SimklProgressTransport(
            okHttpClient = OkHttpClient(),
            requestGate = SimklRequestGate()
        )
        val request = Request.Builder().url(server.url("/sync/activities")).get().build()

        val result = transport.executeRequest(request)

        assertEquals(200, result.statusCode)
        assertEquals(true, result.isSuccessful)
        assertEquals("""{"all":"2026-04-08T10:00:00Z"}""", result.body)
    }

    @Test
    fun `executeRequest maps unsuccessful response fields`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(503).setBody("down"))

        val transport = SimklProgressTransport(
            okHttpClient = OkHttpClient(),
            requestGate = SimklRequestGate()
        )
        val request = Request.Builder().url(server.url("/sync/activities")).get().build()

        val result = transport.executeRequest(request)

        assertEquals(503, result.statusCode)
        assertFalse(result.isSuccessful)
        assertEquals("down", result.body)
    }

    @Test
    fun `executeRequest is serialized by request gate`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(200).setBody("first"))
        server.enqueue(MockResponse().setResponseCode(200).setBody("second"))

        val transport = SimklProgressTransport(
            okHttpClient = OkHttpClient(),
            requestGate = SimklRequestGate()
        )
        val request = Request.Builder().url(server.url("/sync/activities")).get().build()

        val elapsedMs = measureTimeMillis {
            awaitAll(
                async { transport.executeRequest(request) },
                async { transport.executeRequest(request) }
            )
        }

        assertTrue(elapsedMs >= SimklRequestGate.MIN_INTERVAL_MS)
    }

    @Test
    fun `executeRequest rethrows cancellation exception`() = runBlocking {
        val mockClient = mockk<OkHttpClient>(relaxed = true)
        val mockCall = mockk<okhttp3.Call>()
        val request = Request.Builder().url("https://example.test/sync/activities").get().build()
        val cancellation = CancellationException("cancelled")

        every { mockClient.newCall(any()) } returns mockCall
        every { mockCall.execute() } throws cancellation

        val transport = SimklProgressTransport(
            okHttpClient = mockClient,
            requestGate = SimklRequestGate()
        )

        try {
            transport.executeRequest(request)
            throw AssertionError("Expected CancellationException")
        } catch (actual: CancellationException) {
            assertEquals(cancellation.message, actual.message)
            assertNotNull(actual)
        }
    }
}
