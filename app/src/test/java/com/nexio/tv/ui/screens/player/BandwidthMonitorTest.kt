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
}
