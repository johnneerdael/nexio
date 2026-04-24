package com.nexio.tv.data.local

import android.app.Application
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.test.core.app.ApplicationProvider
import com.google.gson.Gson
import com.nexio.tv.core.profile.ProfileManager
import com.nexio.tv.data.repository.KitsuAuthSnapshot
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File

@RunWith(RobolectricTestRunner::class)
class KitsuAuthDataStoreTest {

    private lateinit var context: Application
    private lateinit var factory: ProfileDataStoreFactory

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        factory = ProfileDataStoreFactory(context)
    }

    private fun createDataStore(scope: CoroutineScope): DataStore<Preferences> {
        val tempFile = File.createTempFile("kitsu_auth_profile_test", ".preferences_pb")
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

    private fun makeAuthStore(manager: ProfileManager): KitsuAuthDataStore =
        KitsuAuthDataStore(factory, manager)

    @Test
    fun `clearAuth clears requested profile without depending on active profile`() = runTest {
        val profileManager = makeManager()
        val authStore = makeAuthStore(profileManager)

        authStore.saveForProfile(
            profileId = 1,
            snapshot = KitsuAuthSnapshot(
                enabled = true,
                username = "default-user",
                accessToken = "default-token",
                refreshToken = "default-refresh",
                expiresAtEpochSeconds = 1234L,
                includeNsfw = false
            )
        )
        authStore.saveForProfile(
            profileId = 2,
            snapshot = KitsuAuthSnapshot(
                enabled = true,
                username = "secondary-user",
                accessToken = "secondary-token",
                refreshToken = "secondary-refresh",
                expiresAtEpochSeconds = 5678L,
                includeNsfw = true
            )
        )
        profileManager.setActiveProfile(2)

        authStore.clearAuth(profileId = 1)

        val profile1 = authStore.stateForProfile(1).first()
        val profile2 = authStore.stateForProfile(2).first()
        assertFalse(profile1.isAuthenticated)
        assertNull(profile1.username)
        assertFalse(profile1.includeNsfw)
        assertTrue(profile2.isAuthenticated)
        assertEquals("secondary-user", profile2.username)
        assertTrue(profile2.includeNsfw)
    }
}
