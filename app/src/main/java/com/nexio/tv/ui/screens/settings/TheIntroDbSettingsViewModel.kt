package com.nexio.tv.ui.screens.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.nexio.tv.data.local.TheIntroDbSettings
import com.nexio.tv.data.local.TheIntroDbSettingsDataStore
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
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

    init {
        viewModelScope.launch {
            dataStore.settings.collectLatest { settings ->
                _settings.value = settings
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
