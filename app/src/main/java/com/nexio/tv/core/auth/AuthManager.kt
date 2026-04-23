package com.nexio.tv.core.auth

import android.util.Log
import com.nexio.tv.BuildConfig
import com.nexio.tv.data.local.AppOnboardingDataStore
import com.nexio.tv.data.local.AuthPresenceDataStore
import com.nexio.tv.data.local.SupabaseSessionBackup
import com.nexio.tv.data.local.SupabaseSessionBackupDataStore
import com.nexio.tv.data.remote.supabase.TvLoginExchangeResult
import com.nexio.tv.data.remote.supabase.TvLoginPollResult
import com.nexio.tv.data.remote.supabase.TvLoginStartResult
import com.nexio.tv.domain.model.AuthState
import io.github.jan.supabase.auth.Auth
import io.github.jan.supabase.auth.providers.builtin.Email
import io.github.jan.supabase.auth.status.SessionStatus
import io.github.jan.supabase.postgrest.Postgrest
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
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
    private val sessionBackupDataStore: SupabaseSessionBackupDataStore
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val json = Json { ignoreUnknownKeys = true }

    private val _authState = MutableStateFlow<AuthState>(AuthState.Loading)
    val authState: StateFlow<AuthState> = _authState.asStateFlow()
    private val _sessionUserId = MutableStateFlow<String?>(null)
    val sessionUserId: StateFlow<String?> = _sessionUserId.asStateFlow()

    private var cachedEffectiveUserId: String? = null
    private var cachedEffectiveUserSourceUserId: String? = null
    private var silentSessionRecoveryJob: Job? = null

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
                            Log.w(
                                TAG,
                                "Authenticated status arrived without a hydrated user; keeping cached auth state and retrying restore"
                            )
                            promoteCachedFullAccountIfAvailable()
                            scheduleSilentSessionRecovery("authenticated-without-user")
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
                            if (isReturningUser()) {
                                promoteCachedFullAccountIfAvailable()
                            }
                            scope.launch {
                                try {
                                    auth.refreshCurrentSession()
                                    persistCurrentSessionBackupIfAvailable()
                                } catch (e: Exception) {
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
                        } else {
                            val returning = isReturningUser()
                            if (returning) {
                                Log.w(
                                    TAG,
                                    "Supabase reports no session but returning-user signal is set; attempting durable restore"
                                )
                                promoteCachedFullAccountIfAvailable()
                                scheduleSilentSessionRecovery("not-authenticated-without-session")
                            } else {
                                transitionToSignedOut()
                            }
                        }
                    }

                    is SessionStatus.Initializing -> {
                        _sessionUserId.value = auth.currentUserOrNull()?.id ?: _sessionUserId.value
                        if (_authState.value !is AuthState.FullAccount) {
                            _authState.value = AuthState.Loading
                        }
                    }

                    else -> {
                        // NetworkError etc. — keep current state.
                    }
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

    private suspend fun readHasSessionBackup(): Boolean {
        return try {
            val snapshot = sessionBackupDataStore.snapshot()
            snapshot.hasTokens || snapshot.hasIdentity
        } catch (e: Exception) {
            Log.w(TAG, "Failed to read Supabase session backup marker; assuming none", e)
            false
        }
    }

    private suspend fun isReturningUser(): Boolean {
        if (readHadAuthenticatedSession()) return true
        if (readHasSessionBackup()) return true
        return readHasCompletedOnboardingQr()
    }

    private fun scheduleSilentSessionRecovery(reason: String) {
        if (silentSessionRecoveryJob?.isActive == true) return
        silentSessionRecoveryJob = scope.launch {
            try {
                attemptSilentSessionRecovery(reason)
            } finally {
                silentSessionRecoveryJob = null
            }
        }
    }

    private suspend fun attemptSilentSessionRecovery(reason: String) {
        promoteCachedFullAccountIfAvailable()
        repeat(4) { attempt ->
            delay(500L * (attempt + 1))
            try {
                auth.awaitInitialization()
                val hydratedUser = auth.currentUserOrNull()
                if (hydratedUser != null) {
                    publishAuthenticatedUser(hydratedUser.id, hydratedUser.email)
                    return
                }

                val session = auth.currentSessionOrNull()
                if (session?.refreshToken?.isNotBlank() == true) {
                    auth.refreshCurrentSession()
                    persistCurrentSessionBackupIfAvailable()
                    val refreshedUser = auth.currentUserOrNull()
                    if (refreshedUser != null) {
                        publishAuthenticatedUser(refreshedUser.id, refreshedUser.email)
                    }
                    return
                }

                if (restoreSessionFromBackupIfAvailable()) return
            } catch (e: Exception) {
                if (e.isAuthoritativeRefreshRejection()) {
                    Log.w(TAG, "Authoritative rejection during silent recovery; signing out", e)
                    transitionToSignedOut()
                    return
                }
                Log.w(TAG, "Silent session recovery attempt failed; will retry", e)
            }
        }

        Log.w(
            TAG,
            "Silent session recovery exhausted after $reason; keeping cached full-account state and waiting for the next auth status change"
        )
        promoteCachedFullAccountIfAvailable()
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
                sessionBackupDataStore.clear()
            } catch (e: Exception) {
                Log.w(TAG, "Failed to clear Supabase session backup", e)
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
        val computed = fullAccountStateForSupabaseUser(userId = userId, email = email)
        if (computed !is AuthState.FullAccount && isReturningUser()) {
            Log.w(
                TAG,
                "Authenticated session resolved to a non-full account for a returning user; ignoring downgrade and attempting backup restore"
            )
            promoteCachedFullAccountIfAvailable()
            scheduleSilentSessionRecovery("non-full-authenticated-session")
            return
        }

        _sessionUserId.value = userId
        if (cachedEffectiveUserSourceUserId != userId) {
            cachedEffectiveUserId = null
            cachedEffectiveUserSourceUserId = null
        }
        _authState.value = computed
        if (computed is AuthState.FullAccount) {
            scope.launch {
                try {
                    authPresenceDataStore.markAuthenticated(userId)
                } catch (e: Exception) {
                    Log.w(TAG, "Failed to persist auth presence marker", e)
                }
                persistCurrentSessionBackupIfAvailable(
                    userId = userId,
                    email = computed.email
                )
            }
        }
    }

    private suspend fun readSessionBackupSnapshot(): SupabaseSessionBackup {
        return sessionBackupDataStore.snapshot()
    }

    private suspend fun promoteCachedFullAccountIfAvailable(): Boolean {
        val backup = runCatching { readSessionBackupSnapshot() }
            .onFailure { error -> Log.w(TAG, "Failed to read cached Supabase session backup", error) }
            .getOrNull()
            ?: return false
        val userId = backup.userId?.trim().orEmpty()
        val email = backup.email?.trim().orEmpty()
        if (userId.isBlank() || email.isBlank()) return false
        // Only restore identity for UI continuity here. Do not mark the sync
        // session as live until Supabase has actually hydrated or refreshed a
        // real session for this process.
        _sessionUserId.value = null
        cachedEffectiveUserId = null
        cachedEffectiveUserSourceUserId = null
        _authState.value = AuthState.FullAccount(userId = userId, email = email)
        return true
    }

    private suspend fun restoreSessionFromBackupIfAvailable(): Boolean {
        val backup = runCatching { readSessionBackupSnapshot() }
            .onFailure { error -> Log.w(TAG, "Failed to read Supabase session backup for restore", error) }
            .getOrNull()
            ?: return false
        if (!backup.hasTokens) return false

        return try {
            auth.importAuthToken(
                accessToken = backup.accessToken.orEmpty(),
                refreshToken = backup.refreshToken.orEmpty()
            )
            auth.refreshCurrentSession()
            persistCurrentSessionBackupIfAvailable(
                userId = backup.userId,
                email = backup.email
            )
            val restoredUser = auth.currentUserOrNull()
            if (restoredUser != null) {
                publishAuthenticatedUser(restoredUser.id, restoredUser.email)
            } else {
                promoteCachedFullAccountIfAvailable()
            }
            true
        } catch (e: Exception) {
            if (e.isAuthoritativeRefreshRejection()) {
                Log.w(TAG, "Backup session restore was authoritatively rejected", e)
                transitionToSignedOut()
                return false
            }
            Log.w(TAG, "Backup session restore failed; keeping cached full-account state", e)
            promoteCachedFullAccountIfAvailable()
            false
        }
    }

    private suspend fun persistCurrentSessionBackupIfAvailable(
        userId: String? = auth.currentUserOrNull()?.id,
        email: String? = auth.currentUserOrNull()?.email
    ) {
        val normalizedUserId = userId?.trim().orEmpty()
        val normalizedEmail = email?.trim().orEmpty()
        val accessToken = auth.currentAccessTokenOrNull()?.trim().orEmpty()
        val refreshToken = auth.currentSessionOrNull()?.refreshToken?.trim().orEmpty()
        if (
            normalizedUserId.isBlank() ||
            normalizedEmail.isBlank() ||
            accessToken.isBlank() ||
            refreshToken.isBlank()
        ) {
            return
        }
        try {
            sessionBackupDataStore.save(
                accessToken = accessToken,
                refreshToken = refreshToken,
                userId = normalizedUserId,
                email = normalizedEmail
            )
        } catch (e: Exception) {
            Log.w(TAG, "Failed to persist Supabase session backup", e)
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
            sessionBackupDataStore.clear()
        } catch (e: Exception) {
            Log.w(TAG, "Failed to clear Supabase session backup on sign-out", e)
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
            persistCurrentSessionBackupIfAvailable()
            true
        } catch (refreshError: Exception) {
            Log.e(TAG, "Failed to refresh Supabase session after JWT expiry", refreshError)
            false
        }
    }

    suspend fun startTvLoginSession(
        deviceNonce: String,
        deviceName: String?,
        redirectBaseUrl: String
    ): Result<TvLoginStartResult> {
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
            val hydratedUser = auth.currentUserOrNull()
            if (hydratedUser != null) {
                persistCurrentSessionBackupIfAvailable(
                    userId = hydratedUser.id,
                    email = hydratedUser.email
                )
            }
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
