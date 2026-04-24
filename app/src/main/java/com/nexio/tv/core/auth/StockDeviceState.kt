package com.nexio.tv.core.auth

import com.nexio.tv.data.local.AddonPreferences
import com.nexio.tv.data.remote.supabase.AccountConfigSyncPayload
import com.nexio.tv.data.remote.supabase.CatalogSyncSettings
import com.nexio.tv.data.remote.supabase.DebridSyncSettings
import com.nexio.tv.data.remote.supabase.EasyDebridSyncSettings
import com.nexio.tv.data.remote.supabase.FormatterSyncSettings
import com.nexio.tv.data.remote.supabase.IntegrationSettings
import com.nexio.tv.data.remote.supabase.PlaybackConfigSyncSettings
import com.nexio.tv.data.remote.supabase.PremiumizeSyncSettings
import com.nexio.tv.data.remote.supabase.RealDebridSyncSettings
import com.nexio.tv.data.remote.supabase.StreamSelectionConfigSyncSettings
import com.nexio.tv.data.remote.supabase.TorBoxSyncSettings
import com.nexio.tv.data.remote.supabase.TvdbSyncSettings
import com.nexio.tv.domain.model.UserProfile

fun stockDefaultProfile(): UserProfile =
    UserProfile(id = 1, name = "Default", avatarColorHex = "#1E88E5")

fun stockAddonInstallConfigs(): List<AddonPreferences.AddonInstallConfig> =
    listOf(
        AddonPreferences.AddonInstallConfig(url = "https://v3-cinemeta.strem.io"),
        AddonPreferences.AddonInstallConfig(url = "https://opensubtitles-v3.strem.io")
    )

fun stockAccountConfigSyncPayload(): AccountConfigSyncPayload =
    AccountConfigSyncPayload(
        schemaVersion = 7,
        integrations = IntegrationSettings(
            debrid = DebridSyncSettings(
                premiumize = PremiumizeSyncSettings(configured = false, customerId = null),
                realDebrid = RealDebridSyncSettings(
                    connected = false,
                    username = "",
                    pending = false,
                    deviceCode = "",
                    userCode = "",
                    verificationUrl = "",
                    expiresAt = null
                ),
                torBox = TorBoxSyncSettings(configured = false, email = "", plan = ""),
                easyDebrid = EasyDebridSyncSettings(configured = false, userId = "", paidUntil = "")
            ),
            tvdb = TvdbSyncSettings(
                enabled = true,
                configured = true,
                validationStatus = "VALID",
                lastFailure = ""
            )
        ),
        catalogs = CatalogSyncSettings(),
        playback = PlaybackConfigSyncSettings(
            streamSelection = StreamSelectionConfigSyncSettings(trackingProvider = "TRAKT")
        ),
        formatter = FormatterSyncSettings(
            enabled = true,
            selectedTemplateId = "universal",
            customTemplate = null
        )
    )
