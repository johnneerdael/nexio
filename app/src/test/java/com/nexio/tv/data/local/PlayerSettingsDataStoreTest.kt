package com.nexio.tv.data.local

import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.doublePreferencesKey
import androidx.datastore.preferences.core.mutablePreferencesOf
import androidx.datastore.preferences.core.stringPreferencesKey
import com.nexio.tv.testutil.playerSettingsDataStoreForTest
import kotlinx.coroutines.flow.MutableStateFlow
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
    fun `preferred external player package defaults empty persists and clears`() = runTest {
        val dataStore = playerSettingsDataStoreForTest()

        assertNull(dataStore.playerSettings.first().preferredExternalPlayerPackageName)

        dataStore.setPreferredExternalPlayerPackageName("org.videolan.vlc")
        assertEquals(
            "org.videolan.vlc",
            dataStore.playerSettings.first().preferredExternalPlayerPackageName
        )

        dataStore.setPreferredExternalPlayerPackageName(" ")
        assertNull(dataStore.playerSettings.first().preferredExternalPlayerPackageName)
    }

    @Test
    fun `trakt scrobble api logging defaults off and persists`() = runTest {
        val dataStore = playerSettingsDataStoreForTest()

        assertEquals(false, dataStore.playerSettings.first().traktScrobbleApiLoggingEnabled)

        dataStore.setTraktScrobbleApiLoggingEnabled(true)

        assertEquals(true, dataStore.playerSettings.first().traktScrobbleApiLoggingEnabled)
    }

    @Test
    fun `skipPlaceholderStreamsEnabled defaults to true`() = runTest {
        val dataStore = playerSettingsDataStoreForTest()
        assertEquals(true, dataStore.playerSettings.first().skipPlaceholderStreamsEnabled)
    }

    @Test
    fun `setSkipPlaceholderStreamsEnabled persists toggled value`() = runTest {
        val dataStore = playerSettingsDataStoreForTest()

        dataStore.setSkipPlaceholderStreamsEnabled(false)
        assertEquals(false, dataStore.playerSettings.first().skipPlaceholderStreamsEnabled)

        dataStore.setSkipPlaceholderStreamsEnabled(true)
        assertEquals(true, dataStore.playerSettings.first().skipPlaceholderStreamsEnabled)
    }

    @Test
    fun `dynamic video scheduling defaults disabled and persists selection`() = runTest {
        val dataStore = playerSettingsDataStoreForTest()

        assertEquals(false, dataStore.playerSettings.first().dynamicVideoSchedulingEnabled)

        dataStore.setDynamicVideoSchedulingEnabled(true)
        assertEquals(true, dataStore.playerSettings.first().dynamicVideoSchedulingEnabled)

        dataStore.setDynamicVideoSchedulingEnabled(false)
        assertEquals(false, dataStore.playerSettings.first().dynamicVideoSchedulingEnabled)
    }

    @Test
    fun `safe playback defaults prefer dv7 hevc base layer over dv81 conversion`() = runTest {
        val dataStore = playerSettingsDataStoreForTest()

        val settings = dataStore.playerSettings.first()

        assertEquals(false, settings.experimentalDv7ToDv81Enabled)
        assertEquals(true, settings.experimentalDv7HevcBaseLayerEnabled)
        assertEquals(false, settings.experimentalDv7ToDv81PreserveMappingEnabled)
    }

    @Test
    fun `safe playback defaults disable vod cache and parallel connections`() = runTest {
        val dataStore = playerSettingsDataStoreForTest()

        val settings = dataStore.playerSettings.first()

        assertEquals(VodCacheSizeMode.OFF, settings.vodCacheSizeMode)
        assertEquals(false, settings.useParallelConnections)
    }

    @Test
    fun `player settings data class safe defaults match datastore defaults`() {
        val settings = PlayerSettings()

        assertEquals(false, settings.experimentalDv7ToDv81Enabled)
        assertEquals(true, settings.experimentalDv7HevcBaseLayerEnabled)
        assertEquals(false, settings.experimentalDv7ToDv81PreserveMappingEnabled)
        assertEquals(VodCacheSizeMode.OFF, settings.vodCacheSizeMode)
        assertEquals(false, settings.useParallelConnections)
    }

    @Test
    fun `safe playback migration disables risky settings for existing default profile`() {
        val dv81Key = booleanPreferencesKey("experimental_dv7_to_dv81_enabled")
        val hevcBaseLayerKey = booleanPreferencesKey("experimental_dv7_hevc_base_layer_enabled")
        val preserveMappingKey =
            booleanPreferencesKey("experimental_dv7_to_dv81_preserve_mapping_enabled")
        val vodCacheModeKey = stringPreferencesKey("vod_cache_size_mode")
        val parallelConnectionsKey = booleanPreferencesKey("use_parallel_connections")
        val migrationDoneKey = booleanPreferencesKey("migration_safe_playback_defaults_done")
        val prefs = mutablePreferencesOf(
            dv81Key to true,
            hevcBaseLayerKey to false,
            preserveMappingKey to true,
            vodCacheModeKey to VodCacheSizeMode.ON.name,
            parallelConnectionsKey to true
        )

        applyPlayerSettingsMigrations(prefs)

        assertEquals(false, prefs[dv81Key])
        assertEquals(true, prefs[hevcBaseLayerKey])
        assertEquals(false, prefs[preserveMappingKey])
        assertEquals(VodCacheSizeMode.OFF.name, prefs[vodCacheModeKey])
        assertEquals(false, prefs[parallelConnectionsKey])
        assertEquals(true, prefs[migrationDoneKey])
    }

    @Test
    fun `safe playback migration does not override after it has run`() {
        val dv81Key = booleanPreferencesKey("experimental_dv7_to_dv81_enabled")
        val hevcBaseLayerKey = booleanPreferencesKey("experimental_dv7_hevc_base_layer_enabled")
        val vodCacheModeKey = stringPreferencesKey("vod_cache_size_mode")
        val parallelConnectionsKey = booleanPreferencesKey("use_parallel_connections")
        val migrationDoneKey = booleanPreferencesKey("migration_safe_playback_defaults_done")
        val prefs = mutablePreferencesOf(
            dv81Key to true,
            hevcBaseLayerKey to false,
            vodCacheModeKey to VodCacheSizeMode.ON.name,
            parallelConnectionsKey to true,
            migrationDoneKey to true
        )

        applyPlayerSettingsMigrations(prefs)

        assertEquals(true, prefs[dv81Key])
        assertEquals(false, prefs[hevcBaseLayerKey])
        assertEquals(VodCacheSizeMode.ON.name, prefs[vodCacheModeKey])
        assertEquals(true, prefs[parallelConnectionsKey])
    }

    @Test
    fun `safe playback migration applies independently per profile settings store`() = runTest {
        val activeProfileId = MutableStateFlow(1)
        val dataStore = playerSettingsDataStoreForTest(activeProfileId)

        dataStore.setExperimentalDv7HevcBaseLayerEnabled(false)
        dataStore.setExperimentalDv7ToDv81Enabled(true)
        dataStore.setVodCacheSizeMode(VodCacheSizeMode.ON)
        dataStore.setUseParallelConnections(true)

        activeProfileId.value = 2
        dataStore.setExperimentalDv7HevcBaseLayerEnabled(false)
        dataStore.setExperimentalDv7ToDv81Enabled(true)
        dataStore.setVodCacheSizeMode(VodCacheSizeMode.ON)
        dataStore.setUseParallelConnections(true)

        activeProfileId.value = 1
        val defaultProfileSettings = dataStore.playerSettings.first()

        activeProfileId.value = 2
        val secondaryProfileSettings = dataStore.playerSettings.first()

        assertEquals(false, defaultProfileSettings.experimentalDv7ToDv81Enabled)
        assertEquals(true, defaultProfileSettings.experimentalDv7HevcBaseLayerEnabled)
        assertEquals(VodCacheSizeMode.OFF, defaultProfileSettings.vodCacheSizeMode)
        assertEquals(false, defaultProfileSettings.useParallelConnections)

        assertEquals(false, secondaryProfileSettings.experimentalDv7ToDv81Enabled)
        assertEquals(true, secondaryProfileSettings.experimentalDv7HevcBaseLayerEnabled)
        assertEquals(VodCacheSizeMode.OFF, secondaryProfileSettings.vodCacheSizeMode)
        assertEquals(false, secondaryProfileSettings.useParallelConnections)
    }

    @Test
    fun `enabling dv7 hevc base layer disables dv81 conversion and preserve mapping`() = runTest {
        val dataStore = playerSettingsDataStoreForTest()

        dataStore.setExperimentalDv7ToDv81PreserveMappingEnabled(true)
        dataStore.setExperimentalDv7HevcBaseLayerEnabled(true)

        val settings = dataStore.playerSettings.first()
        assertEquals(true, settings.experimentalDv7HevcBaseLayerEnabled)
        assertEquals(false, settings.experimentalDv7ToDv81Enabled)
        assertEquals(false, settings.experimentalDv7ToDv81PreserveMappingEnabled)
    }

    @Test
    fun `enabling dv81 conversion disables dv7 hevc base layer`() = runTest {
        val dataStore = playerSettingsDataStoreForTest()

        dataStore.playerSettings.first()
        dataStore.setExperimentalDv7HevcBaseLayerEnabled(true)
        dataStore.setExperimentalDv7ToDv81Enabled(true)

        val settings = dataStore.playerSettings.first()
        assertEquals(true, settings.experimentalDv7ToDv81Enabled)
        assertEquals(false, settings.experimentalDv7HevcBaseLayerEnabled)
    }

    @Test
    fun `autoplay defaults to deterministic manual cap`() = runTest {
        val dataStore = playerSettingsDataStoreForTest()

        val settings = dataStore.playerSettings.first()

        assertEquals(true, settings.deterministicAutoplayEnabled)
        assertEquals(40.0, settings.manualBitrateLimitMbps, 0.0)
    }

    @Test
    fun `autoplay defaults migration maps legacy auto benchmark mode to manual cap`() {
        val deterministicAutoplayEnabledKey = booleanPreferencesKey("deterministic_autoplay_enabled")
        val autoplayBandwidthModeKey = stringPreferencesKey("autoplay_bandwidth_mode")
        val manualBitrateLimitMbpsKey = doublePreferencesKey("manual_bitrate_limit_mbps")
        val migrationDoneKey = booleanPreferencesKey("migration_autoplay_manual_defaults_done")
        val prefs = mutablePreferencesOf(
            deterministicAutoplayEnabledKey to false,
            autoplayBandwidthModeKey to "AUTO",
            manualBitrateLimitMbpsKey to 20.0
        )

        applyPlayerSettingsMigrations(prefs)

        assertEquals(true, prefs[deterministicAutoplayEnabledKey])
        assertEquals("MANUAL", prefs[autoplayBandwidthModeKey])
        assertEquals(40.0, prefs[manualBitrateLimitMbpsKey] ?: -1.0, 0.0)
        assertEquals(true, prefs[migrationDoneKey])
    }

    @Test
    fun `manual bitrate limit is coerced to supported range`() = runTest {
        val dataStore = playerSettingsDataStoreForTest()

        dataStore.playerSettings.first()

        dataStore.setManualBitrateLimitMbps(2.0)
        assertEquals(5.0, dataStore.playerSettings.first().manualBitrateLimitMbps, 0.0)

        dataStore.setManualBitrateLimitMbps(205.0)
        assertEquals(200.0, dataStore.playerSettings.first().manualBitrateLimitMbps, 0.0)

        dataStore.setManualBitrateLimitMbps(Double.NaN)
        assertEquals(40.0, dataStore.playerSettings.first().manualBitrateLimitMbps, 0.0)
    }

    @Test
    fun `setting manual bitrate persists autoplay cap`() = runTest {
        val dataStore = playerSettingsDataStoreForTest()

        dataStore.playerSettings.first()
        dataStore.setManualBitrateLimitMbps(85.0)

        assertEquals(85.0, dataStore.playerSettings.first().manualBitrateLimitMbps, 0.0)
    }

    @Test
    fun `vod cache warm ahead defaults to enabled`() = runTest {
        val dataStore = playerSettingsDataStoreForTest()

        val settings = dataStore.playerSettings.first()

        assertTrue(settings.vodCacheWarmAheadEnabled)
    }

    @Test
    fun `vod cache warm ahead persists disabled selection`() = runTest {
        val dataStore = playerSettingsDataStoreForTest()

        dataStore.setVodCacheWarmAheadEnabled(false)
        assertEquals(false, dataStore.playerSettings.first().vodCacheWarmAheadEnabled)

        dataStore.setVodCacheWarmAheadEnabled(true)
        assertEquals(true, dataStore.playerSettings.first().vodCacheWarmAheadEnabled)
    }

}
