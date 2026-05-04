package com.nexio.tv.ui.screens.settings

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.nexio.tv.core.artwork.ArtworkDecisionCache
import com.nexio.tv.core.artwork.PremiumArtworkInvalidationNotifier
import com.nexio.tv.core.integration.IntegrationOwnershipService
import com.nexio.tv.core.integration.RailKeyFactory
import com.nexio.tv.core.profile.ProfileManager
import com.nexio.tv.data.local.HydratedHomeOverlayStore
import com.nexio.tv.data.local.HomeCatalogSnapshotStore
import com.nexio.tv.data.local.MetadataDiskCacheStore
import com.nexio.tv.data.local.PosterRatingsSettingsDataStore
import com.nexio.tv.data.repository.ProviderSettingsRepository
import com.nexio.tv.domain.model.PosterRatingsSettings
import com.nexio.tv.domain.repository.CatalogRepository
import com.nexio.tv.domain.repository.MetaRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

@HiltViewModel
class PosterRatingsSettingsViewModel @Inject constructor(
    @ApplicationContext private val appContext: Context,
    private val dataStore: PosterRatingsSettingsDataStore,
    private val providerSettingsRepository: ProviderSettingsRepository,
    private val metadataDiskCacheStore: MetadataDiskCacheStore,
    private val homeCatalogSnapshotStore: HomeCatalogSnapshotStore,
    private val hydratedHomeOverlayStore: HydratedHomeOverlayStore,
    private val profileManager: ProfileManager,
    private val integrationOwnershipService: IntegrationOwnershipService,
    private val artworkDecisionCache: ArtworkDecisionCache,
    private val premiumArtworkInvalidationNotifier: PremiumArtworkInvalidationNotifier,
    private val metaRepository: MetaRepository,
    private val catalogRepository: CatalogRepository
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

    private val _posterCacheInvalidating = MutableStateFlow(false)
    val posterCacheInvalidating: StateFlow<Boolean> = _posterCacheInvalidating.asStateFlow()

    private val _posterCacheInvalidated = MutableStateFlow(false)
    val posterCacheInvalidated: StateFlow<Boolean> = _posterCacheInvalidated.asStateFlow()

    fun onEvent(event: PosterRatingsSettingsEvent) {
        when (event) {
            is PosterRatingsSettingsEvent.ToggleRpdb -> update {
                dataStore.setRpdbEnabled(event.enabled)
                invalidateArtworkDisplayState()
            }
            is PosterRatingsSettingsEvent.ToggleTopPosters -> update {
                dataStore.setTopPostersEnabled(event.enabled)
                invalidateArtworkDisplayState()
            }
            is PosterRatingsSettingsEvent.InvalidatePosterCache -> invalidatePosterCache()
        }
    }

    private fun invalidatePosterCache() {
        viewModelScope.launch {
            _posterCacheInvalidating.value = true
            invalidateArtworkDisplayState()
            _posterCacheInvalidating.value = false
            _posterCacheInvalidated.value = true
        }
    }

    private suspend fun invalidateArtworkDisplayState() {
        withContext(Dispatchers.IO) {
            val profileId = profileManager.activeProfileId.value
            integrationOwnershipService.syncRails(
                RailKeyFactory.homeCatalogNamespace(profileId),
                emptyList()
            )
            homeCatalogSnapshotStore.clear(profileId = profileId)
            hydratedHomeOverlayStore.clearAll()
            artworkDecisionCache.invalidatePremiumArtworkPolicy()
            premiumArtworkInvalidationNotifier.notifyInvalidated()
        }
    }

    fun validateAndSaveRpdbApiKey(value: String, onSuccess: () -> Unit) {
        val trimmed = value.trim()
        if (trimmed.isBlank()) {
            viewModelScope.launch {
                dataStore.setRpdbApiKey("")
                invalidateArtworkDisplayState()
                onSuccess()
            }
            return
        }
        viewModelScope.launch {
            _validatingRpdb.value = true
            val valid = providerSettingsRepository.validateRpdbApiKey(trimmed)
            _validatingRpdb.value = false
            if (valid) {
                dataStore.setRpdbApiKey(trimmed)
                invalidateArtworkDisplayState()
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
                invalidateArtworkDisplayState()
                onSuccess()
            }
            return
        }
        viewModelScope.launch {
            _validatingTopPosters.value = true
            val valid = providerSettingsRepository.validateTopPostersApiKey(trimmed)
            _validatingTopPosters.value = false
            if (valid) {
                dataStore.setTopPostersApiKey(trimmed)
                invalidateArtworkDisplayState()
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
    data object InvalidatePosterCache : PosterRatingsSettingsEvent()
}
