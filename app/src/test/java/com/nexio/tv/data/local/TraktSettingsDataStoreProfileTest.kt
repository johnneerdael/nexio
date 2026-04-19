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
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File

@RunWith(RobolectricTestRunner::class)
class TraktSettingsDataStoreProfileTest {

    private lateinit var context: Application
    private lateinit var factory: ProfileDataStoreFactory

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        factory = ProfileDataStoreFactory(context)
    }

    private fun createDataStore(scope: CoroutineScope): DataStore<Preferences> {
        val tempFile = File.createTempFile("profile_settings_test", ".preferences_pb")
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

    private fun TestScope.makeSettingsStore(manager: ProfileManager): TraktSettingsDataStore =
        TraktSettingsDataStore(factory, manager)

    @Test
    fun `catalogPreferences default to no enabled catalogs and stay isolated per profile`() = runTest {
        val manager = makeManager()
        val settingsStore = makeSettingsStore(manager)

        val p1Defaults = settingsStore.catalogPreferences.first()
        assertTrue("Profile 1 should start with no enabled Trakt catalogs", p1Defaults.enabledCatalogs.isEmpty())
        assertTrue("Built-in Trakt order should still be available for UI", p1Defaults.catalogOrder.contains(TraktCatalogIds.UP_NEXT))

        settingsStore.setCatalogEnabled(TraktCatalogIds.UP_NEXT, true)
        val p1Prefs = settingsStore.catalogPreferences.first { TraktCatalogIds.UP_NEXT in it.enabledCatalogs }
        assertTrue("Profile 1 should persist manual UP_NEXT enablement", TraktCatalogIds.UP_NEXT in p1Prefs.enabledCatalogs)

        // Create and switch to profile 2
        manager.createProfile("Bob", "#8E24AA")
        val bobId = manager.profiles.first { it.size >= 2 }.first { it.id != 1 }.id
        manager.setActiveProfile(bobId)
        manager.activeProfileId.first { it == bobId }

        val p2Defaults = settingsStore.catalogPreferences.first()
        assertTrue("Profile 2 should also start with no enabled Trakt catalogs", p2Defaults.enabledCatalogs.isEmpty())

        settingsStore.setCatalogEnabled(TraktCatalogIds.CALENDAR, true)
        val p2PrefsAfter = settingsStore.catalogPreferences.first { TraktCatalogIds.CALENDAR in it.enabledCatalogs }
        assertTrue("Profile 2 should persist manual CALENDAR enablement", TraktCatalogIds.CALENDAR in p2PrefsAfter.enabledCatalogs)

        // Switch back to profile 1 — original prefs must be unchanged
        manager.setActiveProfile(1)
        manager.activeProfileId.first { it == 1 }
        val p1PrefsAgain = settingsStore.catalogPreferences.first()
        assertTrue("Profile 1 UP_NEXT should still be enabled", TraktCatalogIds.UP_NEXT in p1PrefsAgain.enabledCatalogs)
        assertFalse("Profile 1 CALENDAR should not inherit profile 2 enablement", TraktCatalogIds.CALENDAR in p1PrefsAgain.enabledCatalogs)
    }

}
