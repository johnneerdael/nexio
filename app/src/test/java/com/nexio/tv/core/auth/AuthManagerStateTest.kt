package com.nexio.tv.core.auth

import com.nexio.tv.domain.model.AuthState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AuthManagerStateTest {
    @Test
    fun `anonymous user with null email returns SignedOut`() {
        assertEquals(
            AuthState.SignedOut,
            fullAccountStateForSupabaseUser(userId = "anon-uuid", email = null)
        )
    }

    @Test
    fun `user with whitespace email returns SignedOut`() {
        assertEquals(
            AuthState.SignedOut,
            fullAccountStateForSupabaseUser(userId = "user-123", email = "   ")
        )
    }

    @Test
    fun `real user with email returns FullAccount`() {
        val state = fullAccountStateForSupabaseUser(
            userId = "user-123",
            email = "user@example.com"
        )

        assertTrue(state is AuthState.FullAccount)
        assertEquals("user-123", (state as AuthState.FullAccount).userId)
        assertEquals("user@example.com", state.email)
    }

    @Test
    fun `missing user id remains signed out`() {
        assertEquals(
            AuthState.SignedOut,
            fullAccountStateForSupabaseUser(userId = "", email = "user@example.com")
        )
    }
}
