@file:OptIn(ExperimentalTvMaterial3Api::class)

package com.nexio.tv.ui.screens.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.unit.dp
import androidx.compose.ui.res.stringResource
import com.nexio.tv.R
import androidx.tv.material3.Border
import androidx.tv.material3.Card
import androidx.tv.material3.CardDefaults
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.Icon
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.nexio.tv.data.local.AVAILABLE_SUBTITLE_LANGUAGES
import com.nexio.tv.data.local.AudioLanguageOption
import com.nexio.tv.data.local.PlayerSettings
import com.nexio.tv.ui.components.NexioDialog
import com.nexio.tv.ui.theme.NexioColors

internal fun LazyListScope.videoSettingsItems(
    playerSettings: PlayerSettings,
    onSetTunnelingEnabled: (Boolean) -> Unit,
    onSetExperimentalDv7ToDv81Enabled: (Boolean) -> Unit,
    onSetExperimentalDv5ToDv81Enabled: (Boolean) -> Unit,
    onSetExperimentalDv7ToDv81PreserveMappingEnabled: (Boolean) -> Unit,
    onItemFocused: () -> Unit = {},
    enabled: Boolean = true
) {
    item(key = "video_section_intro") {
        Text(
            text = stringResource(R.string.video_section),
            style = MaterialTheme.typography.titleMedium,
            color = NexioColors.TextSecondary,
            modifier = Modifier.padding(vertical = 8.dp)
        )
    }

    item(key = "audio_tunneled_playback") {
        ToggleSettingsItem(
            icon = Icons.AutoMirrored.Filled.VolumeUp,
            title = stringResource(R.string.audio_tunneled),
            subtitle = stringResource(R.string.audio_tunneled_sub),
            isChecked = playerSettings.tunnelingEnabled,
            onCheckedChange = onSetTunnelingEnabled,
            onFocused = onItemFocused,
            enabled = enabled
        )
    }

    item(key = "audio_dv7_dovi_experimental") {
        ToggleSettingsItem(
            icon = Icons.Default.Tune,
            title = stringResource(R.string.audio_dv_experimental_title),
            subtitle = stringResource(R.string.audio_dv_experimental_sub),
            isChecked = playerSettings.experimentalDv7ToDv81Enabled,
            onCheckedChange = onSetExperimentalDv7ToDv81Enabled,
            onFocused = onItemFocused,
            enabled = enabled
        )
    }

    item(key = "audio_dv7_dovi_experimental_preserve_mapping") {
        ToggleSettingsItem(
            icon = Icons.Default.Tune,
            title = stringResource(R.string.audio_dv_experimental_preserve_mapping_title),
            subtitle = stringResource(R.string.audio_dv_experimental_preserve_mapping_sub),
            isChecked = playerSettings.experimentalDv7ToDv81PreserveMappingEnabled,
            onCheckedChange = onSetExperimentalDv7ToDv81PreserveMappingEnabled,
            onFocused = onItemFocused,
            enabled = enabled && playerSettings.experimentalDv7ToDv81Enabled
        )
    }

    item(key = "audio_dv5_dovi_experimental") {
        ToggleSettingsItem(
            icon = Icons.Default.Tune,
            title = stringResource(R.string.audio_dv5_compatibility_title),
            subtitle = stringResource(R.string.audio_dv5_compatibility_sub),
            isChecked = playerSettings.experimentalDv5ToDv81Enabled,
            onCheckedChange = onSetExperimentalDv5ToDv81Enabled,
            onFocused = onItemFocused,
            enabled = enabled && playerSettings.experimentalDv7ToDv81Enabled
        )
    }
}

internal fun LazyListScope.audioSettingsItems(
    playerSettings: PlayerSettings,
    onShowAudioLanguageDialog: () -> Unit,
    onShowSecondaryAudioLanguageDialog: () -> Unit,
    onShowDecoderPriorityDialog: () -> Unit,
    onSetSkipSilence: (Boolean) -> Unit,
    onSetExperimentalDtsIecPassthroughEnabled: (Boolean) -> Unit,
    onItemFocused: () -> Unit = {},
    enabled: Boolean = true
) {
    item(key = "audio_section_intro") {
        Spacer(modifier = Modifier.height(16.dp))
        Text(
            text = stringResource(R.string.audio_section),
            style = MaterialTheme.typography.titleMedium,
            color = NexioColors.TextSecondary,
            modifier = Modifier.padding(vertical = 8.dp)
        )
    }

    item(key = "audio_passthrough_info") {
        Text(
            text = stringResource(R.string.audio_passthrough_info),
            style = MaterialTheme.typography.bodySmall,
            color = NexioColors.TextSecondary,
            modifier = Modifier.padding(bottom = 8.dp)
        )
    }

    item(key = "audio_preferred_language") {
        val audioLangName = when (playerSettings.preferredAudioLanguage) {
            AudioLanguageOption.DEFAULT -> stringResource(R.string.audio_lang_default)
            AudioLanguageOption.DEVICE -> stringResource(R.string.audio_lang_device)
            else -> AVAILABLE_SUBTITLE_LANGUAGES.find {
                it.code == playerSettings.preferredAudioLanguage
            }?.name ?: playerSettings.preferredAudioLanguage
        }

        NavigationSettingsItem(
            icon = Icons.Default.Language,
            title = stringResource(R.string.audio_preferred_lang),
            subtitle = audioLangName,
            onClick = onShowAudioLanguageDialog,
            onFocused = onItemFocused,
            enabled = enabled
        )
    }

    item(key = "audio_secondary_preferred_language") {
        val secondaryAudioLangName = playerSettings.secondaryPreferredAudioLanguage?.let { code ->
            AVAILABLE_SUBTITLE_LANGUAGES.find { it.code == code }?.name ?: code
        } ?: stringResource(R.string.sub_not_set)

        NavigationSettingsItem(
            icon = Icons.Default.Language,
            title = stringResource(R.string.sub_secondary_lang),
            subtitle = secondaryAudioLangName,
            onClick = onShowSecondaryAudioLanguageDialog,
            onFocused = onItemFocused,
            enabled = enabled
        )
    }

    item(key = "audio_skip_silence") {
        ToggleSettingsItem(
            icon = Icons.Default.Speed,
            title = stringResource(R.string.audio_skip_silence),
            subtitle = stringResource(R.string.audio_skip_silence_sub),
            isChecked = playerSettings.skipSilence,
            onCheckedChange = onSetSkipSilence,
            onFocused = onItemFocused,
            enabled = enabled
        )
    }

    item(key = "audio_decoder_priority") {
        val decoderName = when (playerSettings.decoderPriority) {
            0 -> stringResource(R.string.audio_decoder_device_only)
            1 -> stringResource(R.string.audio_decoder_prefer_device)
            2 -> stringResource(R.string.audio_decoder_prefer_app)
            else -> stringResource(R.string.audio_decoder_prefer_device)
        }

        NavigationSettingsItem(
            icon = Icons.Default.Tune,
            title = stringResource(R.string.audio_decoder_priority),
            subtitle = decoderName,
            onClick = onShowDecoderPriorityDialog,
            onFocused = onItemFocused,
            enabled = enabled
        )
    }

    item(key = "audio_dts_iec_experimental") {
        ToggleSettingsItem(
            icon = Icons.Default.Tune,
            title = stringResource(R.string.audio_dts_iec_experimental_title),
            subtitle = stringResource(R.string.audio_dts_iec_experimental_sub),
            isChecked = playerSettings.experimentalDtsIecPassthroughEnabled,
            onCheckedChange = onSetExperimentalDtsIecPassthroughEnabled,
            onFocused = onItemFocused,
            enabled = enabled
        )
    }
}

@Composable
internal fun AudioSettingsDialogs(
    showAudioLanguageDialog: Boolean,
    showSecondaryAudioLanguageDialog: Boolean,
    showDecoderPriorityDialog: Boolean,
    selectedLanguage: String,
    selectedSecondaryLanguage: String?,
    selectedPriority: Int,
    onSetPreferredAudioLanguage: (String) -> Unit,
    onSetSecondaryPreferredAudioLanguage: (String?) -> Unit,
    onSetDecoderPriority: (Int) -> Unit,
    onDismissAudioLanguageDialog: () -> Unit,
    onDismissSecondaryAudioLanguageDialog: () -> Unit,
    onDismissDecoderPriorityDialog: () -> Unit
) {
    if (showAudioLanguageDialog) {
        AudioLanguageSelectionDialog(
            selectedLanguage = selectedLanguage,
            onLanguageSelected = {
                onSetPreferredAudioLanguage(it)
                onDismissAudioLanguageDialog()
            },
            onDismiss = onDismissAudioLanguageDialog
        )
    }

    if (showSecondaryAudioLanguageDialog) {
        LanguageSelectionDialog(
            title = stringResource(R.string.sub_secondary_lang),
            selectedLanguage = selectedSecondaryLanguage,
            showNoneOption = true,
            onLanguageSelected = {
                onSetSecondaryPreferredAudioLanguage(it)
                onDismissSecondaryAudioLanguageDialog()
            },
            onDismiss = onDismissSecondaryAudioLanguageDialog
        )
    }

    if (showDecoderPriorityDialog) {
        DecoderPriorityDialog(
            selectedPriority = selectedPriority,
            onPrioritySelected = {
                onSetDecoderPriority(it)
                onDismissDecoderPriorityDialog()
            },
            onDismiss = onDismissDecoderPriorityDialog
        )
    }
}

@Composable
private fun AudioLanguageSelectionDialog(
    selectedLanguage: String,
    onLanguageSelected: (String) -> Unit,
    onDismiss: () -> Unit
) {
    val focusRequester = remember { FocusRequester() }
    val specialOptions = listOf(
        AudioLanguageOption.DEFAULT to stringResource(R.string.audio_lang_default),
        AudioLanguageOption.DEVICE to stringResource(R.string.audio_lang_device)
    )
    val allOptions = specialOptions + AVAILABLE_SUBTITLE_LANGUAGES.map { it.code to it.name }

    LaunchedEffect(Unit) {
        focusRequester.requestFocus()
    }

    NexioDialog(
        onDismiss = onDismiss,
        title = stringResource(R.string.audio_preferred_lang),
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
                contentPadding = androidx.compose.foundation.layout.PaddingValues(vertical = 4.dp)
            ) {
                items(
                    count = allOptions.size,
                    key = { index -> allOptions[index].first }
                ) { index ->
                    val (code, name) = allOptions[index]
                    val isSelected = code == selectedLanguage
                    var isFocused by remember { mutableStateOf(false) }

                    Card(
                        onClick = { onLanguageSelected(code) },
                        modifier = Modifier
                            .fillMaxWidth()
                            .then(if (index == 0) Modifier.focusRequester(focusRequester) else Modifier)
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
            }
        }
    }
}

@Composable
private fun DecoderPriorityDialog(
    selectedPriority: Int,
    onPrioritySelected: (Int) -> Unit,
    onDismiss: () -> Unit
) {
    val focusRequester = remember { FocusRequester() }
    val options = listOf(
        Triple(0, stringResource(R.string.audio_decoder_device_only), stringResource(R.string.audio_decoder_device_only_desc)),
        Triple(1, stringResource(R.string.audio_decoder_prefer_device), stringResource(R.string.audio_decoder_prefer_device_desc)),
        Triple(2, stringResource(R.string.audio_decoder_prefer_app), stringResource(R.string.audio_decoder_prefer_app_desc))
    )

    LaunchedEffect(Unit) {
        focusRequester.requestFocus()
    }

    NexioDialog(
        onDismiss = onDismiss,
        title = stringResource(R.string.audio_decoder_priority),
        subtitle = stringResource(R.string.audio_decoder_controls),
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
                items(
                    count = options.size,
                    key = { index -> options[index].first.toString() }
                ) { index ->
                    val (priority, title, description) = options[index]
                    val isSelected = priority == selectedPriority

                    Card(
                        onClick = { onPrioritySelected(priority) },
                        modifier = Modifier
                            .fillMaxWidth()
                            .then(if (index == 0) Modifier.focusRequester(focusRequester) else Modifier),
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
                                    modifier = Modifier.size(20.dp)
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}
