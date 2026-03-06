package com.nexio.tv.ui.screens.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.nexio.tv.data.local.GeminiSettingsDataStore
import com.nexio.tv.domain.model.GeminiSettings
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class GeminiSettingsViewModel @Inject constructor(
    private val dataStore: GeminiSettingsDataStore
) : ViewModel() {

    private val _uiState = MutableStateFlow(GeminiSettingsUiState())
    val uiState: StateFlow<GeminiSettingsUiState> = _uiState.asStateFlow()

    private val _validationError = MutableSharedFlow<GeminiValidationError>(extraBufferCapacity = 1)
    val validationError: SharedFlow<GeminiValidationError> = _validationError.asSharedFlow()

    init {
        viewModelScope.launch {
            dataStore.settings.collectLatest { settings ->
                if (settings.enabled && settings.apiKey.isBlank()) {
                    dataStore.setEnabled(false)
                    return@collectLatest
                }
                _uiState.update { it.fromSettings(settings) }
            }
        }
    }

    fun setEnabled(enabled: Boolean) {
        viewModelScope.launch {
            if (enabled && _uiState.value.apiKey.isBlank()) {
                _validationError.tryEmit(GeminiValidationError.MissingApiKey)
                dataStore.setEnabled(false)
                return@launch
            }
            dataStore.setEnabled(enabled)
        }
    }

    fun saveApiKey(value: String, onSuccess: () -> Unit) {
        viewModelScope.launch {
            val trimmed = value.trim()
            dataStore.setApiKey(trimmed)
            if (trimmed.isBlank()) {
                dataStore.setEnabled(false)
            }
            onSuccess()
        }
    }
}

data class GeminiSettingsUiState(
    val enabled: Boolean = false,
    val apiKey: String = ""
) {
    fun fromSettings(settings: GeminiSettings): GeminiSettingsUiState = copy(
        enabled = settings.enabled,
        apiKey = settings.apiKey
    )
}

enum class GeminiValidationError {
    MissingApiKey
}
