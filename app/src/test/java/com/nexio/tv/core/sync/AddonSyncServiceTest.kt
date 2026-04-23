package com.nexio.tv.core.sync

import com.nexio.tv.core.auth.AuthManager
import com.nexio.tv.data.local.AddonPreferences
import com.nexio.tv.domain.model.AuthState
import io.github.jan.supabase.postgrest.Postgrest
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class AddonSyncServiceTest {
    @Test
    fun `pushToRemote skips RPC when session is stale`() = runTest {
        val authManager = mockk<AuthManager>(relaxed = true) {
            every { authState } returns MutableStateFlow(AuthState.SessionLost)
            every { currentSessionUserId } returns "durable-user"
            every { hasSyncSession } returns true
        }
        val postgrest = mockk<Postgrest>(relaxed = true)
        val addonPreferences = mockk<AddonPreferences> {
            every { installedAddons } returns flowOf(emptyList())
        }

        val result = AddonSyncService(
            postgrest = postgrest,
            authManager = authManager,
            addonPreferences = addonPreferences
        ).pushToRemote()

        assertTrue(result.isSuccess)
        coVerify(exactly = 0) { postgrest.rpc(any(), any<JsonObject>()) }
    }

    @Test
    fun `getRemoteAddonConfigs fails fast without a live full account session`() = runTest {
        val authManager = mockk<AuthManager>(relaxed = true) {
            every { authState } returns MutableStateFlow(AuthState.SessionLost)
            every { currentSessionUserId } returns "durable-user"
            every { hasSyncSession } returns true
        }
        val postgrest = mockk<Postgrest>(relaxed = true)
        val addonPreferences = mockk<AddonPreferences> {
            every { installedAddons } returns flowOf(emptyList())
        }

        val result = AddonSyncService(
            postgrest = postgrest,
            authManager = authManager,
            addonPreferences = addonPreferences
        ).getRemoteAddonConfigs()

        assertTrue(result.isFailure)
        coVerify(exactly = 0) { postgrest.rpc(any(), any<JsonObject>()) }
    }
}
