package com.nexio.tv.ui.screens.account

import android.content.Context
import android.os.Build
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.nexio.tv.R
import com.nexio.tv.BuildConfig
import com.nexio.tv.core.auth.AuthManager
import com.nexio.tv.core.qr.QrCodeGenerator
import com.nexio.tv.core.sync.AddonSyncService
import com.nexio.tv.core.sync.AccountSettingsSyncService
import com.nexio.tv.core.sync.AccountSyncRefreshNotifier
import com.nexio.tv.data.repository.AddonRepositoryImpl
import com.nexio.tv.domain.model.AuthState
import com.nexio.tv.domain.repository.SyncRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.security.SecureRandom
import java.time.Instant
import java.util.Base64
import javax.inject.Inject

@HiltViewModel
class AccountViewModel @Inject constructor(
    private val authManager: AuthManager,
    private val syncRepository: SyncRepository,
    private val addonSyncService: AddonSyncService,
    private val accountSettingsSyncService: AccountSettingsSyncService,
    private val addonRepository: AddonRepositoryImpl,
    private val accountSyncRefreshNotifier: AccountSyncRefreshNotifier,
    @dagger.hilt.android.qualifiers.ApplicationContext private val context: Context
) : ViewModel() {

    private val _uiState = MutableStateFlow(AccountUiState())
    val uiState: StateFlow<AccountUiState> = _uiState.asStateFlow()
    private var qrLoginPollJob: Job? = null

    init {
        observeAuthState()
    }

    private fun observeAuthState() {
        viewModelScope.launch {
            authManager.authState.collect { state ->
                _uiState.update {
                    it.copy(
                        authState = state,
                        effectiveOwnerId = if (state is AuthState.SignedOut || state is AuthState.Loading) null else it.effectiveOwnerId,
                        connectedStats = if (state is AuthState.FullAccount) it.connectedStats else null,
                        isStatsLoading = if (state is AuthState.FullAccount) it.isStatsLoading else false
                    )
                }
                updateEffectiveOwnerId(state)
                if (state is AuthState.FullAccount) {
                    loadConnectedStats()
                }
            }
        }
    }

    fun signUp(email: String, password: String) {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, error = null) }
            authManager.signUpWithEmail(email, password).fold(
                onSuccess = {
                    pushLocalDataToRemote()
                    _uiState.update { it.copy(isLoading = false) }
                },
                onFailure = { e ->
                    _uiState.update { it.copy(isLoading = false, error = userFriendlyError(e)) }
                }
            )
        }
    }

    fun signIn(email: String, password: String) {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, error = null) }
            authManager.signInWithEmail(email, password).fold(
                onSuccess = {
                    pullRemoteData().onFailure { e ->
                        Log.e("AccountViewModel", "signIn: pullRemoteData failed, continuing signed-in flow", e)
                    }
                    loadConnectedStats()
                    _uiState.update { it.copy(isLoading = false) }
                },
                onFailure = { e ->
                    _uiState.update { it.copy(isLoading = false, error = userFriendlyError(e)) }
                }
            )
        }
    }

    fun generateSyncCode(pin: String) {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, error = null) }
            if (!authManager.isAuthenticated) {
                _uiState.update { it.copy(isLoading = false, error = "Sign in with an account first.") }
                return@launch
            }
            pushLocalDataToRemote()
            syncRepository.generateSyncCode(pin).fold(
                onSuccess = { code ->
                    _uiState.update { it.copy(isLoading = false, generatedSyncCode = code) }
                },
                onFailure = { e ->
                    _uiState.update { it.copy(isLoading = false, error = userFriendlyError(e)) }
                }
            )
        }
    }

    fun getSyncCode(pin: String) {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, error = null) }
            syncRepository.getSyncCode(pin).fold(
                onSuccess = { code ->
                    _uiState.update { it.copy(isLoading = false, generatedSyncCode = code) }
                },
                onFailure = { e ->
                    _uiState.update { it.copy(isLoading = false, error = userFriendlyError(e)) }
                }
            )
        }
    }

    fun claimSyncCode(code: String, pin: String) {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, error = null) }
            if (!authManager.isAuthenticated) {
                _uiState.update { it.copy(isLoading = false, error = "Sign in with an account first.") }
                return@launch
            }
            syncRepository.claimSyncCode(code, pin, Build.MODEL).fold(
                onSuccess = { result ->
                    if (result.success) {
                        authManager.clearEffectiveUserIdCache()
                        pullRemoteData().onFailure { e ->
                            Log.e("AccountViewModel", "claimSyncCode: pullRemoteData failed, continuing", e)
                        }
                        updateEffectiveOwnerId(_uiState.value.authState)
                        _uiState.update { it.copy(isLoading = false, syncClaimSuccess = true) }
                    } else {
                        authManager.signOut()
                        _uiState.update { it.copy(isLoading = false, error = result.message) }
                    }
                },
                onFailure = { e ->
                    authManager.signOut()
                    _uiState.update { it.copy(isLoading = false, error = userFriendlyError(e)) }
                }
            )
        }
    }

    fun signOut() {
        viewModelScope.launch {
            authManager.signOut()
            _uiState.update { it.copy(connectedStats = null, isStatsLoading = false) }
        }
    }

    fun loadLinkedDevices() {
        viewModelScope.launch {
            syncRepository.getLinkedDevices().fold(
                onSuccess = { devices ->
                    _uiState.update { it.copy(linkedDevices = devices) }
                    loadConnectedStats()
                },
                onFailure = { /* silently handle */ }
            )
        }
    }

    fun unlinkDevice(deviceUserId: String) {
        viewModelScope.launch {
            syncRepository.unlinkDevice(deviceUserId)
            loadLinkedDevices()
        }
    }

    fun clearError() {
        _uiState.update { it.copy(error = null) }
    }

    fun clearSyncClaimSuccess() {
        _uiState.update { it.copy(syncClaimSuccess = false) }
    }

    fun clearGeneratedSyncCode() {
        _uiState.update { it.copy(generatedSyncCode = null) }
    }

    fun startQrLogin() {
        viewModelScope.launch {
            cancelQrLoginPolling()
            val nonce = generateDeviceNonce()
            _uiState.update {
                it.copy(
                    isLoading = true,
                    error = null,
                    qrLoginCode = null,
                    qrLoginUrl = null,
                    qrLoginNonce = nonce,
                    qrLoginBitmap = null,
                    qrLoginStatus = "Preparing QR login...",
                    qrLoginExpiresAtMillis = null
                )
            }
            authManager.ensureQrSessionAuthenticated().onFailure { e ->
                _uiState.update {
                    it.copy(
                        isLoading = false,
                        error = userFriendlyError(e),
                        qrLoginStatus = "Failed to authenticate device"
                    )
                }
                return@launch
            }
            authManager.startTvLoginSession(
                deviceNonce = nonce,
                deviceName = Build.MODEL,
                redirectBaseUrl = BuildConfig.TV_LOGIN_WEB_BASE_URL
            ).fold(
                onSuccess = { result ->
                    val expiresAtMillis = runCatching { Instant.parse(result.expiresAt).toEpochMilli() }.getOrNull()
                    val qrBitmap = runCatching { QrCodeGenerator.generate(result.webUrl, 420) }.getOrNull()
                    _uiState.update {
                        it.copy(
                            isLoading = false,
                            qrLoginCode = result.code,
                            qrLoginUrl = result.webUrl,
                            qrLoginBitmap = qrBitmap,
                            qrLoginStatus = "Scan QR and sign in on your phone",
                            qrLoginExpiresAtMillis = expiresAtMillis,
                            qrLoginPollIntervalSeconds = result.pollIntervalSeconds.coerceAtLeast(2)
                        )
                    }
                    startQrLoginPolling()
                },
                onFailure = { e ->
                    _uiState.update {
                        it.copy(
                            isLoading = false,
                            error = userFriendlyError(e),
                            qrLoginStatus = "Failed to start QR login"
                        )
                    }
                }
            )
        }
    }

    fun pollQrLogin() {
        viewModelScope.launch {
            pollQrLoginOnce()
        }
    }

    fun exchangeQrLogin() {
        viewModelScope.launch {
            val current = _uiState.value
            val code = current.qrLoginCode ?: return@launch
            val nonce = current.qrLoginNonce ?: return@launch
            _uiState.update { it.copy(isLoading = true, error = null, qrLoginStatus = "Signing you in...") }
            authManager.exchangeTvLoginSession(code = code, deviceNonce = nonce).fold(
                onSuccess = {
                    pullRemoteData().onFailure { e ->
                        Log.e("AccountViewModel", "exchangeQrLogin: pullRemoteData failed, continuing", e)
                    }
                    loadConnectedStats()
                    _uiState.update { it.copy(isLoading = false, qrLoginStatus = "Signed in successfully") }
                },
                onFailure = { e ->
                    _uiState.update {
                        it.copy(
                            isLoading = false,
                            error = userFriendlyError(e),
                            qrLoginStatus = "Could not complete QR sign in"
                        )
                    }
                }
            )
        }
    }

    fun clearQrLoginSession() {
        cancelQrLoginPolling()
        _uiState.update {
            it.copy(
                qrLoginCode = null,
                qrLoginUrl = null,
                qrLoginNonce = null,
                qrLoginBitmap = null,
                qrLoginStatus = null,
                qrLoginExpiresAtMillis = null
            )
        }
    }

    private suspend fun updateEffectiveOwnerId(state: AuthState) {
        val currentUserId = when (state) {
            is AuthState.FullAccount -> state.userId
            else -> null
        }
        if (currentUserId == null) return

        val effectiveOwnerId = authManager.getEffectiveUserId() ?: currentUserId
        _uiState.update { it.copy(effectiveOwnerId = effectiveOwnerId) }
    }

    private fun loadConnectedStats() {
        viewModelScope.launch {
            _uiState.update { it.copy(isStatsLoading = true) }

            val stats = runCatching {
                val addonsCount = addonRepository.getInstalledAddons().first().size
                AccountConnectedStats(
                    addons = addonsCount,
                    linkedDevices = _uiState.value.linkedDevices.size
                )
            }.getOrNull()

            _uiState.update {
                it.copy(
                    connectedStats = stats ?: it.connectedStats,
                    isStatsLoading = false
                )
            }
        }
    }

    private fun userFriendlyError(e: Throwable): String {
        val raw = e.message ?: ""
        val message = raw.lowercase()
        val compactRaw = raw.lineSequence().firstOrNull()?.trim().orEmpty()
        Log.w("AccountViewModel", "Raw error: $compactRaw")

        return when {
            // PIN errors (from PG RAISE EXCEPTION or any wrapper)
            message.contains("incorrect pin") || message.contains("invalid pin") || message.contains("wrong pin") -> "Incorrect PIN."

            // Sync code errors
            message.contains("expired") -> "Sync code has expired."
            message.contains("invalid") && message.contains("code") -> "Invalid sync code."
            message.contains("not found") || message.contains("no sync code") -> "Sync code not found."
            message.contains("already linked") -> "Device is already linked."
            message.contains("empty response") -> "Something went wrong. Please try again."

            // Auth errors
            message.contains("invalid login credentials") -> "Incorrect email or password."
            message.contains("email not confirmed") -> "Please confirm your email first."
            message.contains("user already registered") -> "An account with this email already exists."
            message.contains("invalid email") -> "Please enter a valid email address."
            message.contains("password") && message.contains("short") -> "Password is too short."
            message.contains("password") && message.contains("weak") -> "Password is too weak."
            message.contains("signup is disabled") -> "Sign up is currently disabled."
            message.contains("rate limit") || message.contains("too many requests") -> "Too many attempts. Please try again later."
            message.contains("tv login") && message.contains("expired") -> "QR login expired. Please try again."
            message.contains("tv login") && message.contains("invalid") -> "Invalid QR login code."
            message.contains("tv login") && message.contains("nonce") -> "This QR login was requested from another device."
            message.contains("start_tv_login_session") && message.contains("could not find the function") ->
                "QR login service is outdated. Reapply TV login SQL setup."
            message.contains("gen_random_bytes") && message.contains("does not exist") ->
                "QR login backend is missing setup. Update TV login SQL setup."
            message.contains("invalid tv login redirect base url") ->
                "QR login URL is misconfigured."
            message.contains("invalid device nonce") ->
                "QR login request was invalid. Please retry."

            // Network errors
            message.contains("unable to resolve host") || message.contains("no address associated") -> "No internet connection."
            message.contains("timeout") || message.contains("timed out") -> "Connection timed out. Please try again."
            message.contains("connection refused") || message.contains("connect failed") -> "Could not connect to server."

            // Auth state
            message.contains("not authenticated") -> "Please sign in first."

            // Supabase HTTP errors (e.g. 404 for missing RPC, 400 for bad params)
            message.contains("404") || message.contains("could not find") -> "Service unavailable. Please try again later."
            message.contains("400") || message.contains("bad request") -> "Invalid request. Please check your input."

            // Fallback
            else -> "An unexpected error occurred."
        }
    }

    private fun startQrLoginPolling() {
        cancelQrLoginPolling()
        qrLoginPollJob = viewModelScope.launch {
            while (isActive) {
                val interval = _uiState.value.qrLoginPollIntervalSeconds.coerceAtLeast(2)
                delay(interval * 1000L)
                pollQrLoginOnce()
            }
        }
    }

    private fun cancelQrLoginPolling() {
        qrLoginPollJob?.cancel()
        qrLoginPollJob = null
    }

    private fun generateDeviceNonce(): String {
        val bytes = ByteArray(24)
        SecureRandom().nextBytes(bytes)
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
    }

    private suspend fun pollQrLoginOnce() {
        val current = _uiState.value
        val code = current.qrLoginCode ?: return
        val nonce = current.qrLoginNonce ?: return
        authManager.pollTvLoginSession(code = code, deviceNonce = nonce).fold(
            onSuccess = { result ->
                val normalizedStatus = result.status.lowercase()
                val expiresAtMillis = result.expiresAt?.let { runCatching { Instant.parse(it).toEpochMilli() }.getOrNull() }
                _uiState.update {
                    it.copy(
                        qrLoginStatus = when (normalizedStatus) {
                            "approved" -> context.getString(R.string.qr_login_approved)
                            "pending" -> context.getString(R.string.qr_login_pending)
                            "expired" -> context.getString(R.string.qr_login_expired)
                            else -> "Status: ${result.status}"
                        },
                        qrLoginExpiresAtMillis = expiresAtMillis ?: it.qrLoginExpiresAtMillis,
                        qrLoginPollIntervalSeconds = (result.pollIntervalSeconds ?: it.qrLoginPollIntervalSeconds).coerceAtLeast(2)
                    )
                }
                when (normalizedStatus) {
                    "approved" -> {
                        cancelQrLoginPolling()
                        exchangeQrLogin()
                    }
                    "expired", "used", "cancelled" -> cancelQrLoginPolling()
                }
            },
            onFailure = { e ->
                _uiState.update { it.copy(error = userFriendlyError(e)) }
            }
        )
    }

    private suspend fun pushLocalDataToRemote() {
        accountSettingsSyncService.pushToRemote()
        addonSyncService.pushToRemote()
    }

    private suspend fun pullRemoteData(): Result<Unit> {
        try {
            addonRepository.isSyncingFromRemote = true
            val remoteAddonUrls = accountSettingsSyncService.pullFromRemoteAndApply().getOrElse { throw it }
            addonRepository.reconcileWithRemoteAddonUrls(
                remoteUrls = remoteAddonUrls,
                removeMissingLocal = true
            )
            accountSyncRefreshNotifier.notifyRefreshRequired()
            addonRepository.isSyncingFromRemote = false
            return Result.success(Unit)
        } catch (e: Exception) {
            addonRepository.isSyncingFromRemote = false
            return Result.failure(e)
        }
    }

    override fun onCleared() {
        cancelQrLoginPolling()
        super.onCleared()
    }
}
