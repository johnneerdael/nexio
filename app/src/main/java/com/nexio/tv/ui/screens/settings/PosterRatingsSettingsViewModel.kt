package com.nexio.tv.ui.screens.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.nexio.tv.data.local.PosterRatingsSettingsDataStore
import com.nexio.tv.data.remote.api.RpdbApi
import com.nexio.tv.data.remote.api.TopPostersApi
import com.nexio.tv.domain.model.PosterRatingsSettings
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
class PosterRatingsSettingsViewModel @Inject constructor(
    private val dataStore: PosterRatingsSettingsDataStore,
    private val rpdbApi: RpdbApi,
    private val topPostersApi: TopPostersApi
) : ViewModel() {

    private val _uiState = MutableStateFlow(PosterRatingsSettingsUiState())
    val uiState: StateFlow<PosterRatingsSettingsUiState> = _uiState.asStateFlow()

    private val _validatingRpdb = MutableStateFlow(false)
    val validatingRpdb: StateFlow<Boolean> = _validatingRpdb.asStateFlow()

    private val _validatingTopPosters = MutableStateFlow(false)
    val validatingTopPosters: StateFlow<Boolean> = _validatingTopPosters.asStateFlow()

    private val _validationError = MutableSharedFlow<PosterRatingsProviderType>(extraBufferCapacity = 1)
    val validationError: SharedFlow<PosterRatingsProviderType> = _validationError.asSharedFlow()

    init {
        viewModelScope.launch {
            dataStore.settings.collectLatest { settings ->
                _uiState.update { it.fromSettings(settings) }
            }
        }
    }

    fun onEvent(event: PosterRatingsSettingsEvent) {
        when (event) {
            is PosterRatingsSettingsEvent.ToggleRpdb -> update { dataStore.setRpdbEnabled(event.enabled) }
            is PosterRatingsSettingsEvent.ToggleTopPosters -> update { dataStore.setTopPostersEnabled(event.enabled) }
        }
    }

    fun validateAndSaveRpdbApiKey(value: String, onSuccess: () -> Unit) {
        val trimmed = value.trim()
        if (trimmed.isBlank()) {
            viewModelScope.launch {
                dataStore.setRpdbApiKey("")
                onSuccess()
            }
            return
        }
        viewModelScope.launch {
            _validatingRpdb.value = true
            val valid = try {
                val response = rpdbApi.verifyApiKey(trimmed)
                if (!response.isSuccessful) {
                    false
                } else {
                    val body = response.body()?.string()?.trim().orEmpty().lowercase()
                    body.isBlank() || body.contains("true") || body.contains("valid")
                }
            } catch (_: Exception) {
                false
            }
            _validatingRpdb.value = false
            if (valid) {
                dataStore.setRpdbApiKey(trimmed)
                onSuccess()
            } else {
                _validationError.tryEmit(PosterRatingsProviderType.RPDB)
            }
        }
    }

    fun validateAndSaveTopPostersApiKey(value: String, onSuccess: () -> Unit) {
        val trimmed = value.trim()
        if (trimmed.isBlank()) {
            viewModelScope.launch {
                dataStore.setTopPostersApiKey("")
                onSuccess()
            }
            return
        }
        viewModelScope.launch {
            _validatingTopPosters.value = true
            val valid = try {
                val response = topPostersApi.verifyApiKey(trimmed)
                if (!response.isSuccessful) {
                    false
                } else {
                    val body = response.body()?.string()?.trim().orEmpty().lowercase()
                    body.isBlank() || body.contains("\"valid\":true") || body.contains("tier")
                }
            } catch (_: Exception) {
                false
            }
            _validatingTopPosters.value = false
            if (valid) {
                dataStore.setTopPostersApiKey(trimmed)
                onSuccess()
            } else {
                _validationError.tryEmit(PosterRatingsProviderType.TOP_POSTERS)
            }
        }
    }

    private fun update(action: suspend () -> Unit) {
        viewModelScope.launch { action() }
    }
}

enum class PosterRatingsProviderType {
    RPDB,
    TOP_POSTERS
}

data class PosterRatingsSettingsUiState(
    val rpdbEnabled: Boolean = false,
    val rpdbApiKey: String = "",
    val topPostersEnabled: Boolean = false,
    val topPostersApiKey: String = ""
) {
    fun fromSettings(settings: PosterRatingsSettings): PosterRatingsSettingsUiState = copy(
        rpdbEnabled = settings.rpdbEnabled,
        rpdbApiKey = settings.rpdbApiKey,
        topPostersEnabled = settings.topPostersEnabled,
        topPostersApiKey = settings.topPostersApiKey
    )
}

sealed class PosterRatingsSettingsEvent {
    data class ToggleRpdb(val enabled: Boolean) : PosterRatingsSettingsEvent()
    data class ToggleTopPosters(val enabled: Boolean) : PosterRatingsSettingsEvent()
}

