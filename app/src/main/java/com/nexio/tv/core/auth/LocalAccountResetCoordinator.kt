package com.nexio.tv.core.auth

import com.nexio.tv.core.profile.ProfileManager
import com.nexio.tv.core.sync.AccountSettingsSyncService
import com.nexio.tv.data.local.AddonPreferences
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class LocalAccountResetCoordinator @Inject constructor(
    private val profileManager: ProfileManager,
    private val addonPreferences: AddonPreferences,
    private val accountSettingsSyncService: AccountSettingsSyncService
) {
    suspend fun resetToSignedOutStockState() {
        profileManager.resetToSingleDefaultProfile()
        addonPreferences.resetToDefaultAddons()
        accountSettingsSyncService.resetLocalAccountConfigToDefaults()
    }
}
