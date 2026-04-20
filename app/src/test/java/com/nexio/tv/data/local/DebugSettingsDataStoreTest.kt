package com.nexio.tv.data.local

import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class DebugSettingsDataStoreTest {

    @Test
    fun `dolby vision diagnostics defaults disabled and can be toggled`() = runTest {
        val store = DebugSettingsDataStore(context = ApplicationProvider.getApplicationContext())

        store.setDolbyVisionDiagnosticsEnabled(false)

        assertFalse(store.dolbyVisionDiagnosticsEnabled.first())

        store.setDolbyVisionDiagnosticsEnabled(true)

        assertTrue(store.dolbyVisionDiagnosticsEnabled.first())
    }

    @Test
    fun `auto translate diagnostics toggles default disabled and persist`() = runTest {
        val store = DebugSettingsDataStore(context = ApplicationProvider.getApplicationContext())

        store.setAutoTranslateDiagnosticsEnabled(false)
        store.setAutoTranslateUnsafeBodyLoggingEnabled(false)

        assertFalse(store.autoTranslateDiagnosticsEnabled.first())
        assertFalse(store.autoTranslateUnsafeBodyLoggingEnabled.first())

        store.setAutoTranslateDiagnosticsEnabled(true)
        store.setAutoTranslateUnsafeBodyLoggingEnabled(true)

        assertTrue(store.autoTranslateDiagnosticsEnabled.first())
        assertTrue(store.autoTranslateUnsafeBodyLoggingEnabled.first())
    }
}
