package com.nexio.tv.core.sync

import android.util.Log
import com.nexio.tv.data.local.AddonPreferences
import com.nexio.tv.data.local.PlayerSettingsDataStore
import com.nexio.tv.data.repository.AddonRepositoryImpl
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

private const val TAG = "BuiltinSubtitleAddon"

@Singleton
class BuiltinSubtitleAddonSyncService @Inject constructor(
    private val playerSettingsDataStore: PlayerSettingsDataStore,
    private val addonPreferences: AddonPreferences,
    private val addonRepository: AddonRepositoryImpl
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    init {
        scope.launch {
            combine(
                playerSettingsDataStore.playerSettings
                    .mapSubtitleLanguageSelection()
                    .distinctUntilChanged(),
                addonPreferences.installedAddons
                    .mapInstalledAddonFingerprint()
                    .distinctUntilChanged()
            ) { subtitleSelection, _ ->
                subtitleSelection
            }.collect { selection ->
                runCatching {
                    addonRepository.syncBuiltinSubtitleAddon(
                        buildBuiltinSubtitleAddonInstallUrl(
                            preferredLanguage = selection.preferredLanguage,
                            secondaryPreferredLanguage = selection.secondaryPreferredLanguage
                        )
                    )
                }.onFailure { error ->
                    Log.w(TAG, "Failed to sync builtin subtitle addon", error)
                }
            }
        }
    }

    private data class SubtitleLanguageSelection(
        val preferredLanguage: String?,
        val secondaryPreferredLanguage: String?
    )

    private fun Flow<com.nexio.tv.data.local.PlayerSettings>.mapSubtitleLanguageSelection() =
        map { settings ->
            SubtitleLanguageSelection(
                preferredLanguage = settings.subtitleStyle.preferredLanguage,
                secondaryPreferredLanguage = settings.subtitleStyle.secondaryPreferredLanguage
            )
        }

    private fun Flow<List<AddonPreferences.AddonInstallConfig>>.mapInstalledAddonFingerprint() =
        map { addons ->
            addons.map { addon -> "${addon.url}|${addon.parserPreset.name}" }
        }
}
