package com.nexio.tv.ui.screens.addon

import android.graphics.Bitmap
import com.nexio.tv.domain.model.Addon
import com.nexio.tv.domain.model.AddonParserPreset

data class AddonManagerUiState(
    val isLoading: Boolean = false,
    val isInstalling: Boolean = false,
    val installUrl: String = "",
    val installParserPreset: AddonParserPreset = AddonParserPreset.GENERIC,
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
    val proposedCatalogOrderKeys: List<String> = emptyList(),
    val proposedDisabledCatalogKeys: List<String> = emptyList(),
    val addedUrls: List<String>,
    val removedUrls: List<String>,
    val catalogsReordered: Boolean = false,
    val disabledCatalogNames: List<String> = emptyList(),
    val enabledCatalogNames: List<String> = emptyList(),
    val addedNames: Map<String, String> = emptyMap(),
    val removedNames: Map<String, String> = emptyMap(),
    val isApplying: Boolean = false
)
