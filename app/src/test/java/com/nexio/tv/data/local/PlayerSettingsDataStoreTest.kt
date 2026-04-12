package com.nexio.tv.data.local

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class PlayerSettingsDataStoreTest {

    @Test
    fun `autoplay bandwidth mode defaults to auto with 20 mbps manual cap`() = runTest {
        val dataStore = PlayerSettingsDataStore(ApplicationProvider.getApplicationContext<Context>())

        val settings = dataStore.playerSettings.first()

        assertEquals(AutoplayBandwidthMode.AUTO, settings.autoplayBandwidthMode)
        assertEquals(20.0, settings.manualBitrateLimitMbps, 0.0)
    }

    @Test
    fun `manual bitrate limit is coerced to supported range`() = runTest {
        val dataStore = PlayerSettingsDataStore(ApplicationProvider.getApplicationContext<Context>())

        dataStore.setManualBitrateLimitMbps(2.0)
        assertEquals(5.0, dataStore.playerSettings.first().manualBitrateLimitMbps, 0.0)

        dataStore.setManualBitrateLimitMbps(205.0)
        assertEquals(200.0, dataStore.playerSettings.first().manualBitrateLimitMbps, 0.0)

        dataStore.setManualBitrateLimitMbps(Double.NaN)
        assertEquals(20.0, dataStore.playerSettings.first().manualBitrateLimitMbps, 0.0)
    }

    @Test
    fun `autoplay bandwidth mode persists manual selection`() = runTest {
        val dataStore = PlayerSettingsDataStore(ApplicationProvider.getApplicationContext<Context>())

        dataStore.setAutoplayBandwidthMode(AutoplayBandwidthMode.MANUAL)

        assertEquals(AutoplayBandwidthMode.MANUAL, dataStore.playerSettings.first().autoplayBandwidthMode)
        assertTrue(dataStore.playerSettings.first().manualBitrateLimitMbps.isFinite())

        dataStore.setAutoplayBandwidthMode(AutoplayBandwidthMode.AUTO)
    }

    @Test
    fun `changing transport settings clears persisted autoplay max bitrate`() = runTest {
        val dataStore = PlayerSettingsDataStore(ApplicationProvider.getApplicationContext<Context>())

        dataStore.setUseParallelConnections(false)
        dataStore.setAutoplayMaxBitrate(42.0)
        assertEquals(42.0, dataStore.playerSettings.first().autoplayMaxBitrateMbps ?: -1.0, 0.0)

        dataStore.setUseParallelConnections(true)
        assertNull(dataStore.playerSettings.first().autoplayMaxBitrateMbps)

        dataStore.setVodCacheSizeMode(VodCacheSizeMode.ON)
        dataStore.setAutoplayMaxBitrate(42.0)
        assertEquals(42.0, dataStore.playerSettings.first().autoplayMaxBitrateMbps ?: -1.0, 0.0)

        dataStore.setVodCacheSizeMode(VodCacheSizeMode.OFF)
        assertNull(dataStore.playerSettings.first().autoplayMaxBitrateMbps)
    }
}
