@file:OptIn(ExperimentalTvMaterial3Api::class)

package com.nexio.tv.ui.screens.settings

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.basicMarquee
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.ClosedCaption
import androidx.compose.material.icons.filled.FormatBold
import androidx.compose.material.icons.filled.FormatSize
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material.icons.filled.Storage
import androidx.compose.material.icons.filled.Subtitles
import androidx.compose.material.icons.filled.VerticalAlignBottom
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.res.stringResource
import com.nexio.tv.R
import androidx.compose.ui.text.input.KeyboardType
import android.view.KeyEvent
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.unit.dp
import androidx.activity.compose.BackHandler
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.tv.material3.Border
import androidx.tv.material3.Button
import androidx.tv.material3.ButtonDefaults
import androidx.tv.material3.Card
import androidx.tv.material3.CardDefaults
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.Icon
import androidx.tv.material3.IconButton
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Switch
import androidx.tv.material3.SwitchDefaults
import androidx.tv.material3.Text
import com.nexio.tv.data.local.AVAILABLE_SUBTITLE_LANGUAGES
import com.nexio.tv.data.local.AudioLanguageOption
import com.nexio.tv.data.local.IecPackerChannelLayout
import com.nexio.tv.data.local.LibassRenderType
import com.nexio.tv.data.local.PlayerPreference
import com.nexio.tv.data.local.PlayerSettings
import com.nexio.tv.data.local.StreamAutoPlayMode
import com.nexio.tv.data.local.StreamAutoPlaySource
import com.nexio.tv.ui.components.NexioDialog
import com.nexio.tv.ui.theme.NexioColors
import kotlinx.coroutines.launch
import androidx.compose.material.icons.filled.PlayCircle
import androidx.compose.material.icons.filled.PauseCircle
import androidx.compose.material.icons.filled.Timer
import androidx.compose.material.icons.filled.VolumeUp
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.filled.Image

@Composable
fun PlaybackSettingsScreen(
    viewModel: PlaybackSettingsViewModel = hiltViewModel(),
    onBackPress: () -> Unit = {}
) {
    BackHandler { onBackPress() }

    SettingsStandaloneScaffold(
        title = stringResource(R.string.playback_title),
        subtitle = stringResource(R.string.playback_subtitle)
    ) {
        PlaybackSettingsContent(viewModel = viewModel)
    }
}

@Composable
fun PlaybackSettingsContent(
    viewModel: PlaybackSettingsViewModel = hiltViewModel(),
    initialFocusRequester: FocusRequester? = null
) {
    val playerSettings by viewModel.playerSettings.collectAsStateWithLifecycle(initialValue = PlayerSettings())
    val streamDiagnosticsEnabled by viewModel.streamDiagnosticsEnabled.collectAsStateWithLifecycle(initialValue = false)
    val startupPerfTelemetryEnabled by viewModel.startupPerfTelemetryEnabled.collectAsStateWithLifecycle(initialValue = false)
    val installedAddonNames by viewModel.installedAddonNames.collectAsStateWithLifecycle(initialValue = emptyList())
    val coroutineScope = rememberCoroutineScope()
    var memoryUsageTrigger by remember { mutableStateOf(0) }
    var showMemoryUsage by remember { mutableStateOf(false) }

    // Dialog states
    var showLanguageDialog by remember { mutableStateOf(false) }
    var showSecondaryLanguageDialog by remember { mutableStateOf(false) }
    var showSubtitleStartupModeDialog by remember { mutableStateOf(false) }
    var showTextColorDialog by remember { mutableStateOf(false) }
    var showBackgroundColorDialog by remember { mutableStateOf(false) }
    var showOutlineColorDialog by remember { mutableStateOf(false) }
    var showAudioLanguageDialog by remember { mutableStateOf(false) }
    var showSecondaryAudioLanguageDialog by remember { mutableStateOf(false) }
    var showDecoderPriorityDialog by remember { mutableStateOf(false) }
    var showIecPackerChannelLayoutDialog by remember { mutableStateOf(false) }
    var showStreamAutoPlayModeDialog by remember { mutableStateOf(false) }
    var showStreamAutoPlaySourceDialog by remember { mutableStateOf(false) }
    var showStreamAutoPlayAddonSelectionDialog by remember { mutableStateOf(false) }
    var showStreamRegexDialog by remember { mutableStateOf(false) }
    var showNextEpisodeThresholdModeDialog by remember { mutableStateOf(false) }
    var showReuseLastLinkCacheDialog by remember { mutableStateOf(false) }
    var showPlayerPreferenceDialog by remember { mutableStateOf(false) }

    fun dismissAllDialogs() {
        showLanguageDialog = false
        showSecondaryLanguageDialog = false
        showSubtitleStartupModeDialog = false
        showTextColorDialog = false
        showBackgroundColorDialog = false
        showOutlineColorDialog = false
        showAudioLanguageDialog = false
        showSecondaryAudioLanguageDialog = false
        showDecoderPriorityDialog = false
        showIecPackerChannelLayoutDialog = false
        showStreamAutoPlayModeDialog = false
        showStreamAutoPlaySourceDialog = false
        showStreamAutoPlayAddonSelectionDialog = false
        showStreamRegexDialog = false
        showNextEpisodeThresholdModeDialog = false
        showReuseLastLinkCacheDialog = false
        showPlayerPreferenceDialog = false
    }

    fun openDialog(setter: () -> Unit) {
        dismissAllDialogs()
        setter()
    }

    LaunchedEffect(memoryUsageTrigger) {
        if (memoryUsageTrigger == 0) return@LaunchedEffect
        showMemoryUsage = true
        kotlinx.coroutines.delay(2200)
        showMemoryUsage = false
    }

    Column(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        SettingsDetailHeader(
            title = stringResource(R.string.playback_title),
            subtitle = stringResource(R.string.playback_subtitle)
        )

        SettingsGroupCard(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
        ) {
            PlaybackSettingsSections(
                initialFocusRequester = initialFocusRequester,
                playerSettings = playerSettings,
                onShowPlayerPreferenceDialog = { openDialog { showPlayerPreferenceDialog = true } },
                onShowAudioLanguageDialog = { openDialog { showAudioLanguageDialog = true } },
                onShowSecondaryAudioLanguageDialog = { openDialog { showSecondaryAudioLanguageDialog = true } },
                onShowDecoderPriorityDialog = { openDialog { showDecoderPriorityDialog = true } },
                onShowIecPackerChannelLayoutDialog = { openDialog { showIecPackerChannelLayoutDialog = true } },
                onShowLanguageDialog = { openDialog { showLanguageDialog = true } },
                onShowSecondaryLanguageDialog = { openDialog { showSecondaryLanguageDialog = true } },
                onShowSubtitleStartupModeDialog = { openDialog { showSubtitleStartupModeDialog = true } },
                onShowTextColorDialog = { openDialog { showTextColorDialog = true } },
                onShowBackgroundColorDialog = { openDialog { showBackgroundColorDialog = true } },
                onShowOutlineColorDialog = { openDialog { showOutlineColorDialog = true } },
                onShowStreamAutoPlayModeDialog = { openDialog { showStreamAutoPlayModeDialog = true } },
                onShowStreamAutoPlaySourceDialog = { openDialog { showStreamAutoPlaySourceDialog = true } },
                onShowStreamAutoPlayAddonSelectionDialog = { openDialog { showStreamAutoPlayAddonSelectionDialog = true } },
                onShowStreamRegexDialog = { openDialog { showStreamRegexDialog = true } },
                onShowNextEpisodeThresholdModeDialog = { openDialog { showNextEpisodeThresholdModeDialog = true } },
                onShowReuseLastLinkCacheDialog = { openDialog { showReuseLastLinkCacheDialog = true } },
                onSetStreamAutoPlayNextEpisodeEnabled = { enabled ->
                    coroutineScope.launch { viewModel.setStreamAutoPlayNextEpisodeEnabled(enabled) }
                },
                onSetStreamAutoPlayPreferBingeGroupForNextEpisode = { enabled ->
                    coroutineScope.launch {
                        viewModel.setStreamAutoPlayPreferBingeGroupForNextEpisode(enabled)
                    }
                },
                onSetNextEpisodeThresholdPercent = { percent ->
                    coroutineScope.launch { viewModel.setNextEpisodeThresholdPercent(percent) }
                },
                onSetNextEpisodeThresholdMinutesBeforeEnd = { minutes ->
                    coroutineScope.launch { viewModel.setNextEpisodeThresholdMinutesBeforeEnd(minutes) }
                },
                onSetReuseLastLinkEnabled = { enabled -> coroutineScope.launch { viewModel.setStreamReuseLastLinkEnabled(enabled) } },
                onSetUniformStreamFormattingEnabled = { enabled ->
                    coroutineScope.launch { viewModel.setUniformStreamFormattingEnabled(enabled) }
                },
                onSetGroupStreamsAcrossAddonsEnabled = { enabled ->
                    coroutineScope.launch { viewModel.setGroupStreamsAcrossAddonsEnabled(enabled) }
                },
                onSetDeduplicateGroupedStreamsEnabled = { enabled ->
                    coroutineScope.launch { viewModel.setDeduplicateGroupedStreamsEnabled(enabled) }
                },
                onSetFilterWebDolbyVisionStreamsEnabled = { enabled ->
                    coroutineScope.launch { viewModel.setFilterWebDolbyVisionStreamsEnabled(enabled) }
                },
                onSetFilterEpisodeMismatchStreamsEnabled = { enabled ->
                    coroutineScope.launch { viewModel.setFilterEpisodeMismatchStreamsEnabled(enabled) }
                },
                onSetFilterMovieYearMismatchStreamsEnabled = { enabled ->
                    coroutineScope.launch { viewModel.setFilterMovieYearMismatchStreamsEnabled(enabled) }
                },
                onSetLoadingOverlayEnabled = { enabled -> coroutineScope.launch { viewModel.setLoadingOverlayEnabled(enabled) } },
                onSetPauseOverlayEnabled = { enabled -> coroutineScope.launch { viewModel.setPauseOverlayEnabled(enabled) } },
                onSetOsdClockEnabled = { enabled -> coroutineScope.launch { viewModel.setOsdClockEnabled(enabled) } },
                onSetSkipIntroEnabled = { enabled -> coroutineScope.launch { viewModel.setSkipIntroEnabled(enabled) } },
                onSetFrameRateMatchingMode = { mode -> coroutineScope.launch { viewModel.setFrameRateMatchingMode(mode) } },
                onSetResolutionMatchingEnabled = { enabled ->
                    coroutineScope.launch { viewModel.setResolutionMatchingEnabled(enabled) }
                },
                onSetSkipSilence = { enabled -> coroutineScope.launch { viewModel.setSkipSilence(enabled) } },
                onSetTunnelingEnabled = { enabled -> coroutineScope.launch { viewModel.setTunnelingEnabled(enabled) } },
                onSetExperimentalDv7ToDv81Enabled = {
                    enabled -> coroutineScope.launch { viewModel.setExperimentalDv7ToDv81Enabled(enabled) }
                },
                onSetExperimentalDtsIecPassthroughEnabled = {
                    enabled -> coroutineScope.launch {
                        viewModel.setExperimentalDtsIecPassthroughEnabled(enabled)
                    }
                },
                onSetIecPackerAc3PassthroughEnabled = { enabled ->
                    coroutineScope.launch { viewModel.setIecPackerAc3PassthroughEnabled(enabled) }
                },
                onSetIecPackerEac3PassthroughEnabled = { enabled ->
                    coroutineScope.launch { viewModel.setIecPackerEac3PassthroughEnabled(enabled) }
                },
                onSetIecPackerDtsPassthroughEnabled = { enabled ->
                    coroutineScope.launch { viewModel.setIecPackerDtsPassthroughEnabled(enabled) }
                },
                onSetIecPackerTruehdPassthroughEnabled = { enabled ->
                    coroutineScope.launch { viewModel.setIecPackerTruehdPassthroughEnabled(enabled) }
                },
                onSetIecPackerDtshdPassthroughEnabled = { enabled ->
                    coroutineScope.launch { viewModel.setIecPackerDtshdPassthroughEnabled(enabled) }
                },
                onSetIecPackerDtshdCoreFallbackEnabled = { enabled ->
                    coroutineScope.launch { viewModel.setIecPackerDtshdCoreFallbackEnabled(enabled) }
                },
                onSetFireOsIecSuperviseAudioDelayEnabled = {
                    enabled -> coroutineScope.launch {
                        viewModel.setFireOsIecSuperviseAudioDelayEnabled(enabled)
                    }
                },
                streamDiagnosticsEnabled = streamDiagnosticsEnabled,
                startupPerfTelemetryEnabled = startupPerfTelemetryEnabled,
                onSetFireOsIecVerboseLoggingEnabled = { enabled ->
                    coroutineScope.launch { viewModel.setFireOsIecVerboseLoggingEnabled(enabled) }
                },
                onSetEnableBufferLogs = { enabled ->
                    coroutineScope.launch { viewModel.setEnableBufferLogs(enabled) }
                },
                onSetStreamDiagnosticsEnabled = { enabled ->
                    coroutineScope.launch { viewModel.setStreamDiagnosticsEnabled(enabled) }
                },
                onSetStartupPerfTelemetryEnabled = { enabled ->
                    coroutineScope.launch { viewModel.setStartupPerfTelemetryEnabled(enabled) }
                },
                onSetIecPackerMaxPcmChannelLayout = { layout ->
                    coroutineScope.launch { viewModel.setIecPackerMaxPcmChannelLayout(layout) }
                },
                onSetExperimentalDv7ToDv81PreserveMappingEnabled = {
                    enabled ->
                    coroutineScope.launch {
                        viewModel.setExperimentalDv7ToDv81PreserveMappingEnabled(enabled)
                    }
                },
                onSetSubtitleSize = { newSize -> coroutineScope.launch { viewModel.setSubtitleSize(newSize) } },
                onSetSubtitleVerticalOffset = { newOffset -> coroutineScope.launch { viewModel.setSubtitleVerticalOffset(newOffset) } },
                onSetSubtitleBold = { bold -> coroutineScope.launch { viewModel.setSubtitleBold(bold) } },
                onSetSubtitleOutlineEnabled = { enabled -> coroutineScope.launch { viewModel.setSubtitleOutlineEnabled(enabled) } },
                onSetUseLibass = { enabled -> coroutineScope.launch { viewModel.setUseLibass(enabled) } },
                onSetLibassRenderType = { renderType -> coroutineScope.launch { viewModel.setLibassRenderType(renderType) } },
                onSetUseParallelConnections = { enabled ->
                    coroutineScope.launch { viewModel.setUseParallelConnections(enabled) }
                    memoryUsageTrigger++
                },
                onSetParallelConnectionCount = { count ->
                    coroutineScope.launch { viewModel.setParallelConnectionCount(count) }
                    memoryUsageTrigger++
                },
                onSetParallelChunkSizeMb = { mb ->
                    coroutineScope.launch { viewModel.setParallelChunkSizeMb(mb) }
                    memoryUsageTrigger++
                },
                onSetVodCacheSizeMode = { mode ->
                    coroutineScope.launch { viewModel.setVodCacheSizeMode(mode) }
                },
                onSetVodCacheSizeMb = { mb ->
                    coroutineScope.launch { viewModel.setVodCacheSizeMb(mb) }
                },
                onResetNetworkSettingsToDefaults = {
                    coroutineScope.launch { viewModel.resetNetworkSettingsToDefaults() }
                    memoryUsageTrigger++
                }
            )
        }

        AnimatedVisibility(
            visible = showMemoryUsage,
            enter = fadeIn(),
            exit = fadeOut()
        ) {
            val totalUsageMb = MemoryBudget.totalUsageMb(
                MemoryBudget.defaultBufferSizeMb,
                playerSettings.parallelConnectionCount,
                playerSettings.parallelChunkSizeMb,
                playerSettings.useParallelConnections
            )
            val usageRatio = totalUsageMb.toFloat() / MemoryBudget.budgetMb.coerceAtLeast(1)
            val usageColor = when {
                usageRatio > 0.9f -> Color(0xFFF44336)
                usageRatio > 0.7f -> Color(0xFFFF9800)
                else -> Color(0xFF4CAF50)
            }
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(
                        color = NexioColors.BackgroundCard,
                        shape = RoundedCornerShape(10.dp)
                    )
                    .border(1.dp, usageColor.copy(alpha = 0.35f), RoundedCornerShape(10.dp))
                    .padding(horizontal = 14.dp, vertical = 10.dp)
            ) {
                Text(
                    text = stringResource(
                        R.string.playback_memory_usage_estimate,
                        totalUsageMb,
                        MemoryBudget.budgetMb
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = usageColor
                )
            }
        }
    }

    PlaybackSettingsDialogsHost(
        playerSettings = playerSettings,
        installedAddonNames = installedAddonNames,
        showPlayerPreferenceDialog = showPlayerPreferenceDialog,
        showLanguageDialog = showLanguageDialog,
        showSecondaryLanguageDialog = showSecondaryLanguageDialog,
        showSubtitleStartupModeDialog = showSubtitleStartupModeDialog,
        showTextColorDialog = showTextColorDialog,
        showBackgroundColorDialog = showBackgroundColorDialog,
        showOutlineColorDialog = showOutlineColorDialog,
        showAudioLanguageDialog = showAudioLanguageDialog,
        showSecondaryAudioLanguageDialog = showSecondaryAudioLanguageDialog,
        showDecoderPriorityDialog = showDecoderPriorityDialog,
        showIecPackerChannelLayoutDialog = showIecPackerChannelLayoutDialog,
        showStreamAutoPlayModeDialog = showStreamAutoPlayModeDialog,
        showStreamAutoPlaySourceDialog = showStreamAutoPlaySourceDialog,
        showStreamAutoPlayAddonSelectionDialog = showStreamAutoPlayAddonSelectionDialog,
        showStreamRegexDialog = showStreamRegexDialog,
        showNextEpisodeThresholdModeDialog = showNextEpisodeThresholdModeDialog,
        showReuseLastLinkCacheDialog = showReuseLastLinkCacheDialog,
        onSetPlayerPreference = { preference ->
            coroutineScope.launch { viewModel.setPlayerPreference(preference) }
        },
        onDismissPlayerPreferenceDialog = ::dismissAllDialogs,
        onSetSubtitlePreferredLanguage = { language ->
            coroutineScope.launch { viewModel.setSubtitlePreferredLanguage(language ?: "none") }
        },
        onSetSubtitleSecondaryLanguage = { language ->
            coroutineScope.launch { viewModel.setSubtitleSecondaryLanguage(language) }
        },
        onSetAddonSubtitleStartupMode = { mode ->
            coroutineScope.launch { viewModel.setAddonSubtitleStartupMode(mode) }
        },
        onSetSubtitleTextColor = { color ->
            coroutineScope.launch { viewModel.setSubtitleTextColor(color.toArgb()) }
        },
        onSetSubtitleBackgroundColor = { color ->
            coroutineScope.launch { viewModel.setSubtitleBackgroundColor(color.toArgb()) }
        },
        onSetSubtitleOutlineColor = { color ->
            coroutineScope.launch { viewModel.setSubtitleOutlineColor(color.toArgb()) }
        },
        onSetPreferredAudioLanguage = { language ->
            coroutineScope.launch { viewModel.setPreferredAudioLanguage(language) }
        },
        onSetSecondaryPreferredAudioLanguage = { language ->
            coroutineScope.launch { viewModel.setSecondaryPreferredAudioLanguage(language) }
        },
        onSetDecoderPriority = { priority ->
            coroutineScope.launch { viewModel.setDecoderPriority(priority) }
        },
        onSetIecPackerMaxPcmChannelLayout = { layout: IecPackerChannelLayout ->
            coroutineScope.launch { viewModel.setIecPackerMaxPcmChannelLayout(layout) }
        },
        onSetStreamAutoPlayMode = { mode ->
            coroutineScope.launch { viewModel.setStreamAutoPlayMode(mode) }
        },
        onSetStreamAutoPlaySource = { source ->
            coroutineScope.launch { viewModel.setStreamAutoPlaySource(source) }
        },
        onSetNextEpisodeThresholdMode = { mode ->
            coroutineScope.launch { viewModel.setNextEpisodeThresholdMode(mode) }
        },
        onSetStreamAutoPlayRegex = { regex ->
            coroutineScope.launch { viewModel.setStreamAutoPlayRegex(regex) }
        },
        onSetStreamAutoPlaySelectedAddons = { selected ->
            coroutineScope.launch { viewModel.setStreamAutoPlaySelectedAddons(selected) }
        },
        onSetReuseLastLinkCacheHours = { hours ->
            coroutineScope.launch { viewModel.setStreamReuseLastLinkCacheHours(hours) }
        },
        onDismissLanguageDialog = ::dismissAllDialogs,
        onDismissSecondaryLanguageDialog = ::dismissAllDialogs,
        onDismissSubtitleStartupModeDialog = ::dismissAllDialogs,
        onDismissTextColorDialog = ::dismissAllDialogs,
        onDismissBackgroundColorDialog = ::dismissAllDialogs,
        onDismissOutlineColorDialog = ::dismissAllDialogs,
        onDismissAudioLanguageDialog = ::dismissAllDialogs,
        onDismissSecondaryAudioLanguageDialog = ::dismissAllDialogs,
        onDismissDecoderPriorityDialog = ::dismissAllDialogs,
        onDismissIecPackerChannelLayoutDialog = ::dismissAllDialogs,
        onDismissStreamAutoPlayModeDialog = ::dismissAllDialogs,
        onDismissStreamAutoPlaySourceDialog = ::dismissAllDialogs,
        onDismissStreamRegexDialog = ::dismissAllDialogs,
        onDismissStreamAutoPlayAddonSelectionDialog = ::dismissAllDialogs,
        onDismissNextEpisodeThresholdModeDialog = ::dismissAllDialogs,
        onDismissReuseLastLinkCacheDialog = ::dismissAllDialogs
    )
}

@Composable
internal fun ToggleSettingsItem(
    icon: ImageVector,
    title: String,
    subtitle: String,
    isChecked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    onFocused: () -> Unit = {},
    enabled: Boolean = true
) {
    var isFocused by remember { mutableStateOf(false) }
    val contentAlpha = if (enabled) 1f else 0.4f

    Card(
        onClick = { if (enabled) onCheckedChange(!isChecked) },
        modifier = Modifier
            .fillMaxWidth()
            .onFocusChanged { state ->
                val nowFocused = state.isFocused
                if (isFocused != nowFocused) {
                    isFocused = nowFocused
                    if (nowFocused) onFocused()
                }
            },
        colors = CardDefaults.colors(
            containerColor = NexioColors.Background,
            focusedContainerColor = NexioColors.Background
        ),
        border = CardDefaults.border(
            focusedBorder = Border(
                border = BorderStroke(2.dp, if (enabled) NexioColors.FocusRing else NexioColors.FocusRing.copy(alpha = 0.3f)),
                shape = RoundedCornerShape(SettingsPillRadius)
            )
        ),
        shape = CardDefaults.shape(shape = RoundedCornerShape(SettingsPillRadius)),
        scale = CardDefaults.scale(focusedScale = 1f, pressedScale = 1f)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = (if (isFocused && enabled) NexioColors.Primary else NexioColors.TextSecondary).copy(alpha = contentAlpha),
                modifier = Modifier.size(22.dp)
            )

            Spacer(modifier = Modifier.width(16.dp))

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.bodyLarge,
                    color = NexioColors.TextPrimary.copy(alpha = contentAlpha),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = NexioColors.TextSecondary.copy(alpha = contentAlpha),
                    modifier = if (isFocused) {
                        Modifier.basicMarquee()
                    } else {
                        Modifier
                    },
                    maxLines = 1,
                    overflow = TextOverflow.Clip
                )
            }

            Spacer(modifier = Modifier.width(16.dp))

            Switch(
                checked = isChecked,
                onCheckedChange = null, // Handled by Card onClick
                colors = SwitchDefaults.colors(
                    checkedThumbColor = NexioColors.Secondary.copy(alpha = contentAlpha),
                    checkedTrackColor = NexioColors.Secondary.copy(alpha = 0.35f * contentAlpha),
                    uncheckedThumbColor = NexioColors.TextSecondary.copy(alpha = contentAlpha),
                    uncheckedTrackColor = NexioColors.Border
                )
            )
        }
    }
}

@Composable
internal fun RenderTypeSettingsItem(
    title: String,
    subtitle: String,
    isSelected: Boolean,
    onClick: () -> Unit,
    onFocused: () -> Unit = {},
    enabled: Boolean = true
) {
    var isFocused by remember { mutableStateOf(false) }
    val contentAlpha = if (enabled) 1f else 0.4f
    
    Card(
        onClick = { if (enabled) onClick() },
        modifier = Modifier
            .fillMaxWidth()
            .onFocusChanged { state ->
                val nowFocused = state.isFocused
                if (isFocused != nowFocused) {
                    isFocused = nowFocused
                    if (nowFocused) onFocused()
                }
            },
        colors = CardDefaults.colors(
            containerColor = if (isSelected) {
                NexioColors.Primary.copy(alpha = 0.15f * contentAlpha)
            } else {
                NexioColors.BackgroundCard
            },
            focusedContainerColor = if (isSelected) {
                NexioColors.Primary.copy(alpha = 0.15f * contentAlpha)
            } else {
                NexioColors.BackgroundCard
            }
        ),
        border = CardDefaults.border(
            focusedBorder = Border(
                border = BorderStroke(2.dp, NexioColors.FocusRing.copy(alpha = contentAlpha)),
                shape = RoundedCornerShape(SettingsSecondaryCardRadius)
            ),
            border = if (isSelected) Border(
                border = BorderStroke(2.dp, NexioColors.Primary.copy(alpha = contentAlpha)),
                shape = RoundedCornerShape(SettingsSecondaryCardRadius)
            ) else Border.None
        ),
        shape = CardDefaults.shape(shape = RoundedCornerShape(SettingsSecondaryCardRadius)),
        scale = CardDefaults.scale(focusedScale = 1f, pressedScale = 1f)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.bodyLarge,
                    color = (if (isSelected) NexioColors.Primary else NexioColors.TextPrimary).copy(alpha = contentAlpha),
                    maxLines = 2,
                    overflow = TextOverflow.Clip
                )
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = NexioColors.TextSecondary.copy(alpha = contentAlpha)
                )
            }
            
            if (isSelected) {
                Spacer(modifier = Modifier.width(16.dp))
                Icon(
                    imageVector = Icons.Default.Check,
                    contentDescription = stringResource(R.string.cd_selected),
                    tint = NexioColors.Primary.copy(alpha = contentAlpha),
                    modifier = Modifier.size(24.dp)
                )
            }
        }
    }
}

@Composable
internal fun NavigationSettingsItem(
    icon: ImageVector,
    title: String,
    subtitle: String,
    onClick: () -> Unit,
    onFocused: () -> Unit = {},
    enabled: Boolean = true
) {
    var isFocused by remember { mutableStateOf(false) }
    val contentAlpha = if (enabled) 1f else 0.4f

    Card(
        onClick = { if (enabled) onClick() },
        modifier = Modifier
            .fillMaxWidth()
            .onFocusChanged { state ->
                val nowFocused = state.isFocused
                if (isFocused != nowFocused) {
                    isFocused = nowFocused
                    if (nowFocused) onFocused()
                }
            },
        colors = CardDefaults.colors(
            containerColor = NexioColors.Background,
            focusedContainerColor = NexioColors.Background
        ),
        border = CardDefaults.border(
            focusedBorder = Border(
                border = BorderStroke(2.dp, if (enabled) NexioColors.FocusRing else NexioColors.FocusRing.copy(alpha = 0.3f)),
                shape = RoundedCornerShape(SettingsPillRadius)
            )
        ),
        shape = CardDefaults.shape(shape = RoundedCornerShape(SettingsPillRadius)),
        scale = CardDefaults.scale(focusedScale = 1f, pressedScale = 1f)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = (if (isFocused && enabled) NexioColors.Primary else NexioColors.TextSecondary).copy(alpha = contentAlpha),
                modifier = Modifier.size(22.dp)
            )

            Spacer(modifier = Modifier.width(16.dp))

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.bodyLarge,
                    color = NexioColors.TextPrimary.copy(alpha = contentAlpha),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = NexioColors.TextSecondary.copy(alpha = contentAlpha),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }

            Icon(
                imageVector = Icons.Default.ChevronRight,
                contentDescription = null,
                tint = NexioColors.TextSecondary.copy(alpha = contentAlpha),
                modifier = Modifier.size(20.dp)
            )
        }
    }
}

@Composable
internal fun SliderSettingsItem(
    icon: ImageVector,
    title: String,
    value: Int,
    valueText: String,
    minValue: Int,
    maxValue: Int,
    step: Int,
    onValueChange: (Int) -> Unit,
    subtitle: String? = null,
    onFocused: () -> Unit = {},
    enabled: Boolean = true
) {
    var isFocused by remember { mutableStateOf(false) }
    val contentAlpha = if (enabled) 1f else 0.4f

    Card(
        onClick = { },
        modifier = Modifier
            .fillMaxWidth()
            .onFocusChanged { state ->
                val nowFocused = state.isFocused
                if (isFocused != nowFocused) {
                    isFocused = nowFocused
                    if (nowFocused) onFocused()
                }
            }
            .onKeyEvent { event ->
                if (!enabled) return@onKeyEvent false
                if (event.nativeKeyEvent.action != KeyEvent.ACTION_DOWN) return@onKeyEvent false
                when (event.nativeKeyEvent.keyCode) {
                    KeyEvent.KEYCODE_DPAD_LEFT -> {
                        val newValue = (value - step).coerceAtLeast(minValue)
                        if (newValue != value) onValueChange(newValue)
                        true
                    }
                    KeyEvent.KEYCODE_DPAD_RIGHT -> {
                        val newValue = (value + step).coerceAtMost(maxValue)
                        if (newValue != value) onValueChange(newValue)
                        true
                    }
                    else -> false
                }
            },
        colors = CardDefaults.colors(
            containerColor = NexioColors.Background,
            focusedContainerColor = NexioColors.Background
        ),
        border = CardDefaults.border(
            focusedBorder = Border(
                border = BorderStroke(2.dp, if (enabled) NexioColors.FocusRing else NexioColors.FocusRing.copy(alpha = 0.3f)),
                shape = RoundedCornerShape(SettingsSecondaryCardRadius)
            )
        ),
        shape = CardDefaults.shape(shape = RoundedCornerShape(SettingsSecondaryCardRadius)),
        scale = CardDefaults.scale(focusedScale = 1f, pressedScale = 1f)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 12.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth()
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = (if (isFocused && enabled) NexioColors.Primary else NexioColors.TextSecondary).copy(alpha = contentAlpha),
                    modifier = Modifier.size(22.dp)
                )

                Spacer(modifier = Modifier.width(16.dp))

                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = title,
                        style = MaterialTheme.typography.bodyLarge,
                        color = NexioColors.TextPrimary.copy(alpha = contentAlpha),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    if (subtitle != null) {
                        Spacer(modifier = Modifier.height(2.dp))
                        Text(
                            text = subtitle,
                            style = MaterialTheme.typography.bodySmall,
                            color = NexioColors.TextSecondary.copy(alpha = contentAlpha),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }

                Spacer(modifier = Modifier.width(12.dp))

                Text(
                    text = valueText,
                    style = MaterialTheme.typography.bodyLarge,
                    color = NexioColors.Primary.copy(alpha = contentAlpha)
                )
            }

            Spacer(modifier = Modifier.height(10.dp))

            // Custom slider controls for TV - use Row with focusable buttons
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                // Decrease button
                var decreaseFocused by remember { mutableStateOf(false) }
                Card(
                    onClick = {
                        if (enabled) {
                            val newValue = (value - step).coerceAtLeast(minValue)
                            onValueChange(newValue)
                        }
                    },
                    modifier = Modifier
                        .onFocusChanged { state ->
                            val nowFocused = state.isFocused
                            if (decreaseFocused != nowFocused) {
                                decreaseFocused = nowFocused
                                if (nowFocused) onFocused()
                            }
                        },
                    colors = CardDefaults.colors(
                        containerColor = NexioColors.Background,
                        focusedContainerColor = NexioColors.Background
                    ),
                    border = CardDefaults.border(
                        focusedBorder = Border(
                            border = BorderStroke(2.dp, NexioColors.FocusRing),
                            shape = CircleShape
                        )
                    ),
                    shape = CardDefaults.shape(shape = CircleShape),
                    scale = CardDefaults.scale(focusedScale = 1.1f)
                ) {
                    Box(
                        contentAlignment = Alignment.Center,
                        modifier = Modifier.size(38.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Remove,
                            contentDescription = stringResource(R.string.cd_decrease),
                            tint = (if (decreaseFocused) NexioColors.OnPrimary else NexioColors.TextPrimary).copy(alpha = contentAlpha),
                            modifier = Modifier.size(20.dp)
                        )
                    }
                }

                // Progress bar
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .height(8.dp)
                        .clip(RoundedCornerShape(4.dp))
                        .background(NexioColors.BackgroundElevated)
                ) {
                    val progress = ((value - minValue).toFloat() / (maxValue - minValue).toFloat()).coerceIn(0f, 1f)
                    Box(
                        modifier = Modifier
                            .fillMaxWidth(progress)
                            .height(8.dp)
                            .clip(RoundedCornerShape(4.dp))
                            .background(NexioColors.Primary.copy(alpha = contentAlpha))
                    )
                }

                // Increase button
                var increaseFocused by remember { mutableStateOf(false) }
                Card(
                    onClick = {
                        if (enabled) {
                            val newValue = (value + step).coerceAtMost(maxValue)
                            onValueChange(newValue)
                        }
                    },
                    modifier = Modifier
                        .onFocusChanged { state ->
                            val nowFocused = state.isFocused
                            if (increaseFocused != nowFocused) {
                                increaseFocused = nowFocused
                                if (nowFocused) onFocused()
                            }
                        },
                    colors = CardDefaults.colors(
                        containerColor = NexioColors.Background,
                        focusedContainerColor = NexioColors.Background
                    ),
                    border = CardDefaults.border(
                        focusedBorder = Border(
                            border = BorderStroke(2.dp, NexioColors.FocusRing),
                            shape = CircleShape
                        )
                    ),
                    shape = CardDefaults.shape(shape = CircleShape),
                    scale = CardDefaults.scale(focusedScale = 1.1f)
                ) {
                    Box(
                        contentAlignment = Alignment.Center,
                        modifier = Modifier.size(38.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Add,
                            contentDescription = stringResource(R.string.cd_increase),
                            tint = (if (increaseFocused) NexioColors.OnPrimary else NexioColors.TextPrimary).copy(alpha = contentAlpha),
                            modifier = Modifier.size(20.dp)
                        )
                    }
                }
            }
        }
    }
}

@Composable
internal fun ColorSettingsItem(
    icon: ImageVector,
    title: String,
    currentColor: Color,
    showTransparent: Boolean = false,
    onClick: () -> Unit,
    onFocused: () -> Unit = {},
    enabled: Boolean = true
) {
    var isFocused by remember { mutableStateOf(false) }
    val contentAlpha = if (enabled) 1f else 0.4f

    Card(
        onClick = { if (enabled) onClick() },
        modifier = Modifier
            .fillMaxWidth()
            .onFocusChanged { state ->
                val nowFocused = state.isFocused
                if (isFocused != nowFocused) {
                    isFocused = nowFocused
                    if (nowFocused) onFocused()
                }
            },
        colors = CardDefaults.colors(
            containerColor = NexioColors.Background,
            focusedContainerColor = NexioColors.Background
        ),
        border = CardDefaults.border(
            focusedBorder = Border(
                border = BorderStroke(2.dp, if (enabled) NexioColors.FocusRing else NexioColors.FocusRing.copy(alpha = 0.3f)),
                shape = RoundedCornerShape(SettingsPillRadius)
            )
        ),
        shape = CardDefaults.shape(shape = RoundedCornerShape(SettingsPillRadius)),
        scale = CardDefaults.scale(focusedScale = 1f, pressedScale = 1f)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = (if (isFocused && enabled) NexioColors.Primary else NexioColors.TextSecondary).copy(alpha = contentAlpha),
                modifier = Modifier.size(22.dp)
            )

            Spacer(modifier = Modifier.width(16.dp))

            Text(
                text = title,
                style = MaterialTheme.typography.bodyLarge,
                color = NexioColors.TextPrimary.copy(alpha = contentAlpha),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f)
            )

            // Color preview
            if (showTransparent || currentColor.alpha == 0f) {
                // Transparent indicator (checkered pattern simulation)
                Box(
                    modifier = Modifier
                        .size(28.dp)
                        .clip(CircleShape)
                        .background(Color.Gray)
                        .border(2.dp, NexioColors.Border, CircleShape)
                ) {
                    // Diagonal line to indicate transparency
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(
                                brush = androidx.compose.ui.graphics.Brush.linearGradient(
                                    colors = listOf(Color.White, Color.Gray, Color.White)
                                )
                            )
                    )
                }
            } else {
                Box(
                    modifier = Modifier
                        .size(28.dp)
                        .clip(CircleShape)
                        .background(currentColor)
                        .border(2.dp, NexioColors.Border, CircleShape)
                )
            }
        }
    }
}

@Composable
internal fun LanguageSelectionDialog(
    title: String,
    selectedLanguage: String?,
    showNoneOption: Boolean,
    extraOptions: List<Pair<String, String>> = emptyList(),
    onLanguageSelected: (String?) -> Unit,
    onDismiss: () -> Unit
) {
    val focusRequester = remember { FocusRequester() }

    LaunchedEffect(Unit) {
        focusRequester.requestFocus()
    }

    NexioDialog(
        onDismiss = onDismiss,
        title = title,
        width = 400.dp,
        suppressFirstKeyUp = false
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(320.dp)
        ) {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                verticalArrangement = Arrangement.spacedBy(8.dp),
                contentPadding = PaddingValues(vertical = 4.dp)
            ) {
                if (showNoneOption) {
                    item(key = "language_none_option") {
                        LanguageOptionItem(
                            name = stringResource(R.string.action_none),
                            code = null,
                            isSelected = selectedLanguage == null,
                            onClick = { onLanguageSelected(null) },
                            modifier = Modifier.focusRequester(focusRequester)
                        )
                    }
                }

                items(
                    items = extraOptions,
                    key = { (code, _) -> "language_extra_$code" }
                ) { (code, name) ->
                    LanguageOptionItem(
                        name = name,
                        code = code,
                        isSelected = selectedLanguage == code,
                        onClick = { onLanguageSelected(code) },
                        modifier = if (!showNoneOption && extraOptions.firstOrNull()?.first == code) {
                            Modifier.focusRequester(focusRequester)
                        } else {
                            Modifier
                        }
                    )
                }

                items(
                    count = AVAILABLE_SUBTITLE_LANGUAGES.size,
                    key = { index -> AVAILABLE_SUBTITLE_LANGUAGES[index].code }
                ) { index ->
                    val language = AVAILABLE_SUBTITLE_LANGUAGES[index]
                    LanguageOptionItem(
                        name = language.name,
                        code = language.code,
                        isSelected = selectedLanguage == language.code,
                        onClick = { onLanguageSelected(language.code) },
                        modifier = if (!showNoneOption && index == 0) {
                            Modifier.focusRequester(focusRequester)
                        } else {
                            Modifier
                        }
                    )
                }
            }
        }
    }
}

@Composable
private fun LanguageOptionItem(
    name: String,
    code: String?,
    isSelected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    var isFocused by remember { mutableStateOf(false) }
    
    Card(
        onClick = onClick,
        modifier = Modifier
            .fillMaxWidth()
            .then(modifier)
            .onFocusChanged { isFocused = it.isFocused },
        colors = CardDefaults.colors(
            containerColor = if (isSelected) NexioColors.FocusBackground else NexioColors.BackgroundCard,
            focusedContainerColor = NexioColors.FocusBackground
        ),
        shape = CardDefaults.shape(shape = RoundedCornerShape(10.dp)),
        scale = CardDefaults.scale(focusedScale = 1f)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = name,
                style = MaterialTheme.typography.bodyLarge,
                color = if (isSelected) NexioColors.Primary else NexioColors.TextPrimary,
                modifier = Modifier.weight(1f)
            )
            
            if (code != null) {
                Text(
                    text = code.uppercase(),
                    style = MaterialTheme.typography.bodySmall,
                    color = NexioColors.TextSecondary
                )
            }
            
            if (isSelected) {
                Spacer(modifier = Modifier.width(12.dp))
                Icon(
                    imageVector = Icons.Default.Check,
                    contentDescription = stringResource(R.string.cd_selected),
                    tint = NexioColors.Primary,
                    modifier = Modifier.size(20.dp)
                )
            }
        }
    }
}

@Composable
internal fun ColorSelectionDialog(
    title: String,
    colors: List<Color>,
    selectedColor: Color,
    showTransparentOption: Boolean = false,
    onColorSelected: (Color) -> Unit,
    onDismiss: () -> Unit
) {
    val focusRequester = remember { FocusRequester() }

    NexioDialog(
        onDismiss = onDismiss,
        title = title,
        suppressFirstKeyUp = false
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = 240.dp)
        ) {
            // Color grid using LazyRow for proper TV focus
            LazyRow(
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                modifier = Modifier.focusRequester(focusRequester)
            ) {
                items(
                    count = colors.size,
                    key = { index -> colors[index].toArgb() }
                ) { index ->
                    val color = colors[index]
                    ColorOption(
                        color = color,
                        isSelected = color.toArgb() == selectedColor.toArgb(),
                        isTransparent = color.alpha == 0f,
                        onClick = { onColorSelected(color) }
                    )
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            // Cancel button
            Card(
                onClick = onDismiss,
                colors = CardDefaults.colors(
                    containerColor = NexioColors.BackgroundElevated,
                    focusedContainerColor = NexioColors.Primary
                ),
                border = CardDefaults.border(
                    focusedBorder = Border(
                        border = BorderStroke(2.dp, NexioColors.FocusRing),
                        shape = RoundedCornerShape(8.dp)
                    )
                ),
                shape = CardDefaults.shape(shape = RoundedCornerShape(8.dp)),
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(
                    text = stringResource(R.string.action_cancel),
                    style = MaterialTheme.typography.bodyLarge,
                    color = NexioColors.TextPrimary,
                    modifier = Modifier
                        .padding(12.dp)
                        .fillMaxWidth(),
                    textAlign = TextAlign.Center
                )
            }
        }
    }

    LaunchedEffect(Unit) {
        focusRequester.requestFocus()
    }
}

@Composable
private fun ColorOption(
    color: Color,
    isSelected: Boolean,
    isTransparent: Boolean = false,
    onClick: () -> Unit
) {
    var isFocused by remember { mutableStateOf(false) }
    
    Card(
        onClick = onClick,
        modifier = Modifier
            .size(48.dp)
            .onFocusChanged { isFocused = it.isFocused },
        colors = CardDefaults.colors(
            containerColor = Color.Transparent
        ),
        border = CardDefaults.border(
            focusedBorder = Border(
                border = BorderStroke(3.dp, NexioColors.FocusRing),
                shape = CircleShape
            ),
            border = if (isSelected) Border(
                border = BorderStroke(3.dp, NexioColors.Primary),
                shape = CircleShape
            ) else Border.None
        ),
        shape = CardDefaults.shape(shape = CircleShape),
        scale = CardDefaults.scale(focusedScale = 1.15f)
    ) {
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier.fillMaxSize()
        ) {
            if (isTransparent) {
                // Checkered pattern for transparent
                Box(
                    modifier = Modifier
                        .size(40.dp)
                        .clip(CircleShape)
                        .background(Color.Gray)
                        .border(1.dp, NexioColors.Border, CircleShape)
                )
            } else {
                Box(
                    modifier = Modifier
                        .size(40.dp)
                        .clip(CircleShape)
                        .background(color)
                        .border(1.dp, NexioColors.Border, CircleShape)
                )
            }
            
            if (isSelected) {
                Icon(
                    imageVector = Icons.Default.Check,
                    contentDescription = stringResource(R.string.cd_selected),
                    tint = if (color == Color.White || color == Color.Yellow) Color.Black else Color.White,
                    modifier = Modifier.size(20.dp)
                )
            }
        }
    }
}
