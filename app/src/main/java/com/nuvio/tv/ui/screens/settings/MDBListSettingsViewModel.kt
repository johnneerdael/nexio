package com.nuvio.tv.ui.screens.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.nuvio.tv.data.local.MDBListSettingsDataStore
import com.nuvio.tv.domain.model.MDBListSettings
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class MDBListSettingsViewModel @Inject constructor(
    private val dataStore: MDBListSettingsDataStore
) : ViewModel() {

    private val _uiState = MutableStateFlow(MDBListSettingsUiState())
    val uiState: StateFlow<MDBListSettingsUiState> = _uiState.asStateFlow()

    init {
        viewModelScope.launch {
            dataStore.settings.collectLatest { settings ->
                _uiState.update { it.fromSettings(settings) }
            }
        }
    }

    fun onEvent(event: MDBListSettingsEvent) {
        when (event) {
            is MDBListSettingsEvent.ToggleEnabled -> update { dataStore.setEnabled(event.enabled) }
            is MDBListSettingsEvent.SetApiKey -> update { dataStore.setApiKey(event.apiKey) }
            is MDBListSettingsEvent.ToggleTrakt -> update { dataStore.setShowTrakt(event.enabled) }
            is MDBListSettingsEvent.ToggleImdb -> update { dataStore.setShowImdb(event.enabled) }
            is MDBListSettingsEvent.ToggleTmdb -> update { dataStore.setShowTmdb(event.enabled) }
            is MDBListSettingsEvent.ToggleLetterboxd -> update { dataStore.setShowLetterboxd(event.enabled) }
            is MDBListSettingsEvent.ToggleTomatoes -> update { dataStore.setShowTomatoes(event.enabled) }
            is MDBListSettingsEvent.ToggleAudience -> update { dataStore.setShowAudience(event.enabled) }
            is MDBListSettingsEvent.ToggleMetacritic -> update { dataStore.setShowMetacritic(event.enabled) }
        }
    }

    private fun update(action: suspend () -> Unit) {
        viewModelScope.launch { action() }
    }
}

data class MDBListSettingsUiState(
    val enabled: Boolean = false,
    val apiKey: String = "",
    val showTrakt: Boolean = true,
    val showImdb: Boolean = true,
    val showTmdb: Boolean = true,
    val showLetterboxd: Boolean = true,
    val showTomatoes: Boolean = true,
    val showAudience: Boolean = true,
    val showMetacritic: Boolean = true
) {
    fun fromSettings(settings: MDBListSettings): MDBListSettingsUiState = copy(
        enabled = settings.enabled,
        apiKey = settings.apiKey,
        showTrakt = settings.showTrakt,
        showImdb = settings.showImdb,
        showTmdb = settings.showTmdb,
        showLetterboxd = settings.showLetterboxd,
        showTomatoes = settings.showTomatoes,
        showAudience = settings.showAudience,
        showMetacritic = settings.showMetacritic
    )
}

sealed class MDBListSettingsEvent {
    data class ToggleEnabled(val enabled: Boolean) : MDBListSettingsEvent()
    data class SetApiKey(val apiKey: String) : MDBListSettingsEvent()
    data class ToggleTrakt(val enabled: Boolean) : MDBListSettingsEvent()
    data class ToggleImdb(val enabled: Boolean) : MDBListSettingsEvent()
    data class ToggleTmdb(val enabled: Boolean) : MDBListSettingsEvent()
    data class ToggleLetterboxd(val enabled: Boolean) : MDBListSettingsEvent()
    data class ToggleTomatoes(val enabled: Boolean) : MDBListSettingsEvent()
    data class ToggleAudience(val enabled: Boolean) : MDBListSettingsEvent()
    data class ToggleMetacritic(val enabled: Boolean) : MDBListSettingsEvent()
}
