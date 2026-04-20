package com.nexio.tv.data.remote

import com.squareup.moshi.Moshi
import java.io.IOException
import kotlinx.coroutines.test.runTest
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.RequestBody
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CustomImdbClientTest {

    @Test
    fun `normalizeCustomImdbBaseUrl trims whitespace and strips trailing slash while keeping path`() {
        assertEquals(
            "https://ratings.example.com/custom/api",
            normalizeCustomImdbBaseUrl("  https://ratings.example.com/custom/api/  ")
        )
    }

    @Test
    fun `validate calls stats endpoint with normalized base url and api key header`() = runTest {
        var capturedPath = ""
        var capturedApiKey = ""
        val client = OkHttpCustomImdbClient(
            okHttpClient = okHttpClient { chain ->
                capturedPath = chain.request().url.encodedPath
                capturedApiKey = chain.request().header("X-API-Key").orEmpty()
                jsonResponse(chain, """{"status":"ok"}""")
            },
            moshi = Moshi.Builder().build()
        )

        val result = client.validate(
            baseUrl = " https://ratings.example.com/custom/ ",
            apiKey = "secret-key"
        )

        assertTrue(result)
        assertEquals("/custom/v1/meta/stats", capturedPath)
        assertEquals("secret-key", capturedApiKey)
    }

    @Test
    fun `validate reuses version path when base url already ends in v1`() = runTest {
        var capturedPath = ""
        val client = OkHttpCustomImdbClient(
            okHttpClient = okHttpClient { chain ->
                capturedPath = chain.request().url.encodedPath
                jsonResponse(chain, """{"status":"ok"}""")
            },
            moshi = Moshi.Builder().build()
        )

        val result = client.validate(
            baseUrl = "https://ratings.example.com/custom/v1/",
            apiKey = "secret-key"
        )

        assertTrue(result)
        assertEquals("/custom/v1/meta/stats", capturedPath)
    }

    @Test
    fun `fetchEpisodeRatings calls single title ratings endpoint with episodes query and maps wrapper payload`() = runTest {
        var capturedPath = ""
        var capturedEpisodesQuery: String? = null
        var capturedMethod = ""
        var capturedRequestBody: String? = null
        val client = OkHttpCustomImdbClient(
            okHttpClient = okHttpClient { chain ->
                capturedPath = chain.request().url.encodedPath
                capturedEpisodesQuery = chain.request().url.queryParameter("episodes")
                capturedMethod = chain.request().method
                capturedRequestBody = chain.request().body?.readUtf8()
                jsonResponse(
                    chain,
                    """
                    {
                      "requestTconst": "tt27444205",
                      "rating": { "tconst": "tt27444205", "averageRating": 8.8, "numVotes": 1200 },
                      "episodesParentTconst": "tt27444205",
                      "episodes": [
                        {
                          "tconst": "tt1000001",
                          "parentTconst": "tt27444205",
                          "seasonNumber": 1,
                          "episodeNumber": 1,
                          "averageRating": 8.3,
                          "numVotes": 200
                        },
                        {
                          "tconst": "tt1000002",
                          "parentTconst": "tt27444205",
                          "seasonNumber": 1,
                          "episodeNumber": 2,
                          "averageRating": 0.0,
                          "numVotes": 20
                        },
                        {
                          "tconst": "tt1000003",
                          "parentTconst": "tt27444205",
                          "seasonNumber": 2,
                          "episodeNumber": 1,
                          "averageRating": 7.5,
                          "numVotes": 180
                        }
                      ]
                    }
                    """.trimIndent()
                )
            },
            moshi = Moshi.Builder().build()
        )

        val result = client.fetchEpisodeRatings(
            baseUrl = "https://ratings.example.com/custom",
            apiKey = "secret-key",
            tconst = "tt27444205"
        )

        assertEquals("/custom/v1/ratings/tt27444205", capturedPath)
        assertEquals("true", capturedEpisodesQuery)
        assertEquals("GET", capturedMethod)
        assertNull(capturedRequestBody)
        assertEquals(
            mapOf(
                (1 to 1) to 8.3,
                (2 to 1) to 7.5
            ),
            result
        )
    }

    @Test
    fun `fetchTitleRatings posts bulk identifiers and maps ratings`() = runTest {
        var capturedPath = ""
        var capturedMethod = ""
        var capturedRequestBody: String? = null
        val client = OkHttpCustomImdbClient(
            okHttpClient = okHttpClient { chain ->
                capturedPath = chain.request().url.encodedPath
                capturedMethod = chain.request().method
                capturedRequestBody = chain.request().body?.readUtf8()
                jsonResponse(
                    chain,
                    """
                    {
                      "results": [
                        { "tconst": "tt32459853", "averageRating": 7.8, "numVotes": 1500 },
                        { "tconst": "tt0944947", "averageRating": 9.2, "numVotes": 2300000 },
                        { "tconst": "tt0000000", "averageRating": 0.0, "numVotes": 1 }
                      ],
                      "missing": ["Hello, world!"]
                    }
                    """.trimIndent()
                )
            },
            moshi = Moshi.Builder().build()
        )

        val result = client.fetchTitleRatings(
            baseUrl = "https://ratings.example.com/custom",
            apiKey = "secret-key",
            identifiers = listOf("Hello, world!", "tt32459853", "tt0944947")
        )

        assertEquals("/custom/v1/ratings/bulk", capturedPath)
        assertEquals("POST", capturedMethod)
        assertEquals(
            """{"identifiers":["Hello, world!","tt32459853","tt0944947"]}""",
            capturedRequestBody
        )
        assertEquals(
            mapOf(
                "tt32459853" to 7.8,
                "tt0944947" to 9.2
            ),
            result
        )
    }

    @Test
    fun `fetchEpisodeRatings reuses version path when base url already ends in v1`() = runTest {
        var capturedPath = ""
        val client = OkHttpCustomImdbClient(
            okHttpClient = okHttpClient { chain ->
                capturedPath = chain.request().url.encodedPath
                jsonResponse(
                    chain,
                    """
                    {
                      "requestTconst": "tt27444205",
                      "episodesParentTconst": "tt27444205",
                      "episodes": [
                        {
                          "tconst": "tt1000001",
                          "parentTconst": "tt27444205",
                          "seasonNumber": 1,
                          "episodeNumber": 1,
                          "averageRating": 8.3,
                          "numVotes": 200
                        }
                      ]
                    }
                    """.trimIndent()
                )
            },
            moshi = Moshi.Builder().build()
        )

        val result = client.fetchEpisodeRatings(
            baseUrl = "https://ratings.example.com/custom/v1/",
            apiKey = "secret-key",
            tconst = "tt27444205"
        )

        assertEquals("/custom/v1/ratings/tt27444205", capturedPath)
        assertEquals(mapOf((1 to 1) to 8.3), result)
    }

    @Test
    fun `validate retries once after rate limit using retry after header`() = runTest {
        var attempts = 0
        val delays = mutableListOf<Long>()
        val client = OkHttpCustomImdbClient(
            okHttpClient = okHttpClient { chain ->
                attempts += 1
                when (attempts) {
                    1 -> jsonResponse(
                        chain,
                        """
                        {
                          "error": {
                            "code": "rate_limited",
                            "message": "rate limit exceeded"
                          }
                        }
                        """.trimIndent(),
                        code = 429,
                        headers = mapOf("Retry-After" to "2")
                    )

                    else -> jsonResponse(chain, """{"status":"ok"}""")
                }
            },
            moshi = Moshi.Builder().build()
        ).also { imdbClient ->
            imdbClient.delayMs = { delayMillis ->
                delays += delayMillis
            }
        }

        val result = client.validate(
            baseUrl = "https://ratings.example.com",
            apiKey = "secret-key"
        )

        assertTrue(result)
        assertEquals(2, attempts)
        assertEquals(listOf(2_000L), delays)
    }

    @Test
    fun `fetchEpisodeRatings retries once after rate limit and falls back to one second delay`() = runTest {
        var attempts = 0
        val delays = mutableListOf<Long>()
        val client = OkHttpCustomImdbClient(
            okHttpClient = okHttpClient { chain ->
                attempts += 1
                when (attempts) {
                    1 -> jsonResponse(
                        chain,
                        """
                        {
                          "error": {
                            "code": "rate_limited",
                            "message": "rate limit exceeded"
                          }
                        }
                        """.trimIndent(),
                        code = 429
                    )

                    else -> jsonResponse(
                        chain,
                        """
                        {
                          "requestTconst": "tt27444205",
                          "episodesParentTconst": "tt27444205",
                          "episodes": [
                            {
                              "tconst": "tt1000001",
                              "parentTconst": "tt27444205",
                              "seasonNumber": 1,
                              "episodeNumber": 1,
                              "averageRating": 8.3,
                              "numVotes": 200
                            }
                          ]
                        }
                        """.trimIndent()
                    )
                }
            },
            moshi = Moshi.Builder().build()
        ).also { imdbClient ->
            imdbClient.delayMs = { delayMillis ->
                delays += delayMillis
            }
        }

        val result = client.fetchEpisodeRatings(
            baseUrl = "https://ratings.example.com",
            apiKey = "secret-key",
            tconst = "tt27444205"
        )

        assertEquals(2, attempts)
        assertEquals(listOf(1_000L), delays)
        assertEquals(mapOf((1 to 1) to 8.3), result)
    }

    @Test
    fun `validate returns false when remote call fails`() = runTest {
        val client = OkHttpCustomImdbClient(
            okHttpClient = okHttpClient { throw IOException("boom") },
            moshi = Moshi.Builder().build()
        )

        assertFalse(client.validate("https://ratings.example.com", "secret-key"))
    }

    private fun okHttpClient(
        handler: (Interceptor.Chain) -> Response
    ): OkHttpClient {
        return OkHttpClient.Builder()
            .addInterceptor(handler)
            .build()
    }

    private fun jsonResponse(
        chain: Interceptor.Chain,
        body: String,
        code: Int = 200,
        headers: Map<String, String> = emptyMap()
    ): Response {
        val builder = Response.Builder()
            .request(chain.request())
            .protocol(Protocol.HTTP_1_1)
            .code(code)
            .message("OK")
            .body(body.toResponseBody("application/json".toMediaType()))

        headers.forEach { (name, value) ->
            builder.addHeader(name, value)
        }

        return builder.build()
    }
}

private fun RequestBody.readUtf8(): String {
    val buffer = Buffer()
    writeTo(buffer)
    return buffer.readUtf8()
}
