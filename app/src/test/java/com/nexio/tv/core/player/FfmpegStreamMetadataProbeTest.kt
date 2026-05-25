package com.nexio.tv.core.player

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger

class FfmpegStreamMetadataProbeTest {
    @After
    fun resetProbeBackend() {
        FfmpegStreamMetadataProbe.resetForTesting()
        CometProxyUrlResolver.resetForTesting()
    }

    @Test
    fun detectsEmbeddedAssSubtitleStreamsFromFfmpegMetadata() {
        val result = FfmpegStreamMetadataProbe.parseForTesting(
            """
            {
              "streams": [
                {"codec_type": "video", "codec_name": "hevc"},
                {"codec_type": "audio", "codec_name": "opus"},
                {"codec_type": "subtitle", "codec_name": "ass"}
              ]
            }
            """.trimIndent()
        )

        assertTrue(result.hasEmbeddedAssSsaSubtitleStream)
    }

    @Test
    fun ignoresNonAssSubtitleStreams() {
        val result = FfmpegStreamMetadataProbe.parseForTesting(
            """
            {
              "streams": [
                {"codec_type": "subtitle", "codec_name": "hdmv_pgs_subtitle"},
                {"codec_type": "subtitle", "codec_name": "subrip"}
              ]
            }
            """.trimIndent()
        )

        assertFalse(result.hasEmbeddedAssSsaSubtitleStream)
    }

    @Test
    fun emptyNativeResultDoesNotPopulateCacheAndNextSuccessCanRecover() {
        var calls = 0
        FfmpegStreamMetadataProbe.setBackendForTesting(
            object : FfmpegStreamMetadataBackend {
                override fun probeStreamMetadataJson(
                    url: String,
                    requestHeadersBlob: String?
                ): String? {
                    calls += 1
                    return if (calls == 1) {
                        """{"streams":[]}"""
                    } else {
                        """{"streams":[{"codec_type":"video","codec_name":"hevc"}]}"""
                    }
                }
            }
        )

        assertNull(FfmpegStreamMetadataProbe.probeBlocking("https://example.test/video.mkv"))

        val recovered = FfmpegStreamMetadataProbe.probeBlocking("https://example.test/video.mkv")

        assertEquals(2, calls)
        assertEquals("hevc", recovered?.streams?.single()?.codecName)
    }

    @Test
    fun probeBlockingAllowsConcurrentNativeProbesForDifferentUrls() = runBlocking {
        val activeCalls = AtomicInteger(0)
        val maxActiveCalls = AtomicInteger(0)
        FfmpegStreamMetadataProbe.setBackendForTesting(
            object : FfmpegStreamMetadataBackend {
                override fun probeStreamMetadataJson(
                    url: String,
                    requestHeadersBlob: String?
                ): String? {
                    val active = activeCalls.incrementAndGet()
                    maxActiveCalls.updateAndGet { current -> maxOf(current, active) }
                    Thread.sleep(120)
                    activeCalls.decrementAndGet()
                    return """{"streams":[{"codec_type":"video","codec_name":"hevc"}]}"""
                }
            }
        )

        val results = (0 until 4)
            .map { index ->
                async(Dispatchers.IO) {
                    FfmpegStreamMetadataProbe.probeBlocking("https://example.test/video-$index.mkv")
                }
            }
            .awaitAll()

        assertTrue(results.all { it?.streams?.single()?.codecName == "hevc" })
        assertTrue(
            "native probes should overlap instead of serializing behind the cache lock",
            maxActiveCalls.get() > 1
        )
    }

    @Test
    fun parsesDolbyVisionProfileFromFfprobeSideDataList() {
        val result = FfmpegStreamMetadataProbe.parseForTesting(
            """
            {
              "streams": [
                {
                  "index": 0,
                  "codec_type": "video",
                  "codec_name": "hevc",
                  "width": 3840,
                  "height": 2160,
                  "avg_frame_rate": "24000/1001",
                  "side_data_list": [
                    {
                      "side_data_type": "DOVI configuration record",
                      "dv_profile": 8
                    }
                  ]
                },
                {"index": 2, "codec_type": "subtitle", "codec_name": "ass"}
              ]
            }
            """.trimIndent()
        )

        assertEquals(8, result.streams.first().dvProfile)
        assertTrue(result.hasEmbeddedAssSsaSubtitleStream)
    }

    @Test
    fun probeBlockingDropsProxyHeadersAfterResolvingCometUrl() {
        val cometUrl = "https://comet.feels.legal/cfg/playback/" +
            "09b382fa312ad70adaba13d707b500697e72e6fb/0/0/n/n?torrent_name=x&name=y"
        val resolvedUrl = "https://43-4.download.real-debrid.com/d/TOKEN/file.mkv"
        var probedUrl: String? = null
        var probedHeadersBlob: String? = null
        CometProxyUrlResolver.setTransportForTesting { _, _ ->
            ProxyResolution.Redirected(resolvedUrl)
        }
        FfmpegStreamMetadataProbe.setBackendForTesting(
            object : FfmpegStreamMetadataBackend {
                override fun probeStreamMetadataJson(
                    url: String,
                    requestHeadersBlob: String?
                ): String? {
                    probedUrl = url
                    probedHeadersBlob = requestHeadersBlob
                    return """{"streams":[{"codec_type":"subtitle","codec_name":"ass"}]}"""
                }
            }
        )

        val result = FfmpegStreamMetadataProbe.probeBlocking(
            url = cometUrl,
            headers = linkedMapOf("Authorization" to "Bearer x")
        )

        assertEquals(resolvedUrl, probedUrl)
        assertNull(probedHeadersBlob)
        assertTrue(result?.hasEmbeddedAssSsaSubtitleStream == true)
    }

    @Test
    fun probeBlockingSkipsProbeWhenCometResolverReturnsResolveFailed() {
        val cometUrl = "https://comet.feels.legal/cfg/playback/" +
            "09b382fa312ad70adaba13d707b500697e72e6fb/0/0/n/n?torrent_name=x&name=y"
        var backendCalls = 0
        CometProxyUrlResolver.setTransportForTesting { _, _ ->
            ProxyResolution.ResolveFailed
        }
        FfmpegStreamMetadataProbe.setBackendForTesting(
            object : FfmpegStreamMetadataBackend {
                override fun probeStreamMetadataJson(
                    url: String,
                    requestHeadersBlob: String?
                ): String? {
                    backendCalls += 1
                    return """{"streams":[{"codec_type":"video","codec_name":"hevc"}]}"""
                }
            }
        )

        val result = FfmpegStreamMetadataProbe.probeBlocking(cometUrl)

        assertNull(result)
        assertEquals(0, backendCalls)
    }

    @Test
    fun probeBlockingSkipsProbeWhenCometResolverReturnsPlaceholder() {
        val cometUrl = "https://comet.feels.legal/cfg/playback/" +
            "09b382fa312ad70adaba13d707b500697e72e6fb/0/0/n/n?torrent_name=x&name=y"
        var backendCalls = 0
        CometProxyUrlResolver.setTransportForTesting { _, _ ->
            ProxyResolution.Placeholder
        }
        FfmpegStreamMetadataProbe.setBackendForTesting(
            object : FfmpegStreamMetadataBackend {
                override fun probeStreamMetadataJson(
                    url: String,
                    requestHeadersBlob: String?
                ): String? {
                    backendCalls += 1
                    return """{"streams":[{"codec_type":"video","codec_name":"hevc"}]}"""
                }
            }
        )

        val result = FfmpegStreamMetadataProbe.probeBlocking(cometUrl)

        assertNull(result)
        assertEquals(0, backendCalls)
    }

    @Test
    fun debugCommandLogsExactUrlAndHeaderBlob() {
        val command = FfmpegStreamMetadataProbe.debugProbeCommandForTesting(
            url = "https://example.test/secret/path/movie.mkv?token=abc",
            requestHeadersBlob = "Authorization: Bearer secret\r\nUser-Agent: Nexio\r\n"
        )

        assertTrue(command.contains("ffprobe -v error"))
        assertTrue(command.contains("-rw_timeout 5000000"))
        assertTrue(command.contains("-probesize 10000"))
        assertTrue(command.contains("-analyzeduration 10000"))
        assertTrue(command.contains("-headers 'Authorization: Bearer secret\\r\\nUser-Agent: Nexio\\r\\n'"))
        assertTrue(command.contains("'https://example.test/secret/path/movie.mkv?token=abc'"))
    }

    @Test
    fun debugCommandOmitsHeadersArgumentWhenNoHeadersArePresent() {
        val command = FfmpegStreamMetadataProbe.debugProbeCommandForTesting(
            url = "https://example.test/video.mkv",
            requestHeadersBlob = null
        )

        assertFalse(command.contains("-headers"))
        assertTrue(command.endsWith("'https://example.test/video.mkv'"))
    }
}
