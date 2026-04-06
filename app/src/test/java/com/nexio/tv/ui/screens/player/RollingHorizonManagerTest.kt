package com.nexio.tv.ui.screens.player

import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.cache.CacheKeyFactory
import androidx.media3.datasource.cache.SimpleCache
import io.mockk.mockk
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

@UnstableApi
@RunWith(RobolectricTestRunner::class)
class RollingHorizonManagerTest {

    private lateinit var manager: RollingHorizonManager

    @Before
    fun setUp() {
        val cache = mockk<SimpleCache>(relaxed = true)
        val cacheKeyFactory = mockk<CacheKeyFactory>(relaxed = true)
        val upstreamFactory = mockk<DataSource.Factory>(relaxed = true)
        manager = RollingHorizonManager(
            cache = cache,
            cacheKeyFactory = cacheKeyFactory,
            upstreamFactory = upstreamFactory,
            capBytes = 1024L * 1024L * 1024L, // 1 GB cap
            activeReadBytePosition = AtomicLong(0L),
            stop = AtomicBoolean(false)
        )
        // Put manager into a healthy default state
        manager.isFrontierAdvancing = true
        manager.bufferAheadSeconds = RollingHorizonManager.HEALTH_MIN_BUFFER_AHEAD_SECONDS.toDouble()
        manager.lastRebufferTimeMs = 0L
        manager.lastSeekTimeMs = 0L
    }

    @Test
    fun `isHealthy returns false when rebuffer was recent`() {
        manager.lastRebufferTimeMs = System.currentTimeMillis() - 1_000L
        assertFalse("Should be unhealthy when rebuffer was within HEALTH_NO_REBUFFER_MS", manager.isHealthy())
    }

    @Test
    fun `isHealthy returns false when seek was recent`() {
        manager.lastSeekTimeMs = System.currentTimeMillis() - 1_000L
        assertFalse("Should be unhealthy when seek was within HEALTH_NO_SEEK_MS", manager.isHealthy())
    }

    @Test
    fun `isHealthy returns false when buffer ahead is low`() {
        manager.bufferAheadSeconds = RollingHorizonManager.HEALTH_MIN_BUFFER_AHEAD_SECONDS.toDouble() - 1.0
        assertFalse("Should be unhealthy when bufferAheadSeconds < HEALTH_MIN_BUFFER_AHEAD_SECONDS", manager.isHealthy())
    }

    @Test
    fun `isHealthy returns false when frontier is not advancing`() {
        manager.isFrontierAdvancing = false
        assertFalse("Should be unhealthy when isFrontierAdvancing is false", manager.isHealthy())
    }

    @Test
    fun `isHealthy returns true when all gates pass`() {
        manager.isFrontierAdvancing = true
        manager.bufferAheadSeconds = RollingHorizonManager.HEALTH_MIN_BUFFER_AHEAD_SECONDS.toDouble()
        assertTrue("Should be healthy when all gates pass", manager.isHealthy())
    }

    @Test
    fun `horizon calculation scales with bitrate at 50 Mbps`() {
        val bytesPerSecond = 50.0 * 1024.0 * 1024.0 / 8.0
        val targetBytes = (RollingHorizonManager.HIGH_WATER_SECONDS * bytesPerSecond).toLong()
        val expectedApprox = 750L * 1024L * 1024L
        val toleranceBytes = 10L * 1024L * 1024L
        assertTrue(
            "At 50 Mbps, target bytes should be ~750 MB, got ${targetBytes / 1024 / 1024} MB",
            Math.abs(targetBytes - expectedApprox) < toleranceBytes
        )
    }

    @Test
    fun `horizon calculation scales with bitrate at 5 Mbps`() {
        val bytesPerSecond = 5.0 * 1024.0 * 1024.0 / 8.0
        val targetBytes = (RollingHorizonManager.HIGH_WATER_SECONDS * bytesPerSecond).toLong()
        val expectedApprox = 75L * 1024L * 1024L
        val toleranceBytes = 2L * 1024L * 1024L
        assertTrue(
            "At 5 Mbps, target bytes should be ~75 MB, got ${targetBytes / 1024 / 1024} MB",
            Math.abs(targetBytes - expectedApprox) < toleranceBytes
        )
    }

    @Test
    fun `LOW_WATER_SECONDS is less than HIGH_WATER_SECONDS`() {
        assertTrue(
            "LOW_WATER_SECONDS must be less than HIGH_WATER_SECONDS",
            RollingHorizonManager.LOW_WATER_SECONDS < RollingHorizonManager.HIGH_WATER_SECONDS
        )
    }

    @Test
    fun `MIN_EFFECTIVE_HORIZON_SECONDS is less than LOW_WATER_SECONDS`() {
        assertTrue(
            "MIN_EFFECTIVE_HORIZON_SECONDS must be less than LOW_WATER_SECONDS",
            RollingHorizonManager.MIN_EFFECTIVE_HORIZON_SECONDS < RollingHorizonManager.LOW_WATER_SECONDS
        )
    }
}
