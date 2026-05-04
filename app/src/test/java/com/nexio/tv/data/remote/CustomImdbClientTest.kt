package com.nexio.tv.data.remote

import com.nexio.tv.core.integration.IntegrationCallResult
import com.nexio.tv.data.integration.imdb.CustomImdbPayload
import com.nexio.tv.data.integration.imdb.CustomImdbRatingsIntegrationProvider
import com.squareup.moshi.Moshi
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.test.runTest
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody
import okio.Buffer
import org.junit.Assert.assertEquals
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
    fun `fetchEpisodeRatings delegates request construction to provider and maps wrapper payload`() = runTest {
        val provider = mockk<CustomImdbRatingsIntegrationProvider>()
        val requestSlot = slot<okhttp3.Request>()
        val baseUrlSlot = slot<String>()
        val client = buildClient(
            provider = provider,
            baseUrl = "https://ratings.example.com/custom",
            apiKey = "secret-key"
        )
        coEvery {
            provider.execute(capture(baseUrlSlot), capture(requestSlot))
        } returns IntegrationCallResult.Success(
            CustomImdbPayload(
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
        )

        val result = client.fetchEpisodeRatings(tconst = "tt27444205")

        assertEquals("https://ratings.example.com/custom", baseUrlSlot.captured)
        assertEquals("/custom/v1/ratings/tt27444205", requestSlot.captured.url.encodedPath)
        assertEquals("true", requestSlot.captured.url.queryParameter("episodes"))
        assertEquals("GET", requestSlot.captured.method)
        assertNull(requestSlot.captured.body)
        assertEquals("secret-key", requestSlot.captured.header("X-API-Key"))
        assertEquals(
            mapOf(
                (1 to 1) to 8.3,
                (2 to 1) to 7.5
            ),
            result
        )
        coVerify(exactly = 1) { provider.execute(any(), any()) }
    }

    @Test
    fun `fetchTitleRatings posts bulk identifiers and maps ratings`() = runTest {
        val provider = mockk<CustomImdbRatingsIntegrationProvider>()
        val requestSlot = slot<okhttp3.Request>()
        val client = buildClient(
            provider = provider,
            baseUrl = "https://ratings.example.com/custom",
            apiKey = "secret-key"
        )
        coEvery { provider.execute(any(), capture(requestSlot)) } returns IntegrationCallResult.Success(
            CustomImdbPayload(
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
        )

        val result = client.fetchTitleRatings(
            identifiers = listOf("Hello, world!", "tt32459853", "tt0944947")
        )

        assertEquals("/custom/v1/ratings/bulk", requestSlot.captured.url.encodedPath)
        assertEquals("POST", requestSlot.captured.method)
        assertEquals(
            """{"identifiers":["Hello, world!","tt32459853","tt0944947"]}""",
            requestSlot.captured.body?.readUtf8()
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
    fun `fetchTitleRatings ignores out of range average ratings`() = runTest {
        val provider = mockk<CustomImdbRatingsIntegrationProvider>()
        val client = buildClient(
            provider = provider,
            baseUrl = "https://ratings.example.com/custom",
            apiKey = "secret-key"
        )
        coEvery { provider.execute(any(), any()) } returns IntegrationCallResult.Success(
            CustomImdbPayload(
                """
                {
                  "results": [
                    { "tconst": "tt0944947", "averageRating": 92345, "numVotes": 2300000 },
                    { "tconst": "tt1111111", "averageRating": 9.1, "numVotes": 1200 }
                  ],
                  "missing": []
                }
                """.trimIndent()
            )
        )

        val result = client.fetchTitleRatings(listOf("tt0944947", "tt1111111"))

        assertEquals(mapOf("tt1111111" to 9.1), result)
    }

    @Test
    fun `fetchEpisodeRatings ignores out of range average ratings`() = runTest {
        val provider = mockk<CustomImdbRatingsIntegrationProvider>()
        val client = buildClient(
            provider = provider,
            baseUrl = "https://ratings.example.com/custom",
            apiKey = "secret-key"
        )
        coEvery { provider.execute(any(), any()) } returns IntegrationCallResult.Success(
            CustomImdbPayload(
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
                      "averageRating": 81234,
                      "numVotes": 200
                    },
                    {
                      "tconst": "tt1000002",
                      "parentTconst": "tt27444205",
                      "seasonNumber": 1,
                      "episodeNumber": 2,
                      "averageRating": 7.4,
                      "numVotes": 180
                    }
                  ]
                }
                """.trimIndent()
            )
        )

        val result = client.fetchEpisodeRatings(tconst = "tt27444205")

        assertEquals(mapOf((1 to 2) to 7.4), result)
    }

    @Test
    fun `fetchEpisodeRatings reuses version path when base url already ends in v1`() = runTest {
        val provider = mockk<CustomImdbRatingsIntegrationProvider>()
        val requestSlot = slot<okhttp3.Request>()
        val client = buildClient(
            provider = provider,
            baseUrl = "https://ratings.example.com/custom/v1/",
            apiKey = "secret-key"
        )
        coEvery { provider.execute(any(), capture(requestSlot)) } returns IntegrationCallResult.Success(
            CustomImdbPayload(
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
        )

        val result = client.fetchEpisodeRatings(tconst = "tt27444205")

        assertEquals("/custom/v1/ratings/tt27444205", requestSlot.captured.url.encodedPath)
        assertEquals(mapOf((1 to 1) to 8.3), result)
    }

    @Test
    fun `fetchEpisodeRatings retries once after rate limit and falls back to one second delay`() = runTest {
        val provider = mockk<CustomImdbRatingsIntegrationProvider>()
        val delays = mutableListOf<Long>()
        val client = buildClient(
            provider = provider,
            baseUrl = "https://ratings.example.com",
            apiKey = "secret-key"
        ).also { imdbClient ->
            imdbClient.delayMs = { delayMillis ->
                delays += delayMillis
            }
        }
        coEvery { provider.execute(any(), any()) } returnsMany listOf(
            IntegrationCallResult.HttpError(statusCode = 429, retryAfterMs = 1_000L),
            IntegrationCallResult.Success(
                CustomImdbPayload(
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
            )
        )

        val result = client.fetchEpisodeRatings(tconst = "tt27444205")

        coVerify(exactly = 2) { provider.execute(any(), any()) }
        assertEquals(listOf(1_000L), delays)
        assertEquals(mapOf((1 to 1) to 8.3), result)
    }

    @Test
    fun `fetchEpisodeRatings retries once after rate limit using retry after header`() = runTest {
        val provider = mockk<CustomImdbRatingsIntegrationProvider>()
        val delays = mutableListOf<Long>()
        val client = buildClient(
            provider = provider,
            baseUrl = "https://ratings.example.com",
            apiKey = "secret-key"
        ).also { imdbClient ->
            imdbClient.delayMs = { delayMillis ->
                delays += delayMillis
            }
        }
        coEvery { provider.execute(any(), any()) } returnsMany listOf(
            IntegrationCallResult.HttpError(statusCode = 429, retryAfterMs = 2_000L),
            IntegrationCallResult.Success(
                CustomImdbPayload(
                    """
                    {
                      "requestTconst": "tt27444205",
                      "episodesParentTconst": "tt27444205",
                      "episodes": []
                    }
                    """.trimIndent()
                )
            )
        )

        val result = client.fetchEpisodeRatings(tconst = "tt27444205")

        coVerify(exactly = 2) { provider.execute(any(), any()) }
        assertEquals(listOf(2_000L), delays)
        assertEquals(emptyMap<Pair<Int, Int>, Double>(), result)
    }

    @Test
    fun `fetchEpisodeRatings returns empty map when remote call fails`() = runTest {
        val provider = mockk<CustomImdbRatingsIntegrationProvider>()
        val client = buildClient(
            provider = provider,
            baseUrl = "https://ratings.example.com",
            apiKey = "secret-key"
        )
        coEvery { provider.execute(any(), any()) } returns IntegrationCallResult.NetworkError(Exception("boom"))

        assertEquals(emptyMap<Pair<Int, Int>, Double>(), client.fetchEpisodeRatings("tt27444205"))
    }

    @Test
    fun `fetchEpisodeRatings returns empty map when provider returns missing`() = runTest {
        val provider = mockk<CustomImdbRatingsIntegrationProvider>()
        val client = buildClient(
            provider = provider,
            baseUrl = "https://ratings.example.com",
            apiKey = "secret-key"
        )
        coEvery { provider.execute(any(), any()) } returns IntegrationCallResult.Missing

        assertEquals(emptyMap<Pair<Int, Int>, Double>(), client.fetchEpisodeRatings("tt27444205"))
    }

    @Test
    fun `fetchEpisodeRatings and fetchTitleRatings return empty maps for malformed payloads`() = runTest {
        val provider = mockk<CustomImdbRatingsIntegrationProvider>()
        val client = buildClient(
            provider = provider,
            baseUrl = "https://ratings.example.com",
            apiKey = "secret-key"
        )
        coEvery { provider.execute(any(), any()) } returnsMany listOf(
            IntegrationCallResult.Success(
                CustomImdbPayload(
                    """
                    {
                      "requestTconst": "tt27444205",
                      "episodes": [
                    """.trimIndent()
                )
            ),
            IntegrationCallResult.Success(
                CustomImdbPayload(
                    """
                    {
                      "results": [
                        { "tconst": "tt32459853", "averageRating": 7.8 }
                    """.trimIndent()
                )
            )
        )

        assertEquals(emptyMap<Pair<Int, Int>, Double>(), client.fetchEpisodeRatings("tt27444205"))
        assertEquals(emptyMap<String, Double>(), client.fetchTitleRatings(listOf("tt32459853")))
        coVerify(exactly = 2) { provider.execute(any(), any()) }
    }

    @Test
    fun `fetchTitleRatings returns empty map when provider returns missing`() = runTest {
        val provider = mockk<CustomImdbRatingsIntegrationProvider>()
        val client = buildClient(
            provider = provider,
            baseUrl = "https://ratings.example.com",
            apiKey = "secret-key"
        )
        coEvery { provider.execute(any(), any()) } returns IntegrationCallResult.Missing

        assertEquals(emptyMap<String, Double>(), client.fetchTitleRatings(listOf("tt32459853")))
    }

    @Test
    fun `fetchEpisodeRatings returns empty map when api key is blank`() = runTest {
        val provider = mockk<CustomImdbRatingsIntegrationProvider>(relaxed = true)
        val client = buildClient(
            provider = provider,
            baseUrl = "https://ratings.example.com",
            apiKey = ""
        )

        assertEquals(emptyMap<Pair<Int, Int>, Double>(), client.fetchEpisodeRatings("tt27444205"))
        coVerify(exactly = 0) { provider.execute(any(), any()) }
    }

    @Test
    fun `validate delegates through provider and preserves success and failure mapping`() = runTest {
        val provider = mockk<CustomImdbRatingsIntegrationProvider>()
        val requestSlot = slot<okhttp3.Request>()
        val baseUrlSlot = slot<String>()
        val client = buildClient(
            provider = provider,
            baseUrl = "https://unused.example.com",
            apiKey = "unused-key"
        )
        coEvery { provider.execute(capture(baseUrlSlot), capture(requestSlot)) } returnsMany listOf(
            IntegrationCallResult.Success(CustomImdbPayload("""{"ok":true}""")),
            IntegrationCallResult.HttpError(statusCode = 503)
        )

        val success = client.validate(" https://ratings.example.com/custom/ ", " secret-key ")
        val failure = client.validate(" https://ratings.example.com/custom/ ", " secret-key ")

        assertTrue(success)
        assertEquals(false, failure)
        assertEquals("https://ratings.example.com/custom", baseUrlSlot.captured)
        assertEquals("/custom/v1/meta/stats", requestSlot.captured.url.encodedPath)
        assertEquals("GET", requestSlot.captured.method)
        assertNull(requestSlot.captured.body)
        assertEquals("secret-key", requestSlot.captured.header("X-API-Key"))
        coVerify(exactly = 2) { provider.execute(any(), any()) }
    }

    @Test
    fun `validate returns false when provider returns missing`() = runTest {
        val provider = mockk<CustomImdbRatingsIntegrationProvider>()
        val client = buildClient(
            provider = provider,
            baseUrl = "https://unused.example.com",
            apiKey = "unused-key"
        )
        coEvery { provider.execute(any(), any()) } returns IntegrationCallResult.Missing

        assertEquals(false, client.validate(" https://ratings.example.com/custom/ ", " secret-key "))
        coVerify(exactly = 1) { provider.execute(any(), any()) }
    }

    private fun buildClient(
        provider: CustomImdbRatingsIntegrationProvider,
        baseUrl: String,
        apiKey: String
    ): OkHttpCustomImdbClient {
        return OkHttpCustomImdbClient(
            integrationProvider = provider,
            moshi = Moshi.Builder().build()
        ).also { client ->
            client.baseUrlProvider = { baseUrl }
            client.apiKeyProvider = { apiKey }
        }
    }
}

private fun RequestBody.readUtf8(): String {
    val buffer = Buffer()
    writeTo(buffer)
    return buffer.readUtf8()
}
