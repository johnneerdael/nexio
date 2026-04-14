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
class SimklSettingsDataStoreProfileTest {

    private lateinit var context: Application
    private lateinit var factory: ProfileDataStoreFactory

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        factory = ProfileDataStoreFactory(context)
    }

    private fun createDataStore(scope: CoroutineScope): DataStore<Preferences> {
        val tempFile = File.createTempFile("simkl_settings_profile_test", ".preferences_pb")
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

    private fun TestScope.makeSettingsStore(manager: ProfileManager): SimklSettingsDataStore =
        SimklSettingsDataStore(factory, manager)

    @Test
    fun `catalogPreferences isolated per profile`() = runTest {
        val manager = makeManager()
        val settingsStore = makeSettingsStore(manager)

        // Enable TV_TRENDING_TODAY on profile 1
        settingsStore.setCatalogEnabled(SimklCatalogIds.TV_TRENDING_TODAY, true)
        val p1Prefs = settingsStore.catalogPreferences.first()
        assertTrue(
            "Profile 1 should have TV_TRENDING_TODAY enabled",
            SimklCatalogIds.TV_TRENDING_TODAY in p1Prefs.enabledCatalogs
        )

        // Create and switch to profile 2
        manager.createProfile("Alice", "#E53935")
        val aliceId = manager.profiles.first { it.size >= 2 }.first { it.id != 1 }.id
        manager.setActiveProfile(aliceId)
        manager.activeProfileId.first { it == aliceId }

        // Profile 2 should have default (empty) catalog preferences
        val p2Prefs = settingsStore.catalogPreferences.first()
        assertFalse(
            "Profile 2 should have default empty catalogs",
            SimklCatalogIds.TV_TRENDING_TODAY in p2Prefs.enabledCatalogs
        )

        // Enable different catalog on profile 2
        settingsStore.setCatalogEnabled(SimklCatalogIds.MOVIE_TRENDING_TODAY, true)
        val p2PrefsAfter = settingsStore.catalogPreferences.first { SimklCatalogIds.MOVIE_TRENDING_TODAY in it.enabledCatalogs }
        assertTrue(
            "Profile 2 should have MOVIE_TRENDING_TODAY enabled",
            SimklCatalogIds.MOVIE_TRENDING_TODAY in p2PrefsAfter.enabledCatalogs
        )
        assertFalse(
            "Profile 2 should NOT have TV_TRENDING_TODAY enabled",
            SimklCatalogIds.TV_TRENDING_TODAY in p2PrefsAfter.enabledCatalogs
        )

        // Switch back to profile 1 — original prefs must be unchanged
        manager.setActiveProfile(1)
        manager.activeProfileId.first { it == 1 }
        val p1PrefsAgain = settingsStore.catalogPreferences.first()
        assertTrue(
            "Profile 1 TV_TRENDING_TODAY should still be enabled",
            SimklCatalogIds.TV_TRENDING_TODAY in p1PrefsAgain.enabledCatalogs
        )
        assertFalse(
            "Profile 1 MOVIE_TRENDING_TODAY should not be enabled",
            SimklCatalogIds.MOVIE_TRENDING_TODAY in p1PrefsAgain.enabledCatalogs
        )
    }
}
