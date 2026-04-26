package com.nexio.tv.data.remote.api

import com.nexio.tv.data.integration.imdb.transport.ImdbSearchRestTransport
import com.nexio.tv.data.integration.imdb.transport.ImdbSearchRestTransportResult
import com.nexio.tv.data.integration.imdb.transport.ImdbSearchWebSocketTransport
import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import kotlinx.coroutines.runBlocking
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ImdbSearchServiceTest {

    @Test
    fun `REST decodes results and forwards request parameters`() = runBlocking {
        val restTransport = FakeImdbSearchRestTransport(
            responses = listOf(
                ImdbSearchRestTransportResult.Success(
                    statusCode = 200,
                    body = """
                        {
                          "results": [
                            {"tconst":"tt0133093","titleType":"movie","primaryTitle":"The Matrix","startYear":1999},
                            {"tconst":"tt10838180","titleType":"tvSeries","primaryTitle":"The Matrix Resurrections"}
                          ],
                          "meta": {"snapshotId": 42, "count": 2}
                        }
                        """.trimIndent()
                )
            )
        )

        val service = newService(
            restBaseUrl = "https://api.example.com",
            restTransport = restTransport
        )
        val results = service.search("matrix")

        assertEquals(2, results.size)
        assertEquals("tt0133093", results[0].tconst)
        assertEquals(1999, results[0].startYear)
        assertNull(results[1].startYear)
        assertEquals("https://api.example.com", restTransport.lastBaseUrl)
        assertEquals("matrix", restTransport.lastQuery)
        assertEquals(setOf("movie", "tvSeries"), restTransport.lastTypes)
        assertEquals("test-key", restTransport.lastApiKey)
        assertEquals(10, restTransport.lastLimit)
    }

    @Test
    fun `short queries skip network`() = runBlocking {
        val restTransport = FakeImdbSearchRestTransport(
            responses = listOf(
                ImdbSearchRestTransportResult.Success(
                    statusCode = 200,
                    body = """{"results":[{"tconst":"tt0133093","titleType":"movie","primaryTitle":"The Matrix","startYear":1999}]}"""
                )
            )
        )

        val service = newService(restTransport = restTransport)
        val results = service.search("a")

        assertEquals(0, results.size)
        assertEquals(0, restTransport.requestCount)
    }

    @Test
    fun `results are cached and second call does not hit network`() = runBlocking {
        val restTransport = FakeImdbSearchRestTransport(
            responses = listOf(
                ImdbSearchRestTransportResult.Success(
                    statusCode = 200,
                    body = """{"results":[{"tconst":"tt0133093","titleType":"movie","primaryTitle":"The Matrix","startYear":1999}]}"""
                )
            )
        )

        val service = newService(restTransport = restTransport)
        val first = service.search("matrix")
        val second = service.search("matrix")

        assertEquals(1, first.size)
        assertEquals(first, second)
        assertEquals(1, restTransport.requestCount)
    }

    @Test
    fun `non-success response yields empty list`() = runBlocking {
        val restTransport = FakeImdbSearchRestTransport(
            responses = listOf(ImdbSearchRestTransportResult.HttpError(statusCode = 429))
        )

        val service = newService(restTransport = restTransport)
        val results = service.search("matrix")

        assertTrue(results.isEmpty())
    }

    @Test
    fun `hard caps response to 10 results`() = runBlocking {
        val builder = StringBuilder()
        builder.append("""{"results":[""")
        repeat(20) { i ->
            if (i > 0) builder.append(",")
            builder.append("""{"tconst":"tt$i","titleType":"movie","primaryTitle":"Title $i"}""")
        }
        builder.append("""]}""")

        val restTransport = FakeImdbSearchRestTransport(
            responses = listOf(
                ImdbSearchRestTransportResult.Success(statusCode = 200, body = builder.toString())
            )
        )

        val service = newService(restTransport = restTransport)
        val results = service.search("title")

        assertEquals(10, results.size)
    }

    @Test
    fun `malformed response body yields empty list`() = runBlocking {
        val restTransport = FakeImdbSearchRestTransport(
            responses = listOf(
                ImdbSearchRestTransportResult.Success(statusCode = 200, body = "{invalid")
            )
        )

        val service = newService(restTransport = restTransport)
        val results = service.search("matrix")

        assertTrue(results.isEmpty())
    }

    private fun newService(
        restTransport: ImdbSearchRestTransport = FakeImdbSearchRestTransport(
            responses = listOf(
                ImdbSearchRestTransportResult.Success(200, "{}")
            )
        ),
        restBaseUrl: String = "https://api.example.com"
    ): OkHttpImdbSearchService = OkHttpImdbSearchService(
        imdbSearchRestTransport = restTransport,
        imdbSearchWebSocketTransport = FakeImdbSearchWebSocketTransport(),
        moshi = Moshi.Builder().add(KotlinJsonAdapterFactory()).build(),
        restBaseUrl = restBaseUrl,
        wsUrl = "",
        apiKey = "test-key"
    )

    private class FakeImdbSearchRestTransport(
        private val responses: List<ImdbSearchRestTransportResult>
    ) : ImdbSearchRestTransport {
        private var responseIndex = 0
        var requestCount = 0
            private set
        lateinit var lastBaseUrl: String
            private set
        lateinit var lastQuery: String
            private set
        lateinit var lastTypes: Set<String>
            private set
        lateinit var lastApiKey: String
            private set
        var lastLimit: Int = 0
            private set

        override suspend fun search(
            restBaseUrl: String,
            query: String,
            types: Set<String>,
            apiKey: String,
            limit: Int
        ): ImdbSearchRestTransportResult {
            requestCount += 1
            lastBaseUrl = restBaseUrl
            lastQuery = query
            lastTypes = types
            lastApiKey = apiKey
            lastLimit = limit

            return if (responses.isNotEmpty()) {
                responses[responseIndex.coerceAtMost(responses.lastIndex)]
                    .also { responseIndex = (responseIndex + 1).coerceAtMost(responses.lastIndex) }
            } else {
                ImdbSearchRestTransportResult.Success(200, "{}")
            }
        }
    }

    private class FakeImdbSearchWebSocketTransport : ImdbSearchWebSocketTransport {
        override fun open(
            wsUrl: String,
            apiKey: String,
            listener: WebSocketListener
        ): WebSocket {
            throw IllegalStateException("WebSocket transport should not be used in this test")
        }
    }
}
