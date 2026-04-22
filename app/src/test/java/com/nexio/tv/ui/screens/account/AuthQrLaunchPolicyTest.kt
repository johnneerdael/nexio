package com.nexio.tv.ui.screens.account

import com.nexio.tv.domain.model.AuthState
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AuthQrLaunchPolicyTest {
    @Test
    fun `launch starts only when signed out session still needs qr`() {
        assertTrue(
            shouldLaunchQrLogin(
                authState = AuthState.SignedOut,
                isSignedIn = false,
                hasQrLoginCode = false,
                isLoading = false
            )
        )
    }

    @Test
    fun `launch stays blocked for returning users in session lost state`() {
        assertFalse(
            shouldLaunchQrLogin(
                authState = AuthState.SessionLost,
                isSignedIn = false,
                hasQrLoginCode = false,
                isLoading = false
            )
        )
    }

    @Test
    fun `launch stays blocked while auth is still loading or already restored`() {
        assertFalse(
            shouldLaunchQrLogin(
                authState = AuthState.Loading,
                isSignedIn = false,
                hasQrLoginCode = false,
                isLoading = false
            )
        )
        assertFalse(
            shouldLaunchQrLogin(
                authState = AuthState.FullAccount(userId = "user-123", email = "user@example.com"),
                isSignedIn = true,
                hasQrLoginCode = false,
                isLoading = false
            )
        )
    }

    @Test
    fun `launch stays blocked when qr session already exists or exchange is in flight`() {
        assertFalse(
            shouldLaunchQrLogin(
                authState = AuthState.SignedOut,
                isSignedIn = false,
                hasQrLoginCode = true,
                isLoading = false
            )
        )
        assertFalse(
            shouldLaunchQrLogin(
                authState = AuthState.SignedOut,
                isSignedIn = false,
                hasQrLoginCode = false,
                isLoading = true
            )
        )
    }
}
