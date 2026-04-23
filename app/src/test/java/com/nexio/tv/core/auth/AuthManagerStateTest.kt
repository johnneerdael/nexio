package com.nexio.tv.core.auth

import com.nexio.tv.domain.model.AuthState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
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

    @Test
    fun `session lost is distinct from signed out`() {
        // SessionLost must not equal SignedOut so UI branches can render
        // different copy for returning users whose session was lost.
        val lost: AuthState = AuthState.SessionLost
        val out: AuthState = AuthState.SignedOut
        assertTrue(lost != out)
    }

    @Test
    fun `anonymous authenticated session does not open sync gate`() {
        val publication = resolveAuthenticatedSessionPublication(
            userId = "anon-uuid",
            email = null,
            isReturningUser = false
        )

        assertEquals(AuthState.SignedOut, publication.authState)
        assertNull(publication.sessionUserId)
    }

    @Test
    fun `stale anonymous returning user does not keep sync session user id`() {
        val publication = resolveAuthenticatedSessionPublication(
            userId = "anon-uuid",
            email = null,
            isReturningUser = true
        )

        assertEquals(AuthState.SessionLost, publication.authState)
        assertNull(publication.sessionUserId)
    }
}
