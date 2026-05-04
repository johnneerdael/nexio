package com.nexio.tv.core.image

import android.net.Uri
import coil.ComponentRegistry
import coil.fetch.SourceResult
import com.nexio.tv.core.integration.IntegrationFetchResult
import com.nexio.tv.core.integration.IntegrationProvider
import com.nexio.tv.core.integration.IntegrationRuntime
import com.nexio.tv.core.integration.IntegrationSpec
import com.nexio.tv.core.integration.RecordingIntegrationRuntime
import com.nexio.tv.data.integration.posters.RpdbIntegrationProvider
import com.nexio.tv.data.integration.posters.TopPostersIntegrationProvider
import com.nexio.tv.data.integration.posters.transport.PosterTransport
import com.nexio.tv.data.integration.posters.transport.PosterTransportResult
import com.nexio.tv.data.remote.api.RpdbApi
import com.nexio.tv.data.remote.api.TopPostersApi
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class IntegrationPosterFetcherTest {
    @Test
    fun `factory accepts mapped Uri poster integration model and rejects remote http Uri`() {
        val factory = IntegrationPosterFetcher.Factory(
            rpdbProvider = RpdbIntegrationProvider(mockk(), mockk<RpdbApi>(), mockk<PosterTransport>()),
            topPostersProvider = TopPostersIntegrationProvider(mockk(), mockk<TopPostersApi>(), mockk<PosterTransport>()),
            fallbackTransport = mockk()
        )
        val registry = ComponentRegistry.Builder()
            .add(factory)
            .build()
        val model = PosterIntegrationRequest(
            provider = IntegrationProvider.RPDB,
            cacheKey = "rpdb:imdb:tt0137523:poster-default",
            apiKey = "key",
            path = "imdb/poster-default/tt0137523.jpg"
        ).toModel()

        assertNotNull(registry.newFetcher(Uri.parse(model), mockk(relaxed = true), mockk(relaxed = true)))
        assertNull(registry.newFetcher(Uri.parse("https://image.tmdb.org/t/p/w500/native.jpg"), mockk(relaxed = true), mockk(relaxed = true)))
    }

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
            topPostersProvider = TopPostersIntegrationProvider(runtime, mockk<TopPostersApi>(), mockk<PosterTransport>()),
            fallbackTransport = mockk<PosterTransport>(relaxed = true)
        )

        val result = fetcher.fetch()

        assertEquals(listOf("rpdb:imdb:tt0137523:poster-default"), runtime.keys)
        assertTrue(result is SourceResult)
    }

    @Test
    fun `poster fetcher renders safe fallback url when premium runtime has no bytes`() = runTest {
        val events = mutableListOf<String>()
        val runtime = mockk<IntegrationRuntime>()
        val spec = mutableListOf<IntegrationSpec<ByteArray>>()
        coEvery { runtime.get<ByteArray>(capture(spec), any()) } coAnswers {
            events += "runtime.get"
            IntegrationFetchResult.Missing
        }
        val transport = mockk<PosterTransport>()
        every { transport.execute("https://image.tmdb.org/t/p/w500/native.jpg") } answers {
            events += "fallback.execute"
            PosterTransportResult(
                statusCode = 200,
                isSuccessful = true,
                body = "fallback".toByteArray()
            )
        }
        val fetcher = IntegrationPosterFetcher(
            request = PosterIntegrationRequest(
                provider = IntegrationProvider.RPDB,
                cacheKey = "rpdb:imdb:tt0137523:poster-default",
                apiKey = "key",
                path = "imdb/poster-default/tt0137523.jpg",
                fallbackUrl = "https://image.tmdb.org/t/p/w500/native.jpg"
            ),
            options = mockk(relaxed = true),
            rpdbProvider = RpdbIntegrationProvider(runtime, mockk<RpdbApi>(), mockk<PosterTransport>()),
            topPostersProvider = TopPostersIntegrationProvider(runtime, mockk<TopPostersApi>(), mockk<PosterTransport>()),
            fallbackTransport = transport
        )

        val result = fetcher.fetch()

        assertTrue(result is SourceResult)
        assertEquals(listOf("rpdb:imdb:tt0137523:poster-default"), spec.map { it.cacheKey })
        assertEquals(listOf("runtime.get", "fallback.execute"), events)
        verify(exactly = 1) { transport.execute("https://image.tmdb.org/t/p/w500/native.jpg") }
    }

    @Test
    fun `poster fetcher does not fetch fallback url when premium runtime returns bytes`() = runTest {
        val runtime = RecordingIntegrationRuntime(successValue = "poster".toByteArray())
        val transport = mockk<PosterTransport>(relaxed = true)
        val fetcher = IntegrationPosterFetcher(
            request = PosterIntegrationRequest(
                provider = IntegrationProvider.RPDB,
                cacheKey = "rpdb:imdb:tt0137523:poster-default",
                apiKey = "key",
                path = "imdb/poster-default/tt0137523.jpg",
                fallbackUrl = "https://image.tmdb.org/t/p/w500/native.jpg"
            ),
            options = mockk(relaxed = true),
            rpdbProvider = RpdbIntegrationProvider(runtime, mockk<RpdbApi>(), mockk<PosterTransport>()),
            topPostersProvider = TopPostersIntegrationProvider(runtime, mockk<TopPostersApi>(), mockk<PosterTransport>()),
            fallbackTransport = transport
        )

        val result = fetcher.fetch()

        assertTrue(result is SourceResult)
        assertEquals(listOf("rpdb:imdb:tt0137523:poster-default"), runtime.keys)
        verify(exactly = 0) { transport.execute(any()) }
    }

    @Test
    fun `poster fetcher refuses premium provider fallback urls`() = runTest {
        val runtime = RecordingIntegrationRuntime<ByteArray>(nextResult = IntegrationFetchResult.Missing)
        val transport = mockk<PosterTransport>(relaxed = true)
        val fetcher = IntegrationPosterFetcher(
            request = PosterIntegrationRequest(
                provider = IntegrationProvider.RPDB,
                cacheKey = "rpdb:imdb:tt0137523:poster-default",
                apiKey = "key",
                path = "imdb/poster-default/tt0137523.jpg",
                fallbackUrl = "https://api.ratingposterdb.com/key/imdb/poster-default/tt0137523.jpg"
            ),
            options = mockk(relaxed = true),
            rpdbProvider = RpdbIntegrationProvider(runtime, mockk<RpdbApi>(), mockk<PosterTransport>()),
            topPostersProvider = TopPostersIntegrationProvider(runtime, mockk<TopPostersApi>(), mockk<PosterTransport>()),
            fallbackTransport = transport
        )

        val result = fetcher.fetch()

        assertNull(result)
        verify(exactly = 0) { transport.execute(any()) }
    }

    @Test
    fun `poster fetcher refuses uppercase premium provider fallback urls`() = runTest {
        val runtime = RecordingIntegrationRuntime<ByteArray>(nextResult = IntegrationFetchResult.Missing)
        val transport = mockk<PosterTransport>(relaxed = true)
        val fetcher = IntegrationPosterFetcher(
            request = PosterIntegrationRequest(
                provider = IntegrationProvider.RPDB,
                cacheKey = "rpdb:imdb:tt0137523:poster-default",
                apiKey = "key",
                path = "imdb/poster-default/tt0137523.jpg",
                fallbackUrl = "https://API.RATINGPOSTERDB.COM/key/imdb/poster-default/tt0137523.jpg"
            ),
            options = mockk(relaxed = true),
            rpdbProvider = RpdbIntegrationProvider(runtime, mockk<RpdbApi>(), mockk<PosterTransport>()),
            topPostersProvider = TopPostersIntegrationProvider(runtime, mockk<TopPostersApi>(), mockk<PosterTransport>()),
            fallbackTransport = transport
        )

        val result = fetcher.fetch()

        assertNull(result)
        verify(exactly = 0) { transport.execute(any()) }
    }

    @Test
    fun `poster fetcher refuses premium provider fallback urls with ports`() = runTest {
        val runtime = RecordingIntegrationRuntime<ByteArray>(nextResult = IntegrationFetchResult.Missing)
        val transport = mockk<PosterTransport>(relaxed = true)
        val fetcher = IntegrationPosterFetcher(
            request = PosterIntegrationRequest(
                provider = IntegrationProvider.RPDB,
                cacheKey = "rpdb:imdb:tt0137523:poster-default",
                apiKey = "key",
                path = "imdb/poster-default/tt0137523.jpg",
                fallbackUrl = "https://api.ratingposterdb.com:443/key/imdb/poster-default/tt0137523.jpg"
            ),
            options = mockk(relaxed = true),
            rpdbProvider = RpdbIntegrationProvider(runtime, mockk<RpdbApi>(), mockk<PosterTransport>()),
            topPostersProvider = TopPostersIntegrationProvider(runtime, mockk<TopPostersApi>(), mockk<PosterTransport>()),
            fallbackTransport = transport
        )

        val result = fetcher.fetch()

        assertNull(result)
        verify(exactly = 0) { transport.execute(any()) }
    }

    @Test
    fun `poster fetcher refuses integration poster fallback urls`() = runTest {
        val runtime = RecordingIntegrationRuntime<ByteArray>(nextResult = IntegrationFetchResult.Missing)
        val transport = mockk<PosterTransport>(relaxed = true)
        val fetcher = IntegrationPosterFetcher(
            request = PosterIntegrationRequest(
                provider = IntegrationProvider.RPDB,
                cacheKey = "rpdb:imdb:tt0137523:poster-default",
                apiKey = "key",
                path = "imdb/poster-default/tt0137523.jpg",
                fallbackUrl = "integration-poster://poster/some-key"
            ),
            options = mockk(relaxed = true),
            rpdbProvider = RpdbIntegrationProvider(runtime, mockk<RpdbApi>(), mockk<PosterTransport>()),
            topPostersProvider = TopPostersIntegrationProvider(runtime, mockk<TopPostersApi>(), mockk<PosterTransport>()),
            fallbackTransport = transport
        )

        val result = fetcher.fetch()

        assertNull(result)
        verify(exactly = 0) { transport.execute(any()) }
    }

    @Test
    fun `poster fetcher refuses top posters premium provider fallback urls`() = runTest {
        val runtime = RecordingIntegrationRuntime<ByteArray>(nextResult = IntegrationFetchResult.Missing)
        val transport = mockk<PosterTransport>(relaxed = true)
        val fetcher = IntegrationPosterFetcher(
            request = PosterIntegrationRequest(
                provider = IntegrationProvider.TOP_POSTERS,
                cacheKey = "top-posters:imdb:tt0137523:poster-default",
                apiKey = "key",
                path = "imdb/poster-default/tt0137523.jpg",
                fallbackUrl = "https://api.top-posters.com/key/imdb/poster-default/tt0137523.jpg"
            ),
            options = mockk(relaxed = true),
            rpdbProvider = RpdbIntegrationProvider(runtime, mockk<RpdbApi>(), mockk<PosterTransport>()),
            topPostersProvider = TopPostersIntegrationProvider(runtime, mockk<TopPostersApi>(), mockk<PosterTransport>()),
            fallbackTransport = transport
        )

        val result = fetcher.fetch()

        assertNull(result)
        verify(exactly = 0) { transport.execute(any()) }
    }
}
