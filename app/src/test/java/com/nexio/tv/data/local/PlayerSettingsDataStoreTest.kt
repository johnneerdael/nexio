package com.nexio.tv.data.local

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class PlayerSettingsDataStoreTest {

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
