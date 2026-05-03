package com.nexio.tv.ui.screens.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.nexio.tv.data.local.OpenSubtitlesPreferences
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class OpenSubtitlesSettingsState(
    val enabled: Boolean = OpenSubtitlesPreferences.DEFAULT_ENABLED,
    val onlyTrusted: Boolean = OpenSubtitlesPreferences.DEFAULT_ONLY_TRUSTED,
    val includeAiTranslated: Boolean = OpenSubtitlesPreferences.DEFAULT_INCLUDE_AI,
)

@HiltViewModel
class OpenSubtitlesSettingsViewModel @Inject constructor(
    private val preferences: OpenSubtitlesPreferences,
) : ViewModel() {
    val state: StateFlow<OpenSubtitlesSettingsState> = combine(
        preferences.enabled,
        preferences.onlyTrusted,
        preferences.includeAiTranslated,
    ) { enabled, onlyTrusted, includeAiTranslated ->
        OpenSubtitlesSettingsState(
            enabled = enabled,
            onlyTrusted = onlyTrusted,
            includeAiTranslated = includeAiTranslated,
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(stopTimeoutMillis = 5_000),
        initialValue = OpenSubtitlesSettingsState(),
    )

    fun onSetEnabled(enabled: Boolean) {
        viewModelScope.launch { preferences.setEnabled(enabled) }
    }

    fun onSetOnlyTrusted(onlyTrusted: Boolean) {
        viewModelScope.launch { preferences.setOnlyTrusted(onlyTrusted) }
    }

    fun onSetIncludeAiTranslated(includeAiTranslated: Boolean) {
        viewModelScope.launch { preferences.setIncludeAiTranslated(includeAiTranslated) }
    }
}
