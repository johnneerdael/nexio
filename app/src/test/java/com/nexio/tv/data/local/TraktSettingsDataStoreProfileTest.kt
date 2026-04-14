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
    fun `catalogPreferences isolated per profile`() = runTest {
        val manager = makeManager()
        val settingsStore = makeSettingsStore(manager)

        // Set catalog prefs on profile 1 — disable UP_NEXT
        settingsStore.setCatalogEnabled(TraktCatalogIds.UP_NEXT, false)
        val p1Prefs = settingsStore.catalogPreferences.first()
        assertFalse("Profile 1 should have UP_NEXT disabled", TraktCatalogIds.UP_NEXT in p1Prefs.enabledCatalogs)

        // Create and switch to profile 2
        manager.createProfile("Bob", "#8E24AA")
        val bobId = manager.profiles.first { it.size >= 2 }.first { it.id != 1 }.id
        manager.setActiveProfile(bobId)
        manager.activeProfileId.first { it == bobId }

        // Profile 2 should have default prefs (UP_NEXT enabled by default)
        val p2Prefs = settingsStore.catalogPreferences.first()
        assertTrue("Profile 2 should have default UP_NEXT enabled", TraktCatalogIds.UP_NEXT in p2Prefs.enabledCatalogs)

        // Set different prefs on profile 2 — disable CALENDAR
        settingsStore.setCatalogEnabled(TraktCatalogIds.CALENDAR, false)
        val p2PrefsAfter = settingsStore.catalogPreferences.first { TraktCatalogIds.CALENDAR !in it.enabledCatalogs }
        assertFalse("Profile 2 should have CALENDAR disabled", TraktCatalogIds.CALENDAR in p2PrefsAfter.enabledCatalogs)

        // Switch back to profile 1 — original prefs must be unchanged
        manager.setActiveProfile(1)
        manager.activeProfileId.first { it == 1 }
        val p1PrefsAgain = settingsStore.catalogPreferences.first()
        assertFalse("Profile 1 UP_NEXT should still be disabled", TraktCatalogIds.UP_NEXT in p1PrefsAgain.enabledCatalogs)
        assertTrue("Profile 1 CALENDAR should still be enabled", TraktCatalogIds.CALENDAR in p1PrefsAgain.enabledCatalogs)
    }

    @Test
    fun `continueWatchingDaysCap isolated per profile`() = runTest {
        val manager = makeManager()
        val settingsStore = makeSettingsStore(manager)

        // Set days cap on profile 1
        settingsStore.setContinueWatchingDaysCap(30)
        val p1Cap = settingsStore.continueWatchingDaysCap.first { it == 30 }
        assertEquals(30, p1Cap)

        // Create and switch to profile 2
        manager.createProfile("Carol", "#43A047")
        val carolId = manager.profiles.first { it.size >= 2 }.first { it.id != 1 }.id
        manager.setActiveProfile(carolId)
        manager.activeProfileId.first { it == carolId }

        // Profile 2 should have default cap
        val p2Cap = settingsStore.continueWatchingDaysCap.first()
        assertEquals(
            "Profile 2 should have default days cap",
            TraktSettingsDataStore.DEFAULT_CONTINUE_WATCHING_DAYS_CAP,
            p2Cap
        )

        // Switch back to profile 1 — cap must still be 30
        manager.setActiveProfile(1)
        manager.activeProfileId.first { it == 1 }
        val p1CapAgain = settingsStore.continueWatchingDaysCap.first()
        assertEquals(30, p1CapAgain)
    }
}
