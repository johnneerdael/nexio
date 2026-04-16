package com.nexio.tv.ui.screens.player

import androidx.media3.exoplayer.dash.DashMediaSource
import androidx.media3.exoplayer.hls.HlsMediaSource
import androidx.media3.exoplayer.source.ProgressiveMediaSource
import androidx.media3.datasource.cache.CacheDataSource
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.nexio.tv.data.local.ProgressivePlaybackDiskMode
import com.nexio.tv.data.local.VodCacheSizeMode
import com.nexio.tv.ui.screens.player.spool.DiskSpoolDataSource
import com.nexio.tv.ui.screens.player.spool.DiskSpoolStorageLocation
import com.nexio.tv.ui.screens.player.spool.SpoolStorageProbeResult
import io.mockk.mockk
import java.io.IOException
import java.io.File
import java.util.concurrent.Executor
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import okio.Buffer
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
    fun diskSpoolDiagnosticsLogging_isGatedByTroubleshootingToggle() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val logs = mutableListOf<String>()
        val factory = PlayerMediaSourceFactory(
            context = context,
            playbackOkHttpClient = noNetworkOkHttpClient()
        ).apply {
            progressivePlaybackDiskMode = ProgressivePlaybackDiskMode.SPOOL
            diskSpoolDiagnosticsLoggerForTesting = { logs += it }
        }

        factory.createMediaSource("https://example.com/video.mkv", emptyMap())
        assertEquals(emptyList<String>(), logs)

        factory.diskSpoolDiagnosticsEnabled = true
        factory.createMediaSource("https://example.com/video.mkv", emptyMap())

        assertTrue(logs.any { it.contains("DISK_SPOOL_DIAG: createMediaSource") })
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
    fun progressivePlayback_usesDiskSpoolFactoryWhenEnabledAndProbePasses() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val writerScheduled = AtomicBoolean(false)
        val factory = PlayerMediaSourceFactory(
            context = context,
            playbackOkHttpClient = noNetworkOkHttpClient()
        ).apply {
            progressivePlaybackDiskMode = ProgressivePlaybackDiskMode.SPOOL
            diskSpoolStorageLocation = DiskSpoolStorageLocation.BUILTIN
            spoolStorageProbeResult = passingProbeResult(context)
            diskSpoolAvailableBytesForTesting = Long.MAX_VALUE
            diskSpoolWriterExecutorForTesting = Executor { writerScheduled.set(true) }
        }

        val dataSourceFactory = factory.progressiveUpstreamFactoryForTesting(
            url = "https://example.com/video.mkv",
            headers = emptyMap(),
        )

        assertTrue(dataSourceFactory is DiskSpoolDataSource.Factory)
        assertTrue(writerScheduled.get())
    }

    @Test
    fun progressivePlayback_passesSanitizedHeadersIntoDiskSpoolWriter() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val content = ByteArray(1024) { (it % 251).toByte() }
        val requests = mutableListOf<RecordedRequest>()
        val server = MockWebServer()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                synchronized(requests) {
                    requests += request
                }
                return when (request.getHeader("Range")) {
                    "bytes=0-0" -> MockResponse()
                        .setResponseCode(206)
                        .setHeader("Accept-Ranges", "bytes")
                        .setHeader("Content-Range", "bytes 0-0/${content.size}")
                        .setHeader("Content-Length", 1)
                        .setBody(Buffer().writeByte(0x2A))

                    "bytes=0-1023" -> MockResponse()
                        .setResponseCode(206)
                        .setHeader("Accept-Ranges", "bytes")
                        .setHeader("Content-Range", "bytes 0-1023/${content.size}")
                        .setHeader("Content-Length", content.size)
                        .setBody(Buffer().write(content))

                    else -> MockResponse().setResponseCode(400)
                }
            }
        }
        server.start()

        val factory = PlayerMediaSourceFactory(
            context = context,
            playbackOkHttpClient = OkHttpClient()
        ).apply {
            progressivePlaybackDiskMode = ProgressivePlaybackDiskMode.SPOOL
            diskSpoolStorageLocation = DiskSpoolStorageLocation.BUILTIN
            spoolStorageProbeResult = passingProbeResult(context)
            diskSpoolAvailableBytesForTesting = Long.MAX_VALUE
            diskSpoolWriterExecutorForTesting = Executor { runnable -> runnable.run() }
        }

        try {
            factory.createMediaSource(
                url = server.url("/video.mkv").toString(),
                headers = mapOf(
                    "Authorization" to "Bearer token",
                    "X-Playback-Token" to "custom-token",
                    "Range" to "bytes=999-1000"
                )
            )

            val recorded = synchronized(requests) { requests.toList() }
            assertEquals(2, recorded.size)
            assertEquals("Bearer token", recorded[0].getHeader("Authorization"))
            assertEquals("custom-token", recorded[0].getHeader("X-Playback-Token"))
            assertEquals("bytes=0-0", recorded[0].getHeader("Range"))
            assertEquals("Bearer token", recorded[1].getHeader("Authorization"))
            assertEquals("custom-token", recorded[1].getHeader("X-Playback-Token"))
            assertEquals("bytes=0-1023", recorded[1].getHeader("Range"))
        } finally {
            server.shutdown()
            factory.shutdown()
        }
    }

    @Test
    fun progressivePlayback_usesNormalFactoryWhenDiskSpoolModeOff() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val factory = PlayerMediaSourceFactory(
            context = context,
            playbackOkHttpClient = noNetworkOkHttpClient()
        ).apply {
            progressivePlaybackDiskMode = ProgressivePlaybackDiskMode.OFF
            spoolStorageProbeResult = passingProbeResult(context)
            diskSpoolAvailableBytesForTesting = Long.MAX_VALUE
        }

        val dataSourceFactory = factory.progressiveUpstreamFactoryForTesting(
            url = "https://example.com/video.mkv",
            headers = emptyMap(),
        )

        assertFalse(dataSourceFactory is DiskSpoolDataSource.Factory)
    }

    @Test
    fun progressivePlayback_usesDiskSpoolWhenDiagnosticResultIsFailedOrStale() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val factory = PlayerMediaSourceFactory(
            context = context,
            playbackOkHttpClient = noNetworkOkHttpClient()
        ).apply {
            progressivePlaybackDiskMode = ProgressivePlaybackDiskMode.SPOOL
            spoolStorageProbeResult = passingProbeResult(context).copy(
                measuredAtMs = System.currentTimeMillis() - 8L * 24L * 60L * 60L * 1000L
            )
            diskSpoolAvailableBytesForTesting = Long.MAX_VALUE
            diskSpoolWriterExecutorForTesting = Executor { }
        }

        val dataSourceFactory = factory.progressiveUpstreamFactoryForTesting(
            url = "https://example.com/video.mkv",
            headers = emptyMap(),
        )

        assertTrue(dataSourceFactory is DiskSpoolDataSource.Factory)
    }

    @Test
    fun progressivePlayback_doesNotUseDiskSpoolForHlsOrDash() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val factory = PlayerMediaSourceFactory(
            context = context,
            playbackOkHttpClient = noNetworkOkHttpClient()
        ).apply {
            progressivePlaybackDiskMode = ProgressivePlaybackDiskMode.SPOOL
            spoolStorageProbeResult = passingProbeResult(context)
            diskSpoolAvailableBytesForTesting = Long.MAX_VALUE
        }

        val hlsFactory = factory.progressiveUpstreamFactoryForTesting(
            url = "https://example.com/master.m3u8",
            headers = emptyMap(),
        )
        val dashFactory = factory.progressiveUpstreamFactoryForTesting(
            url = "https://example.com/manifest.mpd",
            headers = emptyMap(),
        )

        assertFalse(hlsFactory is DiskSpoolDataSource.Factory)
        assertFalse(dashFactory is DiskSpoolDataSource.Factory)
    }

    @Test
    fun progressivePlayback_doesNotSynchronouslyProbeWhenDiskSpoolGatePasses() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val factory = PlayerMediaSourceFactory(
            context = context,
            playbackOkHttpClient = noNetworkOkHttpClient()
        ).apply {
            progressivePlaybackDiskMode = ProgressivePlaybackDiskMode.SPOOL
            spoolStorageProbeResult = passingProbeResult(context)
            diskSpoolAvailableBytesForTesting = Long.MAX_VALUE
            diskSpoolWriterExecutorForTesting = Executor { }
        }

        val dataSourceFactory = factory.progressiveUpstreamFactoryForTesting(
            url = "https://example.com/video.mkv",
            headers = emptyMap(),
        )

        assertTrue(dataSourceFactory is DiskSpoolDataSource.Factory)
    }

    @Test
    fun progressivePlayback_usesNormalFactoryWhenPlaybackStopsDuringDiskSpoolRegistration() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        lateinit var factory: PlayerMediaSourceFactory
        factory = PlayerMediaSourceFactory(
            context = context,
            playbackOkHttpClient = noNetworkOkHttpClient()
        ).apply {
            progressivePlaybackDiskMode = ProgressivePlaybackDiskMode.SPOOL
            spoolStorageProbeResult = passingProbeResult(context)
            diskSpoolAvailableBytesForTesting = Long.MAX_VALUE
            diskSpoolWriterExecutorForTesting = Executor {
                factory.closeActiveDiskSpoolSessionForPlaybackStop()
            }
        }

        val dataSourceFactory = factory.progressiveUpstreamFactoryForTesting(
            url = "https://example.com/video.mkv",
            headers = emptyMap(),
        )

        assertFalse(dataSourceFactory is DiskSpoolDataSource.Factory)
        assertEquals(0, diskSpoolFiles(context).size)
        factory.shutdown()
    }

    @Test
    fun progressivePlayback_usesNormalFactoryWhenPlaybackStopsAfterDiskSpoolRegistration() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        lateinit var factory: PlayerMediaSourceFactory
        factory = PlayerMediaSourceFactory(
            context = context,
            playbackOkHttpClient = noNetworkOkHttpClient()
        ).apply {
            progressivePlaybackDiskMode = ProgressivePlaybackDiskMode.SPOOL
            spoolStorageProbeResult = passingProbeResult(context)
            diskSpoolAvailableBytesForTesting = Long.MAX_VALUE
            diskSpoolWriterExecutorForTesting = Executor { }
            diskSpoolAfterRegistrationForTesting = {
                factory.closeActiveDiskSpoolSessionForPlaybackStop()
            }
        }

        val dataSourceFactory = factory.progressiveUpstreamFactoryForTesting(
            url = "https://example.com/video.mkv",
            headers = emptyMap(),
        )

        assertFalse(dataSourceFactory is DiskSpoolDataSource.Factory)
        assertEquals(0, diskSpoolFiles(context).size)
        factory.shutdown()
    }

    @Test
    fun progressivePlayback_doesNotGateDiskSpoolOnFallbackTargetWhenStreamBitrateIsUnknown() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val factory = PlayerMediaSourceFactory(
            context = context,
            playbackOkHttpClient = noNetworkOkHttpClient()
        ).apply {
            progressivePlaybackDiskMode = ProgressivePlaybackDiskMode.SPOOL
            spoolStorageProbeResult = passingProbeResult(context).copy(
                writeMbps = 45.0,
                readMbps = 45.0,
                combinedMbps = 100.0
            )
            diskSpoolTargetBitrateMbps = 40.0
            diskSpoolAvailableBytesForTesting = Long.MAX_VALUE
            diskSpoolWriterExecutorForTesting = Executor { }
        }

        val dataSourceFactory = factory.progressiveUpstreamFactoryForTesting(
            url = "https://example.com/video.mkv",
            headers = emptyMap(),
        )

        assertTrue(dataSourceFactory is DiskSpoolDataSource.Factory)
    }

    @Test
    fun progressivePlayback_doesNotGateDiskSpoolOnRaisedTargetBitrateWhenConfigured() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val factory = PlayerMediaSourceFactory(
            context = context,
            playbackOkHttpClient = noNetworkOkHttpClient()
        ).apply {
            progressivePlaybackDiskMode = ProgressivePlaybackDiskMode.SPOOL
            spoolStorageProbeResult = passingProbeResult(context).copy(
                writeMbps = 119.0,
                readMbps = 119.0,
                combinedMbps = 360.0
            )
            diskSpoolTargetBitrateMbps = 120.0
            diskSpoolAvailableBytesForTesting = Long.MAX_VALUE
            diskSpoolWriterExecutorForTesting = Executor { }
        }

        val failingDataSourceFactory = factory.progressiveUpstreamFactoryForTesting(
            url = "https://example.com/video-a.mkv",
            headers = emptyMap(),
        )

        assertTrue(failingDataSourceFactory is DiskSpoolDataSource.Factory)

        factory.spoolStorageProbeResult = passingProbeResult(context).copy(
            writeMbps = 120.0,
            readMbps = 120.0,
            combinedMbps = 300.0
        )

        val passingDataSourceFactory = factory.progressiveUpstreamFactoryForTesting(
            url = "https://example.com/video-b.mkv",
            headers = emptyMap(),
        )

        assertTrue(passingDataSourceFactory is DiskSpoolDataSource.Factory)
        factory.shutdown()
    }

    @Test
    fun closeActiveDiskSpoolSessionForPlaybackStop_closesActiveDiskSpoolSession() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val factory = PlayerMediaSourceFactory(
            context = context,
            playbackOkHttpClient = noNetworkOkHttpClient()
        ).apply {
            progressivePlaybackDiskMode = ProgressivePlaybackDiskMode.SPOOL
            spoolStorageProbeResult = passingProbeResult(context)
            diskSpoolAvailableBytesForTesting = Long.MAX_VALUE
            diskSpoolWriterExecutorForTesting = Executor { }
        }

        factory.progressiveUpstreamFactoryForTesting(
            url = "https://example.com/video.mkv",
            headers = emptyMap(),
        )

        assertEquals(1, diskSpoolFiles(context).size)

        factory.closeActiveDiskSpoolSessionForPlaybackStop()

        assertEquals(0, diskSpoolFiles(context).size)
        factory.shutdown()
    }

    @Test
    fun progressivePlayback_closesPreviousDiskSpoolSessionWhenCreatingNextOne() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val factory = PlayerMediaSourceFactory(
            context = context,
            playbackOkHttpClient = noNetworkOkHttpClient()
        ).apply {
            progressivePlaybackDiskMode = ProgressivePlaybackDiskMode.SPOOL
            spoolStorageProbeResult = passingProbeResult(context)
            diskSpoolAvailableBytesForTesting = Long.MAX_VALUE
            diskSpoolWriterExecutorForTesting = Executor { }
        }

        factory.progressiveUpstreamFactoryForTesting(
            url = "https://example.com/video-a.mkv",
            headers = emptyMap(),
        )

        assertEquals(1, diskSpoolFiles(context).size)

        factory.progressiveUpstreamFactoryForTesting(
            url = "https://example.com/video-b.mkv",
            headers = emptyMap(),
        )

        assertEquals(1, diskSpoolFiles(context).size)
        factory.shutdown()
    }

    @Test
    fun shutdown_closesActiveDiskSpoolSession() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val factory = PlayerMediaSourceFactory(
            context = context,
            playbackOkHttpClient = noNetworkOkHttpClient()
        ).apply {
            progressivePlaybackDiskMode = ProgressivePlaybackDiskMode.SPOOL
            spoolStorageProbeResult = passingProbeResult(context)
            diskSpoolAvailableBytesForTesting = Long.MAX_VALUE
            diskSpoolWriterExecutorForTesting = Executor { }
        }

        factory.progressiveUpstreamFactoryForTesting(
            url = "https://example.com/video.mkv",
            headers = emptyMap(),
        )

        assertEquals(1, diskSpoolFiles(context).size)

        factory.shutdown()

        assertEquals(0, diskSpoolFiles(context).size)
    }

    @Test
    fun progressivePlayback_closesActiveDiskSpoolSessionWhenUsingNormalFactory() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val factory = PlayerMediaSourceFactory(
            context = context,
            playbackOkHttpClient = noNetworkOkHttpClient()
        ).apply {
            progressivePlaybackDiskMode = ProgressivePlaybackDiskMode.SPOOL
            spoolStorageProbeResult = passingProbeResult(context)
            diskSpoolAvailableBytesForTesting = Long.MAX_VALUE
            diskSpoolWriterExecutorForTesting = Executor { }
        }

        factory.progressiveUpstreamFactoryForTesting(
            url = "https://example.com/video-a.mkv",
            headers = emptyMap(),
        )

        assertEquals(1, diskSpoolFiles(context).size)

        factory.progressivePlaybackDiskMode = ProgressivePlaybackDiskMode.OFF
        val dataSourceFactory = factory.progressiveUpstreamFactoryForTesting(
            url = "https://example.com/video-b.mkv",
            headers = emptyMap(),
        )

        assertFalse(dataSourceFactory is DiskSpoolDataSource.Factory)
        assertEquals(0, diskSpoolFiles(context).size)
        factory.shutdown()
    }

    @Test
    fun cleanupDiskSpoolDirectory_removesStaleDiagnosticFiles() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val spoolDirectory = context.cacheDir.resolve("player_disk_spool")
        val staleDiagnostic = spoolDirectory.resolve("spool-diagnostic-stale.bin")
        val staleRandom = spoolDirectory.resolve("spool-diagnostic-stale.bin.random")
        val staleConcurrent = spoolDirectory.resolve("spool-diagnostic-stale.bin.concurrent")
        val activeSpool = spoolDirectory.resolve("spool-active.bin")
        val unrelatedFile = spoolDirectory.resolve("keep.txt")
        spoolDirectory.mkdirs()
        staleDiagnostic.writeText("diagnostic")
        staleRandom.writeText("random")
        staleConcurrent.writeText("concurrent")
        activeSpool.writeText("spool")
        unrelatedFile.writeText("keep")

        PlayerMediaSourceFactory(context, OkHttpClient()).cleanupDiskSpoolDirectoryForTesting()

        assertEquals(false, staleDiagnostic.exists())
        assertEquals(false, staleRandom.exists())
        assertEquals(false, staleConcurrent.exists())
        assertEquals(false, activeSpool.exists())
        assertEquals(true, unrelatedFile.exists())
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
    fun warmAheadUpstreamKind_reportsSingleWhenDedicatedWarmAheadFactoryIsAbsent() {
        val factory = PlayerMediaSourceFactory(
            context = mockk(relaxed = true),
            playbackOkHttpClient = OkHttpClient()
        )

        assertEquals("single", factory.currentWarmAheadUpstreamKindForTesting())
    }

    @Test
    fun warmAheadUpstreamKind_reportsPrdsWhenDedicatedWarmAheadFactoryIsPresent() {
        val factory = PlayerMediaSourceFactory(
            context = mockk(relaxed = true),
            playbackOkHttpClient = OkHttpClient()
        )

        factory.progressiveUpstreamFactoryForTesting(
            url = "https://real-debrid.com/path/movie.mkv",
            headers = emptyMap(),
            warmAheadEnabledForProfile = true
        )

        assertEquals("prds", factory.currentWarmAheadUpstreamKindForTesting())
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
    fun vodCacheFragmentSize_usesFourMbFragments() {
        assertEquals(
            4L * 1024L * 1024L,
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
    fun parallelProviderProfile_realDebridUsesTwoByEighteen() {
        val factory = PlayerMediaSourceFactory(
            context = mockk(relaxed = true),
            playbackOkHttpClient = OkHttpClient()
        )

        assertEquals(
            2 to 18,
            factory.parallelProviderProfileForTesting("https://real-debrid.com/path/movie.mkv")
        )
    }

    @Test
    fun parallelProviderProfiles_warmAheadDisabledKeepsRealDebridCurrentProfile() {
        val factory = PlayerMediaSourceFactory(
            context = mockk(relaxed = true),
            playbackOkHttpClient = OkHttpClient()
        )

        assertEquals(
            (2 to 18) to null,
            factory.parallelProviderProfilesForTesting(
                url = "https://real-debrid.com/path/movie.mkv",
                warmAheadEnabledForStream = false
            )
        )
    }

    @Test
    fun parallelProviderProfiles_warmAheadEnabledSplitsRealDebridTwoAndOne() {
        val factory = PlayerMediaSourceFactory(
            context = mockk(relaxed = true),
            playbackOkHttpClient = OkHttpClient()
        )

        assertEquals(
            (2 to 18) to (1 to 18),
            factory.parallelProviderProfilesForTesting(
                url = "https://real-debrid.com/path/movie.mkv",
                warmAheadEnabledForStream = true
            )
        )
    }

    @Test
    fun parallelProviderProfiles_warmAheadDisabledKeepsPremiumizeCurrentProfile() {
        val factory = PlayerMediaSourceFactory(
            context = mockk(relaxed = true),
            playbackOkHttpClient = OkHttpClient()
        )

        assertEquals(
            (3 to 16) to null,
            factory.parallelProviderProfilesForTesting(
                url = "https://premiumize.me/path/movie.mkv",
                warmAheadEnabledForStream = false
            )
        )
    }

    @Test
    fun parallelProviderProfiles_warmAheadEnabledSplitsPremiumizeTwoAndOne() {
        val factory = PlayerMediaSourceFactory(
            context = mockk(relaxed = true),
            playbackOkHttpClient = OkHttpClient()
        )

        assertEquals(
            (2 to 16) to (1 to 16),
            factory.parallelProviderProfilesForTesting(
                url = "https://premiumize.me/path/movie.mkv",
                warmAheadEnabledForStream = true
            )
        )
    }

    @Test
    fun parallelProviderProfiles_warmAheadEnabledSplitsFallbackTwoAndOne() {
        val factory = PlayerMediaSourceFactory(
            context = mockk(relaxed = true),
            playbackOkHttpClient = OkHttpClient()
        )

        assertEquals(
            (2 to 24) to (1 to 16),
            factory.parallelProviderProfilesForTesting(
                url = "https://example.com/path/movie.mkv",
                warmAheadEnabledForStream = true
            )
        )
    }

    @Test
    fun warmAheadFactoryProfile_usesRealDebridWarmAheadProfileWhenEnabled() {
        val factory = PlayerMediaSourceFactory(
            context = mockk(relaxed = true),
            playbackOkHttpClient = OkHttpClient()
        )

        assertEquals(
            1 to 18,
            factory.warmAheadProviderProfileForTesting(
                url = "https://real-debrid.com/path/movie.mkv",
                warmAheadEnabledForStream = true
            )
        )
    }

    @Test
    fun warmAheadFactoryProfile_isNullWhenWarmAheadDisabled() {
        val factory = PlayerMediaSourceFactory(
            context = mockk(relaxed = true),
            playbackOkHttpClient = OkHttpClient()
        )

        assertEquals(
            null,
            factory.warmAheadProviderProfileForTesting(
                url = "https://real-debrid.com/path/movie.mkv",
                warmAheadEnabledForStream = false
            )
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

    @Test
    fun progressivePlayback_usesDiskSpoolWithoutDiagnosticResult() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val factory = PlayerMediaSourceFactory(
            context = context,
            playbackOkHttpClient = noNetworkOkHttpClient()
        ).apply {
            progressivePlaybackDiskMode = ProgressivePlaybackDiskMode.SPOOL
            spoolStorageProbeResult = null
            diskSpoolAvailableBytesForTesting = Long.MAX_VALUE
            diskSpoolWriterExecutorForTesting = Executor { }
        }

        val dataSourceFactory = factory.progressiveUpstreamFactoryForTesting(
            url = "https://example.com/video.mkv",
            headers = emptyMap()
        )

        assertTrue(dataSourceFactory is DiskSpoolDataSource.Factory)
        factory.shutdown()
    }

    @Test
    fun createMediaSource_doesNotWrapDiskSpoolWithVodCacheWhenBothSettingsAreOn() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val factory = PlayerMediaSourceFactory(
            context = context,
            playbackOkHttpClient = noNetworkOkHttpClient()
        ).apply {
            vodCacheSizeMode = VodCacheSizeMode.ON
            progressivePlaybackDiskMode = ProgressivePlaybackDiskMode.SPOOL
            diskSpoolAvailableBytesForTesting = Long.MAX_VALUE
            diskSpoolWriterExecutorForTesting = Executor { }
        }

        factory.createMediaSource("https://example.com/video.mkv", emptyMap())

        assertFalse(factory.getVodCacheLogState("https://example.com/video.mkv").contains("active=true"))
        factory.shutdown()
    }

    @Test
    fun progressivePlayback_usesExternalDiskSpoolDirectoryWhenSelected() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val expectedSpoolDir = File(context.cacheDir.parentFile, "external-spool").also { it.mkdirs() }
        val factory = PlayerMediaSourceFactory(
            context = context,
            playbackOkHttpClient = noNetworkOkHttpClient()
        ).apply {
            progressivePlaybackDiskMode = ProgressivePlaybackDiskMode.SPOOL
            diskSpoolStorageLocation = DiskSpoolStorageLocation.EXTERNAL
            diskSpoolDirectoryResolverForTesting = { _, location ->
                if (location == DiskSpoolStorageLocation.EXTERNAL) expectedSpoolDir else context.cacheDir
            }
            diskSpoolAvailableBytesForTesting = Long.MAX_VALUE
            diskSpoolWriterExecutorForTesting = Executor { }
        }

        val dataSourceFactory = factory.progressiveUpstreamFactoryForTesting(
            url = "https://example.com/video.mkv",
            headers = emptyMap()
        )

        assertTrue(dataSourceFactory is DiskSpoolDataSource.Factory)
        assertEquals(1, expectedSpoolDir.listFiles()?.count { it.name.startsWith("spool-") } ?: 0)
        factory.shutdown()
    }

    @Test
    fun progressivePlayback_usesSingleConnectionDiskSpoolEvenWhenParallelConnectionsEnabled() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val capturedProfiles = mutableListOf<Pair<Int, Int>>()
        val factory = PlayerMediaSourceFactory(
            context = context,
            playbackOkHttpClient = noNetworkOkHttpClient()
        ).apply {
            useParallelConnections = true
            progressivePlaybackDiskMode = ProgressivePlaybackDiskMode.SPOOL
            diskSpoolStorageLocation = DiskSpoolStorageLocation.BUILTIN
            spoolStorageProbeResult = passingProbeResult(context)
            diskSpoolAvailableBytesForTesting = Long.MAX_VALUE
            diskSpoolWriterExecutorForTesting = Executor { }
            diskSpoolWriterProfileObserverForTesting = { connections, chunkBytes, _ ->
                capturedProfiles += connections to (chunkBytes / 1024 / 1024)
            }
        }

        factory.progressiveUpstreamFactoryForTesting(
            url = "https://real-debrid.com/path/video.mkv",
            headers = emptyMap()
        )

        assertEquals(listOf(1 to 24), capturedProfiles)
        factory.shutdown()
    }

    @Test
    fun progressivePlayback_usesAutomaticDiskSpoolCapacityAndFixedStartupBuffer() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val capturedSpoolFiles = mutableListOf<File>()
        val capturedStartupBuffers = mutableListOf<Int>()
        val factory = PlayerMediaSourceFactory(
            context = context,
            playbackOkHttpClient = noNetworkOkHttpClient()
        ).apply {
            progressivePlaybackDiskMode = ProgressivePlaybackDiskMode.SPOOL
            diskSpoolAvailableBytesForTesting = Long.MAX_VALUE
            diskSpoolWriterExecutorForTesting = Executor { }
            diskSpoolSessionObserverForTesting = { file, _ -> capturedSpoolFiles += file }
            diskSpoolWriterProfileObserverForTesting = { _, _, startupBufferBytes ->
                capturedStartupBuffers.add((startupBufferBytes / 1024L / 1024L).toInt())
            }
        }

        factory.progressiveUpstreamFactoryForTesting(
            url = "https://real-debrid.com/path/video.mkv",
            headers = emptyMap()
        )

        assertEquals(0L, capturedSpoolFiles.single().length())
        assertEquals(listOf(100), capturedStartupBuffers)
        factory.shutdown()
    }

    @Test
    fun progressivePlayback_prewarmsStartupBufferBeforeReturningMediaSource() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val requestCount = AtomicInteger(0)
        val content = ByteArray(1024 * 1024) { (it % 251).toByte() }
        val server = MockWebServer()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                requestCount.incrementAndGet()
                return when (request.getHeader("Range")) {
                    "bytes=0-0" -> MockResponse()
                        .setResponseCode(206)
                        .setHeader("Accept-Ranges", "bytes")
                        .setHeader("Content-Range", "bytes 0-0/${content.size}")
                        .setHeader("Content-Length", 1)
                        .setBody(Buffer().writeByte(0x2A))

                    "bytes=0-1048575" -> MockResponse()
                        .setResponseCode(206)
                        .setHeader("Accept-Ranges", "bytes")
                        .setHeader("Content-Range", "bytes 0-1048575/${content.size}")
                        .setHeader("Content-Length", content.size)
                        .setBody(Buffer().write(content))

                    else -> MockResponse().setResponseCode(400)
                }
            }
        }
        server.start()
        val factory = PlayerMediaSourceFactory(
            context = context,
            playbackOkHttpClient = OkHttpClient()
        ).apply {
            progressivePlaybackDiskMode = ProgressivePlaybackDiskMode.SPOOL
            diskSpoolAvailableBytesForTesting = Long.MAX_VALUE
        }

        try {
            factory.createMediaSource(server.url("/video.mkv").toString(), emptyMap())

            assertEquals(2, requestCount.get())
        } finally {
            factory.shutdown()
            server.shutdown()
        }
    }

    private fun noNetworkOkHttpClient(): OkHttpClient {
        return OkHttpClient.Builder()
            .addInterceptor {
                throw IOException("network probe should not run on factory hot path")
            }
            .build()
    }

    private fun passingProbeResult(context: Context): SpoolStorageProbeResult {
        return SpoolStorageProbeResult(
            writeMbps = 180.0,
            readMbps = 180.0,
            combinedMbps = 360.0,
            p99ReadLatencyMs = 40L,
            maxReadStallMs = 70L,
            measuredAtMs = System.currentTimeMillis(),
            durationMs = 60_000L,
            bytesWritten = 1_350_000_000L,
            bytesRead = 1_350_000_000L,
            spoolDirectoryPath = context.cacheDir.resolve("player_disk_spool").absolutePath
        )
    }

    private fun diskSpoolFiles(context: Context): List<java.io.File> {
        return context.cacheDir
            .resolve("player_disk_spool")
            .listFiles()
            ?.filter { it.isFile && it.name.startsWith("spool-") && it.name.endsWith(".bin") }
            .orEmpty()
    }
}
