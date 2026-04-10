package com.nexio.tv.instrumentation

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.emptyPreferences
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PlaybackTraceToggleTest {

    @Test
    fun `setEnabled updates in-memory tracer flag before datastore write`() = runTest {
        PlaybackTracer.enabled = false
        try {
            val dataStore = RecordingPreferencesDataStore()
            val toggle = PlaybackTraceToggle(dataStore)

            toggle.setEnabled(true)

            assertEquals(true, dataStore.enabledAtUpdateStart)
            assertTrue(dataStore.currentPreferences[RecordingPreferencesDataStore.key] == true)
            assertEquals(true, PlaybackTracer.enabled)
        } finally {
            PlaybackTracer.enabled = false
        }
    }

    private class RecordingPreferencesDataStore : DataStore<Preferences> {
        companion object {
            val key = booleanPreferencesKey("playback_trace_enabled")
        }

        private val backingFlow = MutableStateFlow(emptyPreferences())
        var enabledAtUpdateStart: Boolean? = null
        val currentPreferences: Preferences
            get() = backingFlow.value

        override val data: Flow<Preferences> = backingFlow

        override suspend fun updateData(transform: suspend (t: Preferences) -> Preferences): Preferences {
            enabledAtUpdateStart = PlaybackTracer.enabled
            val updated = transform(backingFlow.value)
            backingFlow.value = updated
            return updated
        }
    }
}
