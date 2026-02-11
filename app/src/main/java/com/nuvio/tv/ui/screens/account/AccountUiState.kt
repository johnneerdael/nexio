package com.nuvio.tv.ui.screens.account

import com.nuvio.tv.data.remote.supabase.SupabaseLinkedDevice
import com.nuvio.tv.domain.model.AuthState

data class AccountUiState(
    val authState: AuthState = AuthState.Loading,
    val isLoading: Boolean = false,
    val error: String? = null,
    val generatedSyncCode: String? = null,
    val syncClaimSuccess: Boolean = false,
    val linkedDevices: List<SupabaseLinkedDevice> = emptyList()
)
