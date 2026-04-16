package com.nexio.tv.core.sync

import com.nexio.tv.core.auth.AuthManager
import com.nexio.tv.core.profile.ProfileManager
import com.nexio.tv.data.local.ProfileDataStore
import com.nexio.tv.data.remote.supabase.SupabaseProfilePinVerifyResult
import io.github.jan.supabase.postgrest.Postgrest
import io.github.jan.supabase.postgrest.result.PostgrestResult
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class ProfileSyncServiceTest {
    private fun service(
        authManager: AuthManager = mockk(relaxed = true),
        postgrest: Postgrest = mockk(),
        profileDataStore: ProfileDataStore = mockk(relaxed = true),
        profileManager: ProfileManager = mockk(relaxed = true)
    ): ProfileSyncService {
        every { authManager.hasSyncSession } returns true
        return ProfileSyncService(
            authManager = authManager,
            postgrest = postgrest,
            profileDataStore = profileDataStore,
            profileManager = profileManager
        )
    }

    @Test
    fun `invalid local PIN returns rejected success without RPC`() = runTest {
        val authManager = mockk<AuthManager>(relaxed = true)
        every { authManager.hasSyncSession } returns true
        val postgrest = mockk<Postgrest>(relaxed = true)
        val result = service(authManager = authManager, postgrest = postgrest)

        val response = result.verifyProfilePin(2, "12a4")

        assertTrue(response.isSuccess)
        val decoded = response.getOrThrow()
        assertFalse(decoded.unlocked)
        assertEquals(0, decoded.retryAfterSeconds)
        assertTrue(decoded.pinEnabled)
        coVerify(exactly = 0) { postgrest.rpc(any(), any<JsonObject>()) }
    }

    @Test
    fun `empty RPC response returns failure`() = runTest {
        val authManager = mockk<AuthManager>(relaxed = true)
        every { authManager.hasSyncSession } returns true
        val postgrest = mockk<Postgrest>()
        val rpcResult = mockk<PostgrestResult>()
        coEvery { postgrest.rpc("profile_verify_pin", any<JsonObject>()) } returns rpcResult
        every { rpcResult.decodeList<SupabaseProfilePinVerifyResult>() } returns emptyList()

        val result = service(authManager = authManager, postgrest = postgrest)
            .verifyProfilePin(2, "1234")

        assertTrue(result.isFailure)
        coVerify(exactly = 1) { postgrest.rpc("profile_verify_pin", any<JsonObject>()) }
    }

    @Test
    fun `RPC exception returns failure`() = runTest {
        val authManager = mockk<AuthManager>(relaxed = true)
        every { authManager.hasSyncSession } returns true
        val postgrest = mockk<Postgrest>()
        coEvery { postgrest.rpc("profile_verify_pin", any<JsonObject>()) } throws RuntimeException("boom")

        val result = service(authManager = authManager, postgrest = postgrest)
            .verifyProfilePin(2, "1234")

        assertTrue(result.isFailure)
        coVerify(exactly = 1) { postgrest.rpc("profile_verify_pin", any<JsonObject>()) }
    }

}
