package com.nexio.tv.ui.screens.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.nexio.tv.data.local.ImdbSettingsDataStore
import com.nexio.tv.data.remote.CustomImdbClient
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

@HiltViewModel
class ImdbSettingsViewModel @Inject constructor(
    private val dataStore: ImdbSettingsDataStore,
    private val customImdbClient: CustomImdbClient
) : ViewModel() {

    private val _uiState = MutableStateFlow(ImdbSettingsUiState())
    val uiState: StateFlow<ImdbSettingsUiState> = _uiState.asStateFlow()

    private val _validating = MutableStateFlow(false)
    val validating: StateFlow<Boolean> = _validating.asStateFlow()

    private val _validationError = MutableSharedFlow<ImdbValidationError>(extraBufferCapacity = 1)
    val validationError: SharedFlow<ImdbValidationError> = _validationError.asSharedFlow()

    init {
        viewModelScope.launch {
            dataStore.settings.collectLatest { settings ->
                _uiState.update {
                    ImdbSettingsUiState(
                        enabled = settings.enabled,
                        baseUrl = settings.baseUrl,
                        apiKey = settings.apiKey
                    )
                }
            }
        }
    }

    fun setEnabled(enabled: Boolean) {
        viewModelScope.launch {
            dataStore.setEnabled(enabled)
        }
    }

    fun validateAndSaveConfiguration(
        baseUrl: String,
        apiKey: String,
        onSuccess: () -> Unit
    ) {
        val trimmedBaseUrl = baseUrl.trim()
        val trimmedApiKey = apiKey.trim()

        if (trimmedBaseUrl.isBlank() && trimmedApiKey.isBlank()) {
            viewModelScope.launch {
                dataStore.setBaseUrl("")
                dataStore.setApiKey("")
            }
            onSuccess()
            return
        }

        if (trimmedBaseUrl.isBlank() || trimmedApiKey.isBlank()) {
            _validationError.tryEmit(ImdbValidationError.InvalidConfiguration)
            return
        }

        viewModelScope.launch {
            _validating.value = true
            val valid = customImdbClient.validate(
                baseUrl = trimmedBaseUrl,
                apiKey = trimmedApiKey
            )
            _validating.value = false

            if (valid) {
                dataStore.setBaseUrl(trimmedBaseUrl)
                dataStore.setApiKey(trimmedApiKey)
                onSuccess()
            } else {
                _validationError.tryEmit(ImdbValidationError.InvalidConfiguration)
            }
        }
    }
}

data class ImdbSettingsUiState(
    val enabled: Boolean = false,
    val baseUrl: String = "",
    val apiKey: String = ""
) {
    val isActive: Boolean
        get() = enabled && baseUrl.isNotBlank() && apiKey.isNotBlank()
}

enum class ImdbValidationError {
    InvalidConfiguration
}
