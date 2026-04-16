package com.nexio.tv.ui.screens.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.nexio.tv.core.tvdb.TvdbAuthResult
import com.nexio.tv.core.tvdb.TvdbAuthService
import com.nexio.tv.core.tvdb.TvdbValidationStatus
import com.nexio.tv.data.local.TvdbDiagnosticsDataStore
import com.nexio.tv.data.local.TvdbSettingsDataStore
import com.nexio.tv.data.local.settingsStatusLine
import com.nexio.tv.domain.model.TvdbSettings
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

@HiltViewModel
class TvdbSettingsViewModel @Inject constructor(
    private val dataStore: TvdbSettingsDataStore,
    private val authService: TvdbAuthService,
    private val diagnosticsDataStore: TvdbDiagnosticsDataStore
) : ViewModel() {

    private val _uiState = MutableStateFlow(TvdbSettingsUiState())
    val uiState: StateFlow<TvdbSettingsUiState> = _uiState.asStateFlow()

    private val _validationError = MutableSharedFlow<TvdbValidationError>(replay = 1)
    val validationError: SharedFlow<TvdbValidationError> = _validationError.asSharedFlow()

    init {
        viewModelScope.launch {
            dataStore.settings.collectLatest { settings ->
                _uiState.update { it.fromSettings(settings) }
            }
        }
        viewModelScope.launch {
            diagnosticsDataStore.snapshot.collectLatest { snapshot ->
                _uiState.update {
                    it.copy(
                        tvdbStatusLine = snapshot.settingsStatusLine(),
                        tvdbLastRefreshLine = snapshot.lastUpdateRefreshStatus
                    )
                }
            }
        }
    }

    fun onEvent(event: TvdbSettingsEvent) {
        when (event) {
            is TvdbSettingsEvent.ToggleEnabled -> update {
                dataStore.setEnabled(true)
            }
        }
    }

    fun saveCredentials(
        apiKey: String,
        subscriberPin: String,
        onSuccess: () -> Unit
    ) {
        val trimmedApiKey = apiKey.trim()
        val trimmedPin = subscriberPin.trim()
        val current = _uiState.value

        if (current.validationStatus == TvdbValidationStatus.VALIDATING) {
            return
        }

        if (trimmedApiKey.isBlank()) {
            update {
                dataStore.clearCredentials()
                _uiState.update {
                    it.copy(
                        enabled = true,
                        apiKey = "",
                        validationStatus = TvdbValidationStatus.VALID,
                        lastFailure = ""
                    )
                }
                onSuccess()
            }
            return
        }

        update {
            _uiState.update {
                it.copy(
                    apiKey = trimmedApiKey,
                    validationStatus = TvdbValidationStatus.VALIDATING,
                    lastFailure = ""
                )
            }

            when (val result = authService.validateCredentialsResult(trimmedApiKey, trimmedPin)) {
                is TvdbAuthResult.Valid -> {
                    dataStore.saveCredentials(
                        apiKey = trimmedApiKey,
                        pin = trimmedPin,
                        validationStatus = TvdbValidationStatus.VALID
                    )
                    _uiState.update {
                        it.copy(
                            apiKey = trimmedApiKey,
                            validationStatus = TvdbValidationStatus.VALID,
                            lastFailure = ""
                        )
                    }
                    onSuccess()
                }

                is TvdbAuthResult.InvalidCredentials -> {
                    dataStore.saveValidationFailure(
                        status = TvdbValidationStatus.INVALID,
                        lastFailure = result.lastFailure
                    )
                    _uiState.update {
                        it.copy(
                            enabled = true,
                            apiKey = trimmedApiKey,
                            validationStatus = TvdbValidationStatus.INVALID,
                            lastFailure = result.lastFailure
                        )
                    }
                    _validationError.tryEmit(TvdbValidationError.InvalidCredentials)
                }

                is TvdbAuthResult.AuthUnavailable -> {
                    _uiState.update {
                        it.copy(
                            apiKey = trimmedApiKey,
                            validationStatus = TvdbValidationStatus.FALLBACK_ACTIVE,
                            lastFailure = result.lastFailure
                        )
                    }
                    _validationError.tryEmit(TvdbValidationError.InvalidCredentials)
                }
            }
        }
    }

    fun clearCredentials() {
        update {
            dataStore.clearCredentials()
            _uiState.update {
                it.copy(
                    enabled = true,
                    apiKey = "",
                    validationStatus = TvdbValidationStatus.VALID,
                    lastFailure = ""
                )
            }
        }
    }

    private fun update(action: suspend () -> Unit) {
        viewModelScope.launch { action() }
    }
}

data class TvdbSettingsUiState(
    val enabled: Boolean = false,
    val apiKey: String = "",
    val validationStatus: TvdbValidationStatus = TvdbValidationStatus.NOT_CONFIGURED,
    val lastFailure: String = "",
    val tvdbStatusLine: String? = null,
    val tvdbLastRefreshLine: String? = null
) {
    val isConfigured: Boolean
        get() = true

    val isProviderActive: Boolean
        get() = enabled

    val credentialDisplayValue: String
        get() = if (apiKey.isBlank()) {
            "Not set"
        } else {
            "••••••${apiKey.takeLast(4)}"
        }

    fun fromSettings(settings: TvdbSettings): TvdbSettingsUiState = copy(
        enabled = settings.enabled,
        apiKey = settings.apiKey,
        validationStatus = settings.validationStatus,
        lastFailure = settings.lastFailure
    )
}

sealed class TvdbSettingsEvent {
    data class ToggleEnabled(val enabled: Boolean) : TvdbSettingsEvent()
}

enum class TvdbValidationError {
    MissingApiKey,
    InvalidCredentials
}
