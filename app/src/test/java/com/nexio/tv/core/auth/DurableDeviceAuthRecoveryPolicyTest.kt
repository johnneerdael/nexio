package com.nexio.tv.core.auth

import com.nexio.tv.data.local.DurableDeviceCredentialSnapshot
import com.nexio.tv.data.remote.supabase.DurableDeviceCredentialIssueResult
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DurableDeviceAuthRecoveryPolicyTest {
    @Test
    fun `unavailable session clears live sync marker during recovery`() {
        assertNull(sessionUserIdWhileSessionUnavailable())
    }

    @Test
    fun `authoritative refresh rejection with durable credential falls through to durable recovery`() {
        assertEquals(
            AuthoritativeRefreshRejectionAction.ATTEMPT_DURABLE_RECOVERY,
            resolveAuthoritativeRefreshRejectionAction(hasDurableCredential = true)
        )
    }

    @Test
    fun `authoritative refresh rejection without durable credential signs user out`() {
        assertEquals(
            AuthoritativeRefreshRejectionAction.TRANSITION_SIGNED_OUT,
            resolveAuthoritativeRefreshRejectionAction(hasDurableCredential = false)
        )
    }

    @Test
    fun `local sign out suppresses recovery branching`() {
        assertTrue(shouldSuppressRecoveryForLocalSignOut(isLocalSignOutInProgress = true))
        assertFalse(shouldSuppressRecoveryForLocalSignOut(isLocalSignOutInProgress = false))
    }

    @Test
    fun `not authenticated startup ignores cached identity without a live session`() {
        assertEquals(
            NotAuthenticatedStartupAction.ATTEMPT_RETURNING_USER_RECOVERY,
            resolveNotAuthenticatedStartupAction(
                hasRefreshToken = false,
                isReturningUser = true,
                hasDurableCredential = false
            )
        )
    }

    @Test
    fun `not authenticated startup attempts recovery from durable credential even without returning markers`() {
        assertEquals(
            NotAuthenticatedStartupAction.ATTEMPT_RETURNING_USER_RECOVERY,
            resolveNotAuthenticatedStartupAction(
                hasRefreshToken = false,
                isReturningUser = false,
                hasDurableCredential = true
            )
        )
    }

    @Test
    fun `not authenticated startup prefers refreshable live session over durable recovery`() {
        assertEquals(
            NotAuthenticatedStartupAction.REFRESH_LIVE_SESSION,
            resolveNotAuthenticatedStartupAction(
                hasRefreshToken = true,
                isReturningUser = true,
                hasDurableCredential = true
            )
        )
        assertFalse(
            shouldAttemptDurableSessionRecovery(
                hasRefreshToken = true,
                credential = DurableDeviceCredentialSnapshot(
                    devicePublicId = "device-public-id",
                    deviceSecret = "device-secret"
                )
            )
        )
    }

    @Test
    fun `not authenticated startup signs out fresh install with no live session`() {
        assertEquals(
            NotAuthenticatedStartupAction.TRANSITION_SIGNED_OUT,
            resolveNotAuthenticatedStartupAction(
                hasRefreshToken = false,
                isReturningUser = false,
                hasDurableCredential = false
            )
        )
    }

    @Test
    fun `durable recovery runs when no live refresh token and credential is complete`() {
        assertTrue(
            shouldAttemptDurableSessionRecovery(
                hasRefreshToken = false,
                credential = DurableDeviceCredentialSnapshot(
                    devicePublicId = "device-public-id",
                    deviceSecret = "device-secret"
                )
            )
        )
    }

    @Test
    fun `durable recovery stays disabled when refresh token already exists`() {
        assertFalse(
            shouldAttemptDurableSessionRecovery(
                hasRefreshToken = true,
                credential = DurableDeviceCredentialSnapshot(
                    devicePublicId = "device-public-id",
                    deviceSecret = "device-secret"
                )
            )
        )
    }

    @Test
    fun `durable recovery stays disabled when credential is incomplete`() {
        assertFalse(
            shouldAttemptDurableSessionRecovery(
                hasRefreshToken = false,
                credential = DurableDeviceCredentialSnapshot(
                    devicePublicId = "device-public-id",
                    deviceSecret = ""
                )
            )
        )
    }

    @Test
    fun `qr exchange imports auth only after durable credential save succeeds`() = runTest {
        val calls = mutableListOf<String>()

        finalizeTvLoginExchange(
            result = DurableDeviceCredentialIssueResult(
                devicePublicId = "device-public-id",
                deviceSecret = "device-secret",
                accessToken = "access-token",
                refreshToken = "refresh-token"
            ),
            saveCredential = { publicId, secret ->
                calls += "save:$publicId:$secret"
            },
            importAuthTokens = { accessToken, refreshToken ->
                calls += "import:$accessToken:$refreshToken"
            }
        )

        assertEquals(
            listOf(
                "save:device-public-id:device-secret",
                "import:access-token:refresh-token"
            ),
            calls
        )
    }

    @Test
    fun `qr exchange does not import auth when durable credential save fails`() = runTest {
        var imported = false

        runCatching {
            finalizeTvLoginExchange(
                result = DurableDeviceCredentialIssueResult(
                    devicePublicId = "device-public-id",
                    deviceSecret = "device-secret",
                    accessToken = "access-token",
                    refreshToken = "refresh-token"
                ),
                saveCredential = { _, _ -> error("disk full") },
                importAuthTokens = { _, _ -> imported = true }
            )
        }

        assertFalse(imported)
    }
}
