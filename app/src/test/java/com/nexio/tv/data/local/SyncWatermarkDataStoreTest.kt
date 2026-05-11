package com.nexio.tv.data.local

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.nexio.tv.core.sync.SyncWatermarkSurface
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE)
class SyncWatermarkDataStoreTest {

    private fun newStore(): SyncWatermarkDataStore =
        SyncWatermarkDataStore(ApplicationProvider.getApplicationContext<Context>())

    @Test
    fun `unknown surface returns zero`() = runTest {
        val store = newStore()
        assertEquals(0L, store.get(SyncWatermarkSurface.ACCOUNT_ADDONS, profileId = null))
    }

    @Test
    fun `set then get returns persisted value`() = runTest {
        val store = newStore()
        store.set(SyncWatermarkSurface.ACCOUNT_ADDONS, profileId = null, ms = 1_700_000L)
        assertEquals(1_700_000L, store.get(SyncWatermarkSurface.ACCOUNT_ADDONS, profileId = null))
    }

    @Test
    fun `profile-scoped watermarks isolate by profile`() = runTest {
        val store = newStore()
        store.set(SyncWatermarkSurface.PROFILE_SETTINGS, profileId = 1, ms = 100L)
        store.set(SyncWatermarkSurface.PROFILE_SETTINGS, profileId = 2, ms = 200L)
        assertEquals(100L, store.get(SyncWatermarkSurface.PROFILE_SETTINGS, profileId = 1))
        assertEquals(200L, store.get(SyncWatermarkSurface.PROFILE_SETTINGS, profileId = 2))
    }

    @Test
    fun `surfaces are independent`() = runTest {
        val store = newStore()
        store.set(SyncWatermarkSurface.ACCOUNT_ADDONS, profileId = null, ms = 111L)
        store.set(SyncWatermarkSurface.ACCOUNT_SECRETS, profileId = null, ms = 222L)
        assertEquals(111L, store.get(SyncWatermarkSurface.ACCOUNT_ADDONS, profileId = null))
        assertEquals(222L, store.get(SyncWatermarkSurface.ACCOUNT_SECRETS, profileId = null))
    }

    @Test
    fun `clearAll wipes every watermark`() = runTest {
        val store = newStore()
        store.set(SyncWatermarkSurface.ACCOUNT_ADDONS, profileId = null, ms = 1L)
        store.set(SyncWatermarkSurface.PROFILE_SETTINGS, profileId = 1, ms = 2L)
        store.clearAll()
        assertEquals(0L, store.get(SyncWatermarkSurface.ACCOUNT_ADDONS, profileId = null))
        assertEquals(0L, store.get(SyncWatermarkSurface.PROFILE_SETTINGS, profileId = 1))
    }
}
