package com.nexio.tv.ui.screens.player

import androidx.media3.exoplayer.dash.DashMediaSource
import androidx.media3.exoplayer.hls.HlsMediaSource
import androidx.media3.exoplayer.source.ProgressiveMediaSource
import io.mockk.mockk
import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
        assertTrue(provider.cacheDirectory.exists())

        provider.release()
        provider.cacheDirectory.deleteRecursively()
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
