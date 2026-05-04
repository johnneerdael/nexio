package com.nexio.tv.core.auth

import com.nexio.tv.data.local.DurableDeviceCredentialSnapshot
import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DurableDeviceAuthRecoveryPolicyTest {
    @Test
    fun `supabase auth module routes auto refresh through auth manager`() {
        val source = java.io.File("app/src/main/java/com/nexio/tv/core/di/SupabaseModule.kt").readText()

        assertTrue(source.contains("alwaysAutoRefresh = false"))
        assertFalse(source.contains("alwaysAutoRefresh = true"))
    }

    @Test
    fun `not authenticated startup prefers live refresh before durable recovery`() {
        assertEquals(
            NotAuthenticatedStartupAction.REFRESH_LIVE_SESSION,
            resolveNotAuthenticatedStartupAction(
                hasRefreshToken = true,
                isReturningUser = true,
                hasDurableCredential = true
            )
        )
    }

    @Test
    fun `not authenticated startup uses durable credential when no live refresh exists`() {
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
    fun `not authenticated startup preserves returning user recovery without durable credential`() {
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
    fun `fresh install without refresh or durable credential signs out`() {
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
    fun `jwt expiry refreshes live session before durable recovery`() {
        assertEquals(
            JwtExpiryRecoveryAction.REFRESH_LIVE_SESSION,
            resolveJwtExpiryRecoveryAction(
                hasRefreshToken = true,
                credential = completeCredential()
            )
        )
    }

    @Test
    fun `jwt expiry can force durable recovery after authoritative refresh rejection`() {
        assertEquals(
            JwtExpiryRecoveryAction.ATTEMPT_DURABLE_RECOVERY,
            resolveJwtExpiryRecoveryAction(
                hasRefreshToken = true,
                credential = completeCredential(),
                ignoreCachedRefreshToken = true
            )
        )
    }

    @Test
    fun `authoritative refresh rejection with durable credential falls through to durable recovery`() {
        assertEquals(
            AuthoritativeRefreshRejectionAction.ATTEMPT_DURABLE_RECOVERY,
            resolveAuthoritativeRefreshRejectionAction(hasDurableCredential = true)
        )
    }

    @Test
    fun `authoritative refresh rejection without durable credential signs out`() {
        assertEquals(
            AuthoritativeRefreshRejectionAction.TRANSITION_SIGNED_OUT,
            resolveAuthoritativeRefreshRejectionAction(hasDurableCredential = false)
        )
    }

    @Test
    fun `refresh failure only treats authoritative rejection as durable recovery eligible`() {
        assertEquals(
            RefreshFailureAction.ATTEMPT_DURABLE_RECOVERY,
            resolveRefreshFailureAction(
                refreshError = IllegalStateException("invalid refresh token"),
                hasDurableCredential = true
            )
        )
        assertEquals(
            RefreshFailureAction.KEEP_CURRENT_AUTH_STATE,
            resolveRefreshFailureAction(
                refreshError = IOException("network down"),
                hasDurableCredential = true
            )
        )
    }

    @Test
    fun `authoritative durable recovery rejection clears credential and forces reconnect`() {
        assertEquals(
            DurableRecoveryFailureAction.CLEAR_DURABLE_CREDENTIAL_AND_TRANSITION_SESSION_LOST,
            resolveDurableRecoveryFailureAction(isAuthoritativeRejection = true)
        )
        assertEquals(
            DurableRecoveryFailureAction.KEEP_CURRENT_AUTH_STATE,
            resolveDurableRecoveryFailureAction(isAuthoritativeRejection = false)
        )
    }

    @Test
    fun `revoked durable credential status forces session lost while outages keep current state`() {
        assertEquals(
            DurableCredentialStatusAction.CLEAR_DURABLE_CREDENTIAL_AND_TRANSITION_SESSION_LOST,
            resolveDurableCredentialStatusAction(DurableCredentialRemoteStatus.REVOKED)
        )
        assertEquals(
            DurableCredentialStatusAction.KEEP_CURRENT_AUTH_STATE,
            resolveDurableCredentialStatusAction(DurableCredentialRemoteStatus.ACTIVE)
        )
        assertEquals(
            DurableCredentialStatusAction.KEEP_CURRENT_AUTH_STATE,
            resolveDurableCredentialStatusAction(DurableCredentialRemoteStatus.UNKNOWN)
        )
    }

    @Test
    fun `durable session recovery waits for cached refresh unless explicitly ignored`() {
        assertFalse(
            shouldAttemptDurableSessionRecovery(
                hasRefreshToken = true,
                credential = completeCredential(),
                ignoreCachedRefreshToken = false
            )
        )
        assertTrue(
            shouldAttemptDurableSessionRecovery(
                hasRefreshToken = true,
                credential = completeCredential(),
                ignoreCachedRefreshToken = true
            )
        )
    }

    @Test
    fun `manual sign out prepares pending revoke before remote revoke and local clear`() = runTest {
        val events = mutableListOf<String>()

        handleManualSignOut(
            resetLocalAccountState = { events += "reset-local-stock" },
            clearPresenceMarker = { events += "clear-presence" },
            prepareDurableCredentialRevoke = { events += "prepare-pending-revoke" },
            revokeDurableCredential = { events += "revoke-durable-remote" },
            clearDurableCredential = { events += "clear-durable-local" },
            clearSupabaseSession = { events += "clear-supabase-session" }
        )

        assertEquals(
            listOf(
                "reset-local-stock",
                "clear-presence",
                "prepare-pending-revoke",
                "revoke-durable-remote",
                "clear-durable-local",
                "clear-supabase-session"
            ),
            events
        )
    }

    @Test
    fun `manual sign out keeps durable credential when pending revoke cannot be prepared`() = runTest {
        val events = mutableListOf<String>()

        handleManualSignOut(
            resetLocalAccountState = { events += "reset-local-stock" },
            clearPresenceMarker = { events += "clear-presence" },
            prepareDurableCredentialRevoke = {
                events += "prepare-pending-revoke"
                error("failed to persist pending revoke")
            },
            revokeDurableCredential = { events += "revoke-durable-remote" },
            clearDurableCredential = { events += "clear-durable-local" },
            clearSupabaseSession = { events += "clear-supabase-session" }
        )

        assertEquals(
            listOf(
                "reset-local-stock",
                "clear-presence",
                "prepare-pending-revoke"
            ),
            events
        )
    }

    @Test
    fun `manual sign out preserves cancellation`() = runTest {
        var cancelled = false

        try {
            handleManualSignOut(
                resetLocalAccountState = {},
                clearPresenceMarker = { throw CancellationException("cancelled") },
                prepareDurableCredentialRevoke = {},
                revokeDurableCredential = {},
                clearDurableCredential = {},
                clearSupabaseSession = {}
            )
        } catch (e: CancellationException) {
            cancelled = true
        }

        assertTrue(cancelled)
    }

    @Test
    fun `authoritative durable rejection disables live sync before local stock reset writes`() = runTest {
        val events = mutableListOf<String>()

        handleAuthoritativeDurableCredentialRejection(
            disableLiveAccountSync = { events += "disable-live-sync" },
            resetLocalAccountState = { events += "reset-local-stock" },
            clearDurableCredential = { events += "clear-durable" },
            clearSupabaseSession = { events += "clear-session" },
            transitionToReconnectState = { events += "session-lost" }
        )

        assertEquals(
            listOf(
                "disable-live-sync",
                "reset-local-stock",
                "clear-durable",
                "clear-session",
                "session-lost"
            ),
            events
        )
    }

    @Test
    fun `local sign out suppresses recovery branching`() {
        assertTrue(shouldSuppressRecoveryForLocalSignOut(isLocalSignOutInProgress = true))
        assertFalse(shouldSuppressRecoveryForLocalSignOut(isLocalSignOutInProgress = false))
    }

    private fun completeCredential() = DurableDeviceCredentialSnapshot(
        devicePublicId = "device-public-id",
        deviceSecret = "device-secret"
    )
}
