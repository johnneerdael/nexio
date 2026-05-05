package com.nexio.tv.data.integration.posters

import com.nexio.tv.core.image.PosterIntegrationRequest
import com.nexio.tv.core.integration.IntegrationCachePolicy
import com.nexio.tv.core.integration.IntegrationFetchOptions
import com.nexio.tv.core.integration.IntegrationFetchResult
import com.nexio.tv.core.integration.IntegrationHeaderPolicies
import com.nexio.tv.core.integration.IntegrationLoadResult
import com.nexio.tv.core.integration.IntegrationProvider
import com.nexio.tv.core.integration.IntegrationRuntime
import com.nexio.tv.core.integration.IntegrationSpec
import com.nexio.tv.core.integration.IntegrationWorkClass
import com.nexio.tv.core.integration.PosterApiShapes
import com.nexio.tv.core.integration.StringIntegrationCodec
import com.nexio.tv.core.integration.credentialHash
import com.nexio.tv.data.integration.posters.transport.PosterTransport
import com.nexio.tv.data.integration.posters.transport.PosterTransportResult
import com.nexio.tv.data.remote.api.TopPostersApi
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import retrofit2.Response

class TopPostersIntegrationProviderTest {
    @Test
    fun `fetchPoster routes poster downloads through runtime and poster transport`() = runTest {
        val runtime = mockk<IntegrationRuntime>()
        val topPostersApi = mockk<TopPostersApi>()
        val transport = mockk<PosterTransport>()
        val request = PosterIntegrationRequest(
            provider = IntegrationProvider.TOP_POSTERS,
            cacheKey = "topposters:imdb:tt15940132:poster-default",
            apiKey = "key",
            path = "imdb/poster/tt15940132.jpg"
        )
        val remoteUrl = "https://api.top-posters.com/key/imdb/poster/tt15940132.jpg"
        val payload = "poster".toByteArray()
        val specSlot = slot<IntegrationSpec<ByteArray>>()
        val events = mutableListOf<String>()

        coEvery { runtime.get(capture(specSlot), any<IntegrationFetchOptions>()) } coAnswers {
            events += "runtime.get-enter"
            val loadResult = specSlot.captured.load()
            events += "runtime.get-exit"
            when (loadResult) {
                is IntegrationLoadResult.Success -> IntegrationFetchResult.Updated(loadResult.value)
                else -> IntegrationFetchResult.Missing
            }
        }
        every { transport.execute(remoteUrl) } answers {
            events += "transport.execute"
            PosterTransportResult(
                statusCode = 200,
                isSuccessful = true,
                body = payload
            )
        }

        val provider = TopPostersIntegrationProvider(runtime, topPostersApi, transport)
        val result = provider.fetchPoster(request)

        assertArrayEquals(payload, result)
        assertEquals(
            listOf("runtime.get-enter", "transport.execute", "runtime.get-exit"),
            events
        )
        assertEquals(IntegrationProvider.TOP_POSTERS, specSlot.captured.provider)
        assertEquals(request.cacheKey, specSlot.captured.cacheKey)
        assertEquals(IntegrationWorkClass.USER_VISIBLE, specSlot.captured.workClass)
        assertEquals(
            IntegrationCachePolicy.CacheFirst(
                ttlMs = 24L * 60L * 60L * 1000L,
                staleAfterExpiryMs = 7L * 24L * 60L * 60L * 1000L
            ),
            specSlot.captured.cachePolicy
        )
        coVerify(exactly = 1) { runtime.get(any<IntegrationSpec<ByteArray>>(), any<IntegrationFetchOptions>()) }
        verify(exactly = 1) { transport.execute(remoteUrl) }
    }

    @Test
    fun `fetchPoster maps http failures to null fetch result`() = runTest {
        val runtime = mockk<IntegrationRuntime>()
        val transport = mockk<PosterTransport>()
        val request = PosterIntegrationRequest(
            provider = IntegrationProvider.TOP_POSTERS,
            cacheKey = "topposters:imdb:tt15940132:poster-default",
            apiKey = "key",
            path = "imdb/poster/tt15940132.jpg"
        )
        val remoteUrl = "https://api.top-posters.com/key/imdb/poster/tt15940132.jpg"
        val specSlot = slot<IntegrationSpec<ByteArray>>()

        coEvery { runtime.get(capture(specSlot), any<IntegrationFetchOptions>()) } returns IntegrationFetchResult.Missing
        every { transport.execute(remoteUrl) } returns PosterTransportResult(
            statusCode = 503,
            isSuccessful = false,
            body = "down".toByteArray()
        )

        val provider = TopPostersIntegrationProvider(runtime, mockk<TopPostersApi>(), transport)
        val result = provider.fetchPoster(request)
        val loadResult = specSlot.captured.load()

        assertNull(result)
        assertTrue(loadResult is IntegrationLoadResult.HttpError)
        loadResult as IntegrationLoadResult.HttpError
        assertEquals(503, loadResult.statusCode)
        assertEquals("topposters_poster_failed", loadResult.reason)
    }

    @Test
    fun `fetchPoster maps transport failures to network error`() = runTest {
        val runtime = mockk<IntegrationRuntime>()
        val transport = mockk<PosterTransport>()
        val request = PosterIntegrationRequest(
            provider = IntegrationProvider.TOP_POSTERS,
            cacheKey = "topposters:imdb:tt15940132:poster-default",
            apiKey = "key",
            path = "imdb/poster/tt15940132.jpg"
        )
        val remoteUrl = "https://api.top-posters.com/key/imdb/poster/tt15940132.jpg"
        val specSlot = slot<IntegrationSpec<ByteArray>>()
        val expected = IOException("timeout")

        coEvery { runtime.get(capture(specSlot), any<IntegrationFetchOptions>()) } returns IntegrationFetchResult.Missing
        every { transport.execute(remoteUrl) } throws expected

        val provider = TopPostersIntegrationProvider(runtime, mockk<TopPostersApi>(), transport)
        val result = provider.fetchPoster(request)
        val loadResult = specSlot.captured.load()

        assertNull(result)
        assertTrue(loadResult is IntegrationLoadResult.NetworkError)
        assertEquals(expected, (loadResult as IntegrationLoadResult.NetworkError).throwable)
    }

    @Test
    fun `validateApiKey routes validation through runtime with cache first hashed credential key and returns entitlement`() = runTest {
        val runtime = mockk<IntegrationRuntime>()
        val topPostersApi = mockk<TopPostersApi>()
        val transport = mockk<PosterTransport>()
        val specSlot = slot<IntegrationSpec<String>>()
        val rawApiKey = " top-secret-key "
        val trimmedApiKey = "top-secret-key"
        val json = """
            {
              "valid": true,
              "is_active": true,
              "tier": 1,
              "tier_name": "Premium",
              "tier_info": {
                "features": {
                  "episode_thumbnails": true
                }
              }
            }
        """.trimIndent()

        coEvery { runtime.get(capture(specSlot), any<IntegrationFetchOptions>()) } coAnswers {
            val loadResult = specSlot.captured.load()
            when (loadResult) {
                is IntegrationLoadResult.Success -> IntegrationFetchResult.Updated(loadResult.value)
                else -> IntegrationFetchResult.Missing
            }
        }
        coEvery { topPostersApi.verifyApiKey(trimmedApiKey) } returns Response.success(
            json.toResponseBody("application/json".toMediaType())
        )

        val provider = TopPostersIntegrationProvider(runtime, topPostersApi, transport)
        val snapshot = provider.validateApiKey(rawApiKey)

        requireNotNull(snapshot)
        assertTrue(snapshot.valid)
        assertTrue(snapshot.isActive)
        assertEquals(1, snapshot.tier)
        assertEquals("Premium", snapshot.tierName)
        assertTrue(snapshot.episodeThumbnails)
        assertEquals(snapshot.verifiedAtMs + TopPostersIntegrationProvider.TOP_POSTERS_ENTITLEMENT_TTL_MS, snapshot.expiresAtMs)
        assertEquals(IntegrationProvider.TOP_POSTERS, specSlot.captured.provider)
        assertEquals(PosterApiShapes.TOP_POSTERS_KEY_VALIDATION, specSlot.captured.apiShapeId)
        assertEquals(IntegrationHeaderPolicies.TOP_POSTERS_IMAGE_PATH_KEY_V1, specSlot.captured.headerPolicyId)
        assertEquals("topposters.key.validate", specSlot.captured.operationKey)
        assertEquals(StringIntegrationCodec, specSlot.captured.codec)
        assertEquals(IntegrationWorkClass.USER_VISIBLE, specSlot.captured.workClass)
        assertEquals(
            IntegrationCachePolicy.CacheFirst(
                ttlMs = TopPostersIntegrationProvider.TOP_POSTERS_ENTITLEMENT_TTL_MS,
                staleAfterExpiryMs = TopPostersIntegrationProvider.TOP_POSTERS_ENTITLEMENT_TTL_MS
            ),
            specSlot.captured.cachePolicy
        )
        assertFalse(specSlot.captured.cacheKey.orEmpty().contains(trimmedApiKey))
        assertTrue(
            specSlot.captured.cacheKey.orEmpty().contains(
                credentialHash(IntegrationProvider.TOP_POSTERS, trimmedApiKey)
            )
        )
        coVerify(exactly = 1) { topPostersApi.verifyApiKey(trimmedApiKey) }
    }

    @Test
    fun `validateApiKey load maps non successful response to validation http error`() = runTest {
        val runtime = mockk<IntegrationRuntime>()
        val topPostersApi = mockk<TopPostersApi>()
        val specSlot = slot<IntegrationSpec<String>>()
        val errorBody = """{"error":"invalid"}""".toResponseBody("application/json".toMediaType())

        coEvery { runtime.get(capture(specSlot), any<IntegrationFetchOptions>()) } returns IntegrationFetchResult.Missing
        coEvery { topPostersApi.verifyApiKey("top-secret-key") } returns Response.error(403, errorBody)

        val provider = TopPostersIntegrationProvider(runtime, topPostersApi, mockk<PosterTransport>())
        val snapshot = provider.validateApiKey("top-secret-key")
        val loadResult = specSlot.captured.load()

        assertNull(snapshot)
        assertTrue(loadResult is IntegrationLoadResult.HttpError)
        loadResult as IntegrationLoadResult.HttpError
        assertEquals(403, loadResult.statusCode)
        assertEquals("topposters_key_validation_failed", loadResult.reason)
    }

    @Test
    fun `validateApiKey returns cached validation body without loading from network`() = runTest {
        val runtime = mockk<IntegrationRuntime>()
        val topPostersApi = mockk<TopPostersApi>()
        val specSlot = slot<IntegrationSpec<String>>()
        val cachedJson = """
            {
              "valid": true,
              "is_active": true,
              "tier": 1,
              "tier_name": "Premium",
              "tier_info": {
                "features": {
                  "episode_thumbnails": true
                }
              }
            }
        """.trimIndent()

        coEvery { runtime.get(capture(specSlot), any<IntegrationFetchOptions>()) } returns IntegrationFetchResult.Fresh(cachedJson)

        val provider = TopPostersIntegrationProvider(runtime, topPostersApi, mockk<PosterTransport>())
        val snapshot = provider.validateApiKey("top-secret-key")

        requireNotNull(snapshot)
        assertTrue(snapshot.valid)
        assertTrue(snapshot.episodeThumbnails)
        coVerify(exactly = 0) { topPostersApi.verifyApiKey(any()) }
    }

    @Test
    fun `validateApiKey disables cache policy when force refresh is requested`() = runTest {
        val runtime = mockk<IntegrationRuntime>()
        val topPostersApi = mockk<TopPostersApi>()
        val specSlot = slot<IntegrationSpec<String>>()

        coEvery { runtime.get(capture(specSlot), any<IntegrationFetchOptions>()) } returns IntegrationFetchResult.Updated(
            """{"valid":false}"""
        )

        val provider = TopPostersIntegrationProvider(runtime, topPostersApi, mockk<PosterTransport>())
        provider.validateApiKey("top-secret-key", forceRefresh = true)

        assertEquals(IntegrationCachePolicy.Disabled, specSlot.captured.cachePolicy)
    }
}
