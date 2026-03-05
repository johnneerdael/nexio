package com.nexio.tv.ui.screens.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.nexio.tv.data.local.MDBListCatalogPreferences
import com.nexio.tv.data.local.MDBListSettingsDataStore
import com.nexio.tv.data.remote.api.MDBListApi
import com.nexio.tv.data.repository.MDBListDiscoveryService
import com.nexio.tv.data.repository.MDBListListOption
import com.nexio.tv.domain.model.MDBListSettings
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class MDBListSettingsViewModel @Inject constructor(
    private val dataStore: MDBListSettingsDataStore,
    private val mdbListApi: MDBListApi,
    private val mdbListDiscoveryService: MDBListDiscoveryService
) : ViewModel() {

    private val _uiState = MutableStateFlow(MDBListSettingsUiState())
    val uiState: StateFlow<MDBListSettingsUiState> = _uiState.asStateFlow()

    private val _validating = MutableStateFlow(false)
    val validating: StateFlow<Boolean> = _validating.asStateFlow()

    private val _validationError = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    val validationError: SharedFlow<Unit> = _validationError.asSharedFlow()

    init {
        viewModelScope.launch {
            dataStore.settings.collectLatest { settings ->
                _uiState.update { it.fromSettings(settings) }
            }
        }
        viewModelScope.launch {
            combine(
                dataStore.catalogPreferences,
                mdbListDiscoveryService.observeSnapshot()
            ) { catalogPrefs, snapshot ->
                Triple(catalogPrefs, snapshot.personalLists, snapshot.topLists)
            }.collectLatest { (catalogPrefs, personalLists, topLists) ->
                _uiState.update {
                    it.copy(
                        catalogPreferences = catalogPrefs,
                        personalLists = personalLists,
                        topLists = topLists
                    )
                }
            }
        }
    }

    fun onEvent(event: MDBListSettingsEvent) {
        when (event) {
            is MDBListSettingsEvent.ToggleEnabled -> update { dataStore.setEnabled(event.enabled) }
            is MDBListSettingsEvent.ToggleTrakt -> update { dataStore.setShowTrakt(event.enabled) }
            is MDBListSettingsEvent.ToggleImdb -> update { dataStore.setShowImdb(event.enabled) }
            is MDBListSettingsEvent.ToggleTmdb -> update { dataStore.setShowTmdb(event.enabled) }
            is MDBListSettingsEvent.ToggleLetterboxd -> update { dataStore.setShowLetterboxd(event.enabled) }
            is MDBListSettingsEvent.ToggleTomatoes -> update { dataStore.setShowTomatoes(event.enabled) }
            is MDBListSettingsEvent.ToggleAudience -> update { dataStore.setShowAudience(event.enabled) }
            is MDBListSettingsEvent.ToggleMetacritic -> update { dataStore.setShowMetacritic(event.enabled) }
            is MDBListSettingsEvent.TogglePersonalList -> {
                update {
                    dataStore.setPersonalListEnabled(event.listKey, event.enabled)
                    mdbListDiscoveryService.ensureFresh(force = true)
                }
            }
            is MDBListSettingsEvent.ToggleTopList -> {
                update {
                    dataStore.setTopListSelected(event.listKey, event.enabled)
                    mdbListDiscoveryService.ensureFresh(force = true)
                }
            }
            is MDBListSettingsEvent.MoveCatalogUp -> moveCatalog(event.listKey, -1)
            is MDBListSettingsEvent.MoveCatalogDown -> moveCatalog(event.listKey, 1)
            MDBListSettingsEvent.CatalogManagementOpened -> {
                viewModelScope.launch { mdbListDiscoveryService.ensureFresh(force = false) }
            }
        }
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
                mdbListApi.getUser(trimmed).isSuccessful
            } catch (e: Exception) { false }
            _validating.value = false
            if (valid) {
                dataStore.setApiKey(trimmed)
                onSuccess()
            } else {
                _validationError.tryEmit(Unit)
            }
        }
    }

    private fun update(action: suspend () -> Unit) {
        viewModelScope.launch { action() }
    }

    private fun moveCatalog(listKey: String, direction: Int) {
        val currentState = _uiState.value
        val activeKeys = currentState.activeCatalogKeys()
        if (listKey !in activeKeys) return
        viewModelScope.launch {
            dataStore.moveCatalog(listKey = listKey, direction = direction, availableKeys = activeKeys)
            mdbListDiscoveryService.ensureFresh(force = true)
        }
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
    val showMetacritic: Boolean = true,
    val catalogPreferences: MDBListCatalogPreferences = MDBListCatalogPreferences(),
    val personalLists: List<MDBListListOption> = emptyList(),
    val topLists: List<MDBListListOption> = emptyList()
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

    fun activeCatalogKeys(): Set<String> {
        val personal = personalLists
            .filter { catalogPreferences.isPersonalListEnabled(it.key) }
            .map { it.key }
        val top = topLists
            .filter { catalogPreferences.isTopListSelected(it.key) }
            .map { it.key }
        return (personal + top).toSet()
    }
}

sealed class MDBListSettingsEvent {
    data class ToggleEnabled(val enabled: Boolean) : MDBListSettingsEvent()
    data class ToggleTrakt(val enabled: Boolean) : MDBListSettingsEvent()
    data class ToggleImdb(val enabled: Boolean) : MDBListSettingsEvent()
    data class ToggleTmdb(val enabled: Boolean) : MDBListSettingsEvent()
    data class ToggleLetterboxd(val enabled: Boolean) : MDBListSettingsEvent()
    data class ToggleTomatoes(val enabled: Boolean) : MDBListSettingsEvent()
    data class ToggleAudience(val enabled: Boolean) : MDBListSettingsEvent()
    data class ToggleMetacritic(val enabled: Boolean) : MDBListSettingsEvent()
    data class TogglePersonalList(val listKey: String, val enabled: Boolean) : MDBListSettingsEvent()
    data class ToggleTopList(val listKey: String, val enabled: Boolean) : MDBListSettingsEvent()
    data class MoveCatalogUp(val listKey: String) : MDBListSettingsEvent()
    data class MoveCatalogDown(val listKey: String) : MDBListSettingsEvent()
    data object CatalogManagementOpened : MDBListSettingsEvent()
}
