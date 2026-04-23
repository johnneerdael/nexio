package com.nexio.tv.data.repository

import com.nexio.tv.core.auth.AuthManager
import com.nexio.tv.domain.model.AuthState
import io.github.jan.supabase.postgrest.Postgrest
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SyncRepositoryImplSessionTest {
    @Test
    fun `generate sync code fails fast without a live full-account session`() = runTest {
        val postgrest = mockk<Postgrest>(relaxed = true)
        val repository = createRepository(postgrest)

        val result = repository.generateSyncCode("1234")

        assertTrue(result.isFailure)
        assertEquals("No live full account session", result.exceptionOrNull()?.message)
        coVerify(exactly = 0) { postgrest.rpc(any<String>(), any<JsonObject>()) }
    }

    @Test
    fun `claim sync code fails fast without a live full-account session`() = runTest {
        val postgrest = mockk<Postgrest>(relaxed = true)
        val repository = createRepository(postgrest)

        val result = repository.claimSyncCode(code = "ABCD", pin = "1234", deviceName = "Living Room")

        assertTrue(result.isFailure)
        assertEquals("No live full account session", result.exceptionOrNull()?.message)
        coVerify(exactly = 0) { postgrest.rpc(any<String>(), any<JsonObject>()) }
    }

    private fun createRepository(postgrest: Postgrest): SyncRepositoryImpl {
        val authManager = mockk<AuthManager> {
            every { authState } returns MutableStateFlow(AuthState.FullAccount("user-1", "user@example.com"))
            every { currentSessionUserId } returns null
        }

        return SyncRepositoryImpl(
            postgrest = postgrest,
            authManager = authManager
        )
    }
}
