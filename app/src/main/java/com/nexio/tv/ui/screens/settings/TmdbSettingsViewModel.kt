package com.nexio.tv.ui.screens.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.nexio.tv.data.local.TmdbCatalogIds
import com.nexio.tv.data.local.TmdbCatalogPreferences
import com.nexio.tv.data.local.TmdbCatalogSettingsDataStore
import com.nexio.tv.data.local.TmdbSettingsDataStore
import com.nexio.tv.data.repository.ProviderSettingsRepository
import com.nexio.tv.domain.model.TmdbSettings
import com.nexio.tv.ui.screens.home.order.HomeRailKey
import com.nexio.tv.ui.screens.home.order.HomeRailOrderStore
import com.nexio.tv.ui.screens.home.order.RailFamily
import com.nexio.tv.ui.screens.home.order.RailOrderMutationSource
import dagger.hilt.android.lifecycle.HiltViewModel
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
import javax.inject.Inject

@HiltViewModel
class TmdbSettingsViewModel @Inject constructor(
    private val dataStore: TmdbSettingsDataStore,
    private val tmdbCatalogSettingsDataStore: TmdbCatalogSettingsDataStore,
    private val providerSettingsRepository: ProviderSettingsRepository,
    private val homeRailOrderStore: HomeRailOrderStore
) : ViewModel() {

    private val _uiState = MutableStateFlow(TmdbSettingsUiState())
    val uiState: StateFlow<TmdbSettingsUiState> = _uiState.asStateFlow()
    private val _validating = MutableStateFlow(false)
    val validating: StateFlow<Boolean> = _validating.asStateFlow()
    private val _validationError = MutableSharedFlow<TmdbValidationError>(extraBufferCapacity = 1)
    val validationError: SharedFlow<TmdbValidationError> = _validationError.asSharedFlow()

    init {
        viewModelScope.launch {
            dataStore.settings.collectLatest { settings ->
                _uiState.update { it.fromSettings(settings) }
            }
        }
        viewModelScope.launch {
            tmdbCatalogSettingsDataStore.catalogPreferences.collectLatest { catalogPreferences ->
                _uiState.update { it.fromCatalogPreferences(catalogPreferences) }
            }
        }
    }

    fun onEvent(event: TmdbSettingsEvent) {
        when (event) {
            is TmdbSettingsEvent.ToggleEnabled -> update {
                dataStore.setEnabled(true)
            }
            is TmdbSettingsEvent.ToggleArtwork -> update { dataStore.setUseArtwork(event.enabled) }
            is TmdbSettingsEvent.ToggleBasicInfo -> update { dataStore.setUseBasicInfo(event.enabled) }
            is TmdbSettingsEvent.ToggleDetails -> update { dataStore.setUseDetails(event.enabled) }
            is TmdbSettingsEvent.ToggleCredits -> update { dataStore.setUseCredits(event.enabled) }
            is TmdbSettingsEvent.ToggleProductions -> update { dataStore.setUseProductions(event.enabled) }
            is TmdbSettingsEvent.ToggleNetworks -> update { dataStore.setUseNetworks(event.enabled) }
            is TmdbSettingsEvent.ToggleEpisodes -> update { dataStore.setUseEpisodes(event.enabled) }
            is TmdbSettingsEvent.ToggleMoreLikeThis -> update { dataStore.setUseMoreLikeThis(event.enabled) }
            is TmdbSettingsEvent.ToggleReviews -> update { dataStore.setUseReviews(event.enabled) }
            is TmdbSettingsEvent.ToggleCollections -> update { dataStore.setUseCollections(event.enabled) }
            is TmdbSettingsEvent.ToggleCatalog -> update {
                tmdbCatalogSettingsDataStore.setCatalogEnabled(event.catalogId, event.enabled)
            }
            is TmdbSettingsEvent.MoveCatalogUp -> update {
                tmdbCatalogSettingsDataStore.moveCatalog(event.catalogId, -1)
                val newOrder = tmdbCatalogSettingsDataStore.catalogPreferences.first().catalogOrder
                homeRailOrderStore.reorderProviderKeys(
                    family = RailFamily.TMDB,
                    providerOrder = newOrder.map(::HomeRailKey),
                    source = RailOrderMutationSource.PROVIDER_SETTINGS_SCREEN,
                )
            }
            is TmdbSettingsEvent.MoveCatalogDown -> update {
                tmdbCatalogSettingsDataStore.moveCatalog(event.catalogId, 1)
                val newOrder = tmdbCatalogSettingsDataStore.catalogPreferences.first().catalogOrder
                homeRailOrderStore.reorderProviderKeys(
                    family = RailFamily.TMDB,
                    providerOrder = newOrder.map(::HomeRailKey),
                    source = RailOrderMutationSource.PROVIDER_SETTINGS_SCREEN,
                )
            }
            is TmdbSettingsEvent.ToggleAdultContent -> update {
                tmdbCatalogSettingsDataStore.setIncludeAdult(event.enabled)
            }
            is TmdbSettingsEvent.ToggleDigitalReleaseFilter -> update {
                tmdbCatalogSettingsDataStore.setHideUnreleasedDigital(event.enabled)
            }
        }
    }

    fun validateAndSaveApiKey(value: String, onSuccess: () -> Unit) {
        val trimmed = value.trim()
        if (trimmed.isBlank()) {
            viewModelScope.launch {
                dataStore.setApiKey("")
                dataStore.setEnabled(true)
            }
            onSuccess()
            return
        }
        viewModelScope.launch {
            _validating.value = true
            val valid = providerSettingsRepository.validateTmdbApiKey(trimmed)
            _validating.value = false
            if (valid) {
                dataStore.setApiKey(trimmed)
                onSuccess()
            } else {
                _validationError.tryEmit(TmdbValidationError.InvalidApiKey)
            }
        }
    }

    private fun update(action: suspend () -> Unit) {
        viewModelScope.launch { action() }
    }
}

data class TmdbSettingsUiState(
    val enabled: Boolean = false,
    val apiKey: String = "",
    val useArtwork: Boolean = true,
    val useBasicInfo: Boolean = true,
    val useDetails: Boolean = true,
    val useCredits: Boolean = true,
    val useProductions: Boolean = true,
    val useNetworks: Boolean = true,
    val useEpisodes: Boolean = true,
    val useMoreLikeThis: Boolean = true,
    val useReviews: Boolean = true,
    val useCollections: Boolean = true,
    val catalogOrder: List<String> = TmdbCatalogIds.BUILT_IN_ORDER,
    val enabledCatalogKeys: Set<String> = TmdbCatalogIds.DEFAULT_ENABLED,
    val includeAdult: Boolean = false,
    val hideUnreleasedDigital: Boolean = true
) {
    val isActive: Boolean
        get() = enabled

    val catalogControlsEditable: Boolean
        get() = true

    fun fromSettings(settings: TmdbSettings): TmdbSettingsUiState = copy(
        enabled = settings.enabled,
        apiKey = settings.apiKey,
        useArtwork = settings.useArtwork,
        useBasicInfo = settings.useBasicInfo,
        useDetails = settings.useDetails,
        useCredits = settings.useCredits,
        useProductions = settings.useProductions,
        useNetworks = settings.useNetworks,
        useEpisodes = settings.useEpisodes,
        useMoreLikeThis = settings.useMoreLikeThis,
        useReviews = settings.useReviews,
        useCollections = settings.useCollections
    )

    fun fromCatalogPreferences(catalogPreferences: TmdbCatalogPreferences): TmdbSettingsUiState = copy(
        catalogOrder = catalogPreferences.catalogOrder,
        enabledCatalogKeys = catalogPreferences.enabledCatalogs,
        includeAdult = catalogPreferences.includeAdult,
        hideUnreleasedDigital = catalogPreferences.hideUnreleasedDigital
    )
}

sealed class TmdbSettingsEvent {
    data class ToggleEnabled(val enabled: Boolean) : TmdbSettingsEvent()
    data class ToggleArtwork(val enabled: Boolean) : TmdbSettingsEvent()
    data class ToggleBasicInfo(val enabled: Boolean) : TmdbSettingsEvent()
    data class ToggleDetails(val enabled: Boolean) : TmdbSettingsEvent()
    data class ToggleCredits(val enabled: Boolean) : TmdbSettingsEvent()
    data class ToggleProductions(val enabled: Boolean) : TmdbSettingsEvent()
    data class ToggleNetworks(val enabled: Boolean) : TmdbSettingsEvent()
    data class ToggleEpisodes(val enabled: Boolean) : TmdbSettingsEvent()
    data class ToggleMoreLikeThis(val enabled: Boolean) : TmdbSettingsEvent()
    data class ToggleReviews(val enabled: Boolean) : TmdbSettingsEvent()
    data class ToggleCollections(val enabled: Boolean) : TmdbSettingsEvent()
    data class ToggleCatalog(val catalogId: String, val enabled: Boolean) : TmdbSettingsEvent()
    data class MoveCatalogUp(val catalogId: String) : TmdbSettingsEvent()
    data class MoveCatalogDown(val catalogId: String) : TmdbSettingsEvent()
    data class ToggleAdultContent(val enabled: Boolean) : TmdbSettingsEvent()
    data class ToggleDigitalReleaseFilter(val enabled: Boolean) : TmdbSettingsEvent()
}

enum class TmdbValidationError {
    MissingApiKey,
    InvalidApiKey
}
