package com.nexio.tv.ui.screens.cast

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.nexio.tv.core.metadata.router.MetadataDepth
import com.nexio.tv.core.metadata.router.MetadataRequest
import com.nexio.tv.core.metadata.router.MetadataRouterFacade
import com.nexio.tv.core.metadata.router.MetadataSourceContext
import com.nexio.tv.core.tvdb.TvdbPersonService
import com.nexio.tv.domain.model.ContentType
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class CastDetailViewModel @Inject constructor(
    private val metadataRouterFacade: MetadataRouterFacade,
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
                    // TODO(F-05-04 follow-up): route TVDB person fetch through MetadataRouterFacade once a TVDB-side
                    //   facade method exists (TmdbOrganizationPersonAdapter only covers TMDB).
                    tvdbPersonService.fetchPersonDetail(personId)
                } else {
                    metadataRouterFacade.fetchPersonDetail(
                        metadataRequest = MetadataRequest(
                            contentId = "tmdb:person:$personId",
                            contentType = ContentType.MOVIE,  // sentinel; person-by-id has no canonical content type
                            sourceContext = MetadataSourceContext(),
                            language = "eng",
                            depth = MetadataDepth.DETAIL_SECONDARY
                        ),
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
