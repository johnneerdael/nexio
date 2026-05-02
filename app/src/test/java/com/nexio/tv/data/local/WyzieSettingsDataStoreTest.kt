package com.nexio.tv.data.local

import androidx.test.core.app.ApplicationProvider
import com.nexio.tv.domain.model.WyzieSettings
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class WyzieSettingsDataStoreTest {

    private val context = ApplicationProvider.getApplicationContext<android.content.Context>()

    @Before
    fun setUp() = runTest {
        // Clear all preferences before each test to ensure isolation
        val store = WyzieSettingsDataStore(context)
        store.setApiKey("")
        store.setEnabled(true)
    }

    @Test
    fun defaultsAreEnabledWithoutKey() = runTest {
        val store = WyzieSettingsDataStore(context)
        val s = store.settings.first()
        assertEquals(WyzieSettings(apiKey = null, enabled = true), s)
    }

    @Test
    fun setApiKeyTrimsWhitespace() = runTest {
        val store = WyzieSettingsDataStore(context)
        store.setApiKey("  wyzie-abc123xyz  ")
        assertEquals("wyzie-abc123xyz", store.settings.first().apiKey)
    }

    @Test
    fun setApiKeyToBlankClearsTheKey() = runTest {
        val store = WyzieSettingsDataStore(context)
        store.setApiKey("wyzie-abc")
        store.setApiKey("   ")
        // Blank reads back as null so isUsable is false.
        assertEquals(null, store.settings.first().apiKey)
    }

    @Test
    fun setEnabledRoundTrips() = runTest {
        val store = WyzieSettingsDataStore(context)
        store.setEnabled(false)
        assertEquals(false, store.settings.first().enabled)
        store.setEnabled(true)
        assertEquals(true, store.settings.first().enabled)
    }

    @Test
    fun isUsableRequiresKeyAndEnabled() = runTest {
        val store = WyzieSettingsDataStore(context)
        assertEquals(false, store.settings.first().isUsable)
        store.setApiKey("wyzie-abc")
        assertEquals(true, store.settings.first().isUsable)
        store.setEnabled(false)
        assertEquals(false, store.settings.first().isUsable)
    }
}
