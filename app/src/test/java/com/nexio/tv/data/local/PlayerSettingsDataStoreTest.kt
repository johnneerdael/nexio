package com.nexio.tv.data.local

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.doublePreferencesKey
import androidx.datastore.preferences.core.mutablePreferencesOf
import androidx.datastore.preferences.core.stringPreferencesKey
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
    fun `autoplay bandwidth mode defaults to manual with 40 mbps manual cap`() = runTest {
        val dataStore = PlayerSettingsDataStore(ApplicationProvider.getApplicationContext<Context>())

        val settings = dataStore.playerSettings.first()

        assertEquals(true, settings.deterministicAutoplayEnabled)
        assertEquals(AutoplayBandwidthMode.MANUAL, settings.autoplayBandwidthMode)
        assertEquals(40.0, settings.manualBitrateLimitMbps, 0.0)
    }

    @Test
    fun `autoplay defaults migration enables deterministic manual mode once`() {
        val deterministicAutoplayEnabledKey = booleanPreferencesKey("deterministic_autoplay_enabled")
        val autoplayBandwidthModeKey = stringPreferencesKey("autoplay_bandwidth_mode")
        val manualBitrateLimitMbpsKey = doublePreferencesKey("manual_bitrate_limit_mbps")
        val migrationDoneKey = booleanPreferencesKey("migration_autoplay_manual_defaults_done")
        val prefs = mutablePreferencesOf(
            deterministicAutoplayEnabledKey to false,
            autoplayBandwidthModeKey to AutoplayBandwidthMode.AUTO.name,
            manualBitrateLimitMbpsKey to 20.0
        )

        applyPlayerSettingsMigrations(prefs)

        assertEquals(true, prefs[deterministicAutoplayEnabledKey])
        assertEquals(AutoplayBandwidthMode.MANUAL.name, prefs[autoplayBandwidthModeKey])
        assertEquals(40.0, prefs[manualBitrateLimitMbpsKey] ?: -1.0, 0.0)
        assertEquals(true, prefs[migrationDoneKey])
    }

    @Test
    fun `autoplay defaults migration does not override after it has run`() {
        val deterministicAutoplayEnabledKey = booleanPreferencesKey("deterministic_autoplay_enabled")
        val autoplayBandwidthModeKey = stringPreferencesKey("autoplay_bandwidth_mode")
        val manualBitrateLimitMbpsKey = doublePreferencesKey("manual_bitrate_limit_mbps")
        val migrationDoneKey = booleanPreferencesKey("migration_autoplay_manual_defaults_done")
        val prefs = mutablePreferencesOf(
            deterministicAutoplayEnabledKey to false,
            autoplayBandwidthModeKey to AutoplayBandwidthMode.AUTO.name,
            manualBitrateLimitMbpsKey to 80.0,
            migrationDoneKey to true
        )

        applyPlayerSettingsMigrations(prefs)

        assertEquals(false, prefs[deterministicAutoplayEnabledKey])
        assertEquals(AutoplayBandwidthMode.AUTO.name, prefs[autoplayBandwidthModeKey])
        assertEquals(80.0, prefs[manualBitrateLimitMbpsKey] ?: -1.0, 0.0)
    }

    @Test
    fun `manual bitrate limit is coerced to supported range`() = runTest {
        val dataStore = PlayerSettingsDataStore(ApplicationProvider.getApplicationContext<Context>())

        dataStore.setManualBitrateLimitMbps(2.0)
        assertEquals(5.0, dataStore.playerSettings.first().manualBitrateLimitMbps, 0.0)

        dataStore.setManualBitrateLimitMbps(205.0)
        assertEquals(200.0, dataStore.playerSettings.first().manualBitrateLimitMbps, 0.0)

        dataStore.setManualBitrateLimitMbps(Double.NaN)
        assertEquals(40.0, dataStore.playerSettings.first().manualBitrateLimitMbps, 0.0)
    }

    @Test
    fun `autoplay bandwidth mode persists manual selection`() = runTest {
        val dataStore = PlayerSettingsDataStore(ApplicationProvider.getApplicationContext<Context>())

        dataStore.setAutoplayBandwidthMode(AutoplayBandwidthMode.MANUAL)

        assertEquals(AutoplayBandwidthMode.MANUAL, dataStore.playerSettings.first().autoplayBandwidthMode)
        assertTrue(dataStore.playerSettings.first().manualBitrateLimitMbps.isFinite())
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
