@file:OptIn(ExperimentalTvMaterial3Api::class)

package com.nuvio.tv.ui.screens.player

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.border
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.FormatSize
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material.icons.filled.Tune
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
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
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.tv.foundation.lazy.list.TvLazyColumn
import androidx.tv.foundation.lazy.list.items
import androidx.tv.material3.Card
import androidx.tv.material3.CardDefaults
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.Icon
import androidx.tv.material3.IconButton
import androidx.tv.material3.IconButtonDefaults
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.nuvio.tv.data.local.SubtitleStyleSettings
import com.nuvio.tv.domain.model.Subtitle
import com.nuvio.tv.ui.components.LoadingIndicator
import com.nuvio.tv.ui.theme.NuvioColors
import com.nuvio.tv.ui.theme.NuvioTheme

// Subtitle text color options (matching mobile app)
private val SUBTITLE_TEXT_COLORS = listOf(
    Color.White,
    Color(0xFFFFD700),  // Yellow/Gold
    Color(0xFF00E5FF),  // Cyan
    Color(0xFFFF5C5C),  // Red
    Color(0xFF00FF88),  // Green
)

// Subtitle outline color options
private val SUBTITLE_OUTLINE_COLORS = listOf(
    Color.Black,
    Color.White,
    Color(0xFF00E5FF),  // Cyan
    Color(0xFFFF5C5C),  // Red
)

@Composable
internal fun SubtitleSelectionDialog(
    internalTracks: List<TrackInfo>,
    selectedInternalIndex: Int,
    addonSubtitles: List<Subtitle>,
    selectedAddonSubtitle: Subtitle?,
    isLoadingAddons: Boolean,
    subtitleStyle: SubtitleStyleSettings,
    onInternalTrackSelected: (Int) -> Unit,
    onAddonSubtitleSelected: (Subtitle) -> Unit,
    onDisableSubtitles: () -> Unit,
    onStyleEvent: (PlayerEvent) -> Unit,
    onDismiss: () -> Unit
) {
    var selectedTabIndex by remember { mutableIntStateOf(0) }
    val tabs = listOf("Built-in", "Addons", "Style")
    val tabFocusRequesters = remember { tabs.map { FocusRequester() } }

    Dialog(onDismissRequest = onDismiss) {
        Box(
            modifier = Modifier
                .width(450.dp)
                .clip(RoundedCornerShape(24.dp))
                .background(Color(0xFF0F0F0F))
        ) {
            Column(
                modifier = Modifier.padding(24.dp)
            ) {
                Text(
                    text = "Subtitles",
                    style = MaterialTheme.typography.headlineSmall,
                    color = Color.White,
                    modifier = Modifier.padding(bottom = 16.dp)
                )

                // Tab row
                Row(
                    horizontalArrangement = Arrangement.Center,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 16.dp)
                ) {
                    tabs.forEachIndexed { index, _ ->
                        SubtitleTab(
                            title = tabs[index],
                            isSelected = selectedTabIndex == index,
                            badgeCount = if (index == 1) addonSubtitles.size else null,
                            focusRequester = tabFocusRequesters[index],
                            onClick = { selectedTabIndex = index }
                        )
                        if (index < tabs.lastIndex) {
                            Spacer(modifier = Modifier.width(8.dp))
                        }
                    }
                }

                // Content based on selected tab
                when (selectedTabIndex) {
                    0 -> InternalSubtitlesContent(
                        tracks = internalTracks,
                        selectedIndex = selectedInternalIndex,
                        selectedAddonSubtitle = selectedAddonSubtitle,
                        onTrackSelected = onInternalTrackSelected,
                        onDisableSubtitles = onDisableSubtitles
                    )
                    1 -> AddonSubtitlesContent(
                        subtitles = addonSubtitles,
                        selectedSubtitle = selectedAddonSubtitle,
                        isLoading = isLoadingAddons,
                        onSubtitleSelected = onAddonSubtitleSelected
                    )
                    2 -> SubtitleStyleContent(
                        subtitleStyle = subtitleStyle,
                        onEvent = onStyleEvent
                    )
                }
            }
        }
    }

    // Request focus on the first tab when dialog opens
    LaunchedEffect(Unit) {
        kotlinx.coroutines.delay(100)
        try {
            tabFocusRequesters[0].requestFocus()
        } catch (_: Exception) {}
    }
}

@Composable
private fun SubtitleTab(
    title: String,
    isSelected: Boolean,
    badgeCount: Int?,
    focusRequester: FocusRequester,
    onClick: () -> Unit
) {
    var isFocused by remember { mutableStateOf(false) }

    Card(
        onClick = onClick,
        modifier = Modifier
            .focusRequester(focusRequester)
            .onFocusChanged { isFocused = it.isFocused },
        colors = CardDefaults.colors(
            containerColor = when {
                isSelected -> Color.White.copy(alpha = 0.18f)
                isFocused -> Color.White.copy(alpha = 0.12f)
                else -> Color.White.copy(alpha = 0.06f)
            },
            focusedContainerColor = if (isSelected) Color.White.copy(alpha = 0.22f) else Color.White.copy(alpha = 0.12f)
        ),
        shape = CardDefaults.shape(RoundedCornerShape(12.dp))
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyMedium,
                color = if (isSelected) Color.White else Color.White.copy(alpha = 0.7f)
            )

            if (badgeCount != null && badgeCount > 0) {
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(10.dp))
                        .background(if (isSelected) Color.White.copy(alpha = 0.2f) else NuvioColors.Secondary)
                        .padding(horizontal = 6.dp, vertical = 2.dp)
                ) {
                    Text(
                        text = badgeCount.toString(),
                        style = MaterialTheme.typography.labelSmall,
                        color = Color.White
                    )
                }
            }
        }
    }
}

@Composable
private fun InternalSubtitlesContent(
    tracks: List<TrackInfo>,
    selectedIndex: Int,
    selectedAddonSubtitle: Subtitle?,
    onTrackSelected: (Int) -> Unit,
    onDisableSubtitles: () -> Unit
) {
    TvLazyColumn(
        verticalArrangement = Arrangement.spacedBy(8.dp),
        contentPadding = PaddingValues(top = 4.dp),
        modifier = Modifier.height(300.dp)
    ) {
        item {
            TrackItem(
                track = TrackInfo(index = -1, name = "None", language = null),
                isSelected = selectedIndex == -1 && selectedAddonSubtitle == null,
                onClick = onDisableSubtitles
            )
        }

        if (tracks.isEmpty()) {
            item {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 32.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = "No built-in subtitles available",
                        style = MaterialTheme.typography.bodyMedium,
                        color = Color.White.copy(alpha = 0.5f)
                    )
                }
            }
        } else {
            items(tracks) { track ->
                TrackItem(
                    track = track,
                    isSelected = track.index == selectedIndex && selectedAddonSubtitle == null,
                    onClick = { onTrackSelected(track.index) }
                )
            }
        }
    }
}

@Composable
private fun AddonSubtitlesContent(
    subtitles: List<Subtitle>,
    selectedSubtitle: Subtitle?,
    isLoading: Boolean,
    onSubtitleSelected: (Subtitle) -> Unit
) {
    TvLazyColumn(
        verticalArrangement = Arrangement.spacedBy(8.dp),
        contentPadding = PaddingValues(top = 4.dp),
        modifier = Modifier.height(300.dp)
    ) {
        if (isLoading) {
            item {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 32.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        LoadingIndicator(modifier = Modifier.size(24.dp))
                        Text(
                            text = "Loading subtitles from addons...",
                            style = MaterialTheme.typography.bodyMedium,
                            color = Color.White.copy(alpha = 0.5f)
                        )
                    }
                }
            }
        } else if (subtitles.isEmpty()) {
            item {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 32.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = "No addon subtitles available",
                        style = MaterialTheme.typography.bodyMedium,
                        color = Color.White.copy(alpha = 0.5f)
                    )
                }
            }
        } else {
            items(subtitles) { subtitle ->
                AddonSubtitleItem(
                    subtitle = subtitle,
                    isSelected = selectedSubtitle?.id == subtitle.id,
                    onClick = { onSubtitleSelected(subtitle) }
                )
            }
        }
    }
}

@Composable
private fun AddonSubtitleItem(
    subtitle: Subtitle,
    isSelected: Boolean,
    onClick: () -> Unit
) {
    var isFocused by remember { mutableStateOf(false) }

    Card(
        onClick = onClick,
        modifier = Modifier
            .fillMaxWidth()
            .onFocusChanged { isFocused = it.isFocused },
        colors = CardDefaults.colors(
            containerColor = when {
                isSelected -> Color.White.copy(alpha = 0.12f)
                else -> Color.White.copy(alpha = 0.05f)
            },
            focusedContainerColor = Color.White.copy(alpha = 0.15f)
        ),
        shape = CardDefaults.shape(RoundedCornerShape(12.dp))
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = subtitle.getDisplayLanguage(),
                    style = MaterialTheme.typography.bodyMedium,
                    color = Color.White
                )
                Text(
                    text = subtitle.addonName,
                    style = MaterialTheme.typography.bodySmall,
                    color = Color.White.copy(alpha = 0.5f)
                )
            }

            if (isSelected) {
                Icon(
                    imageVector = Icons.Default.Check,
                    contentDescription = "Selected",
                    tint = NuvioColors.Secondary,
                    modifier = Modifier.size(20.dp)
                )
            }
        }
    }
}

// -- Style Tab (matching mobile grouped section layout) --

@Composable
private fun SubtitleStyleContent(
    subtitleStyle: SubtitleStyleSettings,
    onEvent: (PlayerEvent) -> Unit
) {
    TvLazyColumn(
        verticalArrangement = Arrangement.spacedBy(16.dp),
        contentPadding = PaddingValues(top = 4.dp),
        modifier = Modifier.height(340.dp)
    ) {
        // Core section
        item {
            StyleSection(
                title = "Core",
                icon = Icons.Default.FormatSize
            ) {
                // Font Size
                StyleSettingRow(label = "Font Size") {
                    StyleStepperButton(
                        icon = Icons.Default.Remove,
                        onClick = { onEvent(PlayerEvent.OnSetSubtitleSize(subtitleStyle.size - 10)) }
                    )
                    StyleValueDisplay(text = "${subtitleStyle.size}%")
                    StyleStepperButton(
                        icon = Icons.Default.Add,
                        onClick = { onEvent(PlayerEvent.OnSetSubtitleSize(subtitleStyle.size + 10)) }
                    )
                }

                Spacer(modifier = Modifier.height(12.dp))

                // Bold
                StyleSettingRow(label = "Bold") {
                    StyleToggleButton(
                        isEnabled = subtitleStyle.bold,
                        onClick = { onEvent(PlayerEvent.OnSetSubtitleBold(!subtitleStyle.bold)) }
                    )
                }
            }
        }

        // Advanced section
        item {
            StyleSection(
                title = "Advanced",
                icon = Icons.Default.Tune
            ) {
                // Text Color
                Column {
                    Text(
                        text = "Text Color",
                        style = MaterialTheme.typography.bodyMedium,
                        color = Color.White,
                        modifier = Modifier.padding(bottom = 8.dp)
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        SUBTITLE_TEXT_COLORS.forEach { color ->
                            StyleColorChip(
                                color = color,
                                isSelected = subtitleStyle.textColor == color.toArgb(),
                                onClick = { onEvent(PlayerEvent.OnSetSubtitleTextColor(color.toArgb())) }
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.height(12.dp))

                // Outline
                StyleSettingRow(label = "Outline") {
                    StyleToggleButton(
                        isEnabled = subtitleStyle.outlineEnabled,
                        onClick = { onEvent(PlayerEvent.OnSetSubtitleOutlineEnabled(!subtitleStyle.outlineEnabled)) }
                    )
                }

                // Outline Color (only when outline enabled)
                if (subtitleStyle.outlineEnabled) {
                    Spacer(modifier = Modifier.height(12.dp))

                    Column {
                        Text(
                            text = "Outline Color",
                            style = MaterialTheme.typography.bodyMedium,
                            color = Color.White,
                            modifier = Modifier.padding(bottom = 8.dp)
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            SUBTITLE_OUTLINE_COLORS.forEach { color ->
                                StyleColorChip(
                                    color = color,
                                    isSelected = subtitleStyle.outlineColor == color.toArgb(),
                                    onClick = { onEvent(PlayerEvent.OnSetSubtitleOutlineColor(color.toArgb())) }
                                )
                            }
                        }
                    }
                }

                Spacer(modifier = Modifier.height(12.dp))

                // Bottom Offset
                StyleSettingRow(label = "Bottom Offset") {
                    StyleStepperButton(
                        icon = Icons.Default.Remove,
                        onClick = { onEvent(PlayerEvent.OnSetSubtitleVerticalOffset(subtitleStyle.verticalOffset - 5)) }
                    )
                    StyleValueDisplay(text = "${subtitleStyle.verticalOffset}")
                    StyleStepperButton(
                        icon = Icons.Default.Add,
                        onClick = { onEvent(PlayerEvent.OnSetSubtitleVerticalOffset(subtitleStyle.verticalOffset + 5)) }
                    )
                }
            }
        }

        // Reset Defaults
        item {
            var isFocused by remember { mutableStateOf(false) }
            Card(
                onClick = { onEvent(PlayerEvent.OnResetSubtitleDefaults) },
                modifier = Modifier.onFocusChanged { isFocused = it.isFocused },
                colors = CardDefaults.colors(
                    containerColor = Color.White.copy(alpha = 0.1f),
                    focusedContainerColor = Color.White.copy(alpha = 0.18f)
                ),
                shape = CardDefaults.shape(RoundedCornerShape(12.dp))
            ) {
                Text(
                    text = "Reset Defaults",
                    style = MaterialTheme.typography.bodyMedium,
                    color = Color.White.copy(alpha = 0.7f),
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp)
                )
            }
        }
    }
}

@Composable
private fun StyleSection(
    title: String,
    icon: ImageVector,
    content: @Composable () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(Color.White.copy(alpha = 0.05f))
            .padding(16.dp)
    ) {
        // Section header
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.padding(bottom = 14.dp)
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = Color.White.copy(alpha = 0.7f),
                modifier = Modifier.size(18.dp)
            )
            Text(
                text = title,
                style = MaterialTheme.typography.labelLarge,
                color = Color.White.copy(alpha = 0.7f)
            )
        }

        content()
    }
}

@Composable
private fun StyleSettingRow(
    label: String,
    content: @Composable () -> Unit
) {
    Column(
        modifier = Modifier.fillMaxWidth()
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            color = Color.White,
            modifier = Modifier.padding(bottom = 8.dp)
        )
        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            content()
        }
    }
}

@Composable
private fun StyleStepperButton(
    icon: ImageVector,
    onClick: () -> Unit
) {
    IconButton(
        onClick = onClick,
        modifier = Modifier.size(36.dp),
        colors = IconButtonDefaults.colors(
            containerColor = Color.White.copy(alpha = 0.18f),
            focusedContainerColor = Color.White.copy(alpha = 0.3f),
            contentColor = Color.White,
            focusedContentColor = Color.White
        ),
        shape = IconButtonDefaults.shape(shape = RoundedCornerShape(12.dp))
    ) {
        Icon(icon, null, modifier = Modifier.size(18.dp))
    }
}

@Composable
private fun StyleValueDisplay(text: String) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(10.dp))
            .background(Color.White.copy(alpha = 0.12f))
            .padding(horizontal = 14.dp, vertical = 6.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.bodyMedium,
            color = Color.White
        )
    }
}

@Composable
private fun StyleColorChip(
    color: Color,
    isSelected: Boolean,
    onClick: () -> Unit
) {
    val isLight = (color.red + color.green + color.blue) / 3f > 0.5f
    var isFocused by remember { mutableStateOf(false) }

    val borderModifier = when {
        isFocused -> Modifier.border(2.dp, NuvioColors.FocusRing, CircleShape)
        isSelected -> Modifier.border(2.dp, Color.White, CircleShape)
        else -> Modifier
    }

    IconButton(
        onClick = onClick,
        modifier = Modifier
            .size(36.dp)
            .then(borderModifier)
            .onFocusChanged { isFocused = it.isFocused },
        colors = IconButtonDefaults.colors(
            containerColor = color,
            focusedContainerColor = color,
            contentColor = if (isLight) Color.Black else Color.White,
            focusedContentColor = if (isLight) Color.Black else Color.White
        ),
        shape = IconButtonDefaults.shape(shape = CircleShape)
    ) {
        if (isSelected) {
            Icon(Icons.Default.Check, "Selected", modifier = Modifier.size(18.dp))
        }
    }
}

@Composable
private fun StyleToggleButton(
    isEnabled: Boolean,
    onClick: () -> Unit
) {
    var isFocused by remember { mutableStateOf(false) }
    Card(
        onClick = onClick,
        modifier = Modifier.onFocusChanged { isFocused = it.isFocused },
        colors = CardDefaults.colors(
            containerColor = if (isEnabled) Color.White.copy(alpha = 0.18f) else Color.White.copy(alpha = 0.08f),
            focusedContainerColor = Color.White.copy(alpha = 0.25f)
        ),
        shape = CardDefaults.shape(RoundedCornerShape(10.dp))
    ) {
        Text(
            text = if (isEnabled) "On" else "Off",
            style = MaterialTheme.typography.bodyMedium,
            color = if (isEnabled) Color.White else Color.White.copy(alpha = 0.5f),
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
        )
    }
}

// Shared TrackItem composable (used by both audio TrackSelectionDialog and subtitle dialog)
@Composable
internal fun TrackItem(
    track: TrackInfo,
    isSelected: Boolean,
    onClick: () -> Unit
) {
    var isFocused by remember { mutableStateOf(false) }

    Card(
        onClick = onClick,
        modifier = Modifier
            .fillMaxWidth()
            .onFocusChanged { isFocused = it.isFocused },
        colors = CardDefaults.colors(
            containerColor = if (isSelected) NuvioColors.Secondary.copy(alpha = 0.2f) else NuvioColors.BackgroundCard,
            focusedContainerColor = NuvioColors.FocusBackground
        ),
        shape = CardDefaults.shape(shape = RoundedCornerShape(8.dp))
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column {
                Text(
                    text = track.name,
                    style = MaterialTheme.typography.bodyLarge,
                    color = if (isSelected) NuvioColors.Secondary else NuvioColors.TextPrimary
                )
                if (track.language != null) {
                    Text(
                        text = track.language.uppercase(),
                        style = MaterialTheme.typography.labelSmall,
                        color = NuvioTheme.extendedColors.textSecondary
                    )
                }
            }

            if (isSelected) {
                Icon(
                    imageVector = Icons.Default.Check,
                    contentDescription = "Selected",
                    tint = NuvioColors.Secondary,
                    modifier = Modifier.size(24.dp)
                )
            }
        }
    }
}
