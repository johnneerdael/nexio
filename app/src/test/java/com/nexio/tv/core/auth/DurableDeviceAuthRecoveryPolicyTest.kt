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
    fun `auth exits route through local stock reset coordinator`() {
        val source = File("app/src/main/java/com/nexio/tv/core/auth/AuthManager.kt").readText()

        assertTrue(source.contains("localAccountResetCoordinator.resetToSignedOutStockState()"))
        assertTrue(source.contains("handleManualSignOut("))
        assertTrue(source.contains("handleAuthoritativeDurableCredentialRejection("))
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
    fun `revoked durable credential status resets local auth state`() {
        assertEquals(
            DurableCredentialStatusAction.RESET_TO_STOCK_AND_SESSION_LOST,
            resolveDurableCredentialStatusAction(DurableCredentialRemoteStatus.REVOKED)
        )
    }

    @Test
    fun `active durable credential status keeps current auth state`() {
        assertEquals(
            DurableCredentialStatusAction.KEEP_CURRENT_AUTH_STATE,
            resolveDurableCredentialStatusAction(DurableCredentialRemoteStatus.ACTIVE)
        )
    }

    @Test
    fun `unknown durable credential status keeps current auth state`() {
        assertEquals(
            DurableCredentialStatusAction.KEEP_CURRENT_AUTH_STATE,
            resolveDurableCredentialStatusAction(DurableCredentialRemoteStatus.UNKNOWN)
        )
    }

    @Test
    fun `status check transport failures keep durable auth state`() {
        val source = File("app/src/main/java/com/nexio/tv/core/auth/AuthManager.kt").readText()
        val methodStart = source.indexOf("private suspend fun validateDurableCredentialStillActive()")
        val methodEnd = source.indexOf("private suspend fun activateDurableDeviceCredential", startIndex = methodStart)
        val methodBody = source.substring(methodStart, methodEnd)

        assertTrue(methodBody.contains("catch (e: AuthoritativeDurableCredentialRejectionException)"))
        assertTrue(methodBody.contains("DurableCredentialRemoteStatus.REVOKED"))
        assertTrue(methodBody.contains("catch (e: Exception)"))
        assertTrue(methodBody.contains("DurableCredentialRemoteStatus.UNKNOWN"))
        assertTrue(methodBody.indexOf("catch (e: Exception)") > methodBody.indexOf("catch (e: AuthoritativeDurableCredentialRejectionException)"))
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
    fun `revoked at cold start clears rejected durable credential from local storage`() = runTest {
        var localCredential: DurableDeviceCredentialSnapshot = DurableDeviceCredentialSnapshot(
            devicePublicId = "device-public-id",
            deviceSecret = "device-secret"
        )

        handleAuthoritativeDurableCredentialRejection(
            disableLiveAccountSync = {},
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
            prepareDurableCredentialRevoke = {},
            revokeDurableCredential = {},
            clearDurableCredential = { durableCredentialCleared = true },
            clearSupabaseSession = { supabaseSessionCleared = true }
        )

        assertTrue(resetCalled)
        assertTrue(presenceMarkerCleared)
        assertTrue(durableCredentialCleared)
        assertTrue(supabaseSessionCleared)
    }

    @Test
    fun `manual sign out revokes durable credential before clearing local credential and session`() = runTest {
        val events = mutableListOf<String>()
        val source = File("app/src/main/java/com/nexio/tv/core/auth/AuthManager.kt").readText()

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
        assertTrue(source.contains("revokePendingDurableCredentialIfPresent()"))
        assertTrue(source.contains("callDurableCredentialSelfService("))
        assertTrue(source.contains("action = \"revoke\""))
        assertTrue(source.contains("durableDeviceCredentialStore.clearPendingRevoke()"))
    }

    @Test
    fun `manual sign out keeps local durable credential when pending revoke cannot be prepared`() = runTest {
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
    fun `manual sign out saves pending revoke before remote revoke and local clear`() {
        val source = File("app/src/main/java/com/nexio/tv/core/auth/AuthManager.kt").readText()
        val signOutStart = source.indexOf("suspend fun signOut()")
        val signOutEnd = source.indexOf("fun clearEffectiveUserIdCache()", startIndex = signOutStart)
        val signOutBody = source.substring(signOutStart, signOutEnd)

        assertTrue(signOutBody.contains("prepareDurableCredentialRevokeForLogout()"))
        assertTrue(signOutBody.contains("revokePendingDurableCredentialIfPresent()"))
        assertTrue(
            signOutBody.indexOf("prepareDurableCredentialRevokeForLogout()") <
                signOutBody.indexOf("revokePendingDurableCredentialIfPresent()")
        )
        assertTrue(
            signOutBody.indexOf("revokePendingDurableCredentialIfPresent()") <
                signOutBody.indexOf("durableDeviceCredentialStore.clear()")
        )
    }

    @Test
    fun `startup retries pending durable credential revoke`() {
        val source = File("app/src/main/java/com/nexio/tv/core/auth/AuthManager.kt").readText()
        val initStart = source.indexOf("init {")
        val initEnd = source.indexOf("private fun observeSessionStatus()", startIndex = initStart)
        val initBody = source.substring(initStart, initEnd)

        assertTrue(initBody.contains("retryPendingDurableCredentialRevoke()"))
    }

    @Test
    fun `auth lifecycle retries pending durable credential revoke`() {
        val source = File("app/src/main/java/com/nexio/tv/core/auth/AuthManager.kt").readText()
        val observeStart = source.indexOf("private fun observeSessionStatus()")
        val readPresenceStart = source.indexOf("private suspend fun readHadAuthenticatedSession()", startIndex = observeStart)
        val observeBody = source.substring(observeStart, readPresenceStart)

        assertTrue(observeBody.contains("auth.sessionStatus.collect { status ->"))
        assertTrue(observeBody.contains("retryPendingDurableCredentialRevoke()"))
        assertTrue(observeBody.indexOf("retryPendingDurableCredentialRevoke()") < observeBody.indexOf("when (status)"))
    }

    @Test
    fun `manual sign out clears local Supabase session when remote sign out fails`() {
        val source = File("app/src/main/java/com/nexio/tv/core/auth/AuthManager.kt").readText()
        val signOutStart = source.indexOf("suspend fun signOut()")
        val signOutEnd = source.indexOf("fun clearEffectiveUserIdCache()", startIndex = signOutStart)
        val signOutBody = source.substring(signOutStart, signOutEnd)
        val clearSessionStart = signOutBody.indexOf("clearSupabaseSession = {")
        val clearSessionEnd = signOutBody.indexOf("\n                }\n            )", startIndex = clearSessionStart)
        val clearSessionBody = signOutBody.substring(clearSessionStart, clearSessionEnd)

        assertTrue(clearSessionBody.contains("auth.signOut()"))
        assertTrue(clearSessionBody.contains("auth.clearSession()"))
        assertTrue(clearSessionBody.indexOf("auth.signOut()") < clearSessionBody.indexOf("auth.clearSession()"))
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
    fun `authenticated session publication validates durable credential status before current-user publish`() {
        val source = File("app/src/main/java/com/nexio/tv/core/auth/AuthManager.kt").readText()
        val currentUserBranchStart = source.indexOf("if (user != null) {")
        val recoveredUserBranchStart = source.indexOf("val recoveredUser = authenticatedUserFromAccessToken")
        val currentUserBranch = source.substring(currentUserBranchStart, recoveredUserBranchStart)

        assertTrue(currentUserBranch.contains("enforceDurableCredentialStillActive()"))
        assertTrue(currentUserBranch.indexOf("enforceDurableCredentialStillActive()") < currentUserBranch.indexOf("publishAuthenticatedUser("))
    }

    @Test
    fun `authenticated session publication validates durable credential status before access-token fallback publish`() {
        val source = File("app/src/main/java/com/nexio/tv/core/auth/AuthManager.kt").readText()
        val recoveredUserBranchStart = source.indexOf("val recoveredUser = authenticatedUserFromAccessToken")
        val notAuthenticatedBranchStart = source.indexOf("is SessionStatus.NotAuthenticated ->")
        val recoveredUserBranch = source.substring(recoveredUserBranchStart, notAuthenticatedBranchStart)

        assertTrue(recoveredUserBranch.contains("enforceDurableCredentialStillActive()"))
        assertTrue(recoveredUserBranch.indexOf("enforceDurableCredentialStillActive()") < recoveredUserBranch.indexOf("publishAuthenticatedUser("))
    }

    @Test
    fun `silent session recovery validates durable credential status before early durable branch`() {
        val source = File("app/src/main/java/com/nexio/tv/core/auth/AuthManager.kt").readText()
        val recoveryStart = source.indexOf("private suspend fun attemptSilentSessionRecovery")
        val recoveryEnd = source.indexOf("private fun transitionToSessionLost")
        val recoveryBody = source.substring(recoveryStart, recoveryEnd)
        val earlyBreak = recoveryBody.indexOf("if (shouldAttemptDurableAfterRefreshRejection) break")

        assertTrue(recoveryBody.contains("enforceDurableCredentialStillActive()"))
        assertTrue(recoveryBody.indexOf("enforceDurableCredentialStillActive()") < earlyBreak)
        assertTrue(recoveryBody.indexOf("enforceDurableCredentialStillActive()") < recoveryBody.indexOf("restoreSupabaseSessionFromDurableCredential()"))
    }

    @Test
    fun `silent session recovery rethrows cancellation before broad exception handling`() {
        val source = File("app/src/main/java/com/nexio/tv/core/auth/AuthManager.kt").readText()
        val recoveryStart = source.indexOf("private suspend fun attemptSilentSessionRecovery")
        val recoveryEnd = source.indexOf("private fun transitionToSessionLost")
        val recoveryBody = source.substring(recoveryStart, recoveryEnd)
        val durableRestore = recoveryBody.substring(
            recoveryBody.indexOf("if (restoreSupabaseSessionFromDurableCredential()) return"),
            recoveryBody.indexOf("Durable credential session recovery failed")
        )

        assertTrue(durableRestore.contains("catch (e: CancellationException)"))
        assertTrue(durableRestore.indexOf("catch (e: CancellationException)") < durableRestore.indexOf("catch (e: Exception)"))
    }

    @Test
    fun `refresh token recovery validates durable credential status before refreshing`() {
        val source = File("app/src/main/java/com/nexio/tv/core/auth/AuthManager.kt").readText()
        val refreshStart = source.indexOf("suspend fun refreshSessionIfJwtExpired")
        val refreshEnd = source.indexOf("private suspend fun clearLocalAuthStateAfterAuthoritativeDurableRejection")
        val refreshBody = source.substring(refreshStart, refreshEnd)

        assertTrue(refreshBody.contains("enforceDurableCredentialStillActive()"))
        assertTrue(refreshBody.indexOf("enforceDurableCredentialStillActive()") < refreshBody.indexOf("auth.refreshCurrentSession()"))
    }

    @Test
    fun `refresh token recovery rethrows cancellation before broad exception handling`() {
        val source = File("app/src/main/java/com/nexio/tv/core/auth/AuthManager.kt").readText()
        val refreshStart = source.indexOf("suspend fun refreshSessionIfJwtExpired")
        val refreshEnd = source.indexOf("private suspend fun clearLocalAuthStateAfterAuthoritativeDurableRejection")
        val refreshBody = source.substring(refreshStart, refreshEnd)
        val directDurableRecovery = refreshBody.substring(
            refreshBody.indexOf("JWT expired; restoring Supabase session from durable credential"),
            refreshBody.indexOf("Durable credential was authoritatively rejected after JWT expiry")
        )
        val liveRefresh = refreshBody.substring(
            refreshBody.indexOf("auth.refreshCurrentSession()"),
            refreshBody.indexOf("resolveRefreshFailureAction(")
        )
        val fallbackDurableRecovery = refreshBody.substring(
            refreshBody.indexOf("Refresh token was authoritatively rejected after JWT expiry; restoring Supabase session from durable credential"),
            refreshBody.indexOf("Durable credential was authoritatively rejected after refresh rejection")
        )

        assertTrue(directDurableRecovery.contains("catch (recoveryError: CancellationException)"))
        assertTrue(directDurableRecovery.indexOf("catch (recoveryError: CancellationException)") < directDurableRecovery.indexOf("catch (recoveryError: Exception)"))
        assertTrue(liveRefresh.contains("catch (refreshError: CancellationException)"))
        assertTrue(liveRefresh.indexOf("catch (refreshError: CancellationException)") < liveRefresh.indexOf("catch (refreshError: Exception)"))
        assertTrue(fallbackDurableRecovery.contains("catch (recoveryError: CancellationException)"))
        assertTrue(fallbackDurableRecovery.indexOf("catch (recoveryError: CancellationException)") < fallbackDurableRecovery.indexOf("catch (recoveryError: Exception)"))
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
