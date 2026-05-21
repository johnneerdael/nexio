package com.nexio.tv.ui.components

import androidx.media3.common.Player
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Plan: Bug B — Task B1.
 *
 * Pure-logic tests for TrailerBufferingWatchdog. No coroutines; we drive
 * the clock with a mutable counter and call the watchdog APIs directly.
 */
class TrailerBufferingWatchdogTest {

    @Test
    fun `buffering longer than threshold without progress fires onStall`() {
        var now = 0L
        var stallCount = 0
        val watchdog = TrailerBufferingWatchdog(
            stallTimeoutMs = 10_000L,
            nowMs = { now },
            onStall = { stallCount += 1 }
        )

        watchdog.onPlaybackStateChanged(Player.STATE_BUFFERING)
        watchdog.onProgressTick(positionMs = 0L)
        now += 11_000L
        watchdog.tickWatchdog()

        assertEquals(1, stallCount)
    }

    @Test
    fun `progress advancing while buffering resets the stall timer`() {
        var now = 0L
        var stallCount = 0
        val watchdog = TrailerBufferingWatchdog(
            stallTimeoutMs = 10_000L,
            nowMs = { now },
            onStall = { stallCount += 1 }
        )

        watchdog.onPlaybackStateChanged(Player.STATE_BUFFERING)
        watchdog.onProgressTick(positionMs = 0L)
        now += 5_000L
        watchdog.onProgressTick(positionMs = 100L) // progressed, resets
        now += 7_000L                              // 7s since reset, still under 10s
        watchdog.tickWatchdog()

        assertEquals(0, stallCount)
    }

    @Test
    fun `transition out of BUFFERING clears watchdog state`() {
        var now = 0L
        var stallCount = 0
        val watchdog = TrailerBufferingWatchdog(
            stallTimeoutMs = 10_000L,
            nowMs = { now },
            onStall = { stallCount += 1 }
        )

        watchdog.onPlaybackStateChanged(Player.STATE_BUFFERING)
        watchdog.onProgressTick(positionMs = 0L)
        now += 5_000L
        watchdog.onPlaybackStateChanged(Player.STATE_READY)
        now += 20_000L
        watchdog.tickWatchdog()

        assertEquals(0, stallCount)
    }

    @Test
    fun `stall fires only once per buffering session`() {
        var now = 0L
        var stallCount = 0
        val watchdog = TrailerBufferingWatchdog(
            stallTimeoutMs = 10_000L,
            nowMs = { now },
            onStall = { stallCount += 1 }
        )

        watchdog.onPlaybackStateChanged(Player.STATE_BUFFERING)
        watchdog.onProgressTick(positionMs = 0L)
        now += 11_000L
        watchdog.tickWatchdog()
        watchdog.tickWatchdog()
        watchdog.tickWatchdog()

        assertEquals(1, stallCount)
    }

    @Test
    fun `tickWatchdog before any BUFFERING is a no-op`() {
        var now = 0L
        var stallCount = 0
        val watchdog = TrailerBufferingWatchdog(
            stallTimeoutMs = 10_000L,
            nowMs = { now },
            onStall = { stallCount += 1 }
        )

        watchdog.onPlaybackStateChanged(Player.STATE_READY)
        now += 50_000L
        watchdog.tickWatchdog()

        assertEquals(0, stallCount)
    }
}
