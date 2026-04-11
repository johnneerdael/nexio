package com.nexio.tv.ui.screens.player

import android.content.Context
import android.net.Uri
import androidx.media3.datasource.cache.CacheDataSink
import androidx.media3.datasource.cache.SimpleCache
import androidx.media3.datasource.DataSpec
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class FillControllerTest {

    @Test
    fun shouldPause_pausesAtHighWaterAndResumesBelowLowWater() {
        val profile = ProviderProfile(
            fillHorizonBytes = 100L,
            lowWaterBytes = 50L,
            retainBehindBytes = 0L
        )
        var playbackByte = 0L
        val provider = StreamingCacheProvider(
            context = appContext(),
            cacheDirectoryName = "fill-controller-high-water-${System.nanoTime()}"
        )
        val cache = provider.getOrCreateCache()
        val controller = FillController(
            profile = profile,
            cache = cache,
            cacheKey = "movie",
            playbackByteProvider = { playbackByte }
        )

        try {
            controller.onStart()
            assertEquals(FillController.State.FILLING, controller.state)

            val pauseCountBefore = StreamingMetrics.fillWorkerPauseCount.get()
            assertTrue(controller.shouldPause(fillFrontier = 120L))
            assertEquals(FillController.State.HORIZON_REACHED, controller.state)
            assertEquals(pauseCountBefore + 1L, StreamingMetrics.fillWorkerPauseCount.get())

            assertFalse(controller.shouldPause(fillFrontier = 50L))
            assertEquals(FillController.State.FILLING, controller.state)
        } finally {
            controller.stop()
            provider.release()
            provider.cacheDirectory.deleteRecursively()
        }
    }

    @Test
    fun onMemoryWarning_setsPressureState_andPauses() {
        val provider = StreamingCacheProvider(
            context = appContext(),
            cacheDirectoryName = "fill-controller-memory-${System.nanoTime()}"
        )
        val cache = provider.getOrCreateCache()
        val key = "movie"
        writeSpan(cache, key, position = 0L, bytes = ByteArray(8) { 1 })
        assertTrue(cache.isCached(key, 0L, 8L))
        val controller = FillController(
            profile = ProviderProfile(
                fillHorizonBytes = 100L,
                lowWaterBytes = 50L,
                retainBehindBytes = 16L
            ),
            cache = cache,
            cacheKey = key,
            playbackByteProvider = { 16L }
        )

        try {
            controller.onMemoryWarning()

            assertEquals(FillController.State.MEMORY_PRESSURE, controller.state)
            assertTrue(controller.shouldPause(fillFrontier = 10L))
            assertEquals(FillController.State.MEMORY_PRESSURE, controller.state)
            assertFalse(cache.isCached(key, 0L, 8L))
        } finally {
            controller.stop()
            provider.release()
            provider.cacheDirectory.deleteRecursively()
        }
    }

    @Test
    fun evictBehindPlayback_removesSpansFullyBehindPlaybackAndKeepsAheadSpans() {
        val cacheDirectoryName = "fill-controller-evict-${System.nanoTime()}"
        val provider = StreamingCacheProvider(
            context = appContext(),
            cacheDirectoryName = cacheDirectoryName
        )
        val cache = provider.getOrCreateCache()
        val key = "movie"

        try {
            writeSpan(cache, key, position = 0L, bytes = ByteArray(8) { 1 })
            writeSpan(cache, key, position = 8L, bytes = ByteArray(8) { 2 })
            writeSpan(cache, key, position = 16L, bytes = ByteArray(8) { 3 })

            assertTrue(cache.isCached(key, 0L, 8L))
            assertTrue(cache.isCached(key, 8L, 8L))
            assertTrue(cache.isCached(key, 16L, 8L))

            val controller = FillController(
                profile = ProviderProfile(retainBehindBytes = 8L),
                cache = cache,
                cacheKey = key,
                playbackByteProvider = { 24L }
            )

            controller.evictBehindPlayback()

            assertFalse(cache.isCached(key, 0L, 8L))
            assertFalse(cache.isCached(key, 8L, 8L))
            assertTrue(cache.isCached(key, 16L, 8L))
        } finally {
            provider.release()
            provider.cacheDirectory.deleteRecursively()
        }
    }

    private fun appContext(): Context {
        return ApplicationProvider.getApplicationContext()
    }

    private fun writeSpan(cache: SimpleCache, key: String, position: Long, bytes: ByteArray) {
        val lockedSpan = try {
            cache.startReadWrite(key, position, bytes.size.toLong())
        } catch (e: InterruptedException) {
            throw AssertionError("unexpected interruption while reserving cache span", e)
        }
        val sink = CacheDataSink(cache, 8L * 1024L * 1024L)
        val spec = DataSpec.Builder()
            .setUri(Uri.parse("https://example.com/movie.mkv"))
            .setKey(key)
            .setPosition(position)
            .setLength(bytes.size.toLong())
            .build()
        sink.open(spec)
        try {
            sink.write(bytes, 0, bytes.size)
        } finally {
            try {
                sink.close()
            } finally {
                if (lockedSpan.isHoleSpan) {
                    cache.releaseHoleSpan(lockedSpan)
                }
            }
        }
    }
}
