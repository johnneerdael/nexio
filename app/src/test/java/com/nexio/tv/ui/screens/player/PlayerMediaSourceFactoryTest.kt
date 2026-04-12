package com.nexio.tv.ui.screens.player

import androidx.media3.exoplayer.dash.DashMediaSource
import androidx.media3.exoplayer.hls.HlsMediaSource
import androidx.media3.exoplayer.source.ProgressiveMediaSource
import androidx.media3.datasource.cache.CacheDataSource
import android.content.Context
import androidx.test.core.app.ApplicationProvider
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
    fun progressivePlayback_usesParallelRangeDatasourceWhenEnabled() {
        val factory = PlayerMediaSourceFactory(
            context = mockk(relaxed = true),
            playbackOkHttpClient = OkHttpClient()
        )

        val dataSourceFactory = factory.progressiveUpstreamFactoryForTesting(
            url = "https://example.com/video.mkv",
            headers = mapOf("Authorization" to "Bearer token"),
        )

        assertTrue(dataSourceFactory is ParallelRangeDataSource.Factory)
    }

    @Test
    fun progressivePlayback_usesPlainHttpDatasourceWhenParallelDisabled() {
        val factory = PlayerMediaSourceFactory(
            context = mockk(relaxed = true),
            playbackOkHttpClient = OkHttpClient()
        ).apply {
            useParallelConnections = false
        }

        val dataSourceFactory = factory.progressiveUpstreamFactoryForTesting(
            url = "https://example.com/video.mkv",
            headers = emptyMap(),
        )

        assertFalse(dataSourceFactory is ParallelRangeDataSource.Factory)
    }

    @Test
    fun warmAheadUpstreamKind_reportsPrdsForParallelFactory() {
        val factory = PlayerMediaSourceFactory(
            context = mockk(relaxed = true),
            playbackOkHttpClient = OkHttpClient()
        )

        val dataSourceFactory = factory.progressiveUpstreamFactoryForTesting(
            url = "https://example.com/video.mkv",
            headers = emptyMap()
        )

        assertEquals("prds", PlayerMediaSourceFactory.warmAheadUpstreamKindForTesting(dataSourceFactory))
    }

    @Test
    fun warmAheadCacheKey_usesPlaybackUrlInsteadOfResolvedUrl() {
        assertEquals(
            "https://real-debrid.com/d/ABC/movie.mkv",
            VodWarmAheadPolicy.warmAheadCacheKey(
                playbackStreamUrl = "https://real-debrid.com/d/ABC/movie.mkv",
                resolvedRequestUrl = "https://cdn.example.net/edge/movie.mkv"
            )
        )
    }

    @Test
    fun vodCacheFragmentSize_usesEightMbFragments() {
        assertEquals(
            8L * 1024L * 1024L,
            PlayerMediaSourceFactory.VOD_CACHE_FRAGMENT_BYTES_FOR_TESTING
        )
    }

    @Test
    fun progressivePlaybackStillUsesParallelRangeDatasourceWhenWarmAheadIsDisabled() {
        val factory = PlayerMediaSourceFactory(
            context = mockk(relaxed = true),
            playbackOkHttpClient = OkHttpClient()
        ).apply {
            vodCacheWarmAheadEnabled = false
        }

        val dataSourceFactory = factory.progressiveUpstreamFactoryForTesting(
            url = "https://example.com/video.mkv",
            headers = emptyMap()
        )

        assertTrue(dataSourceFactory is ParallelRangeDataSource.Factory)
    }

    @Test
    fun warmAheadRequestUrl_returnsNullWhenCurrentStreamChanged() {
        val factory = PlayerMediaSourceFactory(
            context = mockk(relaxed = true),
            playbackOkHttpClient = OkHttpClient()
        )
        factory.setWarmAheadStateForTesting(
            streamUrl = "https://example.com/stream-b.mkv",
            resolvedUrl = "https://cdn.example.net/stream-b.mkv",
            eligible = true,
            active = true
        )

        assertEquals(
            null,
            factory.warmAheadRequestUrlForTesting("https://example.com/stream-a.mkv")
        )
    }

    @Test
    fun warmAheadRequestUrl_usesPlaybackUrlWhenResolvedUrlIsMissing() {
        val factory = PlayerMediaSourceFactory(
            context = mockk(relaxed = true),
            playbackOkHttpClient = OkHttpClient()
        )
        factory.setWarmAheadStateForTesting(
            streamUrl = "https://example.com/stream-a.mkv",
            resolvedUrl = null,
            eligible = true,
            active = true
        )

        assertEquals(
            "https://example.com/stream-a.mkv",
            factory.warmAheadRequestUrlForTesting("https://example.com/stream-a.mkv")
        )
    }

    @Test
    fun warmAheadRequestUrl_ignoresResolvedUrlFromDifferentStream() {
        val factory = PlayerMediaSourceFactory(
            context = mockk(relaxed = true),
            playbackOkHttpClient = OkHttpClient()
        )
        factory.setWarmAheadStateForTesting(
            streamUrl = "https://example.com/stream-b.mkv",
            resolvedPlaybackUrl = "https://example.com/stream-a.mkv",
            resolvedUrl = "https://cdn.example.net/stream-a.mkv",
            eligible = true,
            active = true
        )

        assertEquals(
            "https://example.com/stream-b.mkv",
            factory.warmAheadRequestUrlForTesting("https://example.com/stream-b.mkv")
        )
    }

    @Test
    fun warmAheadStartCheckRechecksDisabledSetting() {
        val factory = PlayerMediaSourceFactory(
            context = mockk(relaxed = true),
            playbackOkHttpClient = OkHttpClient()
        )
        factory.setWarmAheadStateForTesting(
            streamUrl = "https://example.com/stream-a.mkv",
            resolvedUrl = null,
            eligible = true,
            active = true
        )

        factory.vodCacheWarmAheadEnabled = false

        assertFalse(factory.shouldAttemptVodWarmAheadStartForTesting())
    }

    @Test
    fun benchmarkProgressiveFactory_usesParallelRangeDatasourceWhenEnabled() {
        val factory = PlayerMediaSourceFactory(
            context = mockk(relaxed = true),
            playbackOkHttpClient = OkHttpClient()
        )

        val dataSourceFactory = factory.createBenchmarkProgressiveDataSourceFactory(
            url = "https://example.com/video.mkv",
            headers = emptyMap(),
            parallelConnectionsEnabled = true,
            parallelConnectionCount = 4,
            parallelChunkSizeMb = 32,
            vodCacheEnabled = false,
            allowStartupBootstrapReuse = true,
            transportSampleTimeMs = { 0L },
            onTransportBytesDownloaded = { _, _ -> }
        )

        assertTrue(dataSourceFactory is ParallelRangeDataSource.Factory)
    }

    @Test
    fun benchmarkProgressiveFactory_wrapsVodCacheWhenEnabled() {
        val factory = PlayerMediaSourceFactory(
            context = ApplicationProvider.getApplicationContext<Context>(),
            playbackOkHttpClient = OkHttpClient()
        )

        val dataSourceFactory = factory.createBenchmarkProgressiveDataSourceFactory(
            url = "https://example.com/video.mkv",
            headers = emptyMap(),
            parallelConnectionsEnabled = false,
            parallelConnectionCount = 2,
            parallelChunkSizeMb = 16,
            vodCacheEnabled = true,
            allowStartupBootstrapReuse = true,
            transportSampleTimeMs = { 0L },
            onTransportBytesDownloaded = { _, _ -> }
        )

        assertTrue(dataSourceFactory.createDataSource() is CacheDataSource)
    }

    @Test
    fun parallelProviderProfile_premiumizeUsesThreeBySixteen() {
        val factory = PlayerMediaSourceFactory(
            context = mockk(relaxed = true),
            playbackOkHttpClient = OkHttpClient()
        )

        assertEquals(
            3 to 16,
            factory.parallelProviderProfileForTesting("https://premiumize.me/path/movie.mkv")
        )
    }

    @Test
    fun parallelProviderProfile_realDebridUsesTwoByTwentyFour() {
        val factory = PlayerMediaSourceFactory(
            context = mockk(relaxed = true),
            playbackOkHttpClient = OkHttpClient()
        )

        assertEquals(
            2 to 24,
            factory.parallelProviderProfileForTesting("https://real-debrid.com/path/movie.mkv")
        )
    }

    @Test
    fun parallelProviderProfile_unknownProviderUsesSafeDefault() {
        val factory = PlayerMediaSourceFactory(
            context = mockk(relaxed = true),
            playbackOkHttpClient = OkHttpClient()
        )

        assertEquals(
            2 to 24,
            factory.parallelProviderProfileForTesting("https://example.com/path/movie.mkv")
        )
    }

    @Test
    fun progressivePlayback_returnsProgressiveMediaSource() {
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
        assertFalse(
            factory.progressiveUpstreamFactoryForTesting(
                url = "https://example.com/master.m3u8",
                headers = emptyMap(),
            ) is ParallelRangeDataSource.Factory
        )
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
