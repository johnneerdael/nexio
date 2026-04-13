package com.nexio.tv.data.local

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.nexio.tv.ui.screens.player.spool.DiskSpoolStorageLocation
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
    fun `default disk spool storage location is builtin`() {
        assertEquals(DiskSpoolStorageLocation.BUILTIN, PlayerSettings().diskSpoolStorageLocation)
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
    fun `setting disk spool storage location persists`() = runTest {
        val dataStore = PlayerSettingsDataStore(ApplicationProvider.getApplicationContext<Context>())

        dataStore.setDiskSpoolStorageLocation(DiskSpoolStorageLocation.EXTERNAL)

        assertEquals(
            DiskSpoolStorageLocation.EXTERNAL,
            dataStore.playerSettings.first().diskSpoolStorageLocation
        )
    }

    @Test
    fun `changing disk spool storage location clears diagnostic result`() = runTest {
        val dataStore = PlayerSettingsDataStore(ApplicationProvider.getApplicationContext<Context>())

        dataStore.setDiskSpoolStorageLocation(DiskSpoolStorageLocation.BUILTIN)
        dataStore.setSpoolStorageProbeResult(probeResult())
        dataStore.setDiskSpoolStorageLocation(DiskSpoolStorageLocation.EXTERNAL)

        assertNull(dataStore.playerSettings.first().spoolStorageProbeResultJson)
    }

    @Test
    fun `enabling disk spool turns vod cache off`() = runTest {
        val dataStore = PlayerSettingsDataStore(ApplicationProvider.getApplicationContext<Context>())

        dataStore.setVodCacheSizeMode(VodCacheSizeMode.ON)
        dataStore.setProgressivePlaybackDiskMode(ProgressivePlaybackDiskMode.SPOOL)

        val settings = dataStore.playerSettings.first()
        assertEquals(ProgressivePlaybackDiskMode.SPOOL, settings.progressivePlaybackDiskMode)
        assertEquals(VodCacheSizeMode.OFF, settings.vodCacheSizeMode)
    }

    @Test
    fun `enabling vod cache turns disk spool off`() = runTest {
        val dataStore = PlayerSettingsDataStore(ApplicationProvider.getApplicationContext<Context>())

        dataStore.setProgressivePlaybackDiskMode(ProgressivePlaybackDiskMode.SPOOL)
        dataStore.setVodCacheSizeMode(VodCacheSizeMode.ON)

        val settings = dataStore.playerSettings.first()
        assertEquals(VodCacheSizeMode.ON, settings.vodCacheSizeMode)
        assertEquals(ProgressivePlaybackDiskMode.OFF, settings.progressivePlaybackDiskMode)
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
