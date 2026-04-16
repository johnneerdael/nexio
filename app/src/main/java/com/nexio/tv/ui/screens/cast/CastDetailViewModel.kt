package com.nexio.tv.ui.screens.cast

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.nexio.tv.core.tmdb.TmdbMetadataService
import com.nexio.tv.core.tvdb.TvdbPersonService
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class CastDetailViewModel @Inject constructor(
    private val tmdbMetadataService: TmdbMetadataService,
    private val tvdbPersonService: TvdbPersonService,
    savedStateHandle: SavedStateHandle
) : ViewModel() {

    val personId: Int = savedStateHandle.get<String>("personId")?.toIntOrNull() ?: 0
    val personName: String = java.net.URLDecoder.decode(
        savedStateHandle.get<String>("personName") ?: "", "UTF-8"
    )
    private val preferCrew: Boolean = savedStateHandle.get<Boolean>("preferCrew") ?: false
    private val provider: String = savedStateHandle.get<String>("provider") ?: "tmdb"

    private val _uiState = MutableStateFlow<CastDetailUiState>(CastDetailUiState.Loading)
    val uiState: StateFlow<CastDetailUiState> = _uiState.asStateFlow()

    init {
        loadPersonDetail()
    }

    fun retry() {
        _uiState.value = CastDetailUiState.Loading
        loadPersonDetail()
    }

    private fun loadPersonDetail() {
        viewModelScope.launch {
            try {
                val detail = if (provider.equals("tvdb", ignoreCase = true)) {
                    tvdbPersonService.fetchPersonDetail(personId)
                } else {
                    tmdbMetadataService.fetchPersonDetail(
                        personId = personId,
                        preferCrewCredits = preferCrew
                    )
                }
                if (detail != null) {
                    _uiState.value = CastDetailUiState.Success(detail)
                } else {
                    _uiState.value = CastDetailUiState.Error("Could not load details for $personName")
                }
            } catch (e: Exception) {
                _uiState.value = CastDetailUiState.Error(e.message ?: "Unknown error")
            }
        }
    }
}
