package com.nexio.tv.ui.screens.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.nexio.tv.data.local.OpenSubtitlesPreferences
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class OpenSubtitlesSettingsViewModel @Inject constructor(
    private val preferences: OpenSubtitlesPreferences
) : ViewModel() {

    private val _settings = MutableStateFlow(
        OpenSubtitlesPreferences.Snapshot(
            enabled = OpenSubtitlesPreferences.DEFAULT_ENABLED,
            onlyTrusted = OpenSubtitlesPreferences.DEFAULT_ONLY_TRUSTED,
            includeAiTranslated = OpenSubtitlesPreferences.DEFAULT_INCLUDE_AI
        )
    )
    val settings: StateFlow<OpenSubtitlesPreferences.Snapshot> = _settings.asStateFlow()

    init {
        viewModelScope.launch {
            combine(
                preferences.enabled,
                preferences.onlyTrusted,
                preferences.includeAiTranslated
            ) { enabled, onlyTrusted, includeAi ->
                OpenSubtitlesPreferences.Snapshot(enabled, onlyTrusted, includeAi)
            }.collect { _settings.value = it }
        }
    }

    fun setEnabled(value: Boolean) {
        viewModelScope.launch { preferences.setEnabled(value) }
    }

    fun setOnlyTrusted(value: Boolean) {
        viewModelScope.launch { preferences.setOnlyTrusted(value) }
    }

    fun setIncludeAiTranslated(value: Boolean) {
        viewModelScope.launch { preferences.setIncludeAiTranslated(value) }
    }
}
