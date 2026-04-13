package com.nexio.tv.data.local

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.nexio.tv.ui.screens.player.spool.SpoolStorageProbeResult
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class PlayerSettingsDataStoreSpoolModeTest {

    @Test
    fun `default progressive playback disk mode is off`() {
        assertEquals(ProgressivePlaybackDiskMode.OFF, PlayerSettings().progressivePlaybackDiskMode)
    }

    @Test
    fun `setting spool mode persists`() = runTest {
        val dataStore = PlayerSettingsDataStore(ApplicationProvider.getApplicationContext<Context>())

        dataStore.setProgressivePlaybackDiskMode(ProgressivePlaybackDiskMode.SPOOL)

        assertEquals(
            ProgressivePlaybackDiskMode.SPOOL,
            dataStore.playerSettings.first().progressivePlaybackDiskMode
        )
    }

    @Test
    fun `setting spool mode does not clear autoplay max bitrate`() = runTest {
        val dataStore = PlayerSettingsDataStore(ApplicationProvider.getApplicationContext<Context>())

        dataStore.setAutoplayMaxBitrate(42.0)
        dataStore.setProgressivePlaybackDiskMode(ProgressivePlaybackDiskMode.SPOOL)

        assertEquals(42.0, dataStore.playerSettings.first().autoplayMaxBitrateMbps ?: -1.0, 0.0)
    }

    @Test
    fun `persisted spool storage probe result json round trips`() = runTest {
        val dataStore = PlayerSettingsDataStore(ApplicationProvider.getApplicationContext<Context>())
        val result = probeResult()

        dataStore.setSpoolStorageProbeResult(result)

        assertEquals(result, dataStore.spoolStorageProbeResult.first())
    }

    @Test
    fun `persisted spool storage probe result json is exposed on player settings`() = runTest {
        val dataStore = PlayerSettingsDataStore(ApplicationProvider.getApplicationContext<Context>())
        val result = probeResult()

        dataStore.setSpoolStorageProbeResult(result)

        assertEquals(
            result,
            SpoolStorageProbeResult.fromJsonOrNull(
                dataStore.playerSettings.first().spoolStorageProbeResultJson
            )
        )
    }

    @Test
    fun `setting spool storage probe result null clears persisted result`() = runTest {
        val dataStore = PlayerSettingsDataStore(ApplicationProvider.getApplicationContext<Context>())

        dataStore.setSpoolStorageProbeResult(probeResult())
        dataStore.setSpoolStorageProbeResult(null)

        assertNull(dataStore.spoolStorageProbeResult.first())
        assertNull(dataStore.playerSettings.first().spoolStorageProbeResultJson)
    }

    @Test
    fun `malformed persisted spool storage probe result json is treated as null`() = runTest {
        val dataStore = PlayerSettingsDataStore(ApplicationProvider.getApplicationContext<Context>())

        dataStore.setSpoolStorageProbeResultJsonForTesting("{not json}")

        assertNull(dataStore.spoolStorageProbeResult.first())
    }

    private fun probeResult(): SpoolStorageProbeResult {
        return SpoolStorageProbeResult(
            writeMbps = 180.0,
            readMbps = 180.0,
            combinedMbps = 360.0,
            p99ReadLatencyMs = 40L,
            maxReadStallMs = 70L,
            measuredAtMs = 1_776_047_817_725L,
            durationMs = 60_000L,
            bytesWritten = 1_350_000_000L,
            bytesRead = 1_350_000_000L,
            spoolDirectoryPath = "/data/user/0/com.nexio.tv/cache/player_disk_spool"
        )
    }
}
