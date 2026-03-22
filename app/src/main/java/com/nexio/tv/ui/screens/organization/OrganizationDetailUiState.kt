package com.nexio.tv.ui.screens.organization

import com.nexio.tv.domain.model.TmdbOrganizationDetail

sealed interface OrganizationDetailUiState {
    data object Loading : OrganizationDetailUiState
    data class Success(val detail: TmdbOrganizationDetail) : OrganizationDetailUiState
    data class Error(val message: String) : OrganizationDetailUiState
}
