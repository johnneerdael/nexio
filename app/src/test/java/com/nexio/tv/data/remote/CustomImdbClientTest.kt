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
    fun `fetchEpisodeRatings posts identifiers and maps nested episode payload`() = runTest {
        var capturedPath = ""
        var capturedBody = ""
        val client = OkHttpCustomImdbClient(
            okHttpClient = okHttpClient { chain ->
                capturedPath = chain.request().url.encodedPath
                capturedBody = chain.request().body!!.readUtf8()
                jsonResponse(
                    chain,
                    """
                    {
                      "items": [
                        {
                          "identifier": "tt27444205",
                          "episodes": [
                            { "seasonNumber": 1, "episodeNumber": 1, "averageRating": 8.3 },
                            { "seasonNumber": 1, "episodeNumber": 2, "averageRating": 0.0 },
                            { "seasonNumber": 2, "episodeNumber": 1, "averageRating": 7.5 }
                          ]
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
            identifiers = listOf("tt27444205")
        )

        assertEquals("/custom/v1/series/bulk/episode-ratings", capturedPath)
        assertEquals("""{"identifiers":["tt27444205"]}""", capturedBody)
        assertEquals(
            mapOf(
                "tt27444205" to mapOf(
                    (1 to 1) to 8.3,
                    (2 to 1) to 7.5
                )
            ),
            result
        )
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
        code: Int = 200
    ): Response {
        return Response.Builder()
            .request(chain.request())
            .protocol(Protocol.HTTP_1_1)
            .code(code)
            .message("OK")
            .body(body.toResponseBody("application/json".toMediaType()))
            .build()
    }
}

private fun RequestBody.readUtf8(): String {
    val buffer = Buffer()
    writeTo(buffer)
    return buffer.readUtf8()
}
