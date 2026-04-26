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
import com.nexio.tv.data.remote.api.RpdbApi
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

class RpdbIntegrationProviderTest {
    @Test
    fun `fetchPoster routes poster downloads through runtime and poster transport`() = runTest {
        val runtime = mockk<IntegrationRuntime>()
        val rpdbApi = mockk<RpdbApi>()
        val transport = mockk<PosterTransport>()
        val request = PosterIntegrationRequest(
            provider = IntegrationProvider.RPDB,
            cacheKey = "rpdb:imdb:tt0137523:poster-default",
            apiKey = "key",
            path = "imdb/poster-default/tt0137523.jpg",
            ttlMs = 45_000L
        )
        val remoteUrl = "https://api.ratingposterdb.com/key/imdb/poster-default/tt0137523.jpg"
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

        val provider = RpdbIntegrationProvider(runtime, rpdbApi, transport)
        val result = provider.fetchPoster(request)

        assertArrayEquals(payload, result)
        assertEquals(
            listOf("runtime.get-enter", "transport.execute", "runtime.get-exit"),
            events
        )
        assertEquals(IntegrationProvider.RPDB, specSlot.captured.provider)
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
    fun `fetchPoster maps missing transport body to http error result`() = runTest {
        val runtime = mockk<IntegrationRuntime>()
        val transport = mockk<PosterTransport>()
        val request = PosterIntegrationRequest(
            provider = IntegrationProvider.RPDB,
            cacheKey = "rpdb:imdb:tt0137523:poster-default",
            apiKey = "key",
            path = "imdb/poster-default/tt0137523.jpg"
        )
        val remoteUrl = "https://api.ratingposterdb.com/key/imdb/poster-default/tt0137523.jpg"
        val specSlot = slot<IntegrationSpec<ByteArray>>()

        coEvery { runtime.get(capture(specSlot), any<IntegrationFetchOptions>()) } returns IntegrationFetchResult.Missing
        every { transport.execute(remoteUrl) } returns PosterTransportResult(
            statusCode = 200,
            isSuccessful = true,
            body = null
        )

        val provider = RpdbIntegrationProvider(runtime, mockk<RpdbApi>(), transport)
        val result = provider.fetchPoster(request)
        val loadResult = specSlot.captured.load()

        assertNull(result)
        assertTrue(loadResult is IntegrationLoadResult.HttpError)
        loadResult as IntegrationLoadResult.HttpError
        assertEquals(200, loadResult.statusCode)
        assertEquals("rpdb_poster_missing_body", loadResult.reason)
    }

    @Test
    fun `fetchPoster maps transport failures to network error`() = runTest {
        val runtime = mockk<IntegrationRuntime>()
        val transport = mockk<PosterTransport>()
        val request = PosterIntegrationRequest(
            provider = IntegrationProvider.RPDB,
            cacheKey = "rpdb:imdb:tt0137523:poster-default",
            apiKey = "key",
            path = "imdb/poster-default/tt0137523.jpg"
        )
        val remoteUrl = "https://api.ratingposterdb.com/key/imdb/poster-default/tt0137523.jpg"
        val specSlot = slot<IntegrationSpec<ByteArray>>()
        val expected = IOException("socket closed")

        coEvery { runtime.get(capture(specSlot), any<IntegrationFetchOptions>()) } returns IntegrationFetchResult.Missing
        every { transport.execute(remoteUrl) } throws expected

        val provider = RpdbIntegrationProvider(runtime, mockk<RpdbApi>(), transport)
        val result = provider.fetchPoster(request)
        val loadResult = specSlot.captured.load()

        assertNull(result)
        assertTrue(loadResult is IntegrationLoadResult.NetworkError)
        assertEquals(expected, (loadResult as IntegrationLoadResult.NetworkError).throwable)
    }
}
