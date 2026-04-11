package com.nexio.tv.ui.screens.player

import android.net.Uri
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.cache.ContentMetadata
import androidx.media3.exoplayer.dash.DashMediaSource
import androidx.media3.exoplayer.hls.HlsMediaSource
import androidx.media3.exoplayer.source.ProgressiveMediaSource
import io.mockk.mockk
import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@androidx.annotation.OptIn(UnstableApi::class)
@RunWith(RobolectricTestRunner::class)
class PlayerMediaSourceFactoryTest {

    @Test
    fun usesHttpUpstream_returnsFalse_forAssetUris() {
        assertFalse(PlayerMediaSourceFactory.usesHttpUpstream("asset:///truehd.mkv"))
    }

    @Test
    fun usesHttpUpstream_returnsTrue_forHttpUris() {
        assertTrue(PlayerMediaSourceFactory.usesHttpUpstream("https://example.com/truehd.mkv"))
    }

    @Test
    fun sanitizeHeaders_stripsRangeAndBlankValues() {
        val sanitized = PlayerMediaSourceFactory.sanitizeHeaders(
            mapOf(
                "Authorization" to "Bearer token",
                "Range" to "bytes=0-1",
                "User-Agent" to "Agent",
                "Blank" to "   ",
            )
        )

        assertEquals(
            mapOf(
                "Authorization" to "Bearer token",
                "User-Agent" to "Agent",
            ),
            sanitized
        )
    }

    @Test
    fun streamingCacheProvider_doesNotCreateCacheUntilRequested() {
        val context = androidx.test.core.app.ApplicationProvider.getApplicationContext<android.content.Context>()
        val provider = StreamingCacheProvider(
            context = context,
            cacheDirectoryName = "stream-cache-lazy-${System.nanoTime()}"
        )

        assertFalse(provider.hasCacheInstance)
        assertFalse(provider.cacheDirectory.exists())
    }

    @Test
    fun streamingCacheProvider_sharesCacheAcrossProvidersForSameDirectory() {
        val context = androidx.test.core.app.ApplicationProvider.getApplicationContext<android.content.Context>()
        val cacheDirectoryName = "stream-cache-shared-${System.nanoTime()}"
        val firstProvider = StreamingCacheProvider(
            context = context,
            cacheDirectoryName = cacheDirectoryName
        )
        val secondProvider = StreamingCacheProvider(
            context = context,
            cacheDirectoryName = cacheDirectoryName
        )

        try {
            val firstCache = firstProvider.getOrCreateCache()
            val secondCache = secondProvider.getOrCreateCache()

            assertSame(firstCache, secondCache)
            assertTrue(firstProvider.hasCacheInstance)
            assertTrue(secondProvider.hasCacheInstance)
        } finally {
            firstProvider.release()
            secondProvider.release()
            firstProvider.cacheDirectory.deleteRecursively()
        }
    }

    @Test
    fun streamingCacheProvider_releaseKeepsSharedCacheAliveUntilLastOwnerReleases() {
        val context = androidx.test.core.app.ApplicationProvider.getApplicationContext<android.content.Context>()
        val cacheDirectoryName = "stream-cache-refcount-${System.nanoTime()}"
        val firstProvider = StreamingCacheProvider(
            context = context,
            cacheDirectoryName = cacheDirectoryName
        )
        val secondProvider = StreamingCacheProvider(
            context = context,
            cacheDirectoryName = cacheDirectoryName
        )

        try {
            assertSame(firstProvider.getOrCreateCache(), secondProvider.getOrCreateCache())

            firstProvider.release()

            assertFalse(firstProvider.hasCacheInstance)
            assertTrue(secondProvider.hasCacheInstance)

            secondProvider.getOrCreateCache()
            assertTrue(secondProvider.hasCacheInstance)
        } finally {
            firstProvider.release()
            secondProvider.release()
            firstProvider.cacheDirectory.deleteRecursively()
        }
    }

    @Test
    fun streamingCacheProvider_releaseIsIdempotent() {
        val context = androidx.test.core.app.ApplicationProvider.getApplicationContext<android.content.Context>()
        val provider = StreamingCacheProvider(
            context = context,
            cacheDirectoryName = "stream-cache-idempotent-${System.nanoTime()}"
        )

        try {
            provider.getOrCreateCache()

            provider.release()
            provider.release()

            assertFalse(provider.hasCacheInstance)
        } finally {
            provider.release()
            provider.cacheDirectory.deleteRecursively()
        }
    }

    @Test
    fun streamingCacheKillSwitch_allowsWhenEnabledAndNoBadExit() {
        assertTrue(
            StreamingCacheKillSwitch.shouldEnable(
                requested = true,
                hasRecentLowMemoryOrSignaledExit = false
            )
        )
    }

    @Test
    fun streamingCacheKillSwitch_disablesWhenRequestedFalse() {
        assertFalse(
            StreamingCacheKillSwitch.shouldEnable(
                requested = false,
                hasRecentLowMemoryOrSignaledExit = false
            )
        )
    }

    @Test
    fun streamingCacheKillSwitch_disablesWhenRecentBadExitExists() {
        assertFalse(
            StreamingCacheKillSwitch.shouldEnable(
                requested = true,
                hasRecentLowMemoryOrSignaledExit = true
            )
        )
    }

    @Test
    fun playbackNetworking_flagOff_returnsPlainDefaultDataSource_andDoesNotOpenCache() {
        val context = androidx.test.core.app.ApplicationProvider.getApplicationContext<android.content.Context>()
        val provider = StreamingCacheProvider(
            context = context,
            cacheDirectoryName = "stream-cache-off-${System.nanoTime()}"
        )

        val factory = PlayerPlaybackNetworking.createDataSourceFactory(
            context = context,
            client = OkHttpClient(),
            defaultHeaders = emptyMap(),
            streamingCacheProvider = provider,
            useStreamingCache = false
        )
        val dataSource = factory.createDataSource()

        assertFalse(dataSource is androidx.media3.datasource.cache.CacheDataSource)
        assertFalse(provider.hasCacheInstance)
        assertFalse(provider.cacheDirectory.exists())
    }

    @Test
    fun playbackNetworking_flagOn_returnsReadOnlyCacheDataSource_andOpensCache() {
        val context = androidx.test.core.app.ApplicationProvider.getApplicationContext<android.content.Context>()
        val provider = StreamingCacheProvider(
            context = context,
            cacheDirectoryName = "stream-cache-on-${System.nanoTime()}"
        )

        val factory = PlayerPlaybackNetworking.createDataSourceFactory(
            context = context,
            client = OkHttpClient(),
            defaultHeaders = emptyMap(),
            streamingCacheProvider = provider,
            useStreamingCache = true
        )
        val dataSource = factory.createDataSource()

        assertTrue(dataSource is androidx.media3.datasource.cache.CacheDataSource)
        assertTrue(provider.hasCacheInstance)

        provider.release()
        provider.cacheDirectory.deleteRecursively()
    }

    @Test
    fun playbackNetworking_acceptsCustomCacheKeyFactory_withCacheEnabled() {
        val context = androidx.test.core.app.ApplicationProvider.getApplicationContext<android.content.Context>()
        val provider = StreamingCacheProvider(
            context = context,
            cacheDirectoryName = "stream-cache-custom-key-${System.nanoTime()}"
        )
        val cacheKeyFactory = StableCacheKeyFactory { uri ->
            "custom:${uri.host.orEmpty()}:${uri.path.orEmpty()}"
        }

        try {
            val factory = PlayerPlaybackNetworking.createDataSourceFactory(
                context = context,
                client = OkHttpClient(),
                defaultHeaders = emptyMap(),
                streamingCacheProvider = provider,
                useStreamingCache = true,
                cacheKeyFactory = cacheKeyFactory
            )
            val dataSource = factory.createDataSource()

            assertTrue(dataSource is androidx.media3.datasource.cache.CacheDataSource)
        } finally {
            provider.release()
            provider.cacheDirectory.deleteRecursively()
        }
    }

    @Test
    fun mediaSourceFactory_flagOff_doesNotOpenStreamingCache() {
        val context = androidx.test.core.app.ApplicationProvider.getApplicationContext<android.content.Context>()
        val provider = StreamingCacheProvider(
            context = context,
            cacheDirectoryName = "media-source-cache-off-${System.nanoTime()}"
        )
        val factory = PlayerMediaSourceFactory(
            context = context,
            playbackOkHttpClient = OkHttpClient(),
            streamingCacheProvider = provider
        )

        factory.createMediaSource(
            url = "https://example.com/movie.mkv",
            headers = emptyMap()
        )

        assertFalse(provider.hasCacheInstance)
        assertFalse(provider.cacheDirectory.exists())
        factory.shutdown()
    }

    @Test
    fun mediaSourceFactory_flagOnForHttp_opensStreamingCache() {
        val context = androidx.test.core.app.ApplicationProvider.getApplicationContext<android.content.Context>()
        val provider = StreamingCacheProvider(
            context = context,
            cacheDirectoryName = "media-source-cache-on-${System.nanoTime()}"
        )
        val factory = PlayerMediaSourceFactory(
            context = context,
            playbackOkHttpClient = OkHttpClient(),
            streamingCacheProvider = provider
        )

        try {
            factory.streamingCacheEnabled = true
            factory.createMediaSource(
                url = "https://example.com/movie.mkv",
                headers = emptyMap()
            )

            assertTrue(provider.hasCacheInstance)
        } finally {
            factory.shutdown()
            provider.cacheDirectory.deleteRecursively()
        }
    }

    @Test
    fun mediaSourceFactory_flagOnForAsset_doesNotOpenStreamingCache() {
        val context = androidx.test.core.app.ApplicationProvider.getApplicationContext<android.content.Context>()
        val provider = StreamingCacheProvider(
            context = context,
            cacheDirectoryName = "media-source-cache-asset-${System.nanoTime()}"
        )
        val factory = PlayerMediaSourceFactory(
            context = context,
            playbackOkHttpClient = OkHttpClient(),
            streamingCacheProvider = provider
        )

        try {
            factory.streamingCacheEnabled = true
            factory.createMediaSource(
                url = "asset:///movie.mkv",
                headers = emptyMap()
            )

            assertFalse(provider.hasCacheInstance)
            assertFalse(provider.cacheDirectory.exists())
        } finally {
            factory.shutdown()
        }
    }

    @Test
    fun startStreamingCacheFill_flagOff_doesNotOpenCacheOrStartFill() {
        val context = androidx.test.core.app.ApplicationProvider.getApplicationContext<android.content.Context>()
        val provider = StreamingCacheProvider(
            context = context,
            cacheDirectoryName = "media-source-fill-flag-off-${System.nanoTime()}"
        )
        val factory = PlayerMediaSourceFactory(
            context = context,
            playbackOkHttpClient = OkHttpClient(),
            streamingCacheProvider = provider
        )

        try {
            factory.streamingCacheEnabled = false

            factory.startStreamingCacheFill(
                url = "https://example.com/movie.mkv",
                headers = emptyMap(),
                contentLength = 32L * 1024L * 1024L,
                playbackByteProvider = { 0L }
            )

            assertFalse(provider.hasCacheInstance)
            assertFalse(provider.cacheDirectory.exists())
            assertFalse(factory.hasActiveFillSession)
        } finally {
            factory.shutdown()
        }
    }

    @Test
    fun startStreamingCacheFill_assetUrl_doesNotOpenCacheOrStartFill() {
        val context = androidx.test.core.app.ApplicationProvider.getApplicationContext<android.content.Context>()
        val provider = StreamingCacheProvider(
            context = context,
            cacheDirectoryName = "media-source-fill-asset-${System.nanoTime()}"
        )
        val factory = PlayerMediaSourceFactory(
            context = context,
            playbackOkHttpClient = OkHttpClient(),
            streamingCacheProvider = provider
        )

        try {
            factory.streamingCacheEnabled = true

            factory.startStreamingCacheFill(
                url = "asset:///movie.mkv",
                headers = emptyMap(),
                contentLength = 32L * 1024L * 1024L,
                playbackByteProvider = { 0L }
            )

            assertFalse(provider.hasCacheInstance)
            assertFalse(provider.cacheDirectory.exists())
            assertFalse(factory.hasActiveFillSession)
        } finally {
            factory.shutdown()
        }
    }

    @Test
    fun startStreamingCacheFill_withoutContentLength_doesNotOpenCacheOrStartFill() {
        val context = androidx.test.core.app.ApplicationProvider.getApplicationContext<android.content.Context>()
        val provider = StreamingCacheProvider(
            context = context,
            cacheDirectoryName = "media-source-fill-no-length-${System.nanoTime()}"
        )
        val factory = PlayerMediaSourceFactory(
            context = context,
            playbackOkHttpClient = OkHttpClient(),
            streamingCacheProvider = provider
        )

        try {
            factory.streamingCacheEnabled = true

            factory.startStreamingCacheFill(
                url = "https://example.com/movie.mkv",
                headers = emptyMap(),
                contentLength = null,
                playbackByteProvider = { 0L }
            )

            assertFalse(provider.hasCacheInstance)
            assertFalse(provider.cacheDirectory.exists())
            assertFalse(factory.hasActiveFillSession)
        } finally {
            factory.shutdown()
        }
    }

    @Test
    fun startStreamingCacheFill_afterMissingContentLength_startsWhenSizeBecomesAvailable() {
        val context = androidx.test.core.app.ApplicationProvider.getApplicationContext<android.content.Context>()
        val provider = StreamingCacheProvider(
            context = context,
            cacheDirectoryName = "media-source-fill-late-length-${System.nanoTime()}"
        )
        val requestEntered = CountDownLatch(1)
        val releaseRequest = CountDownLatch(1)
        val client = OkHttpClient.Builder()
            .addInterceptor { chain ->
                requestEntered.countDown()
                while (!releaseRequest.await(10L, TimeUnit.MILLISECONDS)) {
                    // Keep the worker active until the test shuts it down.
                }
                throw IOException("stopped")
            }
            .build()
        val factory = PlayerMediaSourceFactory(
            context = context,
            playbackOkHttpClient = client,
            streamingCacheProvider = provider
        )

        try {
            factory.streamingCacheEnabled = true

            factory.startStreamingCacheFill(
                url = "https://example.com/movie.mkv",
                headers = emptyMap(),
                contentLength = null,
                playbackByteProvider = { 0L }
            )

            assertFalse(provider.hasCacheInstance)
            assertFalse(factory.hasActiveFillSession)

            factory.startStreamingCacheFill(
                url = "https://example.com/movie.mkv",
                headers = emptyMap(),
                contentLength = 32L * 1024L * 1024L,
                playbackByteProvider = { 0L }
            )

            assertTrue(requestEntered.await(1L, TimeUnit.SECONDS))
            assertTrue(provider.hasCacheInstance)
            assertTrue(factory.hasActiveFillSession)
        } finally {
            releaseRequest.countDown()
            factory.shutdown()
            provider.cacheDirectory.deleteRecursively()
        }
    }

    @Test
    fun startStreamingCacheFill_hlsManifest_doesNotOpenCacheOrStartFill() {
        val context = androidx.test.core.app.ApplicationProvider.getApplicationContext<android.content.Context>()
        val provider = StreamingCacheProvider(
            context = context,
            cacheDirectoryName = "media-source-fill-hls-${System.nanoTime()}"
        )
        val factory = PlayerMediaSourceFactory(
            context = context,
            playbackOkHttpClient = OkHttpClient(),
            streamingCacheProvider = provider
        )

        try {
            factory.streamingCacheEnabled = true

            factory.startStreamingCacheFill(
                url = "https://example.com/master.m3u8",
                headers = emptyMap(),
                contentLength = 32L * 1024L * 1024L,
                playbackByteProvider = { 0L }
            )

            assertFalse(provider.hasCacheInstance)
            assertFalse(provider.cacheDirectory.exists())
            assertFalse(factory.hasActiveFillSession)
        } finally {
            factory.shutdown()
        }
    }

    @Test
    fun startStreamingCacheFill_dashManifest_doesNotOpenCacheOrStartFill() {
        val context = androidx.test.core.app.ApplicationProvider.getApplicationContext<android.content.Context>()
        val provider = StreamingCacheProvider(
            context = context,
            cacheDirectoryName = "media-source-fill-dash-${System.nanoTime()}"
        )
        val factory = PlayerMediaSourceFactory(
            context = context,
            playbackOkHttpClient = OkHttpClient(),
            streamingCacheProvider = provider
        )

        try {
            factory.streamingCacheEnabled = true

            factory.startStreamingCacheFill(
                url = "https://example.com/manifest.mpd",
                headers = emptyMap(),
                contentLength = 32L * 1024L * 1024L,
                playbackByteProvider = { 0L }
            )

            assertFalse(provider.hasCacheInstance)
            assertFalse(provider.cacheDirectory.exists())
            assertFalse(factory.hasActiveFillSession)
        } finally {
            factory.shutdown()
        }
    }

    @Test
    fun shutdown_stopsStreamingCacheFill() {
        val context = androidx.test.core.app.ApplicationProvider.getApplicationContext<android.content.Context>()
        val provider = StreamingCacheProvider(
            context = context,
            cacheDirectoryName = "media-source-fill-shutdown-${System.nanoTime()}"
        )
        val requestEntered = CountDownLatch(1)
        val releaseRequest = CountDownLatch(1)
        val client = OkHttpClient.Builder()
            .addInterceptor {
                requestEntered.countDown()
                while (!releaseRequest.await(10L, TimeUnit.MILLISECONDS)) {
                    // Keep the worker active until shutdown cancels the session.
                }
                throw IOException("stopped")
            }
            .build()
        val factory = PlayerMediaSourceFactory(
            context = context,
            playbackOkHttpClient = client,
            streamingCacheProvider = provider
        )

        try {
            factory.streamingCacheEnabled = true

            factory.startStreamingCacheFill(
                url = "https://example.com/movie.mkv",
                headers = emptyMap(),
                contentLength = 32L * 1024L * 1024L,
                playbackByteProvider = { 0L }
            )

            assertTrue(requestEntered.await(1L, TimeUnit.SECONDS))
            assertTrue(factory.hasActiveFillSession)

            factory.shutdown()

            assertFalse(factory.hasActiveFillSession)
            assertFalse(provider.hasCacheInstance)
        } finally {
            releaseRequest.countDown()
            factory.shutdown()
            provider.cacheDirectory.deleteRecursively()
        }
    }

    @Test
    fun streamingCacheFillSession_startSetsContentLengthMetadataForFillCacheKey() {
        val context = appContext()
        val provider = StreamingCacheProvider(
            context = context,
            cacheDirectoryName = "media-source-fill-metadata-${System.nanoTime()}"
        )
        val cache = provider.getOrCreateCache()
        val cacheKeyFactory = StableCacheKeyFactory()
        val contentLength = 12_345L
        val url = "https://example.com/movie.mkv"
        val session = StreamingCacheFillSession(
            cache = cache,
            cacheKeyFactory = cacheKeyFactory,
            okHttpClient = OkHttpClient(),
            memoryBudget = MemoryBudget(context),
            rangeCoordinator = StreamingRangeCoordinator()
        )

        try {
            session.start(
                url = url,
                headers = emptyMap(),
                contentLength = contentLength,
                playbackByteProvider = { contentLength }
            )

            assertEquals(
                contentLength,
                ContentMetadata.getContentLength(cache.getContentMetadata(cacheKey(cacheKeyFactory, url)))
            )
        } finally {
            session.stop()
            provider.release()
            provider.cacheDirectory.deleteRecursively()
        }
    }

    @Test
    fun streamingCacheFillSession_startAfterSlowStopStartsLatestPendingRequestWithoutOverlap() {
        val context = appContext()
        val provider = StreamingCacheProvider(
            context = context,
            cacheDirectoryName = "media-source-fill-pending-start-${System.nanoTime()}"
        )
        val chunkBytes = 64L
        val contentLength = 16L * 1024L * 1024L
        val firstRequestEntered = CountDownLatch(1)
        val releaseFirstRequest = CountDownLatch(1)
        val requestCount = AtomicInteger(0)
        val replacementHeader = AtomicReference<String?>()
        val client = OkHttpClient.Builder()
            .addInterceptor { chain ->
                if (requestCount.incrementAndGet() == 1) {
                    firstRequestEntered.countDown()
                    while (releaseFirstRequest.count > 0L) {
                        try {
                            releaseFirstRequest.await(10L, TimeUnit.MILLISECONDS)
                        } catch (_: InterruptedException) {
                            // Simulate teardown that outlives the session's bounded stop join.
                        }
                    }
                    throw IOException("slow to stop")
                }

                replacementHeader.set(chain.request().header("X-Replacement"))
                val requestedRange = requireNotNull(chain.request().header("Range"))
                val (start, endInclusive) = parseRange(requestedRange)
                Response.Builder()
                    .request(chain.request())
                    .protocol(Protocol.HTTP_1_1)
                    .code(206)
                    .message("Partial Content")
                    .header("Content-Range", "bytes $start-$endInclusive/$contentLength")
                    .body(ByteArray((endInclusive - start + 1L).toInt()) { 1 }.toResponseBody())
                    .build()
            }
            .build()
        val session = StreamingCacheFillSession(
            cache = provider.getOrCreateCache(),
            cacheKeyFactory = StableCacheKeyFactory(),
            okHttpClient = client,
            memoryBudget = MemoryBudget(context),
            rangeCoordinator = StreamingRangeCoordinator(),
            profile = ProviderProfile(
                chunkBytes = chunkBytes,
                normalFragmentBytes = chunkBytes,
                fillHorizonBytes = contentLength,
                lowWaterBytes = chunkBytes,
                retainBehindBytes = 0L
            )
        )

        try {
            session.start(
                url = "https://example.com/movie.mkv",
                headers = emptyMap(),
                contentLength = contentLength,
                playbackByteProvider = { 0L }
            )
            assertTrue(firstRequestEntered.await(1, TimeUnit.SECONDS))
            val firstWorkerThread = liveCacheFillThreads().single()

            session.start(
                url = "https://example.com/movie.mkv",
                headers = mapOf("X-Replacement" to "stale"),
                contentLength = contentLength,
                playbackByteProvider = { 0L }
            )
            session.start(
                url = "https://example.com/movie.mkv",
                headers = mapOf("X-Replacement" to "latest"),
                contentLength = contentLength,
                playbackByteProvider = { 0L }
            )

            assertEquals(1, requestCount.get())
            assertEquals(listOf(firstWorkerThread), liveCacheFillThreads())

            releaseFirstRequest.countDown()

            assertTrue(waitUntil { replacementHeader.get() == "latest" })
        } finally {
            releaseFirstRequest.countDown()
            session.stop()
            provider.release()
            provider.cacheDirectory.deleteRecursively()
            assertTrue(waitUntil { liveCacheFillThreads().isEmpty() })
        }
    }

    @Test
    fun progressivePlayback_usesPlainMedia3DatasourceWithoutCacheOrPrds() {
        val factory = PlayerMediaSourceFactory(
            context = mockk(relaxed = true),
            playbackOkHttpClient = OkHttpClient()
        )

        val mediaSource = factory.createMediaSource(
            url = "https://example.com/video.mkv",
            headers = mapOf("Authorization" to "Bearer token"),
        )

        assertTrue(mediaSource is ProgressiveMediaSource)
    }

    @Test
    fun hlsPlayback_returnsHlsMediaSource() {
        val factory = PlayerMediaSourceFactory(
            context = mockk(relaxed = true),
            playbackOkHttpClient = OkHttpClient()
        )

        val mediaSource = factory.createMediaSource(
            url = "https://example.com/master.m3u8",
            headers = emptyMap(),
        )

        assertTrue(mediaSource is HlsMediaSource)
    }

    @Test
    fun dashPlayback_returnsDashMediaSource() {
        val factory = PlayerMediaSourceFactory(
            context = mockk(relaxed = true),
            playbackOkHttpClient = OkHttpClient()
        )

        val mediaSource = factory.createMediaSource(
            url = "https://example.com/manifest.mpd",
            headers = emptyMap(),
        )

        assertTrue(mediaSource is DashMediaSource)
    }

    private fun appContext(): android.content.Context {
        return androidx.test.core.app.ApplicationProvider.getApplicationContext()
    }

    private fun cacheKey(cacheKeyFactory: StableCacheKeyFactory, url: String): String {
        return cacheKeyFactory.buildCacheKey(
            DataSpec.Builder()
                .setUri(Uri.parse(url))
                .setLength(C.LENGTH_UNSET.toLong())
                .build()
        )
    }

    private fun parseRange(rangeHeader: String): Pair<Long, Long> {
        val values = rangeHeader.removePrefix("bytes=").split("-")
        return values[0].toLong() to values[1].toLong()
    }

    private fun waitUntil(condition: () -> Boolean): Boolean {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2)
        while (System.nanoTime() < deadline) {
            if (condition()) return true
            Thread.sleep(10L)
        }
        return condition()
    }

    private fun liveCacheFillThreads(): List<Thread> {
        return Thread.getAllStackTraces().keys
            .filter { thread -> thread.name == CacheFillWorker.THREAD_NAME && thread.isAlive }
    }
}
