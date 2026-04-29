package com.nexio.tv.ui.screens.organization

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.nexio.tv.core.metadata.router.MetadataRouterFacade
import com.nexio.tv.domain.model.MetaCompanyKind
import com.nexio.tv.domain.model.OrganizationDiscoverType
import dagger.hilt.android.lifecycle.HiltViewModel
import java.net.URLDecoder
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

@HiltViewModel
class OrganizationDetailViewModel @Inject constructor(
    private val metadataRouterFacade: MetadataRouterFacade,
    savedStateHandle: SavedStateHandle
) : ViewModel() {

    val entityId: Int = savedStateHandle.get<String>("entityId")?.toIntOrNull() ?: 0
    val entityName: String = URLDecoder.decode(
        savedStateHandle.get<String>("entityName") ?: "",
        "UTF-8"
    )
    private val kind: MetaCompanyKind = savedStateHandle.get<String>("kind")
        ?.let { runCatching { MetaCompanyKind.valueOf(it) }.getOrNull() }
        ?: MetaCompanyKind.COMPANY
    private val discoverType: OrganizationDiscoverType = savedStateHandle.get<String>("discoverType")
        ?.let { runCatching { OrganizationDiscoverType.valueOf(it) }.getOrNull() }
        ?: OrganizationDiscoverType.TV_COMPANY

    private val _uiState = MutableStateFlow<OrganizationDetailUiState>(OrganizationDetailUiState.Loading)
    val uiState: StateFlow<OrganizationDetailUiState> = _uiState.asStateFlow()

    init {
        loadOrganizationDetail()
    }

    fun retry() {
        _uiState.value = OrganizationDetailUiState.Loading
        loadOrganizationDetail()
    }

    private fun loadOrganizationDetail() {
        viewModelScope.launch {
            try {
                // F2-TM-03: route through facade so canonical trace events fire.
                val detail = metadataRouterFacade.fetchOrganizationDetail(
                    entityId = entityId,
                    kind = kind,
                    discoverType = discoverType
                )
                if (detail != null) {
                    _uiState.value = OrganizationDetailUiState.Success(detail)
                } else {
                    _uiState.value = OrganizationDetailUiState.Error("Could not load details for $entityName")
                }
            } catch (e: Exception) {
                _uiState.value = OrganizationDetailUiState.Error(e.message ?: "Unknown error")
            }
        }
    }
}
