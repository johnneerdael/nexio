package com.nexio.tv.core.auth

import android.util.Log
import com.nexio.tv.BuildConfig
import com.nexio.tv.data.local.AppOnboardingDataStore
import com.nexio.tv.data.local.AuthPresenceDataStore
import com.nexio.tv.data.local.DurableDeviceCredentialSnapshot
import com.nexio.tv.data.local.DurableDeviceCredentialStore
import com.nexio.tv.data.remote.supabase.DurableDeviceCredentialIssueResult
import com.nexio.tv.data.remote.supabase.DurableDeviceSessionExchangeResult
import com.nexio.tv.data.remote.supabase.TvLoginPollResult
import com.nexio.tv.data.remote.supabase.TvLoginStartResult
import com.nexio.tv.domain.model.AuthState
import io.github.jan.supabase.auth.Auth
import io.github.jan.supabase.auth.providers.builtin.Email
import io.github.jan.supabase.auth.status.SessionStatus
import io.github.jan.supabase.postgrest.Postgrest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

private const val TAG = "AuthManager"

@Singleton
class AuthManager @Inject constructor(
    private val auth: Auth,
    private val postgrest: Postgrest,
    private val httpClient: OkHttpClient,
    private val authPresenceDataStore: AuthPresenceDataStore,
    private val appOnboardingDataStore: AppOnboardingDataStore,
    private val durableDeviceCredentialStore: DurableDeviceCredentialStore
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val json = Json { ignoreUnknownKeys = true }

    private val _authState = MutableStateFlow<AuthState>(AuthState.Loading)
    val authState: StateFlow<AuthState> = _authState.asStateFlow()
    private val _sessionUserId = MutableStateFlow<String?>(null)
    val sessionUserId: StateFlow<String?> = _sessionUserId.asStateFlow()

    private var cachedEffectiveUserId: String? = null
    private var cachedEffectiveUserSourceUserId: String? = null

    init {
        observeSessionStatus()
    }

    private fun observeSessionStatus() {
        scope.launch {
            auth.sessionStatus.collect { status ->
                when (status) {
                    is SessionStatus.Authenticated -> {
                        val user = auth.currentUserOrNull()
                        if (user != null) {
                            publishAuthenticatedUser(user.id, user.email)
                        } else if (isReturningUser()) {
                            // Session says Authenticated but user hasn't been
                            // hydrated yet — for a returning user, treat as
                            // recoverable and show the reconnect nudge.
                            transitionToSessionLost()
                        }
                    }
                    is SessionStatus.NotAuthenticated -> {
                        _sessionUserId.value = sessionUserIdWhileSessionUnavailable()
                        auth.awaitInitialization()
                        val hasCachedIdentity = auth.currentUserOrNull() != null
                        val session = auth.currentSessionOrNull()
                        val hasRefreshToken = session?.refreshToken?.isNotBlank() == true
                        val returning = isReturningUser()
                        when (
                            resolveNotAuthenticatedStartupAction(
                                hasCachedIdentity = hasCachedIdentity,
                                hasRefreshToken = hasRefreshToken,
                                isReturningUser = returning
                            )
                        ) {
                            NotAuthenticatedStartupAction.REFRESH_LIVE_SESSION -> {
                                scope.launch {
                                    try {
                                        auth.refreshCurrentSession()
                                    } catch (e: Exception) {
                                        // Only sign the user out if the refresh token was
                                        // *authoritatively* rejected by the server. Transient
                                        // failures (network down, DNS warm-up after an upgrade,
                                        // 5xx, timeouts) must NOT clear the session — the SDK
                                        // or the next sync attempt will retry.
                                        if (e.isAuthoritativeRefreshRejection()) {
                                            Log.w(TAG, "Refresh token rejected; signing out", e)
                                            transitionToSignedOut()
                                        } else {
                                            Log.w(
                                                TAG,
                                                "Transient session refresh failure; keeping current auth state",
                                                e
                                            )
                                        }
                                    }
                                }
                            }
                            NotAuthenticatedStartupAction.ATTEMPT_RETURNING_USER_RECOVERY -> {
                                Log.w(
                                    TAG,
                                    "Supabase reports no live session; cached identity alone is insufficient, attempting returning-user recovery"
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

    private suspend fun attemptSilentSessionRecovery() {
        // Give the SDK's own storage layer a few chances to hydrate the session
        // before we commit to any SignedOut transition. Each iteration re-checks
        // init, user, and session. If the user re-appears, Supabase will emit
        // Authenticated on its own and we'll pick it up in the collector.
        repeat(3) { attempt ->
            delay(500L * (attempt + 1))
            try {
                auth.awaitInitialization()
                if (auth.currentUserOrNull() != null) return
                val session = auth.currentSessionOrNull()
                if (session?.refreshToken?.isNotBlank() == true) {
                    auth.refreshCurrentSession()
                    return
                }
            } catch (e: Exception) {
                if (e.isAuthoritativeRefreshRejection()) {
                    Log.w(TAG, "Authoritative rejection during silent recovery; signing out", e)
                    transitionToSignedOut()
                    return
                }
                Log.w(TAG, "Silent session recovery attempt failed; will retry", e)
            }
        }
        if (
            shouldAttemptDurableSessionRecovery(
                isReturningUser = true,
                hasRefreshToken = auth.currentSessionOrNull()?.refreshToken?.isNotBlank() == true,
                credential = durableDeviceCredentialStore.snapshot()
            )
        ) {
            try {
                if (restoreSupabaseSessionFromDurableCredential()) return
            } catch (e: Exception) {
                Log.w(TAG, "Durable credential session recovery failed", e)
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

    private fun transitionToSignedOut() {
        _sessionUserId.value = null
        cachedEffectiveUserId = null
        cachedEffectiveUserSourceUserId = null
        _authState.value = AuthState.SignedOut
        scope.launch {
            try {
                authPresenceDataStore.clear()
            } catch (e: Exception) {
                Log.w(TAG, "Failed to clear auth presence marker", e)
            }
            try {
                durableDeviceCredentialStore.clear()
            } catch (e: Exception) {
                Log.w(TAG, "Failed to clear durable device credential", e)
            }
        }
    }

    val isAuthenticated: Boolean
        get() = _authState.value is AuthState.FullAccount

    val hasSyncSession: Boolean
        get() = _sessionUserId.value != null

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
        try {
            auth.signOut()
        } catch (e: Exception) {
            Log.e(TAG, "Sign out failed", e)
        }
        cachedEffectiveUserId = null
        cachedEffectiveUserSourceUserId = null
        try {
            authPresenceDataStore.clear()
        } catch (e: Exception) {
            Log.w(TAG, "Failed to clear auth presence marker on sign-out", e)
        }
        try {
            durableDeviceCredentialStore.clear()
        } catch (e: Exception) {
            Log.w(TAG, "Failed to clear durable device credential on sign-out", e)
        }
    }

    fun clearEffectiveUserIdCache() {
        cachedEffectiveUserId = null
        cachedEffectiveUserSourceUserId = null
    }

    suspend fun refreshSessionIfJwtExpired(error: Throwable): Boolean {
        if (!error.isJwtExpiredError()) return false
        val hasRefreshToken = auth.currentSessionOrNull()?.refreshToken?.isNotBlank() == true
        if (!hasRefreshToken) {
            Log.w(TAG, "JWT expired but no refresh token available; cannot refresh session")
            return false
        }
        return try {
            Log.w(TAG, "JWT expired; refreshing Supabase session and retrying request")
            auth.refreshCurrentSession()
            true
        } catch (refreshError: Exception) {
            Log.e(TAG, "Failed to refresh Supabase session after JWT expiry", refreshError)
            false
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
            val payload = buildJsonObject {
                put("code", code)
                put("device_nonce", deviceNonce)
            }.toString()
            val request = Request.Builder()
                .url("${BuildConfig.SUPABASE_URL}/functions/v1/tv-logins-exchange")
                .header("apikey", BuildConfig.SUPABASE_ANON_KEY)
                .header("Authorization", "Bearer $token")
                .post(payload.toRequestBody("application/json".toMediaType()))
                .build()
            val body = withContext(Dispatchers.IO) {
                httpClient.newCall(request).execute().use { response ->
                    val responseBody = response.body?.string().orEmpty()
                    if (!response.isSuccessful) {
                        throw IllegalStateException("TV login exchange failed (${response.code}): $responseBody")
                    }
                    responseBody
                }
            }
            val result = json.decodeFromString<DurableDeviceCredentialIssueResult>(body)
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

    private suspend fun restoreSupabaseSessionFromDurableCredential(): Boolean {
        val credential = durableDeviceCredentialStore.snapshot()
        if (!credential.isComplete) return false

        val payload = buildJsonObject {
            put("device_public_id", credential.devicePublicId)
            put("device_secret", credential.deviceSecret)
        }.toString()
        val request = Request.Builder()
            .url("${BuildConfig.SUPABASE_URL}/functions/v1/device-session-exchange")
            .header("apikey", BuildConfig.SUPABASE_ANON_KEY)
            .post(payload.toRequestBody("application/json".toMediaType()))
            .build()
        val body = withContext(Dispatchers.IO) {
            httpClient.newCall(request).execute().use { response ->
                val responseBody = response.body?.string().orEmpty()
                if (!response.isSuccessful) {
                    if (response.code == 401 || response.code == 403) {
                        durableDeviceCredentialStore.clear()
                    }
                    throw IllegalStateException(
                        "Durable device session exchange failed (${response.code}): $responseBody"
                    )
                }
                responseBody
            }
        }
        val result = json.decodeFromString<DurableDeviceSessionExchangeResult>(body)
        auth.importAuthToken(result.accessToken, result.refreshToken)
        return true
    }
}

internal fun shouldAttemptDurableSessionRecovery(
    isReturningUser: Boolean,
    hasRefreshToken: Boolean,
    credential: DurableDeviceCredentialSnapshot
): Boolean {
    return isReturningUser && !hasRefreshToken && credential.isComplete
}

internal fun sessionUserIdWhileSessionUnavailable(): String? = null

internal enum class NotAuthenticatedStartupAction {
    REFRESH_LIVE_SESSION,
    ATTEMPT_RETURNING_USER_RECOVERY,
    TRANSITION_SIGNED_OUT
}

internal fun resolveNotAuthenticatedStartupAction(
    hasCachedIdentity: Boolean,
    hasRefreshToken: Boolean,
    isReturningUser: Boolean
): NotAuthenticatedStartupAction {
    if (hasRefreshToken) return NotAuthenticatedStartupAction.REFRESH_LIVE_SESSION
    if (isReturningUser) return NotAuthenticatedStartupAction.ATTEMPT_RETURNING_USER_RECOVERY
    return NotAuthenticatedStartupAction.TRANSITION_SIGNED_OUT
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
