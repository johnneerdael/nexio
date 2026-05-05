package com.nexio.tv.core.image

import android.graphics.Bitmap
import android.graphics.Color
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import coil.annotation.ExperimentalCoilApi
import coil.ComponentRegistry
import coil.disk.DiskCache
import coil.fetch.SourceResult
import coil.request.Options
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
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import java.io.ByteArrayOutputStream
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.rules.TemporaryFolder
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class IntegrationPosterFetcherTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @OptIn(ExperimentalCoilApi::class)
    @Test
    fun `poster fetcher writes resolved bytes to coil disk cache key for every premium provider`() = runTest {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        listOf(
            IntegrationProvider.RPDB to "rpdb",
            IntegrationProvider.TOP_POSTERS to "top_posters"
        ).forEach { (provider, providerTag) ->
            val diskKey = "artwork:$providerTag:poster:tt0137523:imageLang:en:policy:1"
            val diskCache = DiskCache.Builder()
                .directory(temporaryFolder.newFolder("image_cache_$providerTag"))
                .build()
            val runtime = RecordingIntegrationRuntime(successValue = sampleJpeg())
            val fetcher = IntegrationPosterFetcher(
                request = PosterIntegrationRequest(
                    provider = provider,
                    cacheKey = "$providerTag:imdb:tt0137523:poster-default",
                    apiKey = "key",
                    path = "imdb/poster-default/tt0137523.jpg"
                ),
                options = Options(context = context, diskCacheKey = diskKey),
                rpdbProvider = RpdbIntegrationProvider(runtime, mockk<RpdbApi>(), mockk<PosterTransport>()),
                topPostersProvider = TopPostersIntegrationProvider(runtime, mockk<TopPostersApi>(), mockk<PosterTransport>()),
                fallbackTransport = mockk(relaxed = true),
                diskCache = diskCache
            )

            val result = fetcher.fetch()

            assertTrue(result is SourceResult)
            val snapshot = diskCache.openSnapshot(diskKey)
            assertNotNull("Expected cache entry for $provider", snapshot)
            snapshot?.close()
        }
    }

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
    fun `factory rejects malformed top posters thumbnail model without throwing`() {
        val factory = IntegrationPosterFetcher.Factory(
            rpdbProvider = RpdbIntegrationProvider(mockk(), mockk<RpdbApi>(), mockk<PosterTransport>()),
            topPostersProvider = TopPostersIntegrationProvider(mockk(), mockk<TopPostersApi>(), mockk<PosterTransport>()),
            fallbackTransport = mockk()
        )
        val registry = ComponentRegistry.Builder()
            .add(factory)
            .build()
        val malformedModel = "integration-poster://fetch?" +
            "type=topposters-thumbnail" +
            "&apiKey=key" +
            "&idType=imdb" +
            "&mediaId=tt0137523" +
            "&season=0" +
            "&episode=5" +
            "&credentialHash=credential-hash"

        assertNull(registry.newFetcher(Uri.parse(malformedModel), mockk(relaxed = true), mockk(relaxed = true)))
    }

    @Test
    fun `factory rejects providerless top posters thumbnail model without throwing`() {
        val factory = IntegrationPosterFetcher.Factory(
            rpdbProvider = RpdbIntegrationProvider(mockk(), mockk<RpdbApi>(), mockk<PosterTransport>()),
            topPostersProvider = TopPostersIntegrationProvider(mockk(), mockk<TopPostersApi>(), mockk<PosterTransport>()),
            fallbackTransport = mockk()
        )
        val registry = ComponentRegistry.Builder()
            .add(factory)
            .build()
        val providerlessModel = "integration-poster://fetch?" +
            "type=topposters-thumbnail" +
            "&apiKey=key" +
            "&idType=imdb" +
            "&mediaId=tt0137523" +
            "&season=1" +
            "&episode=5" +
            "&credentialHash=credential-hash"

        assertNull(TopPostersThumbnailRequest.fromModel(providerlessModel))
        assertNull(registry.newFetcher(Uri.parse(providerlessModel), mockk(relaxed = true), mockk(relaxed = true)))
    }

    @Test
    fun `factory rejects top posters thumbnail model with invalid poster provider without throwing`() {
        val factory = IntegrationPosterFetcher.Factory(
            rpdbProvider = RpdbIntegrationProvider(mockk(), mockk<RpdbApi>(), mockk<PosterTransport>()),
            topPostersProvider = TopPostersIntegrationProvider(mockk(), mockk<TopPostersApi>(), mockk<PosterTransport>()),
            fallbackTransport = mockk()
        )
        val registry = ComponentRegistry.Builder()
            .add(factory)
            .build()
        val malformedModel = "integration-poster://fetch?" +
            "type=topposters-thumbnail" +
            "&provider=BOGUS" +
            "&apiKey=key" +
            "&idType=imdb" +
            "&mediaId=tt0137523" +
            "&season=0" +
            "&episode=5" +
            "&credentialHash=credential-hash"

        assertNull(registry.newFetcher(Uri.parse(malformedModel), mockk(relaxed = true), mockk(relaxed = true)))
    }

    @Test
    fun `factory rejects thumbnail typed poster fields without dispatching poster fetch`() {
        val factory = IntegrationPosterFetcher.Factory(
            rpdbProvider = RpdbIntegrationProvider(mockk(), mockk<RpdbApi>(), mockk<PosterTransport>()),
            topPostersProvider = TopPostersIntegrationProvider(mockk(), mockk<TopPostersApi>(), mockk<PosterTransport>()),
            fallbackTransport = mockk()
        )
        val registry = ComponentRegistry.Builder()
            .add(factory)
            .build()
        val malformedModel = "integration-poster://fetch?" +
            "type=topposters-thumbnail" +
            "&provider=TOP_POSTERS" +
            "&cacheKey=topposters:imdb:tt0137523:poster-default" +
            "&apiKey=key" +
            "&path=imdb/poster-default/tt0137523.jpg"

        assertNull(PosterIntegrationRequest.fromModel(malformedModel))
        assertNull(registry.newFetcher(Uri.parse(malformedModel), mockk(relaxed = true), mockk(relaxed = true)))
    }

    @Test
    fun `factory rejects malformed percent encoded poster model without throwing`() {
        val factory = IntegrationPosterFetcher.Factory(
            rpdbProvider = RpdbIntegrationProvider(mockk(), mockk<RpdbApi>(), mockk<PosterTransport>()),
            topPostersProvider = TopPostersIntegrationProvider(mockk(), mockk<TopPostersApi>(), mockk<PosterTransport>()),
            fallbackTransport = mockk()
        )
        val registry = ComponentRegistry.Builder()
            .add(factory)
            .build()
        val malformedModel = "integration-poster://fetch?" +
            "type=poster" +
            "&provider=TOP_POSTERS" +
            "&cacheKey=topposters%ZZ" +
            "&apiKey=key" +
            "&path=imdb/poster-default/tt0137523.jpg"

        assertNull(registry.newFetcher(Uri.parse(malformedModel), mockk(relaxed = true), mockk(relaxed = true)))
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
    fun `poster fetcher dispatches top posters thumbnail model to thumbnail runtime request`() = runTest {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val topPostersProvider = mockk<TopPostersIntegrationProvider>()
        val fallbackTransport = mockk<PosterTransport>(relaxed = true)
        val request = TopPostersThumbnailRequest(
            apiKey = "key",
            idType = "imdb",
            mediaId = "tt0137523",
            season = 1,
            episode = 5,
            credentialHash = "credential-hash"
        )
        val fetcher = IntegrationPosterFetcher(
            request = requireNotNull(IntegrationPosterRequest.fromModel(request.toModel())),
            options = Options(context = context, diskCacheKey = request.cacheKey),
            rpdbProvider = mockk<RpdbIntegrationProvider>(relaxed = true),
            topPostersProvider = topPostersProvider,
            fallbackTransport = fallbackTransport
        )

        coEvery { topPostersProvider.fetchThumbnail(request) } returns "thumbnail".toByteArray()

        val result = fetcher.fetch()

        assertTrue(result is SourceResult)
        assertEquals(
            "artwork-asset:TOP_POSTERS:thumbnail:imdb:tt0137523:S1E5:badgeSize:small:badgePos:top-right:blur:false:credential:credential-hash:imageLang:en:policy:1",
            request.cacheKey
        )
        coVerify(exactly = 1) { topPostersProvider.fetchThumbnail(request) }
        coVerify(exactly = 0) { topPostersProvider.fetchPoster(any()) }
        verify(exactly = 0) { fallbackTransport.execute(any()) }
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

    private companion object {
        fun sampleJpeg(): ByteArray {
            val bitmap = Bitmap.createBitmap(8, 8, Bitmap.Config.ARGB_8888).apply {
                eraseColor(Color.RED)
            }
            return ByteArrayOutputStream().use { output ->
                bitmap.compress(Bitmap.CompressFormat.JPEG, 90, output)
                output.toByteArray()
            }
        }
    }
}
