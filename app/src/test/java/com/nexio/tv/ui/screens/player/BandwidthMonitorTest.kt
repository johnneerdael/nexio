package com.nexio.tv.ui.screens.player

import org.junit.Assert.assertEquals
import org.junit.Test

class BandwidthMonitorTest {
    @Test
    fun estimatedBytesPerSecond_usesSlidingWindow() {
        var now = 0L
        val monitor = BandwidthMonitor(windowMs = 5_000L, clockMs = { now })

        monitor.onBytesTransferred(1_000)
        now = 1_000
        monitor.onBytesTransferred(1_000)

        assertEquals(2_000L, monitor.estimatedBytesPerSecond())

        now = 7_000
        monitor.onBytesTransferred(500)

        assertEquals(0L, monitor.estimatedBytesPerSecond())
    }

    @Test
    fun estimatedBytesPerSecond_ignoresOutOfOrderSamplesWithoutSorting() {
        var now = 1_000L
        val monitor = BandwidthMonitor(windowMs = 5_000L, clockMs = { now })

        monitor.onBytesTransferred(1_000)
        now = 500L
        monitor.onBytesTransferred(5_000)
        now = 2_000L
        monitor.onBytesTransferred(1_000)

        assertEquals(2_000L, monitor.estimatedBytesPerSecond())
    }

    @Test
    fun estimatedBytesPerSecond_wrapsAroundCapacityWithoutChangingEstimate() {
        var now = 0L
        val monitor = BandwidthMonitor(windowMs = 10_000L, clockMs = { now }, capacity = 3)

        monitor.onBytesTransferred(1_000)
        now = 1_000L
        monitor.onBytesTransferred(1_000)
        now = 2_000L
        monitor.onBytesTransferred(1_000)
        now = 3_000L
        monitor.onBytesTransferred(1_000)

        assertEquals(1_500L, monitor.estimatedBytesPerSecond())
    }
}
