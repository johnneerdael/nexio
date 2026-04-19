package com.nexio.tv.ui.screens.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.nexio.tv.data.local.SubtitleTranslationSettingsDataStore
import com.nexio.tv.data.local.defaultSubtitleTranslationSettings
import com.nexio.tv.domain.model.SubtitleTranslationDefaults
import com.nexio.tv.domain.model.SubtitleTranslationProvider
import com.nexio.tv.domain.model.SubtitleTranslationSettings
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
class SubtitleTranslationSettingsViewModel @Inject constructor(
    private val dataStore: SubtitleTranslationSettingsDataStore
) : ViewModel() {

    private val _uiState = MutableStateFlow(SubtitleTranslationSettingsUiState())
    val uiState: StateFlow<SubtitleTranslationSettingsUiState> = _uiState.asStateFlow()

    private val _validationError = MutableSharedFlow<SubtitleTranslationValidationError>(extraBufferCapacity = 1)
    val validationError: SharedFlow<SubtitleTranslationValidationError> = _validationError.asSharedFlow()

    init {
        viewModelScope.launch {
            dataStore.settings.collectLatest { settings ->
                val nextState = _uiState.value.fromSettings(settings)
                if (settings.enabled && nextState.enablementError() != null) {
                    dataStore.setEnabled(false)
                    return@collectLatest
                }
                _uiState.update { nextState }
            }
        }
    }

    fun setEnabled(enabled: Boolean) {
        viewModelScope.launch {
            val error = _uiState.value.enablementError()
            if (enabled && error != null) {
                _validationError.tryEmit(error)
                dataStore.setEnabled(false)
                return@launch
            }
            dataStore.setEnabled(enabled)
        }
    }

    fun setProvider(provider: SubtitleTranslationProvider) {
        viewModelScope.launch {
            if (_uiState.value.provider == provider) return@launch
            _uiState.update { it.withProviderDefaults(provider) }
            dataStore.setProvider(provider)
        }
    }

    fun saveModel(value: String, onSuccess: () -> Unit) {
        viewModelScope.launch {
            dataStore.setModel(value.trim())
            onSuccess()
        }
    }

    fun saveBaseUrl(value: String, onSuccess: () -> Unit) {
        viewModelScope.launch {
            dataStore.setBaseUrl(value.trim().trimEnd('/'))
            onSuccess()
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

    fun setAssSsaSystemPromptEnabled(enabled: Boolean) {
        viewModelScope.launch {
            dataStore.setAssSsaSystemPromptEnabled(enabled)
        }
    }
}

data class SubtitleTranslationSettingsUiState(
    val enabled: Boolean = false,
    val provider: SubtitleTranslationProvider = SubtitleTranslationProvider.OPENAI,
    val apiKey: String = "",
    val model: String = SubtitleTranslationDefaults.OPENAI_MODEL,
    val baseUrl: String = SubtitleTranslationDefaults.OPENAI_BASE_URL,
    val assSsaSystemPromptEnabled: Boolean = false
) {
    fun fromSettings(settings: SubtitleTranslationSettings): SubtitleTranslationSettingsUiState = copy(
        enabled = settings.enabled,
        provider = settings.provider,
        apiKey = settings.apiKey,
        model = settings.model,
        baseUrl = settings.baseUrl,
        assSsaSystemPromptEnabled = settings.assSsaSystemPromptEnabled
    )

    fun withProviderDefaults(provider: SubtitleTranslationProvider): SubtitleTranslationSettingsUiState {
        if (this.provider == provider) return this
        val defaults = defaultSubtitleTranslationSettings(provider)
        return copy(provider = provider, model = defaults.model, baseUrl = defaults.baseUrl)
    }

    fun enablementError(): SubtitleTranslationValidationError? {
        if (apiKey.isBlank()) return SubtitleTranslationValidationError.MissingApiKey
        if (model.isBlank()) return SubtitleTranslationValidationError.MissingModel
        if (baseUrl.isNotBlank() && !baseUrl.startsWith("https://") && !baseUrl.startsWith("http://")) {
            return SubtitleTranslationValidationError.InvalidEndpoint
        }
        return null
    }
}

enum class SubtitleTranslationValidationError {
    MissingApiKey,
    MissingModel,
    InvalidEndpoint
}
