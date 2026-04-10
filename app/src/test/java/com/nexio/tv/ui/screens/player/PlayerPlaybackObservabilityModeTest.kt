package com.nexio.tv.ui.screens.player

import org.junit.Assert.assertEquals
import org.junit.Test

class PlayerPlaybackObservabilityModeTest {

    @Test
    fun `direct path uses base client when playback diagnostics is off`() {
        val mode = PlayerMediaSourceFactory.resolvePlaybackClientMode(
            useParallelConnections = false,
            playbackTraceEnabled = false
        )

        assertEquals(PlayerMediaSourceFactory.PlaybackClientMode.BASE, mode)
    }

    @Test
    fun `direct path still uses base client when playback diagnostics is on`() {
        val mode = PlayerMediaSourceFactory.resolvePlaybackClientMode(
            useParallelConnections = false,
            playbackTraceEnabled = true
        )

        assertEquals(PlayerMediaSourceFactory.PlaybackClientMode.BASE, mode)
    }

    @Test
    fun `parallel path uses traced range client only when playback diagnostics is on`() {
        val mode = PlayerMediaSourceFactory.resolvePlaybackClientMode(
            useParallelConnections = true,
            playbackTraceEnabled = true
        )

        assertEquals(PlayerMediaSourceFactory.PlaybackClientMode.TRACED_RANGE, mode)
    }
}
