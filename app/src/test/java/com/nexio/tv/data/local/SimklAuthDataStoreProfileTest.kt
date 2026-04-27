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
class SimklAuthDataStoreProfileTest {

    private lateinit var context: Application
    private lateinit var factory: ProfileDataStoreFactory

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        factory = ProfileDataStoreFactory(context)
    }

    private fun createDataStore(scope: CoroutineScope): DataStore<Preferences> {
        val tempFile = File.createTempFile("simkl_auth_profile_test", ".preferences_pb")
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

    private fun TestScope.makeAuthStore(manager: ProfileManager): SimklAuthDataStore =
        SimklAuthDataStore(factory, manager)

    @Test
    fun `profile1 and profile2 have isolated simkl tokens`() = runTest {
        val manager = makeManager()
        val authStore = makeAuthStore(manager)

        // Save access token on profile 1
        authStore.saveAccessToken("token_p1")
        val stateP1 = authStore.state.first()
        assertEquals("token_p1", stateP1.accessToken)

        // Create and switch to profile 2
        manager.createProfile("Alice", "#E53935")
        val aliceId = manager.profiles.first { it.size >= 2 }.first { it.id != 1 }.id
        manager.setActiveProfile(aliceId)
        manager.activeProfileId.first { it == aliceId }

        // Profile 2 should have no token
        val stateP2 = authStore.state.first()
        assertNull("Profile 2 should have no Simkl token", stateP2.accessToken)

        // Save different token on profile 2
        authStore.saveAccessToken("token_p2")
        val stateP2After = authStore.state.first { it.accessToken != null }
        assertEquals("token_p2", stateP2After.accessToken)

        // Switch back to profile 1 — original token must still be there
        manager.setActiveProfile(1)
        manager.activeProfileId.first { it == 1 }
        val stateP1Again = authStore.state.first()
        assertEquals("token_p1", stateP1Again.accessToken)
    }

    @Test
    fun `clearAuth only clears active simkl profile`() = runTest {
        val manager = makeManager()
        val authStore = makeAuthStore(manager)

        // Save token on profile 1
        authStore.saveAccessToken("token_p1")

        // Create and switch to profile 2, save token there
        manager.createProfile("Bob", "#8E24AA")
        val bobId = manager.profiles.first { it.size >= 2 }.first { it.id != 1 }.id
        manager.setActiveProfile(bobId)
        manager.activeProfileId.first { it == bobId }
        authStore.saveAccessToken("token_p2")

        // Clear auth on profile 2
        authStore.clearAuth()
        val stateP2 = authStore.state.first()
        assertNull("Profile 2 Simkl token should be cleared", stateP2.accessToken)

        // Switch back to profile 1 — token must still be present
        manager.setActiveProfile(1)
        manager.activeProfileId.first { it == 1 }
        val stateP1 = authStore.state.first()
        assertEquals("token_p1", stateP1.accessToken)
    }

    @Test
    fun `saving new token clears stale simkl account identity in same profile`() = runTest {
        val manager = makeManager()
        val authStore = makeAuthStore(manager)

        authStore.saveAccessToken("token_p1")
        authStore.saveUser(username = "old-user", accountId = 123L, accountType = "premium")

        authStore.saveAccessToken("token_p1_new", clearAccountIdentity = true)
        val state = authStore.state.first()

        assertEquals("token_p1_new", state.accessToken)
        assertNull(state.username)
        assertNull(state.accountId)
        assertNull(state.accountType)
    }

    @Test
    fun `refresh token save preserves simkl account identity in same profile`() = runTest {
        val manager = makeManager()
        val authStore = makeAuthStore(manager)

        authStore.saveAccessToken("token_p1")
        authStore.saveUser(username = "old-user", accountId = 123L, accountType = "premium")

        authStore.saveAccessToken("token_p1_refreshed")
        val state = authStore.state.first()

        assertEquals("token_p1_refreshed", state.accessToken)
        assertEquals("old-user", state.username)
        assertEquals(123L, state.accountId)
        assertEquals("premium", state.accountType)
    }
}
