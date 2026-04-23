package com.nexio.tv.ui.screens.account

import com.nexio.tv.domain.model.AuthState
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AuthQrLaunchPolicyTest {

    @Test
    fun `signed out auto starts qr login`() {
        assertTrue(shouldAutoStartQrLogin(AuthState.SignedOut))
    }

    @Test
    fun `session lost does not auto start qr login`() {
        assertFalse(shouldAutoStartQrLogin(AuthState.SessionLost))
    }

    @Test
    fun `full account does not auto start qr login`() {
        assertFalse(
            shouldAutoStartQrLogin(
                AuthState.FullAccount(
                    userId = "user-123",
                    email = "user@example.com"
                )
            )
        )
    }
}
