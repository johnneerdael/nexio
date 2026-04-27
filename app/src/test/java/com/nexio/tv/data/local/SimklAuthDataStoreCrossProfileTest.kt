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
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File

@RunWith(RobolectricTestRunner::class)
class SimklAuthDataStoreCrossProfileTest {

    private lateinit var context: Application
    private lateinit var factory: ProfileDataStoreFactory

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        factory = ProfileDataStoreFactory(context)
    }

    private fun createDataStore(scope: CoroutineScope): DataStore<Preferences> {
        val tempFile = File.createTempFile("profile_ds_simkl_xprofile", ".preferences_pb")
        tempFile.deleteOnExit()
        return PreferenceDataStoreFactory.create(scope = scope, produceFile = { tempFile })
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

    @Test
    fun `profile2 never uses profile1 simkl token`() = runTest {
        val manager = makeManager()
        val authStore = SimklAuthDataStore(factory, manager)

        // Save profile-1 token
        authStore.saveAccessToken(accessToken = "p1-simkl-secret", profileId = 1)

        // Read profile 2 directly
        val p2State = authStore.stateForProfile(profileId = 2).first()
        assertNull("Profile 2 must not see profile 1 access token", p2State.accessToken)

        // Sanity: profile 1 still has its token
        val p1State = authStore.stateForProfile(profileId = 1).first()
        assertEquals("p1-simkl-secret", p1State.accessToken)
    }
}
