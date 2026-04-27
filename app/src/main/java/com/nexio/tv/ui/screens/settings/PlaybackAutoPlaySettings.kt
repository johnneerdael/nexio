@file:OptIn(ExperimentalTvMaterial3Api::class)

package com.nexio.tv.ui.screens.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.FilterAlt
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.res.stringResource
import com.nexio.tv.R
import androidx.tv.material3.Card
import androidx.tv.material3.CardDefaults
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.Icon
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.nexio.tv.data.local.NextEpisodeThresholdMode
import com.nexio.tv.data.local.PlayerSettings
import com.nexio.tv.ui.components.NexioDialog
import com.nexio.tv.ui.theme.NexioColors
import kotlin.math.roundToInt
import java.util.Locale

internal fun LazyListScope.autoPlaySettingsItems(
    playerSettings: PlayerSettings,
    onShowNextEpisodeThresholdModeDialog: () -> Unit,
    onShowReuseLastLinkCacheDialog: () -> Unit,
    onSetDeterministicAutoplayEnabled: (Boolean) -> Unit,
    onSetManualBitrateLimitMbps: (Double) -> Unit,
    onSetStreamAutoPlayNextEpisodeEnabled: (Boolean) -> Unit,
    onSetStreamAutoPlayPreferBingeGroupForNextEpisode: (Boolean) -> Unit,
    onSetNextEpisodeThresholdPercent: (Float) -> Unit,
    onSetNextEpisodeThresholdMinutesBeforeEnd: (Float) -> Unit,
    onSetReuseLastLinkEnabled: (Boolean) -> Unit,
    onSetGroupStreamsAcrossAddonsEnabled: (Boolean) -> Unit,
    onSetDeduplicateGroupedStreamsEnabled: (Boolean) -> Unit,
    onSetServiceWrapEnabled: (Boolean) -> Unit,
    onSetFilterWebDolbyVisionStreamsEnabled: (Boolean) -> Unit,
    onSetSkipPlaceholderStreamsEnabled: (Boolean) -> Unit,
    onSetFilterEpisodeMismatchStreamsEnabled: (Boolean) -> Unit,
    onSetFilterMovieYearMismatchStreamsEnabled: (Boolean) -> Unit,
    onItemFocused: () -> Unit = {}
) {
    item(key = "autoplay_deterministic") {
        ToggleSettingsItem(
            icon = Icons.Default.Tune,
            title = stringResource(R.string.autoplay_deterministic_title),
            subtitle = stringResource(R.string.autoplay_deterministic_sub),
            isChecked = playerSettings.deterministicAutoplayEnabled,
            onCheckedChange = onSetDeterministicAutoplayEnabled,
            enabled = true,
            onFocused = onItemFocused
        )
    }

    item(key = "autoplay_manual_bitrate_limit") {
        val sliderValue = (playerSettings.manualBitrateLimitMbps / 5.0).roundToInt()
        SliderSettingsItem(
            icon = Icons.Default.Tune,
            title = stringResource(R.string.autoplay_manual_bitrate_limit_title),
            subtitle = stringResource(R.string.autoplay_manual_bitrate_limit_sub),
            value = sliderValue,
            valueText = "${sliderValue * 5} Mbps",
            minValue = 1,
            maxValue = 40,
            step = 1,
            onValueChange = { onSetManualBitrateLimitMbps(it * 5.0) },
            onFocused = onItemFocused
        )
    }

    item(key = "autoplay_reuse_last_link") {
        ToggleSettingsItem(
            icon = Icons.Default.History,
            title = stringResource(R.string.autoplay_reuse_last_link),
            subtitle = stringResource(R.string.autoplay_reuse_last_link_sub),
            isChecked = playerSettings.streamReuseLastLinkEnabled,
            onCheckedChange = onSetReuseLastLinkEnabled,
            onFocused = onItemFocused
        )
    }

    if (playerSettings.streamReuseLastLinkEnabled) {
        item(key = "autoplay_reuse_cache_duration") {
            NavigationSettingsItem(
                icon = Icons.Default.Tune,
                title = stringResource(R.string.autoplay_last_link_cache),
                subtitle = formatReuseCacheDuration(playerSettings.streamReuseLastLinkCacheHours),
                onClick = onShowReuseLastLinkCacheDialog,
                onFocused = onItemFocused
            )
        }
    }

    item(key = "streams_deduplicate_grouped") {
        ToggleSettingsItem(
            icon = Icons.Default.ContentCopy,
            title = stringResource(R.string.streams_deduplicate_grouped_title),
            subtitle = stringResource(R.string.streams_deduplicate_grouped_sub),
            isChecked = playerSettings.deduplicateGroupedStreamsEnabled,
            onCheckedChange = onSetDeduplicateGroupedStreamsEnabled,
            onFocused = onItemFocused
        )
    }

    item(key = "streams_filter_dv5_webdl") {
        ToggleSettingsItem(
            icon = Icons.Default.FilterAlt,
            title = stringResource(R.string.streams_filter_dv5_webdl_title),
            subtitle = stringResource(R.string.streams_filter_dv5_webdl_sub),
            isChecked = playerSettings.filterWebDolbyVisionStreamsEnabled,
            onCheckedChange = onSetFilterWebDolbyVisionStreamsEnabled,
            onFocused = onItemFocused
        )
    }

    item(key = "streams_skip_placeholder") {
        ToggleSettingsItem(
            icon = Icons.Default.FilterAlt,
            title = stringResource(R.string.streams_skip_placeholder_title),
            subtitle = stringResource(R.string.streams_skip_placeholder_sub),
            isChecked = playerSettings.skipPlaceholderStreamsEnabled,
            onCheckedChange = onSetSkipPlaceholderStreamsEnabled,
            onFocused = onItemFocused
        )
    }

    item(key = "streams_filter_wrong_episodes") {
        ToggleSettingsItem(
            icon = Icons.Default.FilterAlt,
            title = stringResource(R.string.streams_filter_wrong_episodes_title),
            subtitle = stringResource(R.string.streams_filter_wrong_episodes_sub),
            isChecked = playerSettings.filterEpisodeMismatchStreamsEnabled,
            onCheckedChange = onSetFilterEpisodeMismatchStreamsEnabled,
            onFocused = onItemFocused
        )
    }

    item(key = "streams_filter_wrong_movie_year") {
        ToggleSettingsItem(
            icon = Icons.Default.FilterAlt,
            title = stringResource(R.string.streams_filter_wrong_movie_year_title),
            subtitle = stringResource(R.string.streams_filter_wrong_movie_year_sub),
            isChecked = playerSettings.filterMovieYearMismatchStreamsEnabled,
            onCheckedChange = onSetFilterMovieYearMismatchStreamsEnabled,
            onFocused = onItemFocused
        )
    }

    item(key = "autoplay_next_episode") {
        ToggleSettingsItem(
            icon = Icons.Default.SkipNext,
            title = stringResource(R.string.autoplay_next_episode),
            subtitle = stringResource(R.string.autoplay_next_episode_sub),
            isChecked = playerSettings.streamAutoPlayNextEpisodeEnabled,
            onCheckedChange = onSetStreamAutoPlayNextEpisodeEnabled,
            onFocused = onItemFocused
        )
    }

    item(key = "autoplay_next_episode_prefer_binge_group") {
        ToggleSettingsItem(
            icon = Icons.Default.Tune,
            title = stringResource(R.string.autoplay_prefer_binge_group),
            subtitle = stringResource(R.string.autoplay_prefer_binge_group_sub),
            isChecked = playerSettings.streamAutoPlayPreferBingeGroupForNextEpisode,
            onCheckedChange = onSetStreamAutoPlayPreferBingeGroupForNextEpisode,
            onFocused = onItemFocused
        )
    }

    item(key = "autoplay_threshold_mode") {
        val thresholdModeSubtitle = when (playerSettings.nextEpisodeThresholdMode) {
            NextEpisodeThresholdMode.PERCENTAGE -> stringResource(R.string.autoplay_threshold_pct)
            NextEpisodeThresholdMode.MINUTES_BEFORE_END -> stringResource(R.string.autoplay_threshold_min)
        }
        NavigationSettingsItem(
            icon = Icons.Default.Tune,
            title = stringResource(R.string.autoplay_threshold_mode),
            subtitle = thresholdModeSubtitle,
            onClick = onShowNextEpisodeThresholdModeDialog,
            onFocused = onItemFocused
        )
    }

    item(key = "autoplay_threshold_value") {
        when (playerSettings.nextEpisodeThresholdMode) {
            NextEpisodeThresholdMode.PERCENTAGE -> {
                SliderSettingsItem(
                    icon = Icons.Default.Tune,
                    title = stringResource(R.string.autoplay_threshold_pct_title),
                    subtitle = stringResource(R.string.autoplay_threshold_pct_sub),
                    value = (playerSettings.nextEpisodeThresholdPercent * 2f).roundToInt(),
                    valueText = "${formatHalfStepValue(playerSettings.nextEpisodeThresholdPercent)}%",
                    minValue = 194,
                    maxValue = 199,
                    step = 1,
                    onValueChange = { onSetNextEpisodeThresholdPercent(it / 2f) },
                    onFocused = onItemFocused
                )
            }
            NextEpisodeThresholdMode.MINUTES_BEFORE_END -> {
                SliderSettingsItem(
                    icon = Icons.Default.Tune,
                    title = stringResource(R.string.autoplay_threshold_min_title),
                    subtitle = stringResource(R.string.autoplay_threshold_pct_sub),
                    value = (playerSettings.nextEpisodeThresholdMinutesBeforeEnd * 2f).roundToInt(),
                    valueText = "${formatHalfStepValue(playerSettings.nextEpisodeThresholdMinutesBeforeEnd)} min",
                    minValue = 2,
                    maxValue = 7,
                    step = 1,
                    onValueChange = { onSetNextEpisodeThresholdMinutesBeforeEnd(it / 2f) },
                    onFocused = onItemFocused
                )
            }
        }
    }

}

private fun formatHalfStepValue(value: Float): String {
    return if (value % 1f == 0f) {
        value.toInt().toString()
    } else {
        String.format(Locale.US, "%.1f", value)
    }
}

@Composable
internal fun AutoPlaySettingsDialogs(
    showNextEpisodeThresholdModeDialog: Boolean,
    showReuseLastLinkCacheDialog: Boolean,
    playerSettings: PlayerSettings,
    installedAddonNames: List<String>,
    onSetNextEpisodeThresholdMode: (NextEpisodeThresholdMode) -> Unit,
    onSetReuseLastLinkCacheHours: (Int) -> Unit,
    onDismissNextEpisodeThresholdModeDialog: () -> Unit,
    onDismissReuseLastLinkCacheDialog: () -> Unit
) {
    if (showNextEpisodeThresholdModeDialog) {
        NextEpisodeThresholdModeDialog(
            selectedMode = playerSettings.nextEpisodeThresholdMode,
            onModeSelected = {
                onSetNextEpisodeThresholdMode(it)
                onDismissNextEpisodeThresholdModeDialog()
            },
            onDismiss = onDismissNextEpisodeThresholdModeDialog
        )
    }

    if (showReuseLastLinkCacheDialog) {
        StreamReuseLastLinkCacheDurationDialog(
            selectedHours = playerSettings.streamReuseLastLinkCacheHours,
            onDurationSelected = {
                onSetReuseLastLinkCacheHours(it)
                onDismissReuseLastLinkCacheDialog()
            },
            onDismiss = onDismissReuseLastLinkCacheDialog
        )
    }
}

@Composable
private fun NextEpisodeThresholdModeDialog(
    selectedMode: NextEpisodeThresholdMode,
    onModeSelected: (NextEpisodeThresholdMode) -> Unit,
    onDismiss: () -> Unit
) {
    val focusRequester = remember { FocusRequester() }
    val options = listOf(
        Triple(
            NextEpisodeThresholdMode.PERCENTAGE,
            stringResource(R.string.autoplay_threshold_pct),
            stringResource(R.string.autoplay_threshold_pct_desc)
        ),
        Triple(
            NextEpisodeThresholdMode.MINUTES_BEFORE_END,
            stringResource(R.string.autoplay_threshold_min),
            stringResource(R.string.autoplay_threshold_min_desc)
        )
    )

    LaunchedEffect(Unit) {
        focusRequester.requestFocus()
    }

    NexioDialog(
        onDismiss = onDismiss,
        title = stringResource(R.string.autoplay_threshold_mode),
        width = 520.dp,
        suppressFirstKeyUp = false
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = 320.dp)
        ) {
            LazyColumn(
                verticalArrangement = Arrangement.spacedBy(8.dp),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(vertical = 4.dp)
            ) {
                items(
                    count = options.size,
                    key = { index -> options[index].first.name }
                ) { index ->
                    val (mode, title, description) = options[index]
                    val isSelected = mode == selectedMode

                    Card(
                        onClick = { onModeSelected(mode) },
                        modifier = Modifier
                            .fillMaxWidth()
                            .then(if (index == 0) Modifier.focusRequester(focusRequester) else Modifier),
                        colors = CardDefaults.colors(
                            containerColor = if (isSelected) NexioColors.FocusBackground else NexioColors.BackgroundCard,
                            focusedContainerColor = NexioColors.FocusBackground
                        ),
                        shape = CardDefaults.shape(shape = androidx.compose.foundation.shape.RoundedCornerShape(10.dp)),
                        scale = CardDefaults.scale(focusedScale = 1f)
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(16.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = title,
                                    color = if (isSelected) NexioColors.Primary else NexioColors.TextPrimary,
                                    style = MaterialTheme.typography.bodyLarge
                                )
                                Spacer(modifier = Modifier.height(4.dp))
                                Text(
                                    text = description,
                                    color = NexioColors.TextSecondary,
                                    style = MaterialTheme.typography.bodySmall
                                )
                            }
                            if (isSelected) {
                                Spacer(modifier = Modifier.width(12.dp))
                                Icon(
                                    imageVector = Icons.Default.Check,
                                    contentDescription = stringResource(R.string.cd_selected),
                                    tint = NexioColors.Primary,
                                    modifier = Modifier.height(20.dp)
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

private fun formatReuseCacheDuration(hours: Int): String {
    return when {
        hours < 24 -> "$hours hour${if (hours == 1) "" else "s"}"
        hours % 24 == 0 -> {
            val days = hours / 24
            "$days day${if (days == 1) "" else "s"}"
        }
        else -> {
            val days = hours / 24
            val remainingHours = hours % 24
            "${days}d ${remainingHours}h"
        }
    }
}

@Composable
private fun StreamReuseLastLinkCacheDurationDialog(
    selectedHours: Int,
    onDurationSelected: (Int) -> Unit,
    onDismiss: () -> Unit
) {
    val focusRequester = remember { FocusRequester() }
    val options = listOf(
        1,
        6,
        12,
        24,
        48,
        72,
        168
    )

    LaunchedEffect(Unit) {
        focusRequester.requestFocus()
    }

    NexioDialog(
        onDismiss = onDismiss,
        title = stringResource(R.string.autoplay_last_link_cache),
        width = 420.dp,
        suppressFirstKeyUp = false
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = 320.dp)
        ) {
            LazyColumn(
                verticalArrangement = Arrangement.spacedBy(8.dp),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(vertical = 4.dp)
            ) {
                itemsIndexed(
                    items = options,
                    key = { _, hours -> hours }
                ) { index, hours ->
                    val isSelected = hours == selectedHours
                    Card(
                        onClick = { onDurationSelected(hours) },
                        modifier = Modifier
                            .fillMaxWidth()
                            .then(if (index == 0) Modifier.focusRequester(focusRequester) else Modifier),
                        colors = CardDefaults.colors(
                            containerColor = if (isSelected) NexioColors.FocusBackground else NexioColors.BackgroundCard,
                            focusedContainerColor = NexioColors.FocusBackground
                        ),
                        shape = CardDefaults.shape(shape = androidx.compose.foundation.shape.RoundedCornerShape(10.dp)),
                        scale = CardDefaults.scale(focusedScale = 1f)
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(14.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = formatReuseCacheDuration(hours),
                                style = MaterialTheme.typography.bodyLarge,
                                color = if (isSelected) NexioColors.Primary else NexioColors.TextPrimary,
                                modifier = Modifier.weight(1f)
                            )

                            if (isSelected) {
                                Icon(
                                    imageVector = Icons.Default.Check,
                                    contentDescription = stringResource(R.string.cd_selected),
                                    tint = NexioColors.Primary,
                                    modifier = Modifier.height(20.dp)
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}
