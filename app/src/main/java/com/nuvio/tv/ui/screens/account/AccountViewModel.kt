package com.nuvio.tv.ui.screens.account

import android.os.Build
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.nuvio.tv.core.auth.AuthManager
import com.nuvio.tv.core.plugin.PluginManager
import com.nuvio.tv.core.sync.AddonSyncService
import com.nuvio.tv.core.sync.PluginSyncService
import com.nuvio.tv.core.sync.WatchProgressSyncService
import com.nuvio.tv.data.local.TraktAuthDataStore
import com.nuvio.tv.data.local.WatchProgressPreferences
import com.nuvio.tv.data.repository.AddonRepositoryImpl
import com.nuvio.tv.data.repository.WatchProgressRepositoryImpl
import com.nuvio.tv.domain.repository.SyncRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class AccountViewModel @Inject constructor(
    private val authManager: AuthManager,
    private val syncRepository: SyncRepository,
    private val pluginSyncService: PluginSyncService,
    private val addonSyncService: AddonSyncService,
    private val watchProgressSyncService: WatchProgressSyncService,
    private val pluginManager: PluginManager,
    private val addonRepository: AddonRepositoryImpl,
    private val watchProgressRepository: WatchProgressRepositoryImpl,
    private val watchProgressPreferences: WatchProgressPreferences,
    private val traktAuthDataStore: TraktAuthDataStore
) : ViewModel() {

    private val _uiState = MutableStateFlow(AccountUiState())
    val uiState: StateFlow<AccountUiState> = _uiState.asStateFlow()

    init {
        observeAuthState()
    }

    private fun observeAuthState() {
        viewModelScope.launch {
            authManager.authState.collect { state ->
                _uiState.update { it.copy(authState = state) }
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
                    pullRemoteData()
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
                authManager.signInAnonymously().onFailure { e ->
                    _uiState.update { it.copy(isLoading = false, error = userFriendlyError(e)) }
                    return@launch
                }
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
                authManager.signInAnonymously().onFailure { e ->
                    _uiState.update { it.copy(isLoading = false, error = userFriendlyError(e)) }
                    return@launch
                }
            }
            syncRepository.claimSyncCode(code, pin, Build.MODEL).fold(
                onSuccess = { result ->
                    if (result.success) {
                        pullRemoteData()
                        _uiState.update { it.copy(isLoading = false, syncClaimSuccess = true) }
                    } else {
                        _uiState.update { it.copy(isLoading = false, error = result.message) }
                    }
                },
                onFailure = { e ->
                    _uiState.update { it.copy(isLoading = false, error = userFriendlyError(e)) }
                }
            )
        }
    }

    fun signOut() {
        viewModelScope.launch {
            authManager.signOut()
        }
    }

    fun loadLinkedDevices() {
        viewModelScope.launch {
            syncRepository.getLinkedDevices().fold(
                onSuccess = { devices ->
                    _uiState.update { it.copy(linkedDevices = devices) }
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

    private fun userFriendlyError(e: Throwable): String {
        val raw = e.message ?: ""
        val message = raw.lowercase()
        Log.w("AccountViewModel", "Raw error: $raw", e)

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

    private suspend fun pushLocalDataToRemote() {
        pluginSyncService.pushToRemote()
        addonSyncService.pushToRemote()
        watchProgressSyncService.pushToRemote()
    }

    private suspend fun pullRemoteData() {
        try {
            pluginManager.isSyncingFromRemote = true
            val newPluginUrls = pluginSyncService.getNewRemoteRepoUrls()
            for (url in newPluginUrls) {
                pluginManager.addRepository(url)
            }
            pluginManager.isSyncingFromRemote = false

            addonRepository.isSyncingFromRemote = true
            val newAddonUrls = addonSyncService.getNewRemoteAddonUrls()
            for (url in newAddonUrls) {
                addonRepository.addAddon(url)
            }
            addonRepository.isSyncingFromRemote = false

            val isTraktConnected = traktAuthDataStore.isAuthenticated.first()
            Log.d("AccountViewModel", "pullRemoteData: isTraktConnected=$isTraktConnected")
            if (!isTraktConnected) {
                watchProgressRepository.isSyncingFromRemote = true
                val remoteEntries = watchProgressSyncService.pullFromRemote()
                Log.d("AccountViewModel", "pullRemoteData: pulled ${remoteEntries.size} watch progress entries")
                if (remoteEntries.isNotEmpty()) {
                    watchProgressPreferences.mergeRemoteEntries(remoteEntries.toMap())
                    Log.d("AccountViewModel", "pullRemoteData: merged ${remoteEntries.size} entries into local")
                } else {
                    Log.d("AccountViewModel", "pullRemoteData: no remote watch progress to merge")
                }
                watchProgressRepository.isSyncingFromRemote = false
            }
        } catch (e: Exception) {
            pluginManager.isSyncingFromRemote = false
            addonRepository.isSyncingFromRemote = false
            watchProgressRepository.isSyncingFromRemote = false
            throw e
        }
    }
}
