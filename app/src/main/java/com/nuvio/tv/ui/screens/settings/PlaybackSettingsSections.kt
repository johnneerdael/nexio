@file:OptIn(ExperimentalTvMaterial3Api::class)

package com.nuvio.tv.ui.screens.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.PauseCircle
import androidx.compose.material.icons.filled.Speed
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.unit.dp
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.nuvio.tv.data.local.PlayerSettings
import com.nuvio.tv.data.local.TrailerSettings
import com.nuvio.tv.ui.theme.NuvioColors

@Composable
internal fun PlaybackSettingsSections(
    playerSettings: PlayerSettings,
    trailerSettings: TrailerSettings,
    maxBufferSizeMb: Int,
    showAdvancedExperimental: Boolean,
    onToggleAdvancedExperimental: () -> Unit,
    onShowAudioLanguageDialog: () -> Unit,
    onShowDecoderPriorityDialog: () -> Unit,
    onShowLanguageDialog: () -> Unit,
    onShowSecondaryLanguageDialog: () -> Unit,
    onShowTextColorDialog: () -> Unit,
    onShowBackgroundColorDialog: () -> Unit,
    onShowOutlineColorDialog: () -> Unit,
    onShowStreamAutoPlayModeDialog: () -> Unit,
    onShowStreamAutoPlaySourceDialog: () -> Unit,
    onShowStreamAutoPlayAddonSelectionDialog: () -> Unit,
    onShowStreamAutoPlayPluginSelectionDialog: () -> Unit,
    onShowStreamRegexDialog: () -> Unit,
    onSetLoadingOverlayEnabled: (Boolean) -> Unit,
    onSetPauseOverlayEnabled: (Boolean) -> Unit,
    onSetSkipIntroEnabled: (Boolean) -> Unit,
    onSetFrameRateMatching: (Boolean) -> Unit,
    onSetTrailerEnabled: (Boolean) -> Unit,
    onSetTrailerDelaySeconds: (Int) -> Unit,
    onSetSkipSilence: (Boolean) -> Unit,
    onSetTunnelingEnabled: (Boolean) -> Unit,
    onSetMapDV7ToHevc: (Boolean) -> Unit,
    onSetSubtitleSize: (Int) -> Unit,
    onSetSubtitleVerticalOffset: (Int) -> Unit,
    onSetSubtitleBold: (Boolean) -> Unit,
    onSetSubtitleOutlineEnabled: (Boolean) -> Unit,
    onSetUseLibass: (Boolean) -> Unit,
    onSetLibassRenderType: (com.nuvio.tv.data.local.LibassRenderType) -> Unit,
    onSetBufferMinBufferMs: (Int) -> Unit,
    onSetBufferMaxBufferMs: (Int) -> Unit,
    onSetBufferForPlaybackMs: (Int) -> Unit,
    onSetBufferForPlaybackAfterRebufferMs: (Int) -> Unit,
    onSetBufferTargetSizeMb: (Int) -> Unit,
    onSetUseParallelConnections: (Boolean) -> Unit,
    onSetBufferBackBufferDurationMs: (Int) -> Unit,
    onSetBufferRetainBackBufferFromKeyframe: (Boolean) -> Unit
) {
    Column(modifier = Modifier.fillMaxSize()) {
        Text(
            text = "Playback",
            style = MaterialTheme.typography.headlineMedium,
            color = NuvioColors.Secondary
        )

        Spacer(modifier = Modifier.height(8.dp))

        Text(
            text = "Configure video playback and subtitle options",
            style = MaterialTheme.typography.bodyMedium,
            color = NuvioColors.TextSecondary
        )

        Spacer(modifier = Modifier.height(24.dp))

        LazyColumn(
            contentPadding = PaddingValues(top = 4.dp, bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            item {
                ToggleSettingsItem(
                    icon = Icons.Default.Image,
                    title = "Loading Overlay",
                    subtitle = "Show a loading screen until the first video frame appears",
                    isChecked = playerSettings.loadingOverlayEnabled,
                    onCheckedChange = onSetLoadingOverlayEnabled
                )
            }

            item {
                ToggleSettingsItem(
                    icon = Icons.Default.PauseCircle,
                    title = "Pause Overlay",
                    subtitle = "Show a details overlay after 5 seconds of no input while paused",
                    isChecked = playerSettings.pauseOverlayEnabled,
                    onCheckedChange = onSetPauseOverlayEnabled
                )
            }

            item {
                ToggleSettingsItem(
                    icon = Icons.Default.History,
                    title = "Skip Intro",
                    subtitle = "Use introdb.app to detect intros and recaps",
                    isChecked = playerSettings.skipIntroEnabled,
                    onCheckedChange = onSetSkipIntroEnabled
                )
            }

            item {
                ToggleSettingsItem(
                    icon = Icons.Default.Speed,
                    title = "Auto Frame Rate",
                    subtitle = "Switch display refresh rate to match video frame rate for judder-free playback",
                    isChecked = playerSettings.frameRateMatching,
                    onCheckedChange = onSetFrameRateMatching
                )
            }

            item {
                Spacer(modifier = Modifier.height(16.dp))
                Text(
                    text = "Stream Selection",
                    style = MaterialTheme.typography.titleMedium,
                    color = NuvioColors.TextSecondary,
                    modifier = Modifier.padding(vertical = 8.dp)
                )
            }

            autoPlaySettingsItems(
                playerSettings = playerSettings,
                onShowModeDialog = onShowStreamAutoPlayModeDialog,
                onShowSourceDialog = onShowStreamAutoPlaySourceDialog,
                onShowAddonSelectionDialog = onShowStreamAutoPlayAddonSelectionDialog,
                onShowPluginSelectionDialog = onShowStreamAutoPlayPluginSelectionDialog,
                onShowRegexDialog = onShowStreamRegexDialog
            )

            trailerAndAudioSettingsItems(
                playerSettings = playerSettings,
                trailerSettings = trailerSettings,
                onShowAudioLanguageDialog = onShowAudioLanguageDialog,
                onShowDecoderPriorityDialog = onShowDecoderPriorityDialog,
                onSetTrailerEnabled = onSetTrailerEnabled,
                onSetTrailerDelaySeconds = onSetTrailerDelaySeconds,
                onSetSkipSilence = onSetSkipSilence,
                onSetTunnelingEnabled = onSetTunnelingEnabled,
                onSetMapDV7ToHevc = onSetMapDV7ToHevc
            )

            subtitleSettingsItems(
                playerSettings = playerSettings,
                onShowLanguageDialog = onShowLanguageDialog,
                onShowSecondaryLanguageDialog = onShowSecondaryLanguageDialog,
                onShowTextColorDialog = onShowTextColorDialog,
                onShowBackgroundColorDialog = onShowBackgroundColorDialog,
                onShowOutlineColorDialog = onShowOutlineColorDialog,
                onSetSubtitleSize = onSetSubtitleSize,
                onSetSubtitleVerticalOffset = onSetSubtitleVerticalOffset,
                onSetSubtitleBold = onSetSubtitleBold,
                onSetSubtitleOutlineEnabled = onSetSubtitleOutlineEnabled,
                onSetUseLibass = onSetUseLibass,
                onSetLibassRenderType = onSetLibassRenderType
            )

            bufferSettingsItems(
                showAdvancedExperimental = showAdvancedExperimental,
                playerSettings = playerSettings,
                maxBufferSizeMb = maxBufferSizeMb,
                onToggleAdvancedExperimental = onToggleAdvancedExperimental,
                onSetBufferMinBufferMs = onSetBufferMinBufferMs,
                onSetBufferMaxBufferMs = onSetBufferMaxBufferMs,
                onSetBufferForPlaybackMs = onSetBufferForPlaybackMs,
                onSetBufferForPlaybackAfterRebufferMs = onSetBufferForPlaybackAfterRebufferMs,
                onSetBufferTargetSizeMb = onSetBufferTargetSizeMb,
                onSetUseParallelConnections = onSetUseParallelConnections,
                onSetBufferBackBufferDurationMs = onSetBufferBackBufferDurationMs,
                onSetBufferRetainBackBufferFromKeyframe = onSetBufferRetainBackBufferFromKeyframe
            )
        }
    }
}

@Composable
internal fun PlaybackSettingsDialogsHost(
    playerSettings: PlayerSettings,
    installedAddonNames: List<String>,
    enabledPluginNames: List<String>,
    showLanguageDialog: Boolean,
    showSecondaryLanguageDialog: Boolean,
    showTextColorDialog: Boolean,
    showBackgroundColorDialog: Boolean,
    showOutlineColorDialog: Boolean,
    showAudioLanguageDialog: Boolean,
    showDecoderPriorityDialog: Boolean,
    showStreamAutoPlayModeDialog: Boolean,
    showStreamAutoPlaySourceDialog: Boolean,
    showStreamAutoPlayAddonSelectionDialog: Boolean,
    showStreamAutoPlayPluginSelectionDialog: Boolean,
    showStreamRegexDialog: Boolean,
    onSetSubtitlePreferredLanguage: (String?) -> Unit,
    onSetSubtitleSecondaryLanguage: (String?) -> Unit,
    onSetSubtitleTextColor: (Color) -> Unit,
    onSetSubtitleBackgroundColor: (Color) -> Unit,
    onSetSubtitleOutlineColor: (Color) -> Unit,
    onSetPreferredAudioLanguage: (String) -> Unit,
    onSetDecoderPriority: (Int) -> Unit,
    onSetStreamAutoPlayMode: (com.nuvio.tv.data.local.StreamAutoPlayMode) -> Unit,
    onSetStreamAutoPlaySource: (com.nuvio.tv.data.local.StreamAutoPlaySource) -> Unit,
    onSetStreamAutoPlayRegex: (String) -> Unit,
    onSetStreamAutoPlaySelectedAddons: (Set<String>) -> Unit,
    onSetStreamAutoPlaySelectedPlugins: (Set<String>) -> Unit,
    onDismissLanguageDialog: () -> Unit,
    onDismissSecondaryLanguageDialog: () -> Unit,
    onDismissTextColorDialog: () -> Unit,
    onDismissBackgroundColorDialog: () -> Unit,
    onDismissOutlineColorDialog: () -> Unit,
    onDismissAudioLanguageDialog: () -> Unit,
    onDismissDecoderPriorityDialog: () -> Unit,
    onDismissStreamAutoPlayModeDialog: () -> Unit,
    onDismissStreamAutoPlaySourceDialog: () -> Unit,
    onDismissStreamRegexDialog: () -> Unit,
    onDismissStreamAutoPlayAddonSelectionDialog: () -> Unit,
    onDismissStreamAutoPlayPluginSelectionDialog: () -> Unit
) {
    SubtitleSettingsDialogs(
        showLanguageDialog = showLanguageDialog,
        showSecondaryLanguageDialog = showSecondaryLanguageDialog,
        showTextColorDialog = showTextColorDialog,
        showBackgroundColorDialog = showBackgroundColorDialog,
        showOutlineColorDialog = showOutlineColorDialog,
        playerSettings = playerSettings,
        onSetPreferredLanguage = onSetSubtitlePreferredLanguage,
        onSetSecondaryLanguage = onSetSubtitleSecondaryLanguage,
        onSetTextColor = onSetSubtitleTextColor,
        onSetBackgroundColor = onSetSubtitleBackgroundColor,
        onSetOutlineColor = onSetSubtitleOutlineColor,
        onDismissLanguageDialog = onDismissLanguageDialog,
        onDismissSecondaryLanguageDialog = onDismissSecondaryLanguageDialog,
        onDismissTextColorDialog = onDismissTextColorDialog,
        onDismissBackgroundColorDialog = onDismissBackgroundColorDialog,
        onDismissOutlineColorDialog = onDismissOutlineColorDialog
    )

    AudioSettingsDialogs(
        showAudioLanguageDialog = showAudioLanguageDialog,
        showDecoderPriorityDialog = showDecoderPriorityDialog,
        selectedLanguage = playerSettings.preferredAudioLanguage,
        selectedPriority = playerSettings.decoderPriority,
        onSetPreferredAudioLanguage = onSetPreferredAudioLanguage,
        onSetDecoderPriority = onSetDecoderPriority,
        onDismissAudioLanguageDialog = onDismissAudioLanguageDialog,
        onDismissDecoderPriorityDialog = onDismissDecoderPriorityDialog
    )

    AutoPlaySettingsDialogs(
        showModeDialog = showStreamAutoPlayModeDialog,
        showSourceDialog = showStreamAutoPlaySourceDialog,
        showRegexDialog = showStreamRegexDialog,
        showAddonSelectionDialog = showStreamAutoPlayAddonSelectionDialog,
        showPluginSelectionDialog = showStreamAutoPlayPluginSelectionDialog,
        playerSettings = playerSettings,
        installedAddonNames = installedAddonNames,
        enabledPluginNames = enabledPluginNames,
        onSetMode = onSetStreamAutoPlayMode,
        onSetSource = onSetStreamAutoPlaySource,
        onSetRegex = onSetStreamAutoPlayRegex,
        onSetSelectedAddons = onSetStreamAutoPlaySelectedAddons,
        onSetSelectedPlugins = onSetStreamAutoPlaySelectedPlugins,
        onDismissModeDialog = onDismissStreamAutoPlayModeDialog,
        onDismissSourceDialog = onDismissStreamAutoPlaySourceDialog,
        onDismissRegexDialog = onDismissStreamRegexDialog,
        onDismissAddonSelectionDialog = onDismissStreamAutoPlayAddonSelectionDialog,
        onDismissPluginSelectionDialog = onDismissStreamAutoPlayPluginSelectionDialog
    )
}
