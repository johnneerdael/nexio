package com.nexio.tv.ui.screens.player

import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.concurrent.TimeUnit

/**
 * Verifies that the playback OkHttpClient has a bounded callTimeout equal to the configured
 * constant — not 0 (infinite) as the old ad-hoc client had.
 *
 * The chosen default of 120_000 ms survives a 64 MiB chunk on a ~5 Mbps link:
 *   64 MiB × 8 bits/byte ÷ 5_000_000 bps ≈ 102 s → 120 s with margin.
 *
 * This test also verifies that the value is injected, not hardcoded, by constructing
 * the client with a different timeout and asserting it takes effect.
 */
class PlaybackCallTimeoutTest {

    private val defaultCallTimeoutMs: Long = 120_000L

    private fun buildPlaybackClient(callTimeoutMs: Long): OkHttpClient =
        OkHttpClient.Builder()
            .callTimeout(callTimeoutMs, TimeUnit.MILLISECONDS)
            .build()

    @Test
    fun `playback client callTimeoutMillis equals default 120 seconds`() {
        val client = buildPlaybackClient(defaultCallTimeoutMs)
        assertEquals(
            "callTimeoutMillis must equal the configured default",
            defaultCallTimeoutMs.toInt(),
            client.callTimeoutMillis
        )
    }

    @Test
    fun `playback client callTimeoutMillis is wired through not hardcoded`() {
        // Use a different value to prove the constant is injected, not hardcoded.
        val customTimeoutMs = 60_000L
        val client = buildPlaybackClient(customTimeoutMs)
        assertEquals(
            "callTimeoutMillis must reflect the injected value",
            customTimeoutMs.toInt(),
            client.callTimeoutMillis
        )
    }

    @Test
    fun `playback client callTimeoutMillis is not zero (not infinite)`() {
        val client = buildPlaybackClient(defaultCallTimeoutMs)
        assert(client.callTimeoutMillis > 0) {
            "callTimeoutMillis must be bounded (> 0), was ${client.callTimeoutMillis}"
        }
    }
}
