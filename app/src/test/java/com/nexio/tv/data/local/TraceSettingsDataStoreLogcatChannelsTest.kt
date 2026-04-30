package com.nexio.tv.data.local

import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class TraceSettingsDataStoreLogcatChannelsTest {

    private val context = ApplicationProvider.getApplicationContext<android.content.Context>()
    private val store = TraceSettingsDataStore(context)

    @Before
    fun reset() = runTest {
        store.setFirstPaintLogcatEnabled(false)
        store.setMetaRouteLogcatEnabled(false)
        store.setIntRuntimeLogcatEnabled(false)
    }

    @Test
    fun `firstPaintLogcatEnabled defaults to false`() = runTest {
        assertFalse(store.firstPaintLogcatEnabled.first())
    }

    @Test
    fun `metaRouteLogcatEnabled defaults to false`() = runTest {
        assertFalse(store.metaRouteLogcatEnabled.first())
    }

    @Test
    fun `intRuntimeLogcatEnabled defaults to false`() = runTest {
        assertFalse(store.intRuntimeLogcatEnabled.first())
    }

    @Test
    fun `setFirstPaintLogcatEnabled persists value`() = runTest {
        store.setFirstPaintLogcatEnabled(true)
        assertTrue(store.firstPaintLogcatEnabled.first())
        store.setFirstPaintLogcatEnabled(false)
        assertFalse(store.firstPaintLogcatEnabled.first())
    }

    @Test
    fun `setMetaRouteLogcatEnabled persists value`() = runTest {
        store.setMetaRouteLogcatEnabled(true)
        assertTrue(store.metaRouteLogcatEnabled.first())
    }

    @Test
    fun `setIntRuntimeLogcatEnabled persists value`() = runTest {
        store.setIntRuntimeLogcatEnabled(true)
        assertTrue(store.intRuntimeLogcatEnabled.first())
    }

    @Test
    fun `mode flow is unaffected by channel toggles`() = runTest {
        store.setMode(com.nexio.tv.core.trace.TraceMode.SAFE_METADATA_RUNTIME)
        store.setFirstPaintLogcatEnabled(true)
        assertEquals(com.nexio.tv.core.trace.TraceMode.SAFE_METADATA_RUNTIME, store.mode.first())
    }
}
