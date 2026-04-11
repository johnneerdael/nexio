package com.nexio.tv.ui.screens.player

import androidx.media3.exoplayer.dash.DashMediaSource
import androidx.media3.exoplayer.hls.HlsMediaSource
import androidx.media3.exoplayer.source.ProgressiveMediaSource
import io.mockk.mockk
import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

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
}
