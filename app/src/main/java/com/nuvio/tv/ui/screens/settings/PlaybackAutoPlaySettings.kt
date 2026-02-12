@file:OptIn(ExperimentalTvMaterial3Api::class)

package com.nuvio.tv.ui.screens.settings

import android.view.KeyEvent
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Extension
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Tune
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
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Border
import androidx.tv.material3.Button
import androidx.tv.material3.ButtonDefaults
import androidx.tv.material3.Card
import androidx.tv.material3.CardDefaults
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.Icon
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.nuvio.tv.data.local.PlayerSettings
import com.nuvio.tv.data.local.StreamAutoPlayMode
import com.nuvio.tv.data.local.StreamAutoPlaySource
import com.nuvio.tv.ui.theme.NuvioColors

internal fun LazyListScope.autoPlaySettingsItems(
    playerSettings: PlayerSettings,
    onShowModeDialog: () -> Unit,
    onShowSourceDialog: () -> Unit,
    onShowAddonSelectionDialog: () -> Unit,
    onShowPluginSelectionDialog: () -> Unit,
    onShowRegexDialog: () -> Unit,
    onShowReuseLastLinkCacheDialog: () -> Unit,
    onSetReuseLastLinkEnabled: (Boolean) -> Unit,
    onItemFocused: () -> Unit = {}
) {
    item {
        ToggleSettingsItem(
            icon = Icons.Default.History,
            title = "Reuse Last Link",
            subtitle = "Auto-play your last working stream for this same movie/episode when cache is still valid",
            isChecked = playerSettings.streamReuseLastLinkEnabled,
            onCheckedChange = onSetReuseLastLinkEnabled,
            onFocused = onItemFocused
        )
    }

    if (playerSettings.streamReuseLastLinkEnabled) {
        item {
            NavigationSettingsItem(
                icon = Icons.Default.Tune,
                title = "Last Link Cache Duration",
                subtitle = formatReuseCacheDuration(playerSettings.streamReuseLastLinkCacheHours),
                onClick = onShowReuseLastLinkCacheDialog,
                onFocused = onItemFocused
            )
        }
    }

    item {
        val modeLabel = when (playerSettings.streamAutoPlayMode) {
            StreamAutoPlayMode.MANUAL -> "Manual (choose stream)"
            StreamAutoPlayMode.FIRST_STREAM -> "Auto-play first source"
            StreamAutoPlayMode.REGEX_MATCH -> "Auto-play regex match"
        }
        NavigationSettingsItem(
            icon = Icons.Default.PlayArrow,
            title = "Auto Stream Selection",
            subtitle = modeLabel,
            onClick = onShowModeDialog,
            onFocused = onItemFocused
        )
    }

    if (playerSettings.streamAutoPlayMode != StreamAutoPlayMode.MANUAL) {
        item {
            val sourceLabel = when (playerSettings.streamAutoPlaySource) {
                StreamAutoPlaySource.ALL_SOURCES -> "All sources"
                StreamAutoPlaySource.INSTALLED_ADDONS_ONLY -> "Installed addons only"
                StreamAutoPlaySource.ENABLED_PLUGINS_ONLY -> "Enabled plugins only"
            }
            NavigationSettingsItem(
                icon = Icons.Default.Tune,
                title = "Auto-play Source Scope",
                subtitle = sourceLabel,
                onClick = onShowSourceDialog,
                onFocused = onItemFocused
            )
        }

        if (playerSettings.streamAutoPlaySource != StreamAutoPlaySource.ENABLED_PLUGINS_ONLY) {
            item {
                val addonSubtitle = if (playerSettings.streamAutoPlaySelectedAddons.isEmpty()) {
                    "All installed addons"
                } else {
                    "${playerSettings.streamAutoPlaySelectedAddons.size} selected"
                }
                NavigationSettingsItem(
                    icon = Icons.Default.Language,
                    title = "Allowed Addons",
                    subtitle = addonSubtitle,
                    onClick = onShowAddonSelectionDialog,
                    onFocused = onItemFocused
                )
            }
        }

        if (playerSettings.streamAutoPlaySource != StreamAutoPlaySource.INSTALLED_ADDONS_ONLY) {
            item {
                val pluginSubtitle = if (playerSettings.streamAutoPlaySelectedPlugins.isEmpty()) {
                    "All enabled plugins"
                } else {
                    "${playerSettings.streamAutoPlaySelectedPlugins.size} selected"
                }
                NavigationSettingsItem(
                    icon = Icons.Default.Extension,
                    title = "Allowed Plugins",
                    subtitle = pluginSubtitle,
                    onClick = onShowPluginSelectionDialog,
                    onFocused = onItemFocused
                )
            }
        }
    }

    if (playerSettings.streamAutoPlayMode == StreamAutoPlayMode.REGEX_MATCH) {
        item {
            val regexSubtitle = playerSettings.streamAutoPlayRegex.ifBlank {
                "No pattern set. Example: 4K|2160p|Remux"
            }
            NavigationSettingsItem(
                icon = Icons.Default.Tune,
                title = "Regex Pattern",
                subtitle = regexSubtitle,
                onClick = onShowRegexDialog,
                onFocused = onItemFocused
            )
        }
    }
}

@Composable
internal fun AutoPlaySettingsDialogs(
    showModeDialog: Boolean,
    showSourceDialog: Boolean,
    showRegexDialog: Boolean,
    showAddonSelectionDialog: Boolean,
    showPluginSelectionDialog: Boolean,
    showReuseLastLinkCacheDialog: Boolean,
    playerSettings: PlayerSettings,
    installedAddonNames: List<String>,
    enabledPluginNames: List<String>,
    onSetMode: (StreamAutoPlayMode) -> Unit,
    onSetSource: (StreamAutoPlaySource) -> Unit,
    onSetRegex: (String) -> Unit,
    onSetSelectedAddons: (Set<String>) -> Unit,
    onSetSelectedPlugins: (Set<String>) -> Unit,
    onSetReuseLastLinkCacheHours: (Int) -> Unit,
    onDismissModeDialog: () -> Unit,
    onDismissSourceDialog: () -> Unit,
    onDismissRegexDialog: () -> Unit,
    onDismissAddonSelectionDialog: () -> Unit,
    onDismissPluginSelectionDialog: () -> Unit,
    onDismissReuseLastLinkCacheDialog: () -> Unit
) {
    if (showModeDialog) {
        StreamAutoPlayModeDialog(
            selectedMode = playerSettings.streamAutoPlayMode,
            onModeSelected = {
                onSetMode(it)
                onDismissModeDialog()
            },
            onDismiss = onDismissModeDialog
        )
    }

    if (showSourceDialog) {
        StreamAutoPlaySourceDialog(
            selectedSource = playerSettings.streamAutoPlaySource,
            onSourceSelected = {
                onSetSource(it)
                onDismissSourceDialog()
            },
            onDismiss = onDismissSourceDialog
        )
    }

    if (showRegexDialog) {
        StreamRegexDialog(
            initialRegex = playerSettings.streamAutoPlayRegex,
            onSave = {
                onSetRegex(it)
                onDismissRegexDialog()
            },
            onDismiss = onDismissRegexDialog
        )
    }

    if (showAddonSelectionDialog) {
        StreamAutoPlayProviderSelectionDialog(
            title = "Allowed Addons",
            allLabel = "All installed addons",
            items = installedAddonNames,
            selectedItems = playerSettings.streamAutoPlaySelectedAddons,
            onSelectionSaved = onSetSelectedAddons,
            onDismiss = onDismissAddonSelectionDialog
        )
    }

    if (showPluginSelectionDialog) {
        StreamAutoPlayProviderSelectionDialog(
            title = "Allowed Plugins",
            allLabel = "All enabled plugins",
            items = enabledPluginNames,
            selectedItems = playerSettings.streamAutoPlaySelectedPlugins,
            onSelectionSaved = onSetSelectedPlugins,
            onDismiss = onDismissPluginSelectionDialog
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
private fun StreamAutoPlayModeDialog(
    selectedMode: StreamAutoPlayMode,
    onModeSelected: (StreamAutoPlayMode) -> Unit,
    onDismiss: () -> Unit
) {
    val focusRequester = remember { FocusRequester() }
    val options = listOf(
        Triple(StreamAutoPlayMode.MANUAL, "Manual", "Always show source list and let me choose."),
        Triple(StreamAutoPlayMode.FIRST_STREAM, "Auto-play first stream", "Play the first available source automatically."),
        Triple(StreamAutoPlayMode.REGEX_MATCH, "Auto-play regex match", "Play first source whose text matches your regex pattern.")
    )

    LaunchedEffect(Unit) {
        focusRequester.requestFocus()
    }

    androidx.compose.ui.window.Dialog(onDismissRequest = onDismiss) {
        Box(
            modifier = Modifier
                .clip(androidx.compose.foundation.shape.RoundedCornerShape(16.dp))
                .background(NuvioColors.BackgroundCard)
        ) {
            Column(
                modifier = Modifier
                    .width(460.dp)
                    .padding(24.dp)
            ) {
                Text(
                    text = "Auto Stream Selection",
                    style = MaterialTheme.typography.headlineSmall,
                    color = NuvioColors.TextPrimary
                )
                Spacer(modifier = Modifier.height(16.dp))

                LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(options.size) { index ->
                        val (mode, title, description) = options[index]
                        val isSelected = mode == selectedMode
                        var isFocused by remember { mutableStateOf(false) }

                        Card(
                            onClick = { onModeSelected(mode) },
                            modifier = Modifier
                                .fillMaxWidth()
                                .then(if (index == 0) Modifier.focusRequester(focusRequester) else Modifier)
                                .onFocusChanged { isFocused = it.isFocused },
                            colors = CardDefaults.colors(
                                containerColor = if (isSelected) NuvioColors.Primary.copy(alpha = 0.2f) else NuvioColors.BackgroundElevated,
                                focusedContainerColor = NuvioColors.FocusBackground
                            ),
                            border = CardDefaults.border(
                                focusedBorder = Border(
                                    border = BorderStroke(2.dp, NuvioColors.FocusRing),
                                    shape = androidx.compose.foundation.shape.RoundedCornerShape(8.dp)
                                )
                            ),
                            shape = CardDefaults.shape(shape = androidx.compose.foundation.shape.RoundedCornerShape(8.dp)),
                            scale = CardDefaults.scale(focusedScale = 1.02f)
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
                                        color = if (isSelected || isFocused) NuvioColors.Primary else NuvioColors.TextPrimary,
                                        style = MaterialTheme.typography.bodyLarge
                                    )
                                    Spacer(modifier = Modifier.height(4.dp))
                                    Text(
                                        text = description,
                                        color = NuvioColors.TextSecondary,
                                        style = MaterialTheme.typography.bodySmall
                                    )
                                }
                                if (isSelected) {
                                    Icon(
                                        imageVector = Icons.Default.Check,
                                        contentDescription = "Selected",
                                        tint = NuvioColors.Primary,
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

    androidx.compose.ui.window.Dialog(onDismissRequest = onDismiss) {
        Box(
            modifier = Modifier
                .clip(androidx.compose.foundation.shape.RoundedCornerShape(16.dp))
                .background(NuvioColors.BackgroundCard)
        ) {
            Column(
                modifier = Modifier
                    .width(420.dp)
                    .padding(24.dp)
            ) {
                Text(
                    text = "Last Link Cache Duration",
                    style = MaterialTheme.typography.headlineSmall,
                    color = NuvioColors.TextPrimary
                )
                Spacer(modifier = Modifier.height(16.dp))

                LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    itemsIndexed(options) { index, hours ->
                        val isSelected = hours == selectedHours
                        Card(
                            onClick = { onDurationSelected(hours) },
                            modifier = Modifier
                                .fillMaxWidth()
                                .then(if (index == 0) Modifier.focusRequester(focusRequester) else Modifier),
                            colors = CardDefaults.colors(
                                containerColor = if (isSelected) NuvioColors.Primary.copy(alpha = 0.2f) else NuvioColors.BackgroundElevated,
                                focusedContainerColor = NuvioColors.FocusBackground
                            ),
                            border = CardDefaults.border(
                                focusedBorder = Border(
                                    border = BorderStroke(2.dp, NuvioColors.FocusRing),
                                    shape = androidx.compose.foundation.shape.RoundedCornerShape(8.dp)
                                )
                            ),
                            shape = CardDefaults.shape(shape = androidx.compose.foundation.shape.RoundedCornerShape(8.dp)),
                            scale = CardDefaults.scale(focusedScale = 1.02f)
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
                                    color = if (isSelected) NuvioColors.Primary else NuvioColors.TextPrimary,
                                    modifier = Modifier.weight(1f)
                                )

                                if (isSelected) {
                                    Icon(
                                        imageVector = Icons.Default.Check,
                                        contentDescription = "Selected",
                                        tint = NuvioColors.Primary,
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
}

@Composable
private fun StreamAutoPlaySourceDialog(
    selectedSource: StreamAutoPlaySource,
    onSourceSelected: (StreamAutoPlaySource) -> Unit,
    onDismiss: () -> Unit
) {
    val focusRequester = remember { FocusRequester() }
    val options = listOf(
        Triple(
            StreamAutoPlaySource.ALL_SOURCES,
            "All sources",
            "Auto-play can use both installed addons and enabled plugins."
        ),
        Triple(
            StreamAutoPlaySource.INSTALLED_ADDONS_ONLY,
            "Installed addons only",
            "Auto-play only considers streams coming from your installed addons."
        ),
        Triple(
            StreamAutoPlaySource.ENABLED_PLUGINS_ONLY,
            "Enabled plugins only",
            "Auto-play only considers streams coming from enabled plugins."
        )
    )

    LaunchedEffect(Unit) {
        focusRequester.requestFocus()
    }

    androidx.compose.ui.window.Dialog(onDismissRequest = onDismiss) {
        Box(
            modifier = Modifier
                .clip(androidx.compose.foundation.shape.RoundedCornerShape(16.dp))
                .background(NuvioColors.BackgroundCard)
        ) {
            Column(
                modifier = Modifier
                    .width(520.dp)
                    .padding(24.dp)
            ) {
                Text(
                    text = "Auto-play Source Scope",
                    style = MaterialTheme.typography.headlineSmall,
                    color = NuvioColors.TextPrimary
                )
                Spacer(modifier = Modifier.height(16.dp))

                LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(options.size) { index ->
                        val (source, title, description) = options[index]
                        val isSelected = source == selectedSource

                        Card(
                            onClick = { onSourceSelected(source) },
                            modifier = Modifier
                                .fillMaxWidth()
                                .then(if (index == 0) Modifier.focusRequester(focusRequester) else Modifier),
                            colors = CardDefaults.colors(
                                containerColor = if (isSelected) NuvioColors.Primary.copy(alpha = 0.2f) else NuvioColors.BackgroundElevated,
                                focusedContainerColor = NuvioColors.FocusBackground
                            ),
                            border = CardDefaults.border(
                                focusedBorder = Border(
                                    border = BorderStroke(2.dp, NuvioColors.FocusRing),
                                    shape = androidx.compose.foundation.shape.RoundedCornerShape(8.dp)
                                )
                            ),
                            shape = CardDefaults.shape(shape = androidx.compose.foundation.shape.RoundedCornerShape(8.dp)),
                            scale = CardDefaults.scale(focusedScale = 1.02f)
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
                                        color = if (isSelected) NuvioColors.Primary else NuvioColors.TextPrimary,
                                        style = MaterialTheme.typography.bodyLarge
                                    )
                                    Spacer(modifier = Modifier.height(4.dp))
                                    Text(
                                        text = description,
                                        color = NuvioColors.TextSecondary,
                                        style = MaterialTheme.typography.bodySmall
                                    )
                                }
                                if (isSelected) {
                                    Spacer(modifier = Modifier.width(12.dp))
                                    Icon(
                                        imageVector = Icons.Default.Check,
                                        contentDescription = "Selected",
                                        tint = NuvioColors.Primary,
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
}

@Composable
private fun StreamAutoPlayProviderSelectionDialog(
    title: String,
    allLabel: String,
    items: List<String>,
    selectedItems: Set<String>,
    onSelectionSaved: (Set<String>) -> Unit,
    onDismiss: () -> Unit
) {
    var selected by remember(selectedItems, items) {
        mutableStateOf(selectedItems.intersect(items.toSet()))
    }
    val focusRequester = remember { FocusRequester() }

    LaunchedEffect(Unit) {
        focusRequester.requestFocus()
    }

    androidx.compose.ui.window.Dialog(
        onDismissRequest = {
            onSelectionSaved(selected)
            onDismiss()
        }
    ) {
        Box(
            modifier = Modifier
                .clip(androidx.compose.foundation.shape.RoundedCornerShape(16.dp))
                .background(NuvioColors.BackgroundCard)
        ) {
            Column(
                modifier = Modifier
                    .width(560.dp)
                    .padding(24.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.headlineSmall,
                    color = NuvioColors.TextPrimary
                )

                Card(
                    onClick = { selected = emptySet() },
                    modifier = Modifier
                        .fillMaxWidth()
                        .focusRequester(focusRequester),
                    colors = CardDefaults.colors(
                        containerColor = if (selected.isEmpty()) NuvioColors.Primary.copy(alpha = 0.2f) else NuvioColors.BackgroundElevated,
                        focusedContainerColor = NuvioColors.FocusBackground
                    ),
                    border = CardDefaults.border(
                        focusedBorder = Border(
                            border = BorderStroke(2.dp, NuvioColors.FocusRing),
                            shape = androidx.compose.foundation.shape.RoundedCornerShape(8.dp)
                        )
                    ),
                    shape = CardDefaults.shape(shape = androidx.compose.foundation.shape.RoundedCornerShape(8.dp)),
                    scale = CardDefaults.scale(focusedScale = 1.02f)
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(14.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = allLabel,
                            color = if (selected.isEmpty()) NuvioColors.Primary else NuvioColors.TextPrimary,
                            style = MaterialTheme.typography.bodyLarge,
                            modifier = Modifier.weight(1f)
                        )
                        if (selected.isEmpty()) {
                            Icon(
                                imageVector = Icons.Default.Check,
                                contentDescription = "Selected",
                                tint = NuvioColors.Primary,
                                modifier = Modifier.height(20.dp)
                            )
                        }
                    }
                }

                if (items.isEmpty()) {
                    Text(
                        text = "No items available",
                        style = MaterialTheme.typography.bodySmall,
                        color = NuvioColors.TextSecondary
                    )
                } else {
                    LazyColumn(
                        modifier = Modifier.height(320.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        items(items) { item ->
                            val isSelected = item in selected
                            Card(
                                onClick = {
                                    selected = if (isSelected) {
                                        selected - item
                                    } else {
                                        selected + item
                                    }
                                },
                                modifier = Modifier.fillMaxWidth(),
                                colors = CardDefaults.colors(
                                    containerColor = if (isSelected) NuvioColors.Primary.copy(alpha = 0.2f) else NuvioColors.BackgroundElevated,
                                    focusedContainerColor = NuvioColors.FocusBackground
                                ),
                                border = CardDefaults.border(
                                    focusedBorder = Border(
                                        border = BorderStroke(2.dp, NuvioColors.FocusRing),
                                        shape = androidx.compose.foundation.shape.RoundedCornerShape(8.dp)
                                    )
                                ),
                                shape = CardDefaults.shape(shape = androidx.compose.foundation.shape.RoundedCornerShape(8.dp)),
                                scale = CardDefaults.scale(focusedScale = 1.02f)
                            ) {
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(14.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(
                                        text = item,
                                        color = if (isSelected) NuvioColors.Primary else NuvioColors.TextPrimary,
                                        style = MaterialTheme.typography.bodyMedium,
                                        modifier = Modifier.weight(1f)
                                    )
                                    if (isSelected) {
                                        Icon(
                                            imageVector = Icons.Default.Check,
                                            contentDescription = "Selected",
                                            tint = NuvioColors.Primary,
                                            modifier = Modifier.height(18.dp)
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun StreamRegexDialog(
    initialRegex: String,
    onSave: (String) -> Unit,
    onDismiss: () -> Unit
) {
    var regex by remember(initialRegex) { mutableStateOf(initialRegex) }
    var regexError by remember { mutableStateOf<String?>(null) }
    var isInputFocused by remember { mutableStateOf(false) }
    val inputFocusRequester = remember { FocusRequester() }
    val keyboardController = LocalSoftwareKeyboardController.current
    val presets = remember {
        listOf(
            "Any 1080p+" to "(2160p|4k|1080p)",
            "4K / Remux" to "(2160p|4k|remux)",
            "1080p Standard" to "(1080p|full\\s*hd)",
            "720p / Smaller" to "(720p|webrip|web-dl)",
            "WEB Sources" to "(web[-\\s]?dl|webrip)",
            "BluRay Quality" to "(bluray|b[dr]rip|remux)",
            "HEVC / x265" to "(hevc|x265|h\\.265)",
            "AVC / x264" to "(x264|h\\.264|avc)",
            "HDR / Dolby Vision" to "(hdr|hdr10\\+?|dv|dolby\\s*vision)",
            "Dolby Atmos / DTS" to "(atmos|truehd|dts[-\\s]?hd|dtsx?)",
            "English" to "(\\beng\\b|english)",
            "No CAM/TS" to "^(?!.*\\b(cam|hdcam|ts|telesync)\\b).*$"
        )
    }

    LaunchedEffect(Unit) {
        inputFocusRequester.requestFocus()
    }

    androidx.compose.ui.window.Dialog(onDismissRequest = onDismiss) {
        Box(
            modifier = Modifier
                .clip(androidx.compose.foundation.shape.RoundedCornerShape(16.dp))
                .background(NuvioColors.BackgroundCard)
        ) {
            Column(
                modifier = Modifier
                    .width(700.dp)
                    .padding(24.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Text(
                    text = "Stream Regex Pattern",
                    style = MaterialTheme.typography.headlineSmall,
                    color = NuvioColors.TextPrimary
                )
                Text(
                    text = "Matches against stream name/title/description/addon/url. Example: 4K|2160p|Remux",
                    style = MaterialTheme.typography.bodySmall,
                    color = NuvioColors.TextSecondary
                )

                Text(
                    text = "Presets",
                    style = MaterialTheme.typography.titleSmall,
                    color = NuvioColors.TextSecondary
                )

                LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(presets) { (label, pattern) ->
                        var isFocused by remember { mutableStateOf(false) }
                        Card(
                            onClick = {
                                regex = pattern
                                regexError = null
                            },
                            modifier = Modifier.onFocusChanged { isFocused = it.isFocused },
                            colors = CardDefaults.colors(
                                containerColor = NuvioColors.BackgroundElevated,
                                focusedContainerColor = NuvioColors.FocusBackground
                            ),
                            border = CardDefaults.border(
                                focusedBorder = Border(
                                    border = BorderStroke(2.dp, NuvioColors.FocusRing),
                                    shape = androidx.compose.foundation.shape.RoundedCornerShape(20.dp)
                                )
                            ),
                            shape = CardDefaults.shape(androidx.compose.foundation.shape.RoundedCornerShape(20.dp)),
                            scale = CardDefaults.scale(focusedScale = 1.02f)
                        ) {
                            Text(
                                text = label,
                                modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp),
                                style = MaterialTheme.typography.labelLarge,
                                color = if (isFocused) NuvioColors.Primary else NuvioColors.TextPrimary
                            )
                        }
                    }
                }

                Card(
                    onClick = { inputFocusRequester.requestFocus() },
                    modifier = Modifier
                        .fillMaxWidth()
                        .onFocusChanged { isInputFocused = it.isFocused || it.hasFocus },
                    colors = CardDefaults.colors(
                        containerColor = NuvioColors.BackgroundElevated,
                        focusedContainerColor = NuvioColors.BackgroundElevated
                    ),
                    border = CardDefaults.border(
                        border = Border(
                            border = BorderStroke(1.dp, NuvioColors.Border),
                            shape = androidx.compose.foundation.shape.RoundedCornerShape(10.dp)
                        ),
                        focusedBorder = Border(
                            border = BorderStroke(2.dp, NuvioColors.FocusRing),
                            shape = androidx.compose.foundation.shape.RoundedCornerShape(10.dp)
                        )
                    ),
                    shape = CardDefaults.shape(androidx.compose.foundation.shape.RoundedCornerShape(10.dp)),
                    scale = CardDefaults.scale(focusedScale = 1f)
                ) {
                    Box(modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp)) {
                        BasicTextField(
                            value = regex,
                            onValueChange = {
                                regex = it
                                regexError = null
                            },
                            modifier = Modifier
                                .fillMaxWidth()
                                .focusRequester(inputFocusRequester)
                                .onKeyEvent { keyEvent ->
                                    keyEvent.nativeKeyEvent.keyCode == KeyEvent.KEYCODE_DPAD_CENTER &&
                                        keyEvent.nativeKeyEvent.action == KeyEvent.ACTION_DOWN
                                },
                            singleLine = true,
                            keyboardOptions = KeyboardOptions(
                                keyboardType = KeyboardType.Text,
                                imeAction = ImeAction.Done
                            ),
                            keyboardActions = KeyboardActions(
                                onDone = { keyboardController?.hide() }
                            ),
                            textStyle = MaterialTheme.typography.bodyMedium.copy(color = NuvioColors.TextPrimary),
                            cursorBrush = SolidColor(if (isInputFocused) NuvioColors.Primary else Color.Transparent),
                            decorationBox = { innerTextField ->
                                if (regex.isBlank()) {
                                    Text(
                                        text = "4K|2160p|Remux",
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = NuvioColors.TextTertiary
                                    )
                                }
                                innerTextField()
                            }
                        )
                    }
                }

                if (regexError != null) {
                    Text(
                        text = regexError ?: "",
                        color = NuvioColors.Error,
                        style = MaterialTheme.typography.bodySmall
                    )
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End
                ) {
                    Button(
                        onClick = onDismiss,
                        colors = ButtonDefaults.colors(
                            containerColor = NuvioColors.BackgroundElevated,
                            contentColor = NuvioColors.TextPrimary,
                            focusedContainerColor = NuvioColors.FocusBackground,
                            focusedContentColor = NuvioColors.Primary
                        ),
                        shape = ButtonDefaults.shape(androidx.compose.foundation.shape.RoundedCornerShape(10.dp))
                    ) {
                        Text("Cancel")
                    }
                    Spacer(modifier = Modifier.width(8.dp))
                    Button(
                        onClick = {
                            regex = ""
                            regexError = null
                        },
                        colors = ButtonDefaults.colors(
                            containerColor = NuvioColors.BackgroundElevated,
                            contentColor = NuvioColors.TextPrimary,
                            focusedContainerColor = NuvioColors.FocusBackground,
                            focusedContentColor = NuvioColors.Primary
                        ),
                        shape = ButtonDefaults.shape(androidx.compose.foundation.shape.RoundedCornerShape(10.dp))
                    ) {
                        Text("Clear")
                    }
                    Spacer(modifier = Modifier.width(8.dp))
                    Button(
                        onClick = {
                            val value = regex.trim()
                            if (value.isNotEmpty()) {
                                val valid = runCatching { Regex(value, RegexOption.IGNORE_CASE) }.isSuccess
                                if (!valid) {
                                    regexError = "Invalid regex pattern"
                                    return@Button
                                }
                            }
                            onSave(value)
                        },
                        colors = ButtonDefaults.colors(
                            containerColor = NuvioColors.BackgroundCard,
                            contentColor = NuvioColors.TextPrimary,
                            focusedContainerColor = NuvioColors.FocusBackground,
                            focusedContentColor = NuvioColors.Primary
                        ),
                        shape = ButtonDefaults.shape(androidx.compose.foundation.shape.RoundedCornerShape(10.dp))
                    ) {
                        Text("Save")
                    }
                }
            }
        }
    }
}
