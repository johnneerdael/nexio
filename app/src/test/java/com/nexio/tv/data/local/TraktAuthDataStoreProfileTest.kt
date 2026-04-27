package com.nexio.tv.data.local

import android.app.Application
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.test.core.app.ApplicationProvider
import com.google.gson.Gson
import com.nexio.tv.core.profile.ProfileManager
import com.nexio.tv.data.remote.dto.trakt.TraktTokenResponseDto
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
class TraktAuthDataStoreProfileTest {

    private lateinit var context: Application
    private lateinit var factory: ProfileDataStoreFactory

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        factory = ProfileDataStoreFactory(context)
    }

    private fun createDataStore(scope: CoroutineScope): DataStore<Preferences> {
        val tempFile = File.createTempFile("profile_ds_test", ".preferences_pb")
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

    private fun TestScope.makeAuthStore(manager: ProfileManager): TraktAuthDataStore =
        TraktAuthDataStore(factory, manager)

    private fun fakeToken(access: String, refresh: String = "refresh_$access") =
        TraktTokenResponseDto(
            accessToken = access,
            tokenType = "Bearer",
            expiresIn = 7776000,
            refreshToken = refresh,
            scope = "public",
            createdAt = 0L
        )

    @Test
    fun `profile1 and profile2 have isolated tokens`() = runTest {
        val manager = makeManager()
        val authStore = makeAuthStore(manager)

        // Start on profile 1, save a token
        authStore.saveToken(fakeToken("token_profile1"))
        val stateP1 = authStore.state.first()
        assertEquals("token_profile1", stateP1.accessToken)

        // Create and switch to profile 2
        manager.createProfile("Alice", "#E53935")
        val aliceId = manager.profiles.first { it.size >= 2 }.first { it.id != 1 }.id
        manager.setActiveProfile(aliceId)
        manager.activeProfileId.first { it == aliceId }

        // Profile 2 should have no token
        val stateP2 = authStore.state.first()
        assertNull("Profile 2 should have no token", stateP2.accessToken)

        // Save different token on profile 2
        authStore.saveToken(fakeToken("token_profile2"))
        val stateP2After = authStore.state.first { it.accessToken != null }
        assertEquals("token_profile2", stateP2After.accessToken)

        // Switch back to profile 1 — original token must still be there
        manager.setActiveProfile(1)
        manager.activeProfileId.first { it == 1 }
        val stateP1Again = authStore.state.first()
        assertEquals("token_profile1", stateP1Again.accessToken)
    }

    @Test
    fun `clearAuth only clears active profile`() = runTest {
        val manager = makeManager()
        val authStore = makeAuthStore(manager)

        // Save token on profile 1
        authStore.saveToken(fakeToken("token_profile1"))

        // Create and switch to profile 2, save token there
        manager.createProfile("Alice", "#E53935")
        val aliceId = manager.profiles.first { it.size >= 2 }.first { it.id != 1 }.id
        manager.setActiveProfile(aliceId)
        manager.activeProfileId.first { it == aliceId }
        authStore.saveToken(fakeToken("token_profile2"))

        // Clear auth on profile 2
        authStore.clearAuth()
        val stateP2 = authStore.state.first()
        assertNull("Profile 2 token should be cleared", stateP2.accessToken)

        // Switch back to profile 1 — token must still be present
        manager.setActiveProfile(1)
        manager.activeProfileId.first { it == 1 }
        val stateP1 = authStore.state.first()
        assertEquals("token_profile1", stateP1.accessToken)
    }

    @Test
    fun `saving new token clears stale account identity in same profile`() = runTest {
        val manager = makeManager()
        val authStore = makeAuthStore(manager)

        authStore.saveToken(fakeToken("token_profile1"))
        authStore.saveUser(username = "old-user", userSlug = "old-slug")

        authStore.saveToken(fakeToken("token_profile1_new"), clearAccountIdentity = true)
        val state = authStore.state.first()

        assertEquals("token_profile1_new", state.accessToken)
        assertNull(state.username)
        assertNull(state.userSlug)
    }

    @Test
    fun `refresh token save preserves account identity in same profile`() = runTest {
        val manager = makeManager()
        val authStore = makeAuthStore(manager)

        authStore.saveToken(fakeToken("token_profile1"))
        authStore.saveUser(username = "old-user", userSlug = "old-slug")

        authStore.saveToken(fakeToken("token_profile1_refreshed"))
        val state = authStore.state.first()

        assertEquals("token_profile1_refreshed", state.accessToken)
        assertEquals("old-user", state.username)
        assertEquals("old-slug", state.userSlug)
    }

    @Test
    fun `state flow reacts to profile switch`() = runTest {
        val manager = makeManager()
        val authStore = makeAuthStore(manager)

        // Save token on profile 1
        authStore.saveToken(fakeToken("token_profile1"))

        // Create profile 2 with no token
        manager.createProfile("Alice", "#E53935")
        val aliceId = manager.profiles.first { it.size >= 2 }.first { it.id != 1 }.id

        // Switch to profile 2 — state must change to null token
        manager.setActiveProfile(aliceId)
        val stateAfterSwitch = authStore.state.first { it.accessToken == null }
        assertNull(stateAfterSwitch.accessToken)

        // Switch back to profile 1 — state must reflect profile 1 token
        manager.setActiveProfile(1)
        val stateBackToP1 = authStore.state.first { it.accessToken == "token_profile1" }
        assertEquals("token_profile1", stateBackToP1.accessToken)
    }
}
