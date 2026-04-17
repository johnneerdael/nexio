package com.nexio.tv.core.auth

import android.util.Log
import com.nexio.tv.BuildConfig
import com.nexio.tv.data.remote.supabase.TvLoginExchangeResult
import com.nexio.tv.data.remote.supabase.TvLoginPollResult
import com.nexio.tv.data.remote.supabase.TvLoginStartResult
import com.nexio.tv.domain.model.AuthState
import io.github.jan.supabase.auth.Auth
import io.github.jan.supabase.auth.providers.builtin.Email
import io.github.jan.supabase.auth.status.SessionStatus
import io.github.jan.supabase.postgrest.Postgrest
import kotlinx.coroutines.Dispatchers
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
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

private const val TAG = "AuthManager"

@Singleton
class AuthManager @Inject constructor(
    private val auth: Auth,
    private val postgrest: Postgrest,
    private val httpClient: OkHttpClient
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
                        }
                    }
                    is SessionStatus.NotAuthenticated -> {
                        auth.awaitInitialization()
                        val restoredUser = auth.currentUserOrNull()
                        if (restoredUser != null) {
                            publishAuthenticatedUser(restoredUser.id, restoredUser.email)
                            return@collect
                        }
                        val session = auth.currentSessionOrNull()
                        val hasRefreshToken = session?.refreshToken?.isNotBlank() == true
                        if (hasRefreshToken) {
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
                                        _sessionUserId.value = null
                                        cachedEffectiveUserId = null
                                        cachedEffectiveUserSourceUserId = null
                                        _authState.value = AuthState.SignedOut
                                    } else {
                                        Log.w(
                                            TAG,
                                            "Transient session refresh failure; keeping current auth state",
                                            e
                                        )
                                    }
                                }
                            }
                        } else {
                            _sessionUserId.value = null
                            cachedEffectiveUserId = null
                            cachedEffectiveUserSourceUserId = null
                            _authState.value = AuthState.SignedOut
                        }
                    }
                    is SessionStatus.Initializing -> {
                        _sessionUserId.value = auth.currentUserOrNull()?.id
                        _authState.value = AuthState.Loading
                    }
                    else -> { /* NetworkError etc. — keep current state */ }
                }
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

    private fun publishAuthenticatedUser(userId: String, email: String?) {
        _sessionUserId.value = userId
        if (cachedEffectiveUserSourceUserId != userId) {
            cachedEffectiveUserId = null
            cachedEffectiveUserSourceUserId = null
        }
        _authState.value = fullAccountStateForSupabaseUser(userId = userId, email = email)
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
            val result = json.decodeFromString<TvLoginExchangeResult>(body)
            auth.importAuthToken(result.accessToken, result.refreshToken)
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
