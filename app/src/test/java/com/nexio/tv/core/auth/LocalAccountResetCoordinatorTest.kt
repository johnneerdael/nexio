package com.nexio.tv.core.auth

import com.nexio.tv.core.profile.ProfileManager
import com.nexio.tv.core.sync.AccountSettingsSyncService
import com.nexio.tv.data.local.AddonPreferences
import io.mockk.coVerify
import io.mockk.coVerifyOrder
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.Test

class LocalAccountResetCoordinatorTest {
    @Test
    fun `resetToSignedOutStockState collapses profiles clears addons and resets account config`() = runTest {
        val profileManager = mockk<ProfileManager>(relaxed = true)
        val addonPreferences = mockk<AddonPreferences>(relaxed = true)
        val accountSettingsSyncService = mockk<AccountSettingsSyncService>(relaxed = true)
        val coordinator = LocalAccountResetCoordinator(
            profileManager = profileManager,
            addonPreferences = addonPreferences,
            accountSettingsSyncService = javax.inject.Provider { accountSettingsSyncService }
        )

        coordinator.resetToSignedOutStockState()

        coVerify { profileManager.resetToSingleDefaultProfile() }
        coVerify { addonPreferences.resetToDefaultAddons() }
        coVerify { accountSettingsSyncService.resetLocalAccountConfigToDefaults() }
    }

    @Test
    fun `resetToSignedOutStockState resets profiles addons then account config in order`() = runTest {
        val profileManager = mockk<ProfileManager>(relaxed = true)
        val addonPreferences = mockk<AddonPreferences>(relaxed = true)
        val accountSettingsSyncService = mockk<AccountSettingsSyncService>(relaxed = true)
        val coordinator = LocalAccountResetCoordinator(
            profileManager = profileManager,
            addonPreferences = addonPreferences,
            accountSettingsSyncService = javax.inject.Provider { accountSettingsSyncService }
        )

        coordinator.resetToSignedOutStockState()

        coVerifyOrder {
            profileManager.resetToSingleDefaultProfile()
            addonPreferences.resetToDefaultAddons()
            accountSettingsSyncService.resetLocalAccountConfigToDefaults()
        }
    }
}
