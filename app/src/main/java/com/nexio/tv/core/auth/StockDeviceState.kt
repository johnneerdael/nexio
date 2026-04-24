package com.nexio.tv.core.auth

import com.nexio.tv.data.local.AddonPreferences
import com.nexio.tv.data.remote.supabase.AccountConfigSyncPayload
import com.nexio.tv.domain.model.UserProfile

fun stockDefaultProfile(): UserProfile =
    UserProfile(id = 1, name = "Default", avatarColorHex = "#1E88E5")

fun stockAddonInstallConfigs(): List<AddonPreferences.AddonInstallConfig> =
    listOf(
        AddonPreferences.AddonInstallConfig(url = "https://v3-cinemeta.strem.io"),
        AddonPreferences.AddonInstallConfig(url = "https://opensubtitles-v3.strem.io")
    )

fun stockAccountConfigSyncPayload(): AccountConfigSyncPayload =
    AccountConfigSyncPayload()
