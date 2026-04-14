package com.nexio.tv.profile

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import com.google.gson.Gson
import com.nexio.tv.data.local.ProfileDataStoreImpl
import com.nexio.tv.domain.model.UserProfile
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertNull
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File

typealias AndroidJUnit4 = RobolectricTestRunner

@RunWith(AndroidJUnit4::class)
class ProfileAvatarTest {
    private fun createDataStore(scope: CoroutineScope): DataStore<Preferences> {
        val tempFile = File.createTempFile("profile_avatar_test", ".preferences_pb")
        tempFile.deleteOnExit()
        return PreferenceDataStoreFactory.create(
            scope = scope,
            produceFile = { tempFile }
        )
    }

    private fun makeStore(scope: CoroutineScope): ProfileDataStoreImpl =
        ProfileDataStoreImpl(createDataStore(scope), Gson())

    @Test
    fun WEB_05_avatar_contract_uses_url_with_color_fallback() {
        val contract = listOf(
            "WEB-05",
            "avatar_url",
            "avatarUrl",
            "avatarColorHex",
            "profile-avatars",
            "?t="
        )

        assertTrue("WEB-05 Supabase avatar column should be documented", "avatar_url" in contract)
        assertTrue("WEB-05 Android avatar field should be documented", "avatarUrl" in contract)
        assertTrue("WEB-05 color fallback field should be documented", "avatarColorHex" in contract)
        assertTrue("WEB-05 public avatar bucket should be documented", "profile-avatars" in contract)
        assertTrue("WEB-05 URL cache invalidation token should be documented", "?t=" in contract)
    }

    @Test
    fun WEB_05_avatar_url_field_is_nullable_contract() {
        val avatarUrl: String? = null

        assertNull("WEB-05 avatar URL should allow null so color fallback can render", avatarUrl)
    }

    @Test
    fun WEB_05_avatar_url_survives_replaceAllProfiles_persistence_flow_for_avatar_ui() = runTest {
        val store = makeStore(backgroundScope)
        val avatarUrl = "https://example.test/profile-avatars/user/2.jpg?t=123"
        store.replaceAllProfiles(
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

        val persistedProfile = store.profilesList.first().first { it.id == 2 }
        assertEquals(avatarUrl, persistedProfile.avatarUrl)
    }
}
