package com.nexio.tv.core.sync

import com.nexio.tv.core.auth.AuthManager
import com.nexio.tv.core.profile.ProfileBoundary
import com.nexio.tv.core.profile.ProfileManager
import com.nexio.tv.core.profile.ProfileModeRouter
import com.nexio.tv.data.local.SimklAuthDataStore
import com.nexio.tv.data.local.TraktAuthDataStore
import com.nexio.tv.domain.model.AuthState
import io.github.jan.supabase.postgrest.Postgrest
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertTrue
import org.junit.Test

class ProfileWebSyncServiceTest {
    @Test
    fun `sync active profile requires full account session`() = runTest {
        val postgrest = mockk<Postgrest>(relaxed = true)
        val service = ProfileWebSyncService(
            authManager = mockk<AuthManager> {
                every { authState } returns MutableStateFlow(AuthState.SessionLost)
            },
            postgrest = postgrest,
            traktAuthDataStore = mockk<TraktAuthDataStore>(relaxed = true),
            simklAuthDataStore = mockk<SimklAuthDataStore>(relaxed = true),
            profileModeRouter = ProfileModeRouter(),
            profileBoundary = ProfileBoundary(
                profileManager = mockk<ProfileManager> {
                    every { activeProfileId } returns MutableStateFlow(1)
                    every { isPrimaryProfileActive } returns true
                },
                languageTagProvider = { "en" }
            )
        )

        val result = service.syncActiveProfile(2)

        assertTrue(result.isFailure)
        verify(exactly = 0) { postgrest.from("profile_auth_tokens") }
    }
}
