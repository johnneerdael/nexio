package com.nexio.tv.data.remote.api

import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class ImdbSearchServiceTest {

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

    private fun newService(): OkHttpImdbSearchService = OkHttpImdbSearchService(
        okHttpClient = OkHttpClient(),
        moshi = Moshi.Builder().add(KotlinJsonAdapterFactory()).build(),
        restBaseUrl = server.url("/").toString(),
        wsUrl = "",
        apiKey = "test-key"
    )

    @Test
    fun `REST decodes results and forwards api key header`() = runBlocking {
        server.enqueue(
            MockResponse().setResponseCode(200).setBody(
                """
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

        val service = newService()
        val results = service.search("matrix")

        assertEquals(2, results.size)
        assertEquals("tt0133093", results[0].tconst)
        assertEquals(1999, results[0].startYear)
        assertNull(results[1].startYear)

        val request = server.takeRequest()
        assertEquals("test-key", request.getHeader("X-API-Key"))
        val path = request.path.orEmpty()
        assertTrue("path=$path", path.startsWith("/titles/search"))
        assertTrue("path=$path", path.contains("q=matrix"))
        assertTrue("path=$path", path.contains("types=movie%2CtvSeries") || path.contains("types=movie,tvSeries"))
        assertTrue("path=$path", path.contains("limit=10"))
    }

    @Test
    fun `short queries skip network`() = runBlocking {
        val service = newService()
        val results = service.search("a")
        assertEquals(0, results.size)
        assertEquals(0, server.requestCount)
    }

    @Test
    fun `results are cached and second call does not hit network`() = runBlocking {
        server.enqueue(
            MockResponse().setResponseCode(200).setBody(
                """{"results":[{"tconst":"tt0133093","titleType":"movie","primaryTitle":"The Matrix","startYear":1999}]}"""
            )
        )

        val service = newService()
        val first = service.search("matrix")
        val second = service.search("matrix")

        assertEquals(1, first.size)
        assertEquals(first, second)
        assertEquals(1, server.requestCount)
    }

    @Test
    fun `non-success response yields empty list`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(429).setBody("""{"error":{"code":"rate_limited"}}"""))

        val service = newService()
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

        server.enqueue(MockResponse().setResponseCode(200).setBody(builder.toString()))

        val service = newService()
        val results = service.search("title")

        assertEquals(10, results.size)
    }
}
