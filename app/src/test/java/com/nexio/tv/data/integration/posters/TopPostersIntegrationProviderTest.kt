package com.nexio.tv.data.integration.posters

import com.nexio.tv.core.image.PosterIntegrationRequest
import com.nexio.tv.core.integration.IntegrationCachePolicy
import com.nexio.tv.core.integration.IntegrationFetchOptions
import com.nexio.tv.core.integration.IntegrationFetchResult
import com.nexio.tv.core.integration.IntegrationLoadResult
import com.nexio.tv.core.integration.IntegrationProvider
import com.nexio.tv.core.integration.IntegrationRuntime
import com.nexio.tv.core.integration.IntegrationSpec
import com.nexio.tv.core.integration.IntegrationWorkClass
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
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

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
            path = "imdb/poster/tt15940132.jpg",
            ttlMs = 45_000L
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
                ttlMs = request.ttlMs,
                staleAfterExpiryMs = request.ttlMs
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
}
