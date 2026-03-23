package com.nexio.tv.ui.screens.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.nexio.tv.data.local.OmdbSettingsDataStore
import com.nexio.tv.data.remote.api.OmdbApi
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
class OmdbSettingsViewModel @Inject constructor(
    private val dataStore: OmdbSettingsDataStore,
    private val omdbApi: OmdbApi
) : ViewModel() {

    private val _uiState = MutableStateFlow(OmdbSettingsUiState())
    val uiState: StateFlow<OmdbSettingsUiState> = _uiState.asStateFlow()

    private val _validating = MutableStateFlow(false)
    val validating: StateFlow<Boolean> = _validating.asStateFlow()

    private val _validationError = MutableSharedFlow<OmdbValidationError>(extraBufferCapacity = 1)
    val validationError: SharedFlow<OmdbValidationError> = _validationError.asSharedFlow()

    init {
        viewModelScope.launch {
            dataStore.settings.collectLatest { settings ->
                _uiState.update {
                    OmdbSettingsUiState(
                        enabled = settings.enabled,
                        apiKey = settings.apiKey
                    )
                }
            }
        }
    }

    fun setEnabled(enabled: Boolean) {
        viewModelScope.launch { dataStore.setEnabled(enabled) }
    }

    fun validateAndSaveApiKey(value: String, onSuccess: () -> Unit) {
        val trimmed = value.trim()
        if (trimmed.isBlank()) {
            viewModelScope.launch { dataStore.setApiKey("") }
            onSuccess()
            return
        }
        viewModelScope.launch {
            _validating.value = true
            val valid = try {
                val response = omdbApi.getSeason(
                    apiKey = trimmed,
                    seriesImdbId = OMDB_VALIDATION_SERIES_IMDB_ID,
                    season = OMDB_VALIDATION_SEASON_NUMBER
                )
                response.isSuccessful && response.body()?.response.equals("True", ignoreCase = true)
            } catch (_: Exception) {
                false
            }
            _validating.value = false
            if (valid) {
                dataStore.setApiKey(trimmed)
                onSuccess()
            } else {
                _validationError.tryEmit(OmdbValidationError.InvalidApiKey)
            }
        }
    }
}

data class OmdbSettingsUiState(
    val enabled: Boolean = false,
    val apiKey: String = ""
) {
    val isActive: Boolean
        get() = enabled && apiKey.isNotBlank()
}

enum class OmdbValidationError {
    InvalidApiKey
}

private const val OMDB_VALIDATION_SERIES_IMDB_ID = "tt0944947"
private const val OMDB_VALIDATION_SEASON_NUMBER = 1
