package com.nexio.tv.ui.screens.account

import android.graphics.Bitmap
import com.nexio.tv.data.remote.supabase.SupabaseLinkedDevice
import com.nexio.tv.domain.model.AuthState

data class AccountConnectedStats(
    val addons: Int = 0,
    val linkedDevices: Int = 0
)

data class AccountUiState(
    val authState: AuthState = AuthState.Loading,
    val isLoading: Boolean = false,
    val isStatsLoading: Boolean = false,
    val error: String? = null,
    val generatedSyncCode: String? = null,
    val syncClaimSuccess: Boolean = false,
    val linkedDevices: List<SupabaseLinkedDevice> = emptyList(),
    val effectiveOwnerId: String? = null,
    val connectedStats: AccountConnectedStats? = null,
    val qrLoginCode: String? = null,
    val qrLoginUrl: String? = null,
    val qrLoginNonce: String? = null,
    val qrLoginBitmap: Bitmap? = null,
    val qrLoginStatus: String? = null,
    val qrLoginExpiresAtMillis: Long? = null,
    val qrLoginPollIntervalSeconds: Int = 3
)
