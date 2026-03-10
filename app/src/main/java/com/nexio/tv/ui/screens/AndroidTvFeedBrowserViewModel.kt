package com.nexio.tv.ui.screens

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.nexio.tv.core.recommendations.AndroidTvFeedCatalogService
import com.nexio.tv.domain.model.MetaPreview
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class AndroidTvFeedBrowserUiState(
    val isLoading: Boolean = true,
    val title: String = "",
    val subtitle: String = "",
    val addonBaseUrl: String? = null,
    val items: List<MetaPreview> = emptyList(),
    val error: String? = null
)

@HiltViewModel
class AndroidTvFeedBrowserViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val feedCatalogService: AndroidTvFeedCatalogService
) : ViewModel() {
    private val feedKey: String = savedStateHandle["feedKey"] ?: ""

    private val _uiState = MutableStateFlow(AndroidTvFeedBrowserUiState())
    val uiState: StateFlow<AndroidTvFeedBrowserUiState> = _uiState.asStateFlow()

    init {
        refresh()
    }

    fun refresh() {
        if (feedKey.isBlank()) {
            _uiState.value = AndroidTvFeedBrowserUiState(
                isLoading = false,
                error = "Missing Android TV feed key."
            )
            return
        }

        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, error = null) }
            val row = runCatching {
                feedCatalogService.resolveFeed(feedKey)
            }.getOrElse { error ->
                _uiState.value = AndroidTvFeedBrowserUiState(
                    isLoading = false,
                    error = error.message ?: "Unable to load this Android TV feed."
                )
                return@launch
            }

            if (row == null) {
                _uiState.value = AndroidTvFeedBrowserUiState(
                    isLoading = false,
                    error = "This Android TV feed is no longer available."
                )
                return@launch
            }

            _uiState.value = AndroidTvFeedBrowserUiState(
                isLoading = false,
                title = row.option.title,
                subtitle = row.option.subtitle,
                addonBaseUrl = row.addonBaseUrl,
                items = row.items
            )
        }
    }
}
