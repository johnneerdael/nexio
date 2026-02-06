package com.nuvio.tv.ui.screens.addon

import android.graphics.Bitmap
import com.nuvio.tv.domain.model.Addon

data class AddonManagerUiState(
    val isLoading: Boolean = false,
    val isInstalling: Boolean = false,
    val installUrl: String = "",
    val installedAddons: List<Addon> = emptyList(),
    val error: String? = null,
    // QR mode
    val isQrModeActive: Boolean = false,
    val qrCodeBitmap: Bitmap? = null,
    val serverUrl: String? = null,
    // Pending change from phone
    val pendingChange: PendingChangeInfo? = null
)

data class PendingChangeInfo(
    val changeId: String,
    val proposedUrls: List<String>,
    val addedUrls: List<String>,
    val removedUrls: List<String>,
    val isApplying: Boolean = false
)
