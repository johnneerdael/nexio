package com.nexio.tv.core.profile

import android.app.Application
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.test.core.app.ApplicationProvider
import com.google.gson.Gson
import com.nexio.tv.core.sync.profilePrefsName
import com.nexio.tv.data.local.ProfileDataStoreFactory
import com.nexio.tv.data.local.ProfileDataStoreImpl
import com.nexio.tv.domain.model.UserProfile
import io.github.jan.supabase.postgrest.Postgrest
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File

@RunWith(RobolectricTestRunner::class)
class ProfileManagerTest {

    private lateinit var context: Application
    private lateinit var factory: ProfileDataStoreFactory
    private val spStoreBaseNames = listOf(
        "trakt_library_snapshot",
        "continue_watching_snapshot",
        "simkl_library_snapshot",
        "simkl_discovery_snapshot_v2",
        "simkl_progress_sync_state",
        "trakt_mutation_outbox",
        "trakt_discovery_snapshot"
    )

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        factory = ProfileDataStoreFactory(context)
        context.getSharedPreferences("profile_cleanup_state", Application.MODE_PRIVATE)
            .edit().clear().commit()
        spStoreBaseNames.forEach { baseName ->
            listOf(1, 2, 3, 4).forEach { profileId ->
                val prefsName = profilePrefsName(baseName, profileId)
                context.getSharedPreferences(prefsName, Application.MODE_PRIVATE)
                    .edit().clear().commit()
                sharedPreferencesFile(prefsName).delete()
            }
        }
    }

    private fun createDataStore(scope: CoroutineScope): DataStore<Preferences> {
        val tempFile = File.createTempFile("profile_manager_test", ".preferences_pb")
        tempFile.deleteOnExit()
        return PreferenceDataStoreFactory.create(
            scope = scope,
            produceFile = { tempFile }
        )
    }

    private data class ManagerHarness(
        val manager: ProfileManager,
        val dataStore: ProfileDataStoreImpl
    )

    /**
     * Creates a ProfileManager whose DataStore and StateFlow coroutines run on the test's
     * virtual-time scheduler (backgroundScope), so all emissions are deterministic.
     */
    private fun TestScope.makeManagerHarness(postgrest: Postgrest? = null): ManagerHarness {
        val dataStoreImpl = ProfileDataStoreImpl(createDataStore(backgroundScope), Gson())
        val manager = ProfileManager(
            dataStore = dataStoreImpl,
            factory = factory,
            context = context,
            scope = backgroundScope,
            postgrest = postgrest
        )
        return ManagerHarness(manager, dataStoreImpl)
    }

    private fun TestScope.makeManager(postgrest: Postgrest? = null): ProfileManager =
        makeManagerHarness(postgrest).manager

    private fun sharedPreferencesFile(prefsName: String): File {
        return File(context.applicationInfo.dataDir, "shared_prefs/${prefsName}.xml")
    }

    private fun writeProfileSharedPreferences(profileId: Int) {
        spStoreBaseNames.forEach { baseName ->
            val prefsName = profilePrefsName(baseName, profileId)
            context.getSharedPreferences(prefsName, Application.MODE_PRIVATE)
                .edit()
                .putString("sentinel", "profile-$profileId")
                .commit()
        }
    }

    @Test
    fun `profiles StateFlow initial value contains profile 1 named Default`() = runTest {
        val manager = makeManager()
        val profiles = manager.profiles.value
        assertTrue(profiles.any { it.id == 1 && it.name == "Default" })
    }

    @Test
    fun `activeProfileId StateFlow initial value is 1`() = runTest {
        val manager = makeManager()
        assertEquals(1, manager.activeProfileId.value)
    }

    @Test
    fun `createProfile returns true and profiles list grows by 1`() = runTest {
        val manager = makeManager()
        val initialSize = manager.profiles.value.size
        val result = manager.createProfile("Alice", "#E53935")
        assertTrue(result)
        val profiles = manager.profiles.first { it.size > initialSize }
        assertEquals(initialSize + 1, profiles.size)
    }

    @Test
    fun `created profile gets next available ID from 2 to 4 range`() = runTest {
        val manager = makeManager()
        manager.createProfile("Alice", "#E53935")
        val profiles = manager.profiles.first { it.size >= 2 }
        val alice = profiles.first { it.name == "Alice" }
        assertTrue(alice.id in 2..4)
    }

    @Test
    fun `createProfile when 4 profiles exist returns false`() = runTest {
        val manager = makeManager()
        manager.createProfile("Alice", "#E53935")
        manager.createProfile("Bob", "#8E24AA")
        manager.createProfile("Charlie", "#43A047")
        // createProfile now reads directly from DataStore (not StateFlow cache),
        // so no need to wait for StateFlow to update — the 4th call must return false immediately
        val result = manager.createProfile("Dave", "#FFB300")
        assertFalse(result)
    }

    @Test
    fun `createProfile with empty name defaults to Profile nextId`() = runTest {
        val manager = makeManager()
        manager.createProfile("", "#E53935")
        val profiles = manager.profiles.first { it.size >= 2 }
        val newProfile = profiles.first { it.id != 1 }
        assertEquals("Profile ${newProfile.id}", newProfile.name)
    }

    @Test
    fun `after deleting profile 2 then creating again new profile reuses ID 2`() = runTest {
        val manager = makeManager()
        manager.createProfile("Alice", "#E53935")
        val profilesAfterCreate = manager.profiles.first { it.size == 2 }
        val aliceId = profilesAfterCreate.first { it.name == "Alice" }.id
        assertEquals(2, aliceId)

        manager.deleteProfile(aliceId)
        manager.profiles.first { it.size == 1 }

        manager.createProfile("Bob", "#8E24AA")
        val profilesAfterRecreate = manager.profiles.first { it.size == 2 }
        val bob = profilesAfterRecreate.first { it.name == "Bob" }
        assertEquals(2, bob.id) // slot reuse
    }

    @Test
    fun `deleteProfile profile 1 returns false`() = runTest {
        val manager = makeManager()
        val result = manager.deleteProfile(1)
        assertFalse(result)
    }

    @Test
    fun `deleteProfile does not delete SharedPreferences for profile 1`() = runTest {
        val manager = makeManager()
        writeProfileSharedPreferences(1)

        val result = manager.deleteProfile(1)

        assertFalse(result)
        spStoreBaseNames.forEach { baseName ->
            val prefsName = profilePrefsName(baseName, 1)
            val prefs = context.getSharedPreferences(prefsName, Application.MODE_PRIVATE)
            assertEquals("profile-1", prefs.getString("sentinel", null))
        }
    }

    @Test
    fun `deleteProfile non-existent id returns false`() = runTest {
        val manager = makeManager()
        val result = manager.deleteProfile(99)
        assertFalse(result)
    }

    @Test
    fun `deleteProfile returns true and removes profile from list`() = runTest {
        val manager = makeManager()
        manager.createProfile("Alice", "#E53935")
        val profilesAfterCreate = manager.profiles.first { it.size == 2 }
        val aliceId = profilesAfterCreate.first { it.name == "Alice" }.id

        val result = manager.deleteProfile(aliceId, syncRemoteDelete = true)
        assertTrue(result)
        val profilesAfterDelete = manager.profiles.first { p -> p.none { it.id == aliceId } }
        assertFalse(profilesAfterDelete.any { it.id == aliceId })
    }

    @Test
    fun `deleteProfile clears SharedPreferences files for non-primary profile`() = runTest {
        val manager = makeManager()
        manager.createProfile("Alice", "#E53935")
        val profilesAfterCreate = manager.profiles.first { it.size == 2 }
        val aliceId = profilesAfterCreate.first { it.name == "Alice" }.id
        writeProfileSharedPreferences(aliceId)

        val result = manager.deleteProfile(aliceId, syncRemoteDelete = true)

        assertTrue(result)
        spStoreBaseNames.forEach { baseName ->
            val prefsName = profilePrefsName(baseName, aliceId)
            val prefs = context.getSharedPreferences(prefsName, Application.MODE_PRIVATE)
            assertEquals(null, prefs.getString("sentinel", null))
            assertFalse(sharedPreferencesFile(prefsName).exists())
        }
    }

    @Test
    fun `deleteProfile with remote failure still completes local deletion`() = runTest {
        val postgrest = mockk<Postgrest>()
        coEvery { postgrest.rpc(any<String>(), any<JsonObject>()) } throws RuntimeException("remote down")
        val manager = makeManager(postgrest)
        manager.createProfile("Alice", "#E53935")
        val profilesAfterCreate = manager.profiles.first { it.size == 2 }
        val aliceId = profilesAfterCreate.first { it.name == "Alice" }.id
        writeProfileSharedPreferences(aliceId)

        val result = manager.deleteProfile(aliceId, syncRemoteDelete = true)

        assertTrue(result)
        val profilesAfterDelete = manager.profiles.first { p -> p.none { it.id == aliceId } }
        assertFalse(profilesAfterDelete.any { it.id == aliceId })
        spStoreBaseNames.forEach { baseName ->
            val prefsName = profilePrefsName(baseName, aliceId)
            assertFalse(sharedPreferencesFile(prefsName).exists())
        }
        val pending = context.getSharedPreferences("profile_cleanup_state", Application.MODE_PRIVATE)
            .getStringSet("pending_remote_cleanup", emptySet())
        assertTrue(pending?.contains(aliceId.toString()) == true)
    }

    @Test
    fun `deleteProfile skips remote cleanup when remote sync is gated off but still deletes locally`() = runTest {
        val postgrest = mockk<Postgrest>(relaxed = true)
        val manager = makeManager(postgrest)
        manager.createProfile("Alice", "#E53935")
        val profilesAfterCreate = manager.profiles.first { it.size == 2 }
        val aliceId = profilesAfterCreate.first { it.name == "Alice" }.id
        writeProfileSharedPreferences(aliceId)

        val result = manager.deleteProfile(aliceId, syncRemoteDelete = false)

        assertTrue(result)
        val profilesAfterDelete = manager.profiles.first { p -> p.none { it.id == aliceId } }
        assertFalse(profilesAfterDelete.any { it.id == aliceId })
        spStoreBaseNames.forEach { baseName ->
            val prefsName = profilePrefsName(baseName, aliceId)
            assertFalse(sharedPreferencesFile(prefsName).exists())
        }
        coVerify(exactly = 0) { postgrest.rpc(any<String>(), any<JsonObject>()) }
        val pending = context.getSharedPreferences("profile_cleanup_state", Application.MODE_PRIVATE)
            .getStringSet("pending_remote_cleanup", emptySet())
        assertTrue(pending.isNullOrEmpty())
    }

    @Test
    fun `deleteProfile of active profile resets activeProfileId to 1`() = runTest {
        val manager = makeManager()
        manager.createProfile("Alice", "#E53935")
        val profilesAfterCreate = manager.profiles.first { it.size == 2 }
        val aliceId = profilesAfterCreate.first { it.name == "Alice" }.id

        manager.setActiveProfile(aliceId)
        manager.activeProfileId.first { it == aliceId }

        manager.deleteProfile(aliceId)
        val activeId = manager.activeProfileId.first { it == 1 }
        assertEquals(1, activeId)
    }

    @Test
    fun `updateProfile changes name and avatarColorHex for existing profile`() = runTest {
        val manager = makeManager()
        manager.createProfile("Alice", "#E53935")
        val profilesAfterCreate = manager.profiles.first { it.size == 2 }
        val alice = profilesAfterCreate.first { it.name == "Alice" }

        val updated = alice.copy(name = "Alice Updated", avatarColorHex = "#43A047")
        val result = manager.updateProfile(updated)
        assertTrue(result)

        val profilesAfterUpdate = manager.profiles.first { p -> p.any { it.name == "Alice Updated" } }
        val updatedProfile = profilesAfterUpdate.first { it.id == alice.id }
        assertEquals("Alice Updated", updatedProfile.name)
        assertEquals("#43A047", updatedProfile.avatarColorHex)
    }

    @Test
    fun `ProfileManager profiles emits avatarUrl preserved by replaceAllProfiles`() = runTest {
        val harness = makeManagerHarness()
        val avatarUrl = "https://example.test/profile-avatars/user/2.jpg?t=123"
        harness.dataStore.replaceAllProfiles(
            listOf(
                UserProfile(id = 1, name = "Default", avatarColorHex = "#1E88E5"),
                UserProfile(
                    id = 2,
                    name = "Alice",
                    avatarColorHex = "#E53935",
                    avatarUrl = avatarUrl
                )
            )
        )

        val profiles = harness.manager.profiles.first { emitted ->
            emitted.any { it.id == 2 && it.avatarUrl == avatarUrl }
        }

        assertEquals(avatarUrl, profiles.first { it.id == 2 }.avatarUrl)
    }

    @Test
    fun `updateProfile for non-existent profile returns false`() = runTest {
        val manager = makeManager()
        val nonExistent = UserProfile(id = 99, name = "Ghost", avatarColorHex = "#000000")
        val result = manager.updateProfile(nonExistent)
        assertFalse(result)
    }

    @Test
    fun `setActiveProfile changes activeProfileId`() = runTest {
        val manager = makeManager()
        manager.createProfile("Alice", "#E53935")
        val profilesAfterCreate = manager.profiles.first { it.size == 2 }
        val aliceId = profilesAfterCreate.first { it.name == "Alice" }.id

        manager.setActiveProfile(aliceId)
        val activeId = manager.activeProfileId.first { it == aliceId }
        assertEquals(aliceId, activeId)
    }

    @Test
    fun `setActiveProfile with non-existent id is a no-op`() = runTest {
        val manager = makeManager()
        manager.setActiveProfile(99)
        assertEquals(1, manager.activeProfileId.value)
    }

    @Test
    fun `activeProfile returns correct profile for active id`() = runTest {
        val manager = makeManager()
        manager.createProfile("Alice", "#E53935")
        val profilesAfterCreate = manager.profiles.first { it.size == 2 }
        val aliceId = profilesAfterCreate.first { it.name == "Alice" }.id

        manager.setActiveProfile(aliceId)
        manager.activeProfileId.first { it == aliceId }
        val active = manager.activeProfile
        assertNotNull(active)
        assertEquals(aliceId, active!!.id)
    }

    @Test
    fun `isPrimaryProfileActive returns true when profile 1 is active`() = runTest {
        val manager = makeManager()
        assertTrue(manager.isPrimaryProfileActive)
    }

    @Test
    fun `profileSwitched emits on setActiveProfile`() = runTest {
        val manager = makeManager()
        manager.createProfile("Alice", "#E53935")
        val profilesAfterCreate = manager.profiles.first { it.size == 2 }
        val aliceId = profilesAfterCreate.first { it.name == "Alice" }.id

        val emitted = async { manager.profileSwitched.first() }
        runCurrent()

        manager.setActiveProfile(aliceId)

        assertEquals(aliceId, emitted.await())
    }

    @Test
    fun `setActiveProfile on already active profile does not emit duplicate switch`() = runTest {
        val manager = makeManager()
        manager.createProfile("Alice", "#E53935")
        val profilesAfterCreate = manager.profiles.first { it.size == 2 }
        val aliceId = profilesAfterCreate.first { it.name == "Alice" }.id

        val emitted = mutableListOf<Int>()
        val job = launch { manager.profileSwitched.collect { emitted.add(it) } }
        runCurrent()

        manager.setActiveProfile(aliceId)
        runCurrent()
        manager.setActiveProfile(aliceId)
        runCurrent()

        assertEquals(listOf(aliceId), emitted)
        job.cancel()
    }
}
