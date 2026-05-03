package com.nexio.tv.core.player

import org.junit.After
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Before
import org.junit.Test

class PlayProbeCacheTest {

    private val url = "https://comet.feels.legal/cfg/playback/HASH/0/0/n/n?torrent_name=x"
    private val metadata = FfmpegStreamMetadataProbeResult(
        streams = listOf(
            FfmpegStreamMetadata(codecType = "video", codecName = "hevc", dvProfile = 5)
        )
    )

    @Before
    fun setUp() {
        PlayProbeCache.resetForTesting()
    }

    @After
    fun tearDown() {
        PlayProbeCache.resetForTesting()
    }

    @Test
    fun getReturnsNullWhenNothingWasPut() {
        assertNull(PlayProbeCache.get(url, headers = null))
    }

    @Test
    fun putThenGetReturnsTheSameInstance() {
        PlayProbeCache.put(url, headers = null, metadata = metadata)

        assertSame(metadata, PlayProbeCache.get(url, headers = null))
    }

    @Test
    fun getIsRepeatableWithinTtlForMultipleConsumers() {
        PlayProbeCache.put(url, headers = null, metadata = metadata)

        assertNotNull(PlayProbeCache.get(url, headers = null))
        assertNotNull(PlayProbeCache.get(url, headers = null))
        assertNotNull(PlayProbeCache.get(url, headers = null))
    }

    @Test
    fun headersDifferenceProducesASeparateCacheEntry() {
        val a = mapOf("Authorization" to "Bearer A")
        val b = mapOf("Authorization" to "Bearer B")
        val metaB = metadata.copy(streams = listOf(FfmpegStreamMetadata("video", "av1")))

        PlayProbeCache.put(url, a, metadata)
        PlayProbeCache.put(url, b, metaB)

        assertSame(metadata, PlayProbeCache.get(url, a))
        assertSame(metaB, PlayProbeCache.get(url, b))
    }

    @Test
    fun headerOrderDoesNotAffectKey() {
        val a = linkedMapOf("X-A" to "1", "X-B" to "2")
        val b = linkedMapOf("X-B" to "2", "X-A" to "1")

        PlayProbeCache.put(url, a, metadata)

        assertSame(metadata, PlayProbeCache.get(url, b))
    }

    @Test
    fun rangeHeaderIsExcludedFromKey() {
        val withRange = mapOf("Authorization" to "Bearer A", "Range" to "bytes=0-0")
        val withoutRange = mapOf("Authorization" to "Bearer A")

        PlayProbeCache.put(url, withoutRange, metadata)

        assertSame(metadata, PlayProbeCache.get(url, withRange))
    }

    @Test
    fun entryExpiresAfterTtl() {
        var now = 0L
        PlayProbeCache.setClockForTesting { now }
        PlayProbeCache.put(url, headers = null, metadata = metadata)

        now += 30_001L

        assertNull(PlayProbeCache.get(url, headers = null))
    }

    @Test
    fun entrySurvivesJustBeforeTtl() {
        var now = 0L
        PlayProbeCache.setClockForTesting { now }
        PlayProbeCache.put(url, headers = null, metadata = metadata)

        now += 30_000L

        assertNotNull(PlayProbeCache.get(url, headers = null))
    }

    @Test
    fun lruEvictsOldestWhenCapExceeded() {
        val urls = (0..4).map { "https://host/p/$it" }
        val metas = urls.map { metadata.copy() }

        urls.zip(metas).forEach { (entryUrl, entryMetadata) ->
            PlayProbeCache.put(entryUrl, headers = null, metadata = entryMetadata)
        }

        assertNull(PlayProbeCache.get(urls[0], headers = null))
        assertNotNull(PlayProbeCache.get(urls[1], headers = null))
        assertNotNull(PlayProbeCache.get(urls[4], headers = null))
    }

    @Test
    fun expiredEntriesAreRemovedOnGet() {
        var now = 0L
        PlayProbeCache.setClockForTesting { now }
        PlayProbeCache.put(url, headers = null, metadata = metadata)

        now += 60_000L

        assertNull(PlayProbeCache.get(url, headers = null))

        now += 1L
        PlayProbeCache.put(url, headers = null, metadata = metadata)

        assertNotNull(PlayProbeCache.get(url, headers = null))
    }
}
