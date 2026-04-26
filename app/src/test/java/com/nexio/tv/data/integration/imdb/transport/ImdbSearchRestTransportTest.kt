package com.nexio.tv.data.integration.imdb.transport

import java.io.IOException
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ImdbSearchRestTransportTest {

    @Test
    fun `search preserves request path query and headers`() {
        val server = MockWebServer()
        server.enqueue(MockResponse().setResponseCode(200).setBody("{}"))
        server.start()

        val transport = OkHttpImdbSearchRestTransport(OkHttpClient())
        val result = runBlocking {
            transport.search(
                restBaseUrl = server.url("/").toString(),
                query = "matrix",
                types = setOf("movie", "tvSeries"),
                apiKey = "test-key",
                limit = 10
            )
        }

        assertTrue(result is ImdbSearchRestTransportResult.Success)
        val request = server.takeRequest()
        assertEquals("/titles/search?q=matrix&types=movie%2CtvSeries&limit=10", request.path)
        assertEquals("test-key", request.getHeader("X-API-Key"))
        assertEquals("application/json", request.getHeader("Accept"))

        server.shutdown()
    }

    @Test
    fun `search maps non-success responses to http error`() {
        val server = MockWebServer()
        server.enqueue(MockResponse().setResponseCode(429).setBody("too many requests"))
        server.start()

        val transport = OkHttpImdbSearchRestTransport(OkHttpClient())
        val result = runBlocking {
            transport.search(
                restBaseUrl = server.url("/").toString(),
                query = "matrix",
                types = setOf("movie", "tvSeries"),
                apiKey = "test-key",
                limit = 10
            )
        }

        assertTrue(result is ImdbSearchRestTransportResult.HttpError)
        result as ImdbSearchRestTransportResult.HttpError
        assertEquals(429, result.statusCode)

        server.shutdown()
    }

    @Test
    fun `search maps execution exceptions to network error`() {
        val exception = IOException("boom")
        val client = OkHttpClient.Builder()
            .addInterceptor(Interceptor { throw exception })
            .build()
        val transport = OkHttpImdbSearchRestTransport(client)
        val result = runBlocking {
            transport.search(
                restBaseUrl = "https://example.com",
                query = "matrix",
                types = setOf("movie", "tvSeries"),
                apiKey = "test-key",
                limit = 10
            )
        }

        assertTrue(result is ImdbSearchRestTransportResult.NetworkError)
        result as ImdbSearchRestTransportResult.NetworkError
        assertEquals(exception, result.exception)
    }
}
