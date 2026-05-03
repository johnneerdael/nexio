package com.nexio.tv.core.auth

import android.util.Log
import com.nexio.tv.data.integration.supabase.transport.DurableDeviceCredentialSelfServiceException
import com.nexio.tv.data.integration.supabase.transport.DurableDeviceCredentialSelfServiceTransport
import com.nexio.tv.data.integration.supabase.transport.DurableDeviceSessionExchangeException
import com.nexio.tv.data.integration.supabase.transport.DurableDeviceSessionTransport
import com.nexio.tv.data.integration.supabase.transport.TvLoginExchangeTransport
import com.nexio.tv.data.local.AppOnboardingDataStore
import com.nexio.tv.data.local.AuthPresenceDataStore
import com.nexio.tv.data.local.DurableDeviceCredentialSnapshot
import com.nexio.tv.data.local.DurableDeviceCredentialStore
import com.nexio.tv.data.remote.supabase.TvLoginPollResult
import com.nexio.tv.data.remote.supabase.TvLoginStartResult
import com.nexio.tv.domain.model.AuthState
import io.github.jan.supabase.auth.Auth
import io.github.jan.supabase.auth.providers.builtin.Email
import io.github.jan.supabase.auth.status.SessionStatus
import io.github.jan.supabase.postgrest.Postgrest
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import javax.inject.Inject
import javax.inject.Singleton

private const val TAG = "AuthManager"

private class AuthoritativeDurableCredentialRejectionException(message: String) : IllegalStateException(message)

@Singleton
class AuthManager @Inject constructor(
    private val auth: Auth,
    private val postgrest: Postgrest,
    private val tvLoginExchangeTransport: TvLoginExchangeTransport,
    private val durableDeviceSessionTransport: DurableDeviceSessionTransport,
    private val durableDeviceCredentialSelfServiceTransport: DurableDeviceCredentialSelfServiceTransport,
    private val authPresenceDataStore: AuthPresenceDataStore,
    private val appOnboardingDataStore: AppOnboardingDataStore,
    private val durableDeviceCredentialStore: DurableDeviceCredentialStore
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    @Volatile
    private var localSignOutInProgress = false

    private val _authState = MutableStateFlow<AuthState>(AuthState.Loading)
    val authState: StateFlow<AuthState> = _authState.asStateFlow()
    private val _sessionUserId = MutableStateFlow<String?>(null)
    val sessionUserId: StateFlow<String?> = _sessionUserId.asStateFlow()

    private var cachedEffectiveUserId: String? = null
    private var cachedEffectiveUserSourceUserId: String? = null

    init {
        retryPendingDurableCredentialRevoke()
        observeSessionStatus()
    }

    private fun observeSessionStatus() {
        scope.launch {
            auth.sessionStatus.collect { status ->
                retryPendingDurableCredentialRevoke()
                when (status) {
                    is SessionStatus.Authenticated -> {
                        val user = auth.currentUserOrNull()
                        if (user != null) {
                            val hasDurableCredential = durableDeviceCredentialStore.snapshot().isComplete
                            if (hasDurableCredential) {
                                enforceDurableCredentialStillActive()
                                if (_authState.value is AuthState.SessionLost) return@collect
                            }
                            publishAuthenticatedUser(user.id, user.email)
                        } else if (isReturningUser()) {
                            // Session says Authenticated but user hasn't been
                            // hydrated yet — for a returning user, treat as
                            // recoverable and show the reconnect nudge.
                            transitionToSessionLost()
                        }
                    }
                    is SessionStatus.NotAuthenticated -> {
                        if (shouldSuppressRecoveryForLocalSignOut(localSignOutInProgress)) {
                            transitionToSignedOut(clearPresenceMarker = false)
                            return@collect
                        }
                        auth.awaitInitialization()
                        val restoredUser = auth.currentUserOrNull()
                        if (restoredUser != null) {
                            publishAuthenticatedUser(restoredUser.id, restoredUser.email)
                            return@collect
                        }
                        val session = auth.currentSessionOrNull()
                        val hasRefreshToken = session?.refreshToken?.isNotBlank() == true
                        val returning = isReturningUser()
                        val hasDurableCredential = durableDeviceCredentialStore.snapshot().isComplete
                        when (
                            resolveNotAuthenticatedStartupAction(
                                hasRefreshToken = hasRefreshToken,
                                isReturningUser = returning,
                                hasDurableCredential = hasDurableCredential
                            )
                        ) {
                            NotAuthenticatedStartupAction.REFRESH_LIVE_SESSION -> {
                                scope.launch {
                                    try {
                                        auth.refreshCurrentSession()
                                    } catch (e: CancellationException) {
                                        throw e
                                    } catch (e: Exception) {
                                        // Only sign the user out if the refresh token was
                                        // *authoritatively* rejected by the server. Transient
                                        // failures (network down, DNS warm-up after an upgrade,
                                        // 5xx, timeouts) must NOT clear the session — the SDK
                                        // or the next sync attempt will retry.
                                        when (
                                            resolveRefreshFailureAction(
                                                refreshError = e,
                                                hasDurableCredential = durableDeviceCredentialStore.snapshot().isComplete
                                            )
                                        ) {
                                            RefreshFailureAction.ATTEMPT_DURABLE_RECOVERY -> {
                                                Log.w(TAG, "Refresh token rejected; attempting durable recovery", e)
                                                attemptSilentSessionRecovery(ignoreCachedRefreshToken = true)
                                            }
                                            RefreshFailureAction.TRANSITION_SIGNED_OUT -> {
                                                Log.w(TAG, "Refresh token rejected; signing out", e)
                                                transitionToSignedOut()
                                            }
                                            RefreshFailureAction.KEEP_CURRENT_AUTH_STATE -> {
                                                Log.w(
                                                    TAG,
                                                    "Transient session refresh failure; keeping current auth state",
                                                    e
                                                )
                                            }
                                        }
                                    }
                                }
                            }
                            NotAuthenticatedStartupAction.ATTEMPT_RETURNING_USER_RECOVERY -> {
                                Log.w(
                                    TAG,
                                    "Supabase reports no session but recovery signal is set; keeping state and retrying"
                                )
                                scope.launch { attemptSilentSessionRecovery() }
                            }
                            NotAuthenticatedStartupAction.TRANSITION_SIGNED_OUT -> {
                                transitionToSignedOut()
                            }
                        }
                    }
                    is SessionStatus.Initializing -> {
                        _sessionUserId.value = sessionUserIdWhileSessionUnavailable()
                        _authState.value = AuthState.Loading
                    }
                    else -> { /* NetworkError etc. — keep current state */ }
                }
            }
        }
    }

    private suspend fun readHadAuthenticatedSession(): Boolean {
        return try {
            authPresenceDataStore.hadAuthenticatedSession.first()
        } catch (e: Exception) {
            Log.w(TAG, "Failed to read auth presence marker; assuming none", e)
            false
        }
    }

    private suspend fun readHasCompletedOnboardingQr(): Boolean {
        return try {
            appOnboardingDataStore.hasSeenAuthQrOnFirstLaunch.first()
        } catch (e: Exception) {
            Log.w(TAG, "Failed to read onboarding QR flag; assuming none", e)
            false
        }
    }

    /**
     * Returns true if any durable signal on-device suggests this user has
     * authenticated before — either the presence marker (populated on every
     * Authenticated event since the marker shipped) or the onboarding QR
     * flag (set when the user completed the first-run QR sign-in, which
     * predates the marker). Either signal is enough to treat a session-miss
     * as recoverable rather than a fresh-install SignedOut.
     */
    private suspend fun isReturningUser(): Boolean {
        if (readHadAuthenticatedSession()) return true
        return readHasCompletedOnboardingQr()
    }

    private suspend fun attemptSilentSessionRecovery(ignoreCachedRefreshToken: Boolean = false) {
        // Give the SDK's own storage layer a few chances to hydrate the session
        // before we commit to any SignedOut transition. Each iteration re-checks
        // init, user, and session. If the user re-appears, Supabase will emit
        // Authenticated on its own and we'll pick it up in the collector.
        var shouldAttemptDurableAfterRefreshRejection = ignoreCachedRefreshToken
        repeat(3) { attempt ->
            if (shouldAttemptDurableAfterRefreshRejection) return@repeat
            delay(500L * (attempt + 1))
            try {
                auth.awaitInitialization()
                if (auth.currentUserOrNull() != null) return
                val session = auth.currentSessionOrNull()
                when (
                    resolveJwtExpiryRecoveryAction(
                        hasRefreshToken = session?.refreshToken?.isNotBlank() == true,
                        credential = durableDeviceCredentialStore.snapshot(),
                        ignoreCachedRefreshToken = shouldAttemptDurableAfterRefreshRejection
                    )
                ) {
                    JwtExpiryRecoveryAction.REFRESH_LIVE_SESSION -> {
                        auth.refreshCurrentSession()
                        return
                    }
                    JwtExpiryRecoveryAction.ATTEMPT_DURABLE_RECOVERY -> {
                        shouldAttemptDurableAfterRefreshRejection = true
                    }
                    JwtExpiryRecoveryAction.NO_RECOVERY_PATH -> Unit
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                when (
                    resolveRefreshFailureAction(
                        refreshError = e,
                        hasDurableCredential = durableDeviceCredentialStore.snapshot().isComplete
                    )
                ) {
                    RefreshFailureAction.ATTEMPT_DURABLE_RECOVERY -> {
                        Log.w(TAG, "Authoritative rejection during silent recovery; attempting durable recovery", e)
                        shouldAttemptDurableAfterRefreshRejection = true
                    }
                    RefreshFailureAction.TRANSITION_SIGNED_OUT -> {
                        Log.w(TAG, "Authoritative rejection during silent recovery; signing out", e)
                        transitionToSignedOut()
                        return
                    }
                    RefreshFailureAction.KEEP_CURRENT_AUTH_STATE -> {
                        Log.w(TAG, "Silent session recovery attempt failed; will retry", e)
                    }
                }
            }
        }
        val credential = durableDeviceCredentialStore.snapshot()
        if (credential.isComplete) {
            enforceDurableCredentialStillActive()
            if (_authState.value is AuthState.SessionLost) return
        }
        if (
            shouldAttemptDurableSessionRecovery(
                hasRefreshToken = auth.currentSessionOrNull()?.refreshToken?.isNotBlank() == true,
                credential = credential,
                ignoreCachedRefreshToken = shouldAttemptDurableAfterRefreshRejection
            )
        ) {
            try {
                if (restoreSupabaseSessionFromDurableCredential(credential)) return
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                when (
                    resolveDurableRecoveryFailureAction(
                        isAuthoritativeRejection = e is AuthoritativeDurableCredentialRejectionException
                    )
                ) {
                    DurableRecoveryFailureAction.CLEAR_DURABLE_CREDENTIAL_AND_TRANSITION_SESSION_LOST -> {
                        Log.w(
                            TAG,
                            "Durable credential was authoritatively rejected during silent recovery; clearing local auth state",
                            e
                        )
                        clearLocalAuthStateAfterAuthoritativeDurableRejection()
                        return
                    }
                    DurableRecoveryFailureAction.KEEP_CURRENT_AUTH_STATE -> {
                        Log.w(TAG, "Durable credential session recovery failed", e)
                    }
                }
            }
        }
        Log.w(
            TAG,
            "Silent session recovery exhausted without restoring a session; marking SessionLost for returning user"
        )
        transitionToSessionLost()
    }

    private fun transitionToSessionLost() {
        _sessionUserId.value = null
        cachedEffectiveUserId = null
        cachedEffectiveUserSourceUserId = null
        _authState.value = AuthState.SessionLost
        // Intentionally do NOT clear the presence marker — the user has not
        // explicitly signed out, and the next cold start should still treat
        // them as returning.
    }

    private fun transitionToSignedOut(clearPresenceMarker: Boolean = true) {
        _sessionUserId.value = null
        cachedEffectiveUserId = null
        cachedEffectiveUserSourceUserId = null
        _authState.value = AuthState.SignedOut
        if (clearPresenceMarker) {
            scope.launch {
                try {
                    authPresenceDataStore.clear()
                } catch (e: Exception) {
                    Log.w(TAG, "Failed to clear auth presence marker", e)
                }
            }
        }
    }

    val isAuthenticated: Boolean
        get() = _authState.value is AuthState.FullAccount

    val hasSyncSession: Boolean
        get() = hasLiveFullAccountSyncSession(_authState.value, _sessionUserId.value)

    val currentSessionUserId: String?
        get() = _sessionUserId.value

    val currentUserId: String?
        get() = when (val state = _authState.value) {
            is AuthState.FullAccount -> state.userId
            else -> null
        }

    private suspend fun publishAuthenticatedUser(userId: String, email: String?) {
        _sessionUserId.value = userId
        if (cachedEffectiveUserSourceUserId != userId) {
            cachedEffectiveUserId = null
            cachedEffectiveUserSourceUserId = null
        }
        val computed = fullAccountStateForSupabaseUser(userId = userId, email = email)
        val newState = if (computed !is AuthState.FullAccount && isReturningUser()) {
            // Supabase reports "authenticated" but with no email — typically a
            // stale anonymous session left over from a QR-pairing attempt.
            // For a returning user, this should surface the reconnect nudge,
            // not the fresh-install sign-in pitch.
            AuthState.SessionLost
        } else {
            computed
        }
        _authState.value = newState
        if (newState is AuthState.FullAccount) {
            scope.launch {
                try {
                    authPresenceDataStore.markAuthenticated(userId)
                } catch (e: Exception) {
                    Log.w(TAG, "Failed to persist auth presence marker", e)
                }
            }
        }
    }

    /**
     * Returns the effective user ID for data operations.
     * For sync-linked devices, this returns the sync owner's user ID.
     * For direct users, returns their own user ID.
     */
    suspend fun getEffectiveUserId(fallbackToOwnIdOnFailure: Boolean = true): String? {
        val userId = currentSessionUserId ?: return null
        if (cachedEffectiveUserSourceUserId != userId) {
            cachedEffectiveUserId = null
            cachedEffectiveUserSourceUserId = null
        }
        cachedEffectiveUserId?.let { return it }

        suspend fun resolveAndCache(): String {
            val result = postgrest.rpc("get_sync_owner")
            val effectiveId = result.decodeAs<String>()
            cachedEffectiveUserId = effectiveId
            cachedEffectiveUserSourceUserId = userId
            return effectiveId
        }

        return try {
            resolveAndCache()
        } catch (e: Exception) {
            if (refreshSessionIfJwtExpired(e)) {
                return try {
                    resolveAndCache()
                } catch (retryError: Exception) {
                    if (fallbackToOwnIdOnFailure) {
                        Log.e(TAG, "Failed to get effective user ID after refresh; falling back to own ID", retryError)
                        userId
                    } else {
                        Log.e(TAG, "Failed to get effective user ID after refresh", retryError)
                        null
                    }
                }
            }

            if (fallbackToOwnIdOnFailure) {
                Log.e(TAG, "Failed to get effective user ID, falling back to own ID", e)
                userId
            } else {
                Log.e(TAG, "Failed to get effective user ID", e)
                null
            }
        }
    }

    suspend fun signUpWithEmail(email: String, password: String): Result<Unit> {
        return try {
            auth.signUpWith(Email) {
                this.email = email
                this.password = password
            }
            Result.success(Unit)
        } catch (e: Exception) {
            Log.e(TAG, "Sign up failed", e)
            Result.failure(e)
        }
    }

    suspend fun signInWithEmail(email: String, password: String): Result<Unit> {
        return try {
            auth.signInWith(Email) {
                this.email = email
                this.password = password
            }
            Result.success(Unit)
        } catch (e: Exception) {
            Log.e(TAG, "Sign in failed", e)
            Result.failure(e)
        }
    }

    /**
     * QR login RPCs currently require an authenticated Supabase session.
     * This creates/reuses an anonymous session only for the QR flow while
     * keeping app-level auth state exposed as SignedOut until a full account exists.
     */
    suspend fun ensureQrSessionAuthenticated(): Result<Unit> {
        auth.awaitInitialization()
        val user = auth.currentUserOrNull()
        val hasToken = auth.currentAccessTokenOrNull() != null

        if (user != null && hasToken) {
            return Result.success(Unit)
        }

        return try {
            auth.signInAnonymously()
            Result.success(Unit)
        } catch (e: Exception) {
            Log.e(TAG, "QR anonymous sign in failed", e)
            Result.failure(e)
        }
    }

    suspend fun signOut() {
        localSignOutInProgress = true
        transitionToSignedOut(clearPresenceMarker = false)
        cachedEffectiveUserId = null
        cachedEffectiveUserSourceUserId = null
        try {
            handleManualSignOut(
                clearPresenceMarker = {
                    authPresenceDataStore.clear()
                },
                prepareDurableCredentialRevoke = {
                    prepareDurableCredentialRevokeForLogout()
                },
                revokeDurableCredential = {
                    revokePendingDurableCredentialIfPresent()
                },
                clearDurableCredential = {
                    durableDeviceCredentialStore.clear()
                },
                clearSupabaseSession = {
                    try {
                        auth.signOut()
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        Log.w(TAG, "Remote Supabase sign-out failed; clearing local session", e)
                        auth.clearSession()
                    }
                }
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "Sign out failed", e)
        } finally {
            localSignOutInProgress = false
        }
    }

    fun clearEffectiveUserIdCache() {
        cachedEffectiveUserId = null
        cachedEffectiveUserSourceUserId = null
    }

    suspend fun refreshSessionIfJwtExpired(error: Throwable): Boolean {
        if (!error.isJwtExpiredError()) return false
        val credential = durableDeviceCredentialStore.snapshot()
        val hasRefreshToken = auth.currentSessionOrNull()?.refreshToken?.isNotBlank() == true
        return when (
            resolveJwtExpiryRecoveryAction(
                hasRefreshToken = hasRefreshToken,
                credential = credential
            )
        ) {
            JwtExpiryRecoveryAction.REFRESH_LIVE_SESSION -> {
                try {
                    Log.w(TAG, "JWT expired; refreshing Supabase session and retrying request")
                    auth.refreshCurrentSession()
                    true
                } catch (refreshError: CancellationException) {
                    throw refreshError
                } catch (refreshError: Exception) {
                    when (
                        resolveRefreshFailureAction(
                            refreshError = refreshError,
                            hasDurableCredential = credential.isComplete
                        )
                    ) {
                        RefreshFailureAction.ATTEMPT_DURABLE_RECOVERY -> {
                            Log.w(
                                TAG,
                                "Refresh token was authoritatively rejected after JWT expiry; restoring from durable credential",
                                refreshError
                            )
                            attemptDurableSessionRecoveryAfterJwtExpiry(credential)
                        }
                        RefreshFailureAction.TRANSITION_SIGNED_OUT,
                        RefreshFailureAction.KEEP_CURRENT_AUTH_STATE -> {
                            Log.e(TAG, "Failed to refresh Supabase session after JWT expiry", refreshError)
                            false
                        }
                    }
                }
            }
            JwtExpiryRecoveryAction.ATTEMPT_DURABLE_RECOVERY -> {
                Log.w(TAG, "JWT expired; restoring Supabase session from durable credential")
                attemptDurableSessionRecoveryAfterJwtExpiry(credential)
            }
            JwtExpiryRecoveryAction.NO_RECOVERY_PATH -> {
                Log.w(TAG, "JWT expired but no refresh token or durable credential available; cannot refresh session")
                false
            }
        }
    }

    private suspend fun attemptDurableSessionRecoveryAfterJwtExpiry(
        credential: DurableDeviceCredentialSnapshot
    ): Boolean {
        return try {
            restoreSupabaseSessionFromDurableCredential(credential)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            when (
                resolveDurableRecoveryFailureAction(
                    isAuthoritativeRejection = e is AuthoritativeDurableCredentialRejectionException
                )
            ) {
                DurableRecoveryFailureAction.CLEAR_DURABLE_CREDENTIAL_AND_TRANSITION_SESSION_LOST -> {
                    Log.w(
                        TAG,
                        "Durable credential was authoritatively rejected after JWT expiry; clearing credential",
                        e
                    )
                    clearLocalAuthStateAfterAuthoritativeDurableRejection()
                }
                DurableRecoveryFailureAction.KEEP_CURRENT_AUTH_STATE -> {
                    Log.e(TAG, "Failed to restore Supabase session from durable credential after JWT expiry", e)
                }
            }
            false
        }
    }

    private suspend fun restoreSupabaseSessionFromDurableCredential(
        credential: DurableDeviceCredentialSnapshot? = null
    ): Boolean {
        val resolvedCredential = credential ?: durableDeviceCredentialStore.snapshot()
        if (!resolvedCredential.isComplete) return false
        try {
            val result = durableDeviceSessionTransport.exchangeSession(
                devicePublicId = resolvedCredential.devicePublicId.orEmpty(),
                deviceSecret = resolvedCredential.deviceSecret.orEmpty()
            )
            auth.importAuthToken(result.accessToken, result.refreshToken)
            return true
        } catch (e: DurableDeviceSessionExchangeException) {
            if (e.statusCode == 401 || e.statusCode == 403) {
                throw AuthoritativeDurableCredentialRejectionException(e.message.orEmpty())
            }
            throw e
        }
    }

    private suspend fun clearLocalAuthStateAfterAuthoritativeDurableRejection() {
        durableDeviceCredentialStore.clear()
        auth.clearSession()
        transitionToSessionLost()
    }

    private suspend fun enforceDurableCredentialStillActive() {
        when (
            resolveDurableCredentialStatusAction(validateDurableCredentialStillActive())
        ) {
            DurableCredentialStatusAction.CLEAR_DURABLE_CREDENTIAL_AND_TRANSITION_SESSION_LOST -> {
                Log.w(TAG, "Durable credential is revoked; clearing local auth state")
                clearLocalAuthStateAfterAuthoritativeDurableRejection()
            }
            DurableCredentialStatusAction.KEEP_CURRENT_AUTH_STATE -> Unit
        }
    }

    private fun retryPendingDurableCredentialRevoke() {
        scope.launch {
            try {
                revokePendingDurableCredentialIfPresent()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "Pending durable credential revoke retry failed", e)
            }
        }
    }

    private suspend fun prepareDurableCredentialRevokeForLogout() {
        val credential = durableDeviceCredentialStore.snapshot()
        if (!credential.isComplete) return
        durableDeviceCredentialStore.savePendingRevoke(
            devicePublicId = credential.devicePublicId.orEmpty(),
            deviceSecret = credential.deviceSecret.orEmpty()
        )
    }

    private suspend fun revokePendingDurableCredentialIfPresent() {
        val pending = durableDeviceCredentialStore.pendingRevokeSnapshot()
        if (!pending.isComplete) return

        val response = durableDeviceCredentialSelfServiceTransport.revoke(
            devicePublicId = pending.devicePublicId.orEmpty(),
            deviceSecret = pending.deviceSecret.orEmpty()
        )
        if (response.revoked || response.status.equals("revoked", ignoreCase = true)) {
            durableDeviceCredentialStore.clearPendingRevoke()
        }
    }

    private suspend fun validateDurableCredentialStillActive(): DurableCredentialRemoteStatus {
        val credential = durableDeviceCredentialStore.snapshot()
        if (!credential.isComplete) return DurableCredentialRemoteStatus.UNKNOWN

        return try {
            val response = durableDeviceCredentialSelfServiceTransport.status(
                devicePublicId = credential.devicePublicId.orEmpty(),
                deviceSecret = credential.deviceSecret.orEmpty()
            )
            when {
                response.revoked || response.status.equals("revoked", ignoreCase = true) ->
                    DurableCredentialRemoteStatus.REVOKED
                response.active || response.status.equals("active", ignoreCase = true) ->
                    DurableCredentialRemoteStatus.ACTIVE
                else -> DurableCredentialRemoteStatus.UNKNOWN
            }
        } catch (e: DurableDeviceCredentialSelfServiceException) {
            if (e.statusCode == 401 || e.statusCode == 403) {
                DurableCredentialRemoteStatus.REVOKED
            } else {
                Log.w(TAG, "Durable credential status check failed; keeping current auth state", e)
                DurableCredentialRemoteStatus.UNKNOWN
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "Durable credential status check failed; keeping current auth state", e)
            DurableCredentialRemoteStatus.UNKNOWN
        }
    }

    suspend fun startTvLoginSession(deviceNonce: String, deviceName: String?, redirectBaseUrl: String): Result<TvLoginStartResult> {
        return try {
            Result.success(
                startTvLoginSessionRpc(
                    deviceNonce = deviceNonce,
                    deviceName = deviceName,
                    redirectBaseUrl = redirectBaseUrl
                )
            )
        } catch (e: Exception) {
            val message = e.message.orEmpty().lowercase()
            val shouldRetryLegacySignature = !deviceName.isNullOrBlank() &&
                message.contains("could not find the function") &&
                message.contains("start_tv_login_session") &&
                message.contains("p_device_name")

            if (shouldRetryLegacySignature) {
                return try {
                    Log.w(TAG, "start_tv_login_session legacy signature detected; retrying without p_device_name")
                    Result.success(
                        startTvLoginSessionRpc(
                            deviceNonce = deviceNonce,
                            deviceName = null,
                            redirectBaseUrl = redirectBaseUrl
                        )
                    )
                } catch (retryError: Exception) {
                    Log.e(TAG, "Failed to start TV login session after legacy retry", retryError)
                    Result.failure(retryError)
                }
            }

            Log.e(TAG, "Failed to start TV login session", e)
            Result.failure(e)
        }
    }

    private suspend fun startTvLoginSessionRpc(
        deviceNonce: String,
        deviceName: String?,
        redirectBaseUrl: String
    ): TvLoginStartResult {
        val params = buildJsonObject {
            put("p_device_nonce", deviceNonce)
            put("p_redirect_base_url", redirectBaseUrl)
            if (!deviceName.isNullOrBlank()) put("p_device_name", deviceName)
        }
        val response = postgrest.rpc("start_tv_login_session", params)
        return response.decodeList<TvLoginStartResult>().firstOrNull()
            ?: throw Exception("Empty response from start_tv_login_session")
    }

    suspend fun pollTvLoginSession(code: String, deviceNonce: String): Result<TvLoginPollResult> {
        return try {
            val params = buildJsonObject {
                put("p_code", code)
                put("p_device_nonce", deviceNonce)
            }
            val response = postgrest.rpc("poll_tv_login_session", params)
            val result = response.decodeList<TvLoginPollResult>().firstOrNull()
                ?: return Result.failure(Exception("Empty response from poll_tv_login_session"))
            Result.success(result)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to poll TV login session", e)
            Result.failure(e)
        }
    }

    suspend fun exchangeTvLoginSession(code: String, deviceNonce: String): Result<Unit> {
        return try {
            val token = auth.currentAccessTokenOrNull()
                ?: return Result.failure(Exception("Not authenticated"))
            val result = tvLoginExchangeTransport.exchange(
                token = token,
                code = code,
                deviceNonce = deviceNonce
            )
            auth.importAuthToken(result.accessToken, result.refreshToken)
            durableDeviceCredentialStore.save(
                devicePublicId = result.devicePublicId,
                deviceSecret = result.deviceSecret
            )
            Result.success(Unit)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to exchange TV login session", e)
            Result.failure(e)
        }
    }
}

internal fun fullAccountStateForSupabaseUser(userId: String, email: String?): AuthState {
    val normalizedUserId = userId.trim()
    if (normalizedUserId.isBlank()) return AuthState.SignedOut
    // Anonymous Supabase sessions (created via signInAnonymously for QR-pairing RPCs)
    // have no email. Treat them as SignedOut so the QR login screen stays visible until
    // a real account exists. Prefer UserInfo.isAnonymous if phone-only or magic-link
    // auth is added later — the email heuristic assumes email+password is the only
    // real-account auth method.
    val normalizedEmail = email?.trim()?.takeIf { it.isNotBlank() } ?: return AuthState.SignedOut
    return AuthState.FullAccount(userId = normalizedUserId, email = normalizedEmail)
}

internal enum class NotAuthenticatedStartupAction {
    REFRESH_LIVE_SESSION,
    ATTEMPT_RETURNING_USER_RECOVERY,
    TRANSITION_SIGNED_OUT
}

internal enum class JwtExpiryRecoveryAction {
    ATTEMPT_DURABLE_RECOVERY,
    REFRESH_LIVE_SESSION,
    NO_RECOVERY_PATH
}

internal enum class AuthoritativeRefreshRejectionAction {
    ATTEMPT_DURABLE_RECOVERY,
    TRANSITION_SIGNED_OUT
}

internal enum class DurableRecoveryFailureAction {
    KEEP_CURRENT_AUTH_STATE,
    CLEAR_DURABLE_CREDENTIAL_AND_TRANSITION_SESSION_LOST
}

internal enum class RefreshFailureAction {
    ATTEMPT_DURABLE_RECOVERY,
    TRANSITION_SIGNED_OUT,
    KEEP_CURRENT_AUTH_STATE
}

internal enum class DurableCredentialRemoteStatus {
    ACTIVE,
    REVOKED,
    UNKNOWN
}

internal enum class DurableCredentialStatusAction {
    KEEP_CURRENT_AUTH_STATE,
    CLEAR_DURABLE_CREDENTIAL_AND_TRANSITION_SESSION_LOST
}

internal fun resolveDurableCredentialStatusAction(
    status: DurableCredentialRemoteStatus
): DurableCredentialStatusAction {
    return when (status) {
        DurableCredentialRemoteStatus.REVOKED ->
            DurableCredentialStatusAction.CLEAR_DURABLE_CREDENTIAL_AND_TRANSITION_SESSION_LOST
        DurableCredentialRemoteStatus.ACTIVE,
        DurableCredentialRemoteStatus.UNKNOWN ->
            DurableCredentialStatusAction.KEEP_CURRENT_AUTH_STATE
    }
}

internal fun resolveNotAuthenticatedStartupAction(
    hasRefreshToken: Boolean,
    isReturningUser: Boolean,
    hasDurableCredential: Boolean
): NotAuthenticatedStartupAction {
    if (hasRefreshToken) return NotAuthenticatedStartupAction.REFRESH_LIVE_SESSION
    if (hasDurableCredential) return NotAuthenticatedStartupAction.ATTEMPT_RETURNING_USER_RECOVERY
    if (isReturningUser) return NotAuthenticatedStartupAction.ATTEMPT_RETURNING_USER_RECOVERY
    return NotAuthenticatedStartupAction.TRANSITION_SIGNED_OUT
}

internal fun resolveJwtExpiryRecoveryAction(
    hasRefreshToken: Boolean,
    credential: DurableDeviceCredentialSnapshot,
    ignoreCachedRefreshToken: Boolean = false
): JwtExpiryRecoveryAction {
    if (hasRefreshToken && !ignoreCachedRefreshToken) return JwtExpiryRecoveryAction.REFRESH_LIVE_SESSION
    if (credential.isComplete) return JwtExpiryRecoveryAction.ATTEMPT_DURABLE_RECOVERY
    return JwtExpiryRecoveryAction.NO_RECOVERY_PATH
}

internal fun resolveAuthoritativeRefreshRejectionAction(
    hasDurableCredential: Boolean
): AuthoritativeRefreshRejectionAction {
    return if (hasDurableCredential) {
        AuthoritativeRefreshRejectionAction.ATTEMPT_DURABLE_RECOVERY
    } else {
        AuthoritativeRefreshRejectionAction.TRANSITION_SIGNED_OUT
    }
}

internal fun resolveDurableRecoveryFailureAction(
    isAuthoritativeRejection: Boolean
): DurableRecoveryFailureAction {
    return if (isAuthoritativeRejection) {
        DurableRecoveryFailureAction.CLEAR_DURABLE_CREDENTIAL_AND_TRANSITION_SESSION_LOST
    } else {
        DurableRecoveryFailureAction.KEEP_CURRENT_AUTH_STATE
    }
}

internal fun resolveRefreshFailureAction(
    refreshError: Throwable,
    hasDurableCredential: Boolean
): RefreshFailureAction {
    if (!refreshError.isAuthoritativeRefreshRejection()) {
        return RefreshFailureAction.KEEP_CURRENT_AUTH_STATE
    }

    return when (
        resolveAuthoritativeRefreshRejectionAction(
            hasDurableCredential = hasDurableCredential
        )
    ) {
        AuthoritativeRefreshRejectionAction.ATTEMPT_DURABLE_RECOVERY ->
            RefreshFailureAction.ATTEMPT_DURABLE_RECOVERY
        AuthoritativeRefreshRejectionAction.TRANSITION_SIGNED_OUT ->
            RefreshFailureAction.TRANSITION_SIGNED_OUT
    }
}

internal fun shouldAttemptDurableSessionRecovery(
    hasRefreshToken: Boolean,
    credential: DurableDeviceCredentialSnapshot,
    ignoreCachedRefreshToken: Boolean
): Boolean {
    if (!credential.isComplete) return false
    return !hasRefreshToken || ignoreCachedRefreshToken
}

internal fun sessionUserIdWhileSessionUnavailable(): String? = null

internal fun shouldSuppressRecoveryForLocalSignOut(isLocalSignOutInProgress: Boolean): Boolean =
    isLocalSignOutInProgress

internal fun hasLiveFullAccountSyncSession(
    authState: AuthState,
    sessionUserId: String?
): Boolean {
    return authState is AuthState.FullAccount && !sessionUserId.isNullOrBlank()
}

internal fun liveFullAccountSessionUserId(
    authState: AuthState,
    sessionUserId: String?
): String? {
    return sessionUserId?.takeIf { hasLiveFullAccountSyncSession(authState, it) }
}

internal suspend fun handleManualSignOut(
    clearPresenceMarker: suspend () -> Unit,
    prepareDurableCredentialRevoke: suspend () -> Unit,
    revokeDurableCredential: suspend () -> Unit,
    clearDurableCredential: suspend () -> Unit,
    clearSupabaseSession: suspend () -> Unit
) {
    try {
        clearPresenceMarker()
    } catch (clearError: CancellationException) {
        throw clearError
    } catch (clearError: Exception) {
        Log.w(TAG, "Failed clearing auth presence marker on sign-out", clearError)
    }
    try {
        prepareDurableCredentialRevoke()
    } catch (clearError: CancellationException) {
        throw clearError
    } catch (clearError: Exception) {
        Log.w(TAG, "Failed preparing durable device credential revoke on sign-out", clearError)
        return
    }
    try {
        revokeDurableCredential()
    } catch (clearError: CancellationException) {
        throw clearError
    } catch (clearError: Exception) {
        Log.w(TAG, "Failed revoking durable device credential on sign-out", clearError)
    }
    try {
        clearDurableCredential()
    } catch (clearError: CancellationException) {
        throw clearError
    } catch (clearError: Exception) {
        Log.w(TAG, "Failed clearing durable device credential on sign-out", clearError)
    }
    clearSupabaseSession()
}

private fun Throwable.isJwtExpiredError(): Boolean {
    var current: Throwable? = this
    while (current != null) {
        if (current.message?.contains("jwt expired", ignoreCase = true) == true) return true
        current = current.cause
    }
    return false
}

/**
 * Returns true only if this exception represents the auth server *authoritatively*
 * rejecting the refresh token (so the user really must sign in again). Network /
 * transport / 5xx errors are NOT authoritative — they should be retried, not used
 * as grounds to drop the session. Bug history: post-upgrade cold-starts hit a
 * brief network gap and we used to log users out on the resulting IOException.
 */
private fun Throwable.isAuthoritativeRefreshRejection(): Boolean {
    var current: Throwable? = this
    while (current != null) {
        if (current is java.io.IOException) return false
        val message = current.message?.lowercase().orEmpty()
        if (message.isNotBlank()) {
            if (
                message.contains("invalid_grant") ||
                message.contains("invalid refresh token") ||
                message.contains("refresh token not found") ||
                message.contains("refresh_token_not_found") ||
                message.contains("user not found") ||
                message.contains("user_not_found") ||
                message.contains("token has been revoked") ||
                message.contains("already used")
            ) {
                return true
            }
        }
        current = current.cause
    }
    return false
}
