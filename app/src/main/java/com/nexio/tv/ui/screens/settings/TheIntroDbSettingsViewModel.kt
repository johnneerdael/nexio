package com.nexio.tv.ui.screens.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.nexio.tv.data.local.TheIntroDbSettings
import com.nexio.tv.data.local.TheIntroDbSettingsDataStore
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

@HiltViewModel
class TheIntroDbSettingsViewModel @Inject constructor(
    private val dataStore: TheIntroDbSettingsDataStore
) : ViewModel() {

    private val _settings = MutableStateFlow(TheIntroDbSettings())
    val settings: StateFlow<TheIntroDbSettings> = _settings.asStateFlow()
    private val _validationError = MutableSharedFlow<TheIntroDbValidationError>(extraBufferCapacity = 1)
    val validationError: SharedFlow<TheIntroDbValidationError> = _validationError.asSharedFlow()

    init {
        viewModelScope.launch {
            dataStore.settings.collectLatest { settings ->
                if (settings.enabled && settings.apiKey.isBlank()) {
                    dataStore.setEnabled(false)
                    return@collectLatest
                }
                _settings.value = settings
            }
        }
    }

    fun setEnabled(value: Boolean) {
        viewModelScope.launch {
            if (value && _settings.value.apiKey.isBlank()) {
                _validationError.tryEmit(TheIntroDbValidationError.MissingApiKey)
                dataStore.setEnabled(false)
                return@launch
            }
            dataStore.setEnabled(value)
        }
    }

    fun setApiKey(value: String) {
        viewModelScope.launch {
            dataStore.setApiKey(value)
            if (value.trim().isBlank()) {
                dataStore.setEnabled(false)
            }
        }
    }

    fun setShowIntroButton(value: Boolean) {
        viewModelScope.launch { dataStore.setShowIntroButton(value) }
    }

    fun setShowRecapButton(value: Boolean) {
        viewModelScope.launch { dataStore.setShowRecapButton(value) }
    }

    fun setShowCreditsButton(value: Boolean) {
        viewModelScope.launch { dataStore.setShowCreditsButton(value) }
    }

    fun setShowPreviewButton(value: Boolean) {
        viewModelScope.launch { dataStore.setShowPreviewButton(value) }
    }
}

enum class TheIntroDbValidationError {
    MissingApiKey
}
