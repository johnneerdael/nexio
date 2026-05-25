package com.nexio.tv.core.auth

import com.nexio.tv.core.profile.ProfileManager
import com.nexio.tv.core.sync.AccountSettingsSyncService
import com.nexio.tv.data.local.AddonPreferences
import com.nexio.tv.data.local.SyncWatermarkDataStore
import javax.inject.Inject
import javax.inject.Provider
import javax.inject.Singleton

@Singleton
class LocalAccountResetCoordinator @Inject constructor(
    private val profileManager: ProfileManager,
    private val addonPreferences: AddonPreferences,
    private val accountSettingsSyncService: Provider<AccountSettingsSyncService>,
    private val syncWatermarkDataStore: SyncWatermarkDataStore
) {
    suspend fun resetToSignedOutStockState() {
        profileManager.resetToSingleDefaultProfile()
        addonPreferences.resetToDefaultAddons()
        accountSettingsSyncService.get().resetLocalAccountConfigToDefaults()
        syncWatermarkDataStore.clearAll()
    }
}
