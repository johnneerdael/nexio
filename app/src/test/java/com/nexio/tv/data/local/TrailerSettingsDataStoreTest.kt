package com.nexio.tv.data.local

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import java.io.File
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

@OptIn(ExperimentalCoroutinesApi::class)
class TrailerSettingsDataStoreTest {

    @get:Rule
    val temp = TemporaryFolder()

    private val dispatcher = UnconfinedTestDispatcher()
    private val scope = TestScope(dispatcher + Job())

    @After
    fun tearDown() {
        scope.cancel()
    }

    @Test
    fun `default max quality is 1080p`() = runTest(dispatcher) {
        val store = createStore("default")

        val settings = store.settings.first()

        assertEquals(TrailerMaxQuality.P1080, settings.maxQuality)
        assertEquals(1080, settings.maxQuality.maxHeight)
    }

    @Test
    fun `max quality round trips all supported values`() = runTest(dispatcher) {
        val store = createStore("roundtrip")

        store.setMaxQuality(TrailerMaxQuality.P720)
        assertEquals(TrailerMaxQuality.P720, store.settings.first().maxQuality)

        store.setMaxQuality(TrailerMaxQuality.P1080)
        assertEquals(TrailerMaxQuality.P1080, store.settings.first().maxQuality)

        store.setMaxQuality(TrailerMaxQuality.P2160)
        assertEquals(TrailerMaxQuality.P2160, store.settings.first().maxQuality)
    }

    @Test
    fun `invalid persisted max quality falls back to 1080p`() = runTest(dispatcher) {
        val dataStore = createPreferencesDataStore("invalid")
        dataStore.edit { prefs ->
            prefs[stringPreferencesKey("trailer_max_quality")] = "not-a-quality"
        }
        val store = TrailerSettingsDataStore(dataStore)

        assertEquals(TrailerMaxQuality.P1080, store.settings.first().maxQuality)
    }

    private fun createStore(name: String): TrailerSettingsDataStore =
        TrailerSettingsDataStore(createPreferencesDataStore(name))

    private fun createPreferencesDataStore(name: String): DataStore<Preferences> =
        PreferenceDataStoreFactory.create(scope = scope) {
            File(temp.root, "$name.preferences_pb")
        }
}
