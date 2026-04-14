package com.nexio.tv.data.local

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import com.google.gson.Gson
import com.nexio.tv.domain.model.UserProfile
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File

@RunWith(RobolectricTestRunner::class)
class ProfileDataStoreTest {

    private fun createDataStore(scope: CoroutineScope): DataStore<Preferences> {
        val tempFile = File.createTempFile("profile_settings_test", ".preferences_pb")
        tempFile.deleteOnExit()
        return PreferenceDataStoreFactory.create(
            scope = scope,
            produceFile = { tempFile }
        )
    }

    private fun makeStore(scope: CoroutineScope): ProfileDataStoreImpl =
        ProfileDataStoreImpl(createDataStore(scope), Gson())

    @Test
    fun `profilesList emits default profile when DataStore is empty`() = runTest {
        val store = makeStore(backgroundScope)
        val profiles = store.profilesList.first()
        assertEquals(1, profiles.size)
        assertEquals(1, profiles[0].id)
        assertEquals("Default", profiles[0].name)
        assertEquals("#1E88E5", profiles[0].avatarColorHex)
    }

    @Test
    fun `activeProfileId emits 1 by default`() = runTest {
        val store = makeStore(backgroundScope)
        val activeId = store.activeProfileId.first()
        assertEquals(1, activeId)
    }

    @Test
    fun `upsertProfile adds a new profile to the list`() = runTest {
        val store = makeStore(backgroundScope)
        val alice = UserProfile(id = 2, name = "Alice", avatarColorHex = "#E53935")
        store.upsertProfile(alice)
        val profiles = store.profilesList.first()
        assertTrue(profiles.any { it.id == 2 && it.name == "Alice" })
    }

    @Test
    fun `upsertProfile updates existing profile without duplication`() = runTest {
        val store = makeStore(backgroundScope)
        store.upsertProfile(UserProfile(id = 2, name = "Alice", avatarColorHex = "#E53935"))
        store.upsertProfile(UserProfile(id = 2, name = "Alice Updated", avatarColorHex = "#E53935"))
        val profiles = store.profilesList.first()
        val id2Profiles = profiles.filter { it.id == 2 }
        assertEquals(1, id2Profiles.size)
        assertEquals("Alice Updated", id2Profiles[0].name)
    }

    @Test
    fun `deleteProfile removes profile from list`() = runTest {
        val store = makeStore(backgroundScope)
        store.upsertProfile(UserProfile(id = 2, name = "Alice", avatarColorHex = "#E53935"))
        store.deleteProfile(2)
        val profiles = store.profilesList.first()
        assertFalse(profiles.any { it.id == 2 })
    }

    @Test
    fun `deleteProfile profile 1 is a no-op`() = runTest {
        val store = makeStore(backgroundScope)
        store.deleteProfile(1)
        val profiles = store.profilesList.first()
        assertTrue(profiles.any { it.id == 1 })
    }

    @Test
    fun `deleteProfile of active profile resets activeProfileId to 1`() = runTest {
        val store = makeStore(backgroundScope)
        store.upsertProfile(UserProfile(id = 2, name = "Alice", avatarColorHex = "#E53935"))
        store.setActiveProfile(2)
        store.deleteProfile(2)
        val activeId = store.activeProfileId.first()
        assertEquals(1, activeId)
    }

    @Test
    fun `setActiveProfile changes activeProfileId`() = runTest {
        val store = makeStore(backgroundScope)
        store.upsertProfile(UserProfile(id = 3, name = "Bob", avatarColorHex = "#8E24AA"))
        store.setActiveProfile(3)
        val activeId = store.activeProfileId.first()
        assertEquals(3, activeId)
    }

    @Test
    fun `normalizeProfiles always ensures profile 1 exists in list`() = runTest {
        val store = makeStore(backgroundScope)
        // Replace all with a list that has no profile 1 — normalizeProfiles should add it back
        store.replaceAllProfiles(listOf(
            UserProfile(id = 2, name = "Alice", avatarColorHex = "#E53935")
        ))
        val profiles = store.profilesList.first()
        assertTrue(profiles.any { it.id == 1 })
    }

    @Test
    fun `empty DataStore returns default profile without crash`() = runTest {
        val store = makeStore(backgroundScope)
        val profiles = store.profilesList.first()
        // Should be default, not crash
        assertEquals("Default", profiles.first().name)
    }

    @Test
    fun `ProfileJson round-trip preserves all fields including avatarUrl, avatarId and pinEnabled`() = runTest {
        val store = makeStore(backgroundScope)
        val avatarUrl = "https://example.test/profile-avatars/user/2.jpg?t=123"
        val original = UserProfile(
            id = 2,
            name = "Alice",
            avatarColorHex = "#E53935",
            usesPrimaryAddons = true,
            avatarUrl = avatarUrl,
            avatarId = "avatar_001",
            pinEnabled = true
        )
        store.upsertProfile(original)
        val retrieved = store.profilesList.first().first { it.id == 2 }
        assertEquals(original, retrieved)
    }

    @Test
    fun `replaceAllProfiles preserves avatarUrl from web sync`() = runTest {
        val store = makeStore(backgroundScope)
        val avatarUrl = "https://example.test/profile-avatars/user/2.jpg?t=123"
        val webSyncedProfiles = listOf(
            UserProfile(id = 1, name = "Default", avatarColorHex = "#1E88E5"),
            UserProfile(
                id = 2,
                name = "Alice",
                avatarColorHex = "#E53935",
                avatarUrl = avatarUrl
            )
        )

        store.replaceAllProfiles(webSyncedProfiles)

        val profile = store.profilesList.first().first { it.id == 2 }
        assertEquals(avatarUrl, profile.avatarUrl)
    }

    @Test
    fun `replaceAllProfiles replaces the full list`() = runTest {
        val store = makeStore(backgroundScope)
        store.upsertProfile(UserProfile(id = 2, name = "Alice", avatarColorHex = "#E53935"))
        val newProfiles = listOf(
            UserProfile(id = 1, name = "Default", avatarColorHex = "#1E88E5"),
            UserProfile(id = 3, name = "Bob", avatarColorHex = "#8E24AA")
        )
        store.replaceAllProfiles(newProfiles)
        val profiles = store.profilesList.first()
        assertFalse(profiles.any { it.id == 2 })
        assertTrue(profiles.any { it.id == 3 })
    }
}
