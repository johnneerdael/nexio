package com.nexio.tv.core.auth

import com.nexio.tv.data.local.DurableDeviceCredentialSnapshot
import java.io.IOException
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

    private fun completeCredential() = DurableDeviceCredentialSnapshot(
        devicePublicId = "device-public-id",
        deviceSecret = "device-secret"
    )
}
