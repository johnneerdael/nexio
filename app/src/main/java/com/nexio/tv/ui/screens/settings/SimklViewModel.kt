package com.nexio.tv.ui.screens.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.nexio.tv.data.local.SimklAuthDataStore
import com.nexio.tv.data.local.SimklAuthState
import com.nexio.tv.data.local.SimklCatalogIds
import com.nexio.tv.data.local.SimklCatalogPreferences
import com.nexio.tv.data.local.SimklSettingsDataStore
import com.nexio.tv.core.profile.ProfileManager
import com.nexio.tv.data.repository.SimklSettingsAuthGateway
import com.nexio.tv.data.repository.SimklTokenPollResult
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

enum class SimklConnectionMode {
    DISCONNECTED,
    AWAITING_APPROVAL,
    CONNECTED
}

data class SimklUiState(
    val mode: SimklConnectionMode = SimklConnectionMode.DISCONNECTED,
    val credentialsConfigured: Boolean = true,
    val isLoading: Boolean = false,
    val isPolling: Boolean = false,
    val username: String? = null,
    val accountType: String? = null,
    val deviceUserCode: String? = null,
    val verificationUrl: String? = null,
    val pollIntervalSeconds: Int = 5,
    val deviceCodeExpiresAtMillis: Long? = null,
    val catalogPreferences: SimklCatalogPreferences = SimklCatalogPreferences(),
    val statusMessage: String? = null,
    val errorMessage: String? = null
)

@HiltViewModel
class SimklViewModel @Inject constructor(
    private val simklAuthService: SimklSettingsAuthGateway,
    private val simklAuthDataStore: SimklAuthDataStore,
    private val simklSettingsDataStore: SimklSettingsDataStore,
    private val catalogPriorityHydrationNotifier: com.nexio.tv.core.sync.CatalogPriorityHydrationNotifier,
    private val profileManager: ProfileManager
) : ViewModel() {
    private val _uiState = MutableStateFlow(SimklUiState())
    val uiState: StateFlow<SimklUiState> = _uiState.asStateFlow()

    val isPrimaryProfile: StateFlow<Boolean> = profileManager.activeProfileId
        .map { it == 1 }
        .stateIn(viewModelScope, SharingStarted.Eagerly, true)

    private var pollJob: Job? = null

    init {
        _uiState.update { it.copy(credentialsConfigured = simklAuthService.hasRequiredCredentials()) }
        observeSettings()
        observeAuthState()
    }

    fun onConnectClick() {
        if (!simklAuthService.hasRequiredCredentials()) {
            _uiState.update {
                it.copy(
                    errorMessage = "Missing SIMKL_CLIENT_ID in local.properties",
                    credentialsConfigured = false
                )
            }
            return
        }
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, errorMessage = null, statusMessage = null) }
            val result = simklAuthService.startPinAuth()
            _uiState.update { state ->
                if (result.isSuccess) state.copy(isLoading = false, statusMessage = "Enter code on simkl.com/pin")
                else state.copy(isLoading = false, errorMessage = result.exceptionOrNull()?.message ?: "Failed to start SIMKL auth")
            }
        }
    }

    fun onRetryPolling() {
        startPollingIfNeeded(force = true)
    }

    fun onCancelDeviceFlow() {
        viewModelScope.launch {
            pollJob?.cancel()
            simklAuthDataStore.clearDeviceFlow()
            _uiState.update {
                it.copy(mode = SimklConnectionMode.DISCONNECTED, isPolling = false, statusMessage = null, errorMessage = null)
            }
        }
    }


    fun onCatalogEnabledChanged(catalogId: String, enabled: Boolean) {
        if (catalogId !in SimklCatalogIds.BUILT_IN_ORDER) return
        viewModelScope.launch {
            simklSettingsDataStore.setCatalogEnabled(catalogId, enabled)
            if (enabled) catalogPriorityHydrationNotifier.notifyPriorityHydrationRequired()
            _uiState.update { it.copy(statusMessage = "SIMKL catalogs updated") }
        }
    }

    fun onDisconnectClick() {
        viewModelScope.launch {
            pollJob?.cancel()
            _uiState.update { it.copy(isLoading = true, errorMessage = null) }
            simklAuthService.revokeAndLogout()
            _uiState.update {
                it.copy(
                    isLoading = false,
                    mode = SimklConnectionMode.DISCONNECTED,
                    isPolling = false,
                    statusMessage = "Disconnected from SIMKL"
                )
            }
        }
    }

    fun onSyncNow() {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, errorMessage = null, statusMessage = "Syncing...") }
            val username = simklAuthService.fetchUserSettings()
            _uiState.update {
                it.copy(
                    isLoading = false,
                    username = username ?: it.username,
                    statusMessage = "Sync completed"
                )
            }
        }
    }


    private fun observeSettings() {
        viewModelScope.launch {
            simklSettingsDataStore.catalogPreferences.collectLatest { catalogPreferences ->
                _uiState.update { it.copy(catalogPreferences = catalogPreferences) }
            }
        }
    }

    private fun observeAuthState() {
        viewModelScope.launch {
            simklAuthDataStore.state.collectLatest { authState -> applyAuthState(authState) }
        }
    }

    private fun applyAuthState(authState: SimklAuthState) {
        val mode = when {
            authState.isAuthenticated -> SimklConnectionMode.CONNECTED
            !authState.userCode.isNullOrBlank() -> SimklConnectionMode.AWAITING_APPROVAL
            else -> SimklConnectionMode.DISCONNECTED
        }
        _uiState.update { state ->
            state.copy(
                mode = mode,
                username = authState.username,
                accountType = authState.accountType,
                deviceUserCode = authState.userCode,
                verificationUrl = authState.verificationUrl,
                pollIntervalSeconds = authState.pollInterval ?: 5,
                deviceCodeExpiresAtMillis = authState.expiresAt,
                credentialsConfigured = simklAuthService.hasRequiredCredentials(),
                errorMessage = if (mode == SimklConnectionMode.CONNECTED) null else state.errorMessage
            )
        }
        startPollingIfNeeded(force = false)
    }

    private fun startPollingIfNeeded(force: Boolean) {
        val state = _uiState.value
        if (state.mode != SimklConnectionMode.AWAITING_APPROVAL) {
            pollJob?.cancel()
            pollJob = null
            return
        }
        if (!force && pollJob?.isActive == true) return
        pollJob?.cancel()
        pollJob = viewModelScope.launch {
            _uiState.update { it.copy(isPolling = true) }
            while (true) {
                when (val result = simklAuthService.pollPin()) {
                    is SimklTokenPollResult.Pending -> Unit
                    is SimklTokenPollResult.SlowDown -> {
                        _uiState.update { it.copy(statusMessage = "SIMKL asked to slow down") }
                    }
                    is SimklTokenPollResult.Approved -> {
                        _uiState.update {
                            it.copy(
                                isPolling = false,
                                statusMessage = "Connected to SIMKL",
                                errorMessage = null,
                                username = result.username ?: it.username
                            )
                        }
                        return@launch
                    }
                    is SimklTokenPollResult.Failed -> {
                        _uiState.update { it.copy(isPolling = false, errorMessage = result.reason) }
                        return@launch
                    }
                }
                delay((_uiState.value.pollIntervalSeconds.coerceAtLeast(1)) * 1000L)
            }
        }
    }
}
