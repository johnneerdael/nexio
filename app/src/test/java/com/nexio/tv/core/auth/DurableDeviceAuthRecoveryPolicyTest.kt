package com.nexio.tv.core.auth

import com.nexio.tv.data.local.DurableDeviceCredentialSnapshot
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
    fun `owner session imports explicitly retrieve user before persisting`() {
        val source = File("app/src/main/java/com/nexio/tv/core/auth/AuthManager.kt").readText()

        assertTrue(source.contains("retrieveUser = true"))
        assertTrue(source.contains("autoRefresh = false"))
        assertTrue(source.contains("importPersistedOwnerSession("))
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
    fun `authoritative durable recovery rejection after jwt expiry clears local credential and forces reconnect`() {
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
    fun `revoked during running session clears stale refresh token before forcing reconnect`() = runTest {
        var durableCredentialCleared = false
        var persistedRefreshToken: String? = "stale-refresh-token"
        var resetCalled = false
        var terminalState: String? = null

        handleAuthoritativeDurableCredentialRejection(
            resetLocalAccountState = { resetCalled = true },
            clearDurableCredential = { durableCredentialCleared = true },
            clearSupabaseSession = { persistedRefreshToken = null },
            transitionToReconnectState = { terminalState = "SessionLost" }
        )

        assertTrue(resetCalled)
        assertTrue(durableCredentialCleared)
        assertNull(persistedRefreshToken)
        assertEquals("SessionLost", terminalState)
    }

    @Test
    fun `revoked at cold start clears rejected durable credential from local storage`() = runTest {
        var localCredential: DurableDeviceCredentialSnapshot = DurableDeviceCredentialSnapshot(
            devicePublicId = "device-public-id",
            deviceSecret = "device-secret"
        )

        handleAuthoritativeDurableCredentialRejection(
            resetLocalAccountState = {},
            clearDurableCredential = {
                localCredential = DurableDeviceCredentialSnapshot()
            },
            clearSupabaseSession = {},
            transitionToReconnectState = {}
        )

        assertFalse(localCredential.isComplete)
    }

    @Test
    fun `local sign out suppresses recovery branching`() {
        assertTrue(shouldSuppressRecoveryForLocalSignOut(isLocalSignOutInProgress = true))
        assertFalse(shouldSuppressRecoveryForLocalSignOut(isLocalSignOutInProgress = false))
    }

    @Test
    fun `manual sign out triggers local stock reset`() = runTest {
        var resetCalled = false
        var durableCredentialCleared = false
        var presenceMarkerCleared = false
        var supabaseSessionCleared = false

        handleManualSignOut(
            resetLocalAccountState = { resetCalled = true },
            clearPresenceMarker = { presenceMarkerCleared = true },
            clearDurableCredential = { durableCredentialCleared = true },
            clearSupabaseSession = { supabaseSessionCleared = true }
        )

        assertTrue(resetCalled)
        assertTrue(presenceMarkerCleared)
        assertTrue(durableCredentialCleared)
        assertTrue(supabaseSessionCleared)
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
    fun `not authenticated startup prefers refresh token over durable credential when both exist`() {
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
    fun `jwt expiry recovery prefers refresh token over durable credential when both exist`() {
        assertEquals(
            JwtExpiryRecoveryAction.REFRESH_LIVE_SESSION,
            resolveJwtExpiryRecoveryAction(
                hasRefreshToken = true,
                credential = DurableDeviceCredentialSnapshot(
                    devicePublicId = "device-public-id",
                    deviceSecret = "device-secret"
                )
            )
        )
    }

    @Test
    fun `jwt expiry recovery ignores cached refresh token after authoritative rejection`() {
        assertEquals(
            JwtExpiryRecoveryAction.ATTEMPT_DURABLE_RECOVERY,
            resolveJwtExpiryRecoveryAction(
                hasRefreshToken = true,
                credential = DurableDeviceCredentialSnapshot(
                    devicePublicId = "device-public-id",
                    deviceSecret = "device-secret"
                ),
                ignoreCachedRefreshToken = true
            )
        )
    }

    @Test
    fun `jwt expiry recovery falls back to refresh token only for legacy refresh-only sessions`() {
        assertEquals(
            JwtExpiryRecoveryAction.REFRESH_LIVE_SESSION,
            resolveJwtExpiryRecoveryAction(
                hasRefreshToken = true,
                credential = DurableDeviceCredentialSnapshot()
            )
        )
        assertEquals(
            JwtExpiryRecoveryAction.NO_RECOVERY_PATH,
            resolveJwtExpiryRecoveryAction(
                hasRefreshToken = false,
                credential = DurableDeviceCredentialSnapshot()
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
    fun `authoritative refresh rejection still allows durable recovery when rejected refresh token remains cached`() {
        assertTrue(
            shouldAttemptDurableSessionRecovery(
                hasRefreshToken = true,
                credential = DurableDeviceCredentialSnapshot(
                    devicePublicId = "device-public-id",
                    deviceSecret = "device-secret"
                ),
                ignoreCachedRefreshToken = true
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
    fun `authenticated session observer clears ownerless durable credential during manual account auth`() {
        assertTrue(
            shouldClearDurableCredentialForAuthenticatedSession(
                credential = DurableDeviceCredentialSnapshot(
                    devicePublicId = "device-public-id",
                    deviceSecret = "device-secret",
                    ownerUserId = null
                ),
                authenticatedUserId = "owner-b",
                isManualAccountAuthInProgress = true
            )
        )
        assertFalse(
            shouldClearDurableCredentialForAuthenticatedSession(
                credential = DurableDeviceCredentialSnapshot(
                    devicePublicId = "device-public-id",
                    deviceSecret = "device-secret",
                    ownerUserId = null
                ),
                authenticatedUserId = "owner-b",
                isManualAccountAuthInProgress = false
            )
        )
    }

    @Test
    fun `authenticated session observer never binds ownerless durable credential during manual account auth`() {
        assertFalse(
            shouldBindDurableCredentialOwner(
                credential = DurableDeviceCredentialSnapshot(
                    devicePublicId = "device-public-id",
                    deviceSecret = "device-secret",
                    ownerUserId = null
                ),
                authenticatedUserId = "owner-b",
                isManualAccountAuthInProgress = true
            )
        )
        assertTrue(
            shouldBindDurableCredentialOwner(
                credential = DurableDeviceCredentialSnapshot(
                    devicePublicId = "device-public-id",
                    deviceSecret = "device-secret",
                    ownerUserId = null
                ),
                authenticatedUserId = "owner-b",
                isManualAccountAuthInProgress = false
            )
        )
    }

    @Test
    fun `runtime never requests metadata-only durable credential backfill`() {
        assertFalse(
            shouldRequestDurableCredentialBackfill(
                hasRefreshToken = true,
                credential = DurableDeviceCredentialSnapshot()
            )
        )
        assertFalse(
            shouldRequestDurableCredentialBackfill(
                hasRefreshToken = false,
                credential = DurableDeviceCredentialSnapshot()
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
            activateCredential = { publicId, secret ->
                calls += "activate:$publicId:$secret"
            },
            importAuthTokens = { accessToken, refreshToken ->
                calls += "import:$accessToken:$refreshToken"
            }
        )

        assertEquals(
            listOf(
                "save:device-public-id:device-secret",
                "activate:device-public-id:device-secret",
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
                activateCredential = { _, _ -> imported = true },
                importAuthTokens = { _, _ -> imported = true }
            )
        }

        assertFalse(imported)
    }
}
