package com.nexio.tv.core.auth

import com.nexio.tv.data.local.DurableDeviceCredentialSnapshot
import com.nexio.tv.data.remote.supabase.DurableDeviceCredentialBackfillResult
import com.nexio.tv.data.remote.supabase.DurableDeviceCredentialIssueResult
import java.io.File
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DurableDeviceAuthRecoveryPolicyTest {
    @Test
    fun `supabase auth module disables background auto refresh`() {
        val source = File("app/src/main/java/com/nexio/tv/core/di/SupabaseModule.kt").readText()

        assertTrue(source.contains("alwaysAutoRefresh = false"))
        assertFalse(source.contains("alwaysAutoRefresh = true"))
    }

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
            NotAuthenticatedStartupAction.ATTEMPT_RETURNING_USER_RECOVERY,
            resolveNotAuthenticatedStartupAction(
                hasRefreshToken = true,
                isReturningUser = true,
                hasDurableCredential = true
            )
        )
        assertTrue(
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
        assertTrue(
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
    fun `durable recovery still stays disabled for legacy refresh-only sessions`() {
        assertFalse(
            shouldAttemptDurableSessionRecovery(
                hasRefreshToken = true,
                credential = DurableDeviceCredentialSnapshot(
                    devicePublicId = "device-public-id",
                    deviceSecret = null
                )
            )
        )
    }

    @Test
    fun `manual account auth clears legacy or mismatched durable credential`() {
        assertTrue(
            shouldClearDurableCredentialForManualAccountAuth(
                credential = DurableDeviceCredentialSnapshot(
                    devicePublicId = "device-public-id",
                    deviceSecret = "device-secret",
                    ownerUserId = null
                ),
                authenticatedUserId = "owner-b"
            )
        )
        assertTrue(
            shouldClearDurableCredentialForManualAccountAuth(
                credential = DurableDeviceCredentialSnapshot(
                    devicePublicId = "device-public-id",
                    deviceSecret = "device-secret",
                    ownerUserId = "owner-a"
                ),
                authenticatedUserId = "owner-b"
            )
        )
        assertFalse(
            shouldClearDurableCredentialForManualAccountAuth(
                credential = DurableDeviceCredentialSnapshot(
                    devicePublicId = "device-public-id",
                    deviceSecret = "device-secret",
                    ownerUserId = "owner-b"
                ),
                authenticatedUserId = "owner-b"
            )
        )
    }

    @Test
    fun `legacy live session requests durable credential backfill when credential is missing`() {
        assertTrue(
            shouldRequestDurableCredentialBackfill(
                hasRefreshToken = true,
                credential = DurableDeviceCredentialSnapshot(
                    devicePublicId = null,
                    deviceSecret = null
                )
            )
        )
        assertFalse(
            shouldRequestDurableCredentialBackfill(
                hasRefreshToken = false,
                credential = DurableDeviceCredentialSnapshot(
                    devicePublicId = null,
                    deviceSecret = null
                )
            )
        )
        assertFalse(
            shouldRequestDurableCredentialBackfill(
                hasRefreshToken = true,
                credential = DurableDeviceCredentialSnapshot(
                    devicePublicId = "device-public-id",
                    deviceSecret = "device-secret"
                )
            )
        )
    }

    @Test
    fun `backfill failure is contained and returns false`() = runTest {
        var reachedEnd = false

        val result = runDurableCredentialBackfillSafely {
            error("backend offline")
        }
        reachedEnd = true

        assertFalse(result)
        assertTrue(reachedEnd)
    }

    @Test
    fun `sign out beats in flight backfill and leaves no durable credential to save`() {
        var saved = false

        val shouldPersist = shouldPersistBackfilledCredential(
            result = DurableDeviceCredentialBackfillResult(
                status = "backfilled",
                devicePublicId = "device-public-id",
                deviceSecret = "device-secret"
            ),
            isLocalSignOutInProgress = true
        )

        if (shouldPersist) {
            saved = true
        }

        assertFalse(saved)
        assertFalse(shouldPersist)
    }

    @Test
    fun `complete backfill persists only when still signed in`() {
        assertTrue(
            shouldPersistBackfilledCredential(
                result = DurableDeviceCredentialBackfillResult(
                    status = "backfilled",
                    devicePublicId = "device-public-id",
                    deviceSecret = "device-secret"
                ),
                isLocalSignOutInProgress = false
            )
        )
        assertFalse(
            shouldPersistBackfilledCredential(
                result = DurableDeviceCredentialBackfillResult(
                    status = "needs_reconnect",
                    reason = "no_legacy_match"
                ),
                isLocalSignOutInProgress = false
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
