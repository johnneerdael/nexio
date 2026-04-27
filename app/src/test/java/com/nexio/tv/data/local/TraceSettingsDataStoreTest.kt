package com.nexio.tv.data.local

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.nexio.tv.core.trace.TraceMode
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class TraceSettingsDataStoreTest {
    private val context = ApplicationProvider.getApplicationContext<Application>()

    @Before
    fun clear() = runTest {
        val store = TraceSettingsDataStore(context)
        store.setMode(TraceMode.OFF)
    }

    @Test
    fun `default mode is OFF`() = runTest {
        val store = TraceSettingsDataStore(context)
        assertEquals(TraceMode.OFF, store.mode.first())
    }

    @Test
    fun `setMode persists`() = runTest {
        val store = TraceSettingsDataStore(context)
        store.setMode(TraceMode.SAFE_METADATA_RUNTIME)
        assertEquals(TraceMode.SAFE_METADATA_RUNTIME, store.mode.first())
    }
}
