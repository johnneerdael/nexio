package com.nexio.tv.data.local

import android.app.Application
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.test.core.app.ApplicationProvider
import com.google.gson.Gson
import com.nexio.tv.core.profile.ProfileManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File

@RunWith(RobolectricTestRunner::class)
class MDBListSettingsDataStoreProfileTest {

    private lateinit var context: Application
    private lateinit var factory: ProfileDataStoreFactory

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        File(context.filesDir, "datastore")
            .listFiles()
            ?.filter { it.name.startsWith("mdblist_settings") }
            ?.forEach { it.delete() }
        factory = ProfileDataStoreFactory(context)
    }

    private fun createDataStore(scope: CoroutineScope): DataStore<Preferences> {
        val tempFile = File.createTempFile("mdblist_settings_profile_test", ".preferences_pb")
        tempFile.deleteOnExit()
        return PreferenceDataStoreFactory.create(
            scope = scope,
            produceFile = { tempFile }
        )
    }

    private fun TestScope.makeManager(): ProfileManager {
        val dataStoreImpl = ProfileDataStoreImpl(createDataStore(backgroundScope), Gson())
        return ProfileManager(
            dataStore = dataStoreImpl,
            factory = factory,
            context = context,
            scope = backgroundScope
        )
    }

    private fun makeSettingsStore(manager: ProfileManager): MDBListSettingsDataStore =
        MDBListSettingsDataStore(factory, manager)

    @Test
    fun `settings and catalog preferences stay isolated per profile`() = runTest {
        val manager = makeManager()
        val settingsStore = makeSettingsStore(manager)

        settingsStore.setEnabled(true)
        settingsStore.setApiKey("profile-one-key")
        settingsStore.setTopListSelected("top:alpha", true)
        settingsStore.setPersonalListEnabled("personal:hidden", false)

        val p1Settings = settingsStore.settings.first { it.apiKey == "profile-one-key" }
        assertTrue(p1Settings.enabled)
        assertEquals("profile-one-key", p1Settings.apiKey)
        assertTrue(settingsStore.catalogPreferences.first().isTopListSelected("top:alpha"))
        assertFalse(settingsStore.catalogPreferences.first().isPersonalListEnabled("personal:hidden"))

        manager.createProfile("Alice", "#E53935")
        val aliceId = manager.profiles.first { it.size >= 2 }.first { it.id != 1 }.id
        manager.setActiveProfile(aliceId)
        manager.activeProfileId.first { it == aliceId }

        val p2Defaults = settingsStore.settings.first()
        assertFalse("Profile 2 should not inherit MDBList enablement", p2Defaults.enabled)
        assertEquals("Profile 2 should not inherit MDBList API key", "", p2Defaults.apiKey)
        assertFalse(settingsStore.catalogPreferences.first().isTopListSelected("top:alpha"))
        assertTrue(settingsStore.catalogPreferences.first().isPersonalListEnabled("personal:hidden"))

        settingsStore.setEnabled(true)
        settingsStore.setApiKey("profile-two-key")
        settingsStore.setTopListSelected("top:beta", true)

        manager.setActiveProfile(1)
        manager.activeProfileId.first { it == 1 }

        val p1Again = settingsStore.settings.first()
        assertEquals("profile-one-key", p1Again.apiKey)
        assertTrue(settingsStore.catalogPreferences.first().isTopListSelected("top:alpha"))
        assertFalse(settingsStore.catalogPreferences.first().isTopListSelected("top:beta"))
    }
}
