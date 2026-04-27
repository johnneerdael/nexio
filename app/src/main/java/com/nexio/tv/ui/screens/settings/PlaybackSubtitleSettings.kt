@file:OptIn(ExperimentalTvMaterial3Api::class)

package com.nexio.tv.ui.screens.settings

import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.background
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.ClosedCaption
import androidx.compose.material.icons.filled.FormatBold
import androidx.compose.material.icons.filled.FormatSize
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material.icons.filled.Subtitles
import androidx.compose.material.icons.filled.VerticalAlignBottom
import androidx.compose.runtime.Composable
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.unit.dp
import androidx.compose.ui.res.stringResource
import com.nexio.tv.R
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.nexio.tv.data.local.AVAILABLE_SUBTITLE_LANGUAGES
import com.nexio.tv.data.local.PlayerSettings
import com.nexio.tv.data.local.AddonSubtitleStartupMode
import com.nexio.tv.data.local.SUBTITLE_LANGUAGE_FORCED
import com.nexio.tv.data.local.clampSubtitleBackgroundAlpha
import com.nexio.tv.ui.components.NexioDialog
import com.nexio.tv.ui.theme.NexioColors

private val subtitleBackgroundColors = listOf(
    Color.Transparent,
    Color(0xBF000000),     // was Color.Black
    Color(0x80000000),
    Color(0xBF1A1A1A),     // was Color(0xFF1A1A1A)
    Color(0xBF2D2D2D)      // was Color(0xFF2D2D2D)
)

private val subtitleOutlineColors = listOf(
    Color.Black,
    Color(0xFF1A1A1A),
    Color(0xFF333333),
    Color.White
)

internal fun LazyListScope.subtitleSettingsItems(
    playerSettings: PlayerSettings,
    onShowLanguageDialog: () -> Unit,
    onShowSecondaryLanguageDialog: () -> Unit,
    onShowSubtitleStartupModeDialog: () -> Unit,
    onShowBackgroundColorDialog: () -> Unit,
    onShowOutlineColorDialog: () -> Unit,
    onSetSubtitleSize: (Int) -> Unit,
    onSetSubtitleVerticalOffset: (Int) -> Unit,
    onSetSubtitleBold: (Boolean) -> Unit,
    onSetSubtitleOutlineEnabled: (Boolean) -> Unit,
    onSetBurnInProtectionEnabled: (Boolean) -> Unit,
    onItemFocused: () -> Unit = {},
    enabled: Boolean = true
) {
    item(key = "subtitle_header") {
        Spacer(modifier = androidx.compose.ui.Modifier.height(16.dp))
        Text(
            text = stringResource(R.string.sub_section),
            style = MaterialTheme.typography.titleMedium,
            color = NexioColors.TextSecondary,
            modifier = androidx.compose.ui.Modifier.padding(vertical = 8.dp)
        )
    }

    item(key = "subtitle_burn_in_protection") {
        ToggleSettingsItem(
            icon = Icons.Default.Shield,
            title = stringResource(R.string.subtitle_burn_in_protection_title),
            subtitle = stringResource(R.string.subtitle_burn_in_protection_subtitle),
            isChecked = playerSettings.burnInProtection.enabled,
            onCheckedChange = onSetBurnInProtectionEnabled,
            onFocused = onItemFocused,
            enabled = enabled
        )
    }

    item(key = "subtitle_preferred_language") {
        val languageName = if (playerSettings.subtitleStyle.preferredLanguage == "none") {
            stringResource(R.string.action_none)
        } else if (playerSettings.subtitleStyle.preferredLanguage == SUBTITLE_LANGUAGE_FORCED) {
            stringResource(R.string.sub_forced_lang)
        } else {
            AVAILABLE_SUBTITLE_LANGUAGES.find {
                it.code == playerSettings.subtitleStyle.preferredLanguage
            }?.name ?: "English"
        }

        NavigationSettingsItem(
            icon = Icons.Default.Language,
            title = stringResource(R.string.sub_preferred_lang),
            subtitle = languageName,
            onClick = onShowLanguageDialog,
            onFocused = onItemFocused,
            enabled = enabled
        )
    }

    item(key = "subtitle_secondary_language") {
        val secondaryLanguageName = playerSettings.subtitleStyle.secondaryPreferredLanguage?.let { code ->
            if (code == SUBTITLE_LANGUAGE_FORCED) stringResource(R.string.sub_forced_lang)
            else AVAILABLE_SUBTITLE_LANGUAGES.find { it.code == code }?.name
        } ?: stringResource(R.string.sub_not_set)

        NavigationSettingsItem(
            icon = Icons.Default.Language,
            title = stringResource(R.string.sub_secondary_lang),
            subtitle = secondaryLanguageName,
            onClick = onShowSecondaryLanguageDialog,
            onFocused = onItemFocused,
            enabled = enabled
        )
    }

    item(key = "subtitle_startup_mode") {
        NavigationSettingsItem(
            icon = Icons.Default.Subtitles,
            title = stringResource(R.string.sub_startup_mode_title),
            subtitle = subtitleStartupModeLabel(playerSettings.addonSubtitleStartupMode),
            onClick = onShowSubtitleStartupModeDialog,
            onFocused = onItemFocused,
            enabled = enabled
        )
    }

    item(key = "subtitle_size") {
        SliderSettingsItem(
            icon = Icons.Default.FormatSize,
            title = stringResource(R.string.sub_size),
            value = playerSettings.subtitleStyle.size,
            valueText = "${playerSettings.subtitleStyle.size}%",
            minValue = 50,
            maxValue = 200,
            step = 10,
            onValueChange = onSetSubtitleSize,
            onFocused = onItemFocused,
            enabled = enabled
        )
    }

    item(key = "subtitle_vertical_offset") {
        SliderSettingsItem(
            icon = Icons.Default.VerticalAlignBottom,
            title = stringResource(R.string.sub_vertical_offset),
            value = playerSettings.subtitleStyle.verticalOffset,
            valueText = "${playerSettings.subtitleStyle.verticalOffset}%",
            minValue = -20,
            maxValue = 50,
            step = 1,
            onValueChange = onSetSubtitleVerticalOffset,
            onFocused = onItemFocused,
            enabled = enabled
        )
    }

    item(key = "subtitle_bold") {
        ToggleSettingsItem(
            icon = Icons.Default.FormatBold,
            title = stringResource(R.string.sub_bold),
            subtitle = stringResource(R.string.sub_bold_sub),
            isChecked = playerSettings.subtitleStyle.bold,
            onCheckedChange = onSetSubtitleBold,
            onFocused = onItemFocused,
            enabled = enabled
        )
    }

    item(key = "subtitle_background_color") {
        ColorSettingsItem(
            icon = Icons.Default.Palette,
            title = stringResource(R.string.sub_bg_color),
            currentColor = Color(playerSettings.subtitleStyle.backgroundColor),
            showTransparent = playerSettings.subtitleStyle.backgroundColor == Color.Transparent.toArgb(),
            onClick = onShowBackgroundColorDialog,
            onFocused = onItemFocused,
            enabled = enabled
        )
    }

    item(key = "subtitle_outline_toggle") {
        ToggleSettingsItem(
            icon = Icons.Default.ClosedCaption,
            title = stringResource(R.string.sub_outline),
            subtitle = stringResource(R.string.sub_outline_sub),
            isChecked = playerSettings.subtitleStyle.outlineEnabled,
            onCheckedChange = onSetSubtitleOutlineEnabled,
            onFocused = onItemFocused,
            enabled = enabled
        )
    }

    if (playerSettings.subtitleStyle.outlineEnabled) {
        item(key = "subtitle_outline_color") {
            ColorSettingsItem(
                icon = Icons.Default.Palette,
                title = stringResource(R.string.sub_outline_color),
                currentColor = Color(playerSettings.subtitleStyle.outlineColor),
                onClick = onShowOutlineColorDialog,
                onFocused = onItemFocused,
                enabled = enabled
            )
        }
    }

}

@Composable
internal fun SubtitleSettingsDialogs(
    showLanguageDialog: Boolean,
    showSecondaryLanguageDialog: Boolean,
    showSubtitleStartupModeDialog: Boolean,
    showBackgroundColorDialog: Boolean,
    showOutlineColorDialog: Boolean,
    playerSettings: PlayerSettings,
    onSetPreferredLanguage: (String?) -> Unit,
    onSetSecondaryLanguage: (String?) -> Unit,
    onSetAddonSubtitleStartupMode: (AddonSubtitleStartupMode) -> Unit,
    onSetBackgroundColor: (Color) -> Unit,
    onSetOutlineColor: (Color) -> Unit,
    onDismissLanguageDialog: () -> Unit,
    onDismissSecondaryLanguageDialog: () -> Unit,
    onDismissSubtitleStartupModeDialog: () -> Unit,
    onDismissBackgroundColorDialog: () -> Unit,
    onDismissOutlineColorDialog: () -> Unit
) {
    if (showLanguageDialog) {
        LanguageSelectionDialog(
            title = stringResource(R.string.sub_preferred_lang),
            selectedLanguage = if (playerSettings.subtitleStyle.preferredLanguage == "none") null else playerSettings.subtitleStyle.preferredLanguage,
            showNoneOption = true,
            extraOptions = listOf(SUBTITLE_LANGUAGE_FORCED to stringResource(R.string.sub_forced_lang)),
            onLanguageSelected = {
                onSetPreferredLanguage(it)
                onDismissLanguageDialog()
            },
            onDismiss = onDismissLanguageDialog
        )
    }

    if (showSecondaryLanguageDialog) {
        LanguageSelectionDialog(
            title = stringResource(R.string.sub_secondary_lang),
            selectedLanguage = playerSettings.subtitleStyle.secondaryPreferredLanguage,
            showNoneOption = true,
            extraOptions = listOf(SUBTITLE_LANGUAGE_FORCED to stringResource(R.string.sub_forced_lang)),
            onLanguageSelected = {
                onSetSecondaryLanguage(it)
                onDismissSecondaryLanguageDialog()
            },
            onDismiss = onDismissSecondaryLanguageDialog
        )
    }

    if (showSubtitleStartupModeDialog) {
        AddonSubtitleStartupModeDialog(
            selectedMode = playerSettings.addonSubtitleStartupMode,
            onModeSelected = {
                onSetAddonSubtitleStartupMode(it)
                onDismissSubtitleStartupModeDialog()
            },
            onDismiss = onDismissSubtitleStartupModeDialog
        )
    }

    if (showBackgroundColorDialog) {
        ColorSelectionDialog(
            title = stringResource(R.string.sub_bg_color),
            colors = subtitleBackgroundColors,
            selectedColor = Color(playerSettings.subtitleStyle.backgroundColor),
            showTransparentOption = true,
            onColorSelected = {
                onSetBackgroundColor(Color(clampSubtitleBackgroundAlpha(it.toArgb())))
                onDismissBackgroundColorDialog()
            },
            onDismiss = onDismissBackgroundColorDialog
        )
    }

    if (showOutlineColorDialog) {
        ColorSelectionDialog(
            title = stringResource(R.string.sub_outline_color),
            colors = subtitleOutlineColors,
            selectedColor = Color(playerSettings.subtitleStyle.outlineColor),
            onColorSelected = {
                onSetOutlineColor(it)
                onDismissOutlineColorDialog()
            },
            onDismiss = onDismissOutlineColorDialog
        )
    }
}

@Composable
private fun subtitleStartupModeLabel(mode: AddonSubtitleStartupMode): String {
    return when (mode) {
        AddonSubtitleStartupMode.FAST_STARTUP -> stringResource(R.string.sub_startup_mode_fast)
        AddonSubtitleStartupMode.PREFERRED_ONLY -> stringResource(R.string.sub_startup_mode_preferred)
        AddonSubtitleStartupMode.ALL_SUBTITLES -> stringResource(R.string.sub_startup_mode_all)
    }
}

@Composable
private fun AddonSubtitleStartupModeDialog(
    selectedMode: AddonSubtitleStartupMode,
    onModeSelected: (AddonSubtitleStartupMode) -> Unit,
    onDismiss: () -> Unit
) {
    val options = listOf(
        Triple(
            AddonSubtitleStartupMode.FAST_STARTUP,
            stringResource(R.string.sub_startup_mode_fast),
            stringResource(R.string.sub_startup_mode_fast_desc)
        ),
        Triple(
            AddonSubtitleStartupMode.PREFERRED_ONLY,
            stringResource(R.string.sub_startup_mode_preferred),
            stringResource(R.string.sub_startup_mode_preferred_desc)
        ),
        Triple(
            AddonSubtitleStartupMode.ALL_SUBTITLES,
            stringResource(R.string.sub_startup_mode_all),
            stringResource(R.string.sub_startup_mode_all_desc)
        )
    )

    androidx.compose.ui.window.Dialog(onDismissRequest = onDismiss) {
        androidx.compose.foundation.layout.Box(
            modifier = androidx.compose.ui.Modifier
                .clip(androidx.compose.foundation.shape.RoundedCornerShape(16.dp))
                .background(NexioColors.BackgroundCard)
        ) {
            androidx.compose.foundation.layout.Column(
                modifier = androidx.compose.ui.Modifier
                    .width(460.dp)
                    .padding(24.dp)
            ) {
                Text(
                    text = stringResource(R.string.sub_startup_mode_title),
                    style = MaterialTheme.typography.headlineSmall,
                    color = NexioColors.TextPrimary
                )
                Spacer(modifier = androidx.compose.ui.Modifier.height(16.dp))

                androidx.compose.foundation.lazy.LazyColumn(
                    verticalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(8.dp)
                ) {
                    items(
                        items = options,
                        key = { it.first.name }
                    ) { (mode, title, description) ->
                        RenderTypeSettingsItem(
                            title = title,
                            subtitle = description,
                            isSelected = mode == selectedMode,
                            onClick = { onModeSelected(mode) },
                            onFocused = {}
                        )
                    }
                }
            }
        }
    }
}
