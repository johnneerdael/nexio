package com.nexio.tv.core.image

import coil.fetch.SourceResult
import com.nexio.tv.core.integration.IntegrationProvider
import com.nexio.tv.core.integration.RecordingIntegrationRuntime
import com.nexio.tv.data.integration.posters.RpdbIntegrationProvider
import com.nexio.tv.data.integration.posters.TopPostersIntegrationProvider
import com.nexio.tv.data.integration.posters.transport.PosterTransport
import com.nexio.tv.data.remote.api.RpdbApi
import com.nexio.tv.data.remote.api.TopPostersApi
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class IntegrationPosterFetcherTest {
    @Test
    fun `poster fetcher converts poster request into runtime cache key instead of remote url pass through`() = runTest {
        val runtime = RecordingIntegrationRuntime(successValue = "poster".toByteArray())
        val fetcher = IntegrationPosterFetcher(
            request = PosterIntegrationRequest(
                provider = IntegrationProvider.RPDB,
                cacheKey = "rpdb:imdb:tt0137523:poster-default",
                apiKey = "key",
                path = "imdb/poster-default/tt0137523.jpg"
            ),
            options = mockk(relaxed = true),
            rpdbProvider = RpdbIntegrationProvider(runtime, mockk<RpdbApi>(), mockk<PosterTransport>()),
            topPostersProvider = TopPostersIntegrationProvider(runtime, mockk<TopPostersApi>(), mockk<PosterTransport>())
        )

        val result = fetcher.fetch()

        assertEquals(listOf("rpdb:imdb:tt0137523:poster-default"), runtime.keys)
        assertTrue(result is SourceResult)
    }
}
