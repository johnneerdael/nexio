package com.nexio.tv.data.integration.simkl.transport

import io.mockk.every
import io.mockk.mockk
import java.io.IOException
import kotlin.coroutines.cancellation.CancellationException
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class SimklDiscoveryTransportTest {
    @Test
    fun `fetchDiscoveryBody maps invalid URL to missing`() {
        val transport = SimklDiscoveryTransport(mockk(relaxed = true))

        val result = transport.fetchDiscoveryBody(":::not-a-url")

        assertEquals(SimklDiscoveryTransportResult.Missing, result)
    }

    @Test
    fun `fetchDiscoveryBody maps successful response body`() {
        val server = MockWebServer()
        server.enqueue(MockResponse().setResponseCode(200).setBody("{}"))
        server.start()

        val transport = SimklDiscoveryTransport(OkHttpClient())
        val result = transport.fetchDiscoveryBody(server.url("/discovery.json").toString())

        assertEquals(SimklDiscoveryTransportResult.Success("{}"), result)
        server.shutdown()
    }

    @Test
    fun `fetchDiscoveryBody maps non-success response to http error`() {
        val server = MockWebServer()
        server.enqueue(MockResponse().setResponseCode(503).setBody("down"))
        server.start()

        val transport = SimklDiscoveryTransport(OkHttpClient())
        val result = transport.fetchDiscoveryBody(server.url("/discovery.json").toString())

        assertEquals(SimklDiscoveryTransportResult.HttpError(503), result)
        server.shutdown()
    }

    @Test
    fun `fetchDiscoveryBody maps blank body to missing`() {
        val server = MockWebServer()
        server.enqueue(MockResponse().setResponseCode(200).setBody("   "))
        server.start()

        val transport = SimklDiscoveryTransport(OkHttpClient())
        val result = transport.fetchDiscoveryBody(server.url("/discovery.json").toString())

        assertEquals(SimklDiscoveryTransportResult.Missing, result)
        server.shutdown()
    }

    @Test
    fun `fetchDiscoveryBody maps execution exceptions to network error`() {
        val exception = IOException("boom")
        val client = mockk<OkHttpClient>()
        val call = mockk<okhttp3.Call>()

        every { client.newCall(any()) } returns call
        every { call.execute() } throws exception

        val transport = SimklDiscoveryTransport(client)
        val result = transport.fetchDiscoveryBody("https://example.test/discovery.json")

        assertTrue(result is SimklDiscoveryTransportResult.NetworkError)
        result as SimklDiscoveryTransportResult.NetworkError
        assertSame(exception, result.exception)
    }

    @Test
    fun `fetchDiscoveryBody rethrows cancellation`() {
        val exception = CancellationException("cancelled")
        val client = mockk<OkHttpClient>()
        val call = mockk<okhttp3.Call>()

        every { client.newCall(any()) } returns call
        every { call.execute() } throws exception

        val transport = SimklDiscoveryTransport(client)

        try {
            transport.fetchDiscoveryBody("https://example.test/discovery.json")
        } catch (actual: CancellationException) {
            assertSame(exception, actual)
            return
        }

        throw AssertionError("Expected CancellationException")
    }
}
