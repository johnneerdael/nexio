package com.nexio.tv.ui.screens.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.nexio.tv.data.local.WyzieSettingsDataStore
import com.nexio.tv.domain.model.WyzieSettings
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class WyzieSubtitleSettingsViewModel @Inject constructor(
    private val dataStore: WyzieSettingsDataStore,
) : ViewModel() {

    val state: StateFlow<WyzieSettings> = dataStore.settings.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(stopTimeoutMillis = 5_000),
        initialValue = WyzieSettings.DEFAULT,
    )

    fun onSetEnabled(enabled: Boolean) {
        viewModelScope.launch { dataStore.setEnabled(enabled) }
    }

    fun onSetApiKey(value: String) {
        viewModelScope.launch { dataStore.setApiKey(value) }
    }

    fun onClearApiKey() {
        viewModelScope.launch { dataStore.setApiKey("") }
    }
}

/**
 * Returns the masked form of the key for display, or [notSetLabel] when blank.
 */
internal fun maskWyzieKey(key: String?, notSetLabel: String): String {
    val trimmed = key?.trim().orEmpty()
    if (trimmed.isBlank()) return notSetLabel
    return if (trimmed.length <= 4) "••••" else "••••••${trimmed.takeLast(4)}"
}
