@file:OptIn(ExperimentalTvMaterial3Api::class)

package com.nexio.tv.ui.screens.player

import android.view.KeyEvent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.ui.text.style.TextOverflow
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
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.tv.material3.Card
import androidx.tv.material3.CardDefaults
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.Icon
import androidx.tv.material3.IconButton
import androidx.tv.material3.IconButtonDefaults
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.nexio.tv.data.local.SubtitleStyleSettings
import com.nexio.tv.domain.model.Subtitle
import com.nexio.tv.ui.components.LoadingIndicator
import com.nexio.tv.ui.theme.NexioColors
import androidx.compose.ui.res.stringResource
import com.nexio.tv.R

// Subtitle text color options (matching mobile app)
private val SUBTITLE_TEXT_COLORS = listOf(
    Color.White,
    Color(0xFFD9D9D9),  // Light gray
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
    preferredLanguage: String,
    secondaryPreferredLanguage: String?,
    isLoadingAddons: Boolean,
    aiSubtitlesEnabled: Boolean,
    aiSubtitlesAvailable: Boolean,
    isAiSubtitleTranslating: Boolean,
    aiSubtitleError: String?,
    onInternalTrackSelected: (Int) -> Unit,
    onAddonSubtitleSelected: (Subtitle) -> Unit,
    onDisableSubtitles: () -> Unit,
    onOpenDelayOverlay: () -> Unit,
    onToggleAiSubtitles: () -> Unit,
    onDismiss: () -> Unit
) {
    var selectedTabIndex by remember { mutableIntStateOf(0) }
    val tabs = listOf(
        stringResource(R.string.subtitle_tab_subtitles),
        stringResource(R.string.subtitle_tab_delay)
    )
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
                    text = stringResource(R.string.subtitle_dialog_title),
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
                        val onTabClick = when (index) {
                            1 -> { { onOpenDelayOverlay() } }
                            else -> { { selectedTabIndex = index } }
                        }
                        SubtitleTab(
                            title = tabs[index],
                            isSelected = selectedTabIndex == index,
                            badgeCount = null,
                            focusRequester = tabFocusRequesters[index],
                            onClick = onTabClick
                        )
                        if (index < tabs.lastIndex) {
                            Spacer(modifier = Modifier.width(8.dp))
                        }
                    }
                }

                if (isAiSubtitleTranslating) {
                    Text(
                        text = stringResource(R.string.subtitle_ai_translate_loading),
                        style = MaterialTheme.typography.bodySmall,
                        color = NexioColors.TextSecondary,
                        modifier = Modifier.padding(bottom = 10.dp)
                    )
                } else if (!aiSubtitleError.isNullOrBlank()) {
                    Text(
                        text = aiSubtitleError,
                        style = MaterialTheme.typography.bodySmall,
                        color = Color(0xFFFFA8A8),
                        modifier = Modifier.padding(bottom = 10.dp)
                    )
                }

                // Content based on selected tab
                when (selectedTabIndex) {
                    0 -> SubtitleSourcesContent(
                        internalTracks = internalTracks,
                        selectedInternalIndex = selectedInternalIndex,
                        addonSubtitles = addonSubtitles,
                        selectedSubtitle = selectedAddonSubtitle,
                        preferredLanguage = preferredLanguage,
                        secondaryPreferredLanguage = secondaryPreferredLanguage,
                        isLoading = isLoadingAddons,
                        aiSubtitlesEnabled = aiSubtitlesEnabled,
                        aiSubtitlesAvailable = aiSubtitlesAvailable,
                        onInternalTrackSelected = onInternalTrackSelected,
                        onDisableSubtitles = onDisableSubtitles,
                        onSubtitleSelected = onAddonSubtitleSelected,
                        onToggleAiSubtitles = onToggleAiSubtitles
                    )
                    else -> Unit
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
                color = if (isSelected) Color.White else Color.White.copy(alpha = 0.7f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )

            if (badgeCount != null && badgeCount > 0) {
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(10.dp))
                        .background(if (isSelected) Color.White.copy(alpha = 0.2f) else NexioColors.Secondary)
                        .padding(horizontal = 6.dp, vertical = 2.dp)
                ) {
                    Text(
                        text = badgeCount.toString(),
                        style = MaterialTheme.typography.labelSmall,
                        color = if (isSelected) Color.White else NexioColors.OnSecondary
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
    preferredLanguage: String,
    secondaryPreferredLanguage: String?,
    onTrackSelected: (Int) -> Unit,
    onDisableSubtitles: () -> Unit,
    firstTrackFocusRequester: FocusRequester? = null
) {
    val orderedTracks = remember(tracks, preferredLanguage, secondaryPreferredLanguage) {
        sortByPreferredLanguages(
            items = tracks,
            preferredLanguages = listOfNotNull(preferredLanguage, secondaryPreferredLanguage)
        ) { it.language }
    }

    LazyColumn(
        verticalArrangement = Arrangement.spacedBy(8.dp),
        contentPadding = PaddingValues(top = 4.dp),
        modifier = Modifier.height(300.dp)
    ) {
        item {
            TrackItem(
                track = TrackInfo(index = -1, name = stringResource(R.string.subtitle_none), language = null),
                isSelected = selectedIndex == -1 && selectedAddonSubtitle == null,
                onClick = onDisableSubtitles,
                focusRequester = if (selectedIndex == -1 || tracks.isEmpty()) firstTrackFocusRequester else null
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
                        text = stringResource(R.string.subtitle_no_builtin),
                        style = MaterialTheme.typography.bodyMedium,
                        color = Color.White.copy(alpha = 0.5f)
                    )
                }
            }
        } else {
            items(orderedTracks) { track ->
                TrackItem(
                    track = track,
                    isSelected = track.index == selectedIndex && selectedAddonSubtitle == null,
                    onClick = { onTrackSelected(track.index) },
                    focusRequester = if (track == orderedTracks.firstOrNull() && selectedIndex != -1) {
                        firstTrackFocusRequester
                    } else {
                        null
                    }
                )
            }
        }
    }
}

@Composable
private fun SubtitleSourcesContent(
    internalTracks: List<TrackInfo>,
    selectedInternalIndex: Int,
    addonSubtitles: List<Subtitle>,
    selectedSubtitle: Subtitle?,
    preferredLanguage: String,
    secondaryPreferredLanguage: String?,
    isLoading: Boolean,
    aiSubtitlesEnabled: Boolean,
    aiSubtitlesAvailable: Boolean,
    onInternalTrackSelected: (Int) -> Unit,
    onDisableSubtitles: () -> Unit,
    onSubtitleSelected: (Subtitle) -> Unit,
    onToggleAiSubtitles: () -> Unit
) {
    val languageFilters = remember(
        addonSubtitles,
        preferredLanguage,
        secondaryPreferredLanguage
    ) {
        buildAddonLanguageFilters(
            subtitles = addonSubtitles,
            preferredLanguage = preferredLanguage,
            secondaryPreferredLanguage = secondaryPreferredLanguage
        )
    }
    val selectedAddonLanguageKey = selectedSubtitle
        ?.let { subtitle -> languageFilters.firstOrNull { it.matches(subtitle) }?.key }

    var selectedSourceKey by remember(
        selectedSubtitle?.id,
        selectedSubtitle?.url,
        languageFilters
    ) {
        mutableStateOf(selectedAddonLanguageKey ?: BUILT_IN_SUBTITLE_SOURCE_KEY)
    }
    LaunchedEffect(selectedAddonLanguageKey, languageFilters) {
        selectedSourceKey = when {
            selectedAddonLanguageKey != null -> selectedAddonLanguageKey
            selectedSourceKey == BUILT_IN_SUBTITLE_SOURCE_KEY -> BUILT_IN_SUBTITLE_SOURCE_KEY
            languageFilters.any { it.key == selectedSourceKey } -> selectedSourceKey
            else -> BUILT_IN_SUBTITLE_SOURCE_KEY
        }
    }

    val selectedFilter = languageFilters.firstOrNull { it.key == selectedSourceKey }
    val firstContentFocusRequester = remember(selectedSourceKey) { FocusRequester() }

    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        SubtitleSourceSelector(
            languageFilters = languageFilters,
            selectedSourceKey = selectedSourceKey,
            aiSubtitlesEnabled = aiSubtitlesEnabled,
            aiSubtitlesAvailable = aiSubtitlesAvailable,
            onSelectSource = { selectedSourceKey = it },
            onToggleAiSubtitles = onToggleAiSubtitles,
            downFocusRequester = firstContentFocusRequester
        )

        if (selectedSourceKey == BUILT_IN_SUBTITLE_SOURCE_KEY) {
            InternalSubtitlesContent(
                tracks = internalTracks,
                selectedIndex = selectedInternalIndex,
                selectedAddonSubtitle = selectedSubtitle,
                preferredLanguage = preferredLanguage,
                secondaryPreferredLanguage = secondaryPreferredLanguage,
                onTrackSelected = onInternalTrackSelected,
                onDisableSubtitles = onDisableSubtitles,
                firstTrackFocusRequester = firstContentFocusRequester
            )
        } else {
            AddonSubtitleList(
                subtitles = selectedFilter?.items.orEmpty(),
                selectedSubtitle = selectedSubtitle,
                isLoading = isLoading,
                onSubtitleSelected = onSubtitleSelected,
                firstSubtitleFocusRequester = firstContentFocusRequester
            )
        }
    }
}

@Composable
private fun SubtitleSourceSelector(
    languageFilters: List<SubtitleFilter>,
    selectedSourceKey: String,
    aiSubtitlesEnabled: Boolean,
    aiSubtitlesAvailable: Boolean,
    onSelectSource: (String) -> Unit,
    onToggleAiSubtitles: () -> Unit,
    downFocusRequester: FocusRequester?
) {
    LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        item(key = BUILT_IN_SUBTITLE_SOURCE_KEY) {
            FilterChip(
                label = stringResource(R.string.subtitle_tab_builtin),
                isSelected = selectedSourceKey == BUILT_IN_SUBTITLE_SOURCE_KEY,
                onClick = { onSelectSource(BUILT_IN_SUBTITLE_SOURCE_KEY) },
                onMoveDown = { downFocusRequester?.requestFocus() }
            )
        }

        items(languageFilters, key = { it.key }) { filter ->
            FilterChip(
                label = filter.label,
                isSelected = selectedSourceKey == filter.key,
                onClick = { onSelectSource(filter.key) },
                onMoveDown = { downFocusRequester?.requestFocus() }
            )
        }

        if (aiSubtitlesAvailable) {
            item(key = "auto_translate") {
                FilterChip(
                    label = stringResource(R.string.subtitle_auto_translate),
                    isSelected = aiSubtitlesEnabled,
                    onClick = onToggleAiSubtitles,
                    onMoveDown = { downFocusRequester?.requestFocus() }
                )
            }
        }
    }
}

@Composable
private fun AddonSubtitleList(
    subtitles: List<Subtitle>,
    selectedSubtitle: Subtitle?,
    isLoading: Boolean,
    onSubtitleSelected: (Subtitle) -> Unit,
    firstSubtitleFocusRequester: FocusRequester
) {
    LazyColumn(
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
                            text = stringResource(R.string.subtitle_loading_addon),
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
                        text = stringResource(R.string.subtitle_no_addon),
                        style = MaterialTheme.typography.bodyMedium,
                        color = Color.White.copy(alpha = 0.5f)
                    )
                }
            }
        } else {
            items(
                items = subtitles,
                key = { subtitle -> "${subtitle.addonName}:${subtitle.id}:${subtitle.url}" }
            ) { subtitle ->
                AddonSubtitleItem(
                    subtitle = subtitle,
                    isSelected = selectedSubtitle?.id == subtitle.id && selectedSubtitle.url == subtitle.url,
                    onClick = { onSubtitleSelected(subtitle) },
                    focusRequester = if (subtitle == subtitles.firstOrNull()) firstSubtitleFocusRequester else null
                )
            }
        }
    }
}

@Composable
private fun FilterChip(
    label: String,
    isSelected: Boolean,
    onClick: () -> Unit,
    onMoveDown: () -> Unit
) {
    var isFocused by remember { mutableStateOf(false) }

    Card(
        onClick = onClick,
        modifier = Modifier
            .onFocusChanged { isFocused = it.isFocused }
            .onKeyEvent { event ->
                if (event.nativeKeyEvent.action == KeyEvent.ACTION_DOWN &&
                    event.nativeKeyEvent.keyCode == KeyEvent.KEYCODE_DPAD_DOWN
                ) {
                    onMoveDown()
                    true
                } else {
                    false
                }
            },
        colors = CardDefaults.colors(
            containerColor = when {
                isSelected -> Color.White.copy(alpha = 0.2f)
                isFocused -> Color.White.copy(alpha = 0.12f)
                else -> Color.White.copy(alpha = 0.06f)
            },
            focusedContainerColor = if (isSelected) Color.White.copy(alpha = 0.24f) else Color.White.copy(alpha = 0.12f)
        ),
        shape = CardDefaults.shape(RoundedCornerShape(10.dp))
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodySmall,
            color = Color.White,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)
        )
    }
}

private data class SubtitleFilter(
    val key: String,
    val label: String,
    val items: List<Subtitle>
){
    fun matches(subtitle: Subtitle): Boolean {
        return items.any { item -> item.id == subtitle.id && item.url == subtitle.url }
    }
}

private const val BUILT_IN_SUBTITLE_SOURCE_KEY = "built_in"

private fun buildAddonLanguageFilters(
    subtitles: List<Subtitle>,
    preferredLanguage: String,
    secondaryPreferredLanguage: String?
): List<SubtitleFilter> {
    val preferredFirst = sortByPreferredLanguages(
        items = subtitles,
        preferredLanguages = listOfNotNull(preferredLanguage, secondaryPreferredLanguage)
    ) { it.lang }

    return preferredFirst
        .groupBy { subtitleLanguageGroupKey(it.lang) }
        .map { (languageKey, items) ->
            SubtitleFilter(
                key = "lang:$languageKey",
                label = Subtitle.languageCodeToName(languageKey),
                items = items
            )
        }
}

private fun subtitleLanguageGroupKey(language: String): String {
    val normalized = PlayerSubtitleUtils.normalizeLanguageCode(language)
    if (normalized == "pt-br") {
        return "pt-br"
    }
    return normalized
        .substringBefore('-')
        .substringBefore('_')
}

private fun <T> sortByPreferredLanguages(
    items: List<T>,
    preferredLanguages: List<String>,
    languageSelector: (T) -> String?
): List<T> {
    val targets = preferredLanguages
        .map { it.trim() }
        .filter { it.isNotBlank() && !it.equals("none", ignoreCase = true) }
        .distinctBy { PlayerSubtitleUtils.normalizeLanguageCode(it) }
    if (targets.isEmpty()) {
        return items
    }

    fun matchRank(language: String?): Int {
        if (language.isNullOrBlank()) return Int.MAX_VALUE
        return targets.indexOfFirst { target ->
            PlayerSubtitleUtils.matchesLanguageCode(language, target)
        }.let { index -> if (index >= 0) index else Int.MAX_VALUE }
    }

    return items.sortedWith(compareBy { item -> matchRank(languageSelector(item)) })
}

@Composable
private fun AddonSubtitleItem(
    subtitle: Subtitle,
    isSelected: Boolean,
    onClick: () -> Unit,
    focusRequester: FocusRequester? = null
) {
    var isFocused by remember { mutableStateOf(false) }

    Card(
        onClick = onClick,
        modifier = Modifier
            .fillMaxWidth()
            .then(if (focusRequester != null) Modifier.focusRequester(focusRequester) else Modifier)
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
                    text = Subtitle.languageCodeToName(PlayerSubtitleUtils.normalizeLanguageCode(subtitle.lang)),
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
                    tint = NexioColors.Secondary,
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
    LazyColumn(
        verticalArrangement = Arrangement.spacedBy(16.dp),
        contentPadding = PaddingValues(top = 4.dp),
        modifier = Modifier.height(340.dp)
    ) {
        // Core section
        item {
            StyleSection(
                title = stringResource(R.string.subtitle_section_core),
                icon = Icons.Default.FormatSize
            ) {
                // Font Size
                StyleSettingRow(label = stringResource(R.string.subtitle_font_size)) {
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
                StyleSettingRow(label = stringResource(R.string.subtitle_bold)) {
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
                title = stringResource(R.string.subtitle_section_advanced),
                icon = Icons.Default.Tune
            ) {
                // Text Color
                Column {
                    Text(
                        text = stringResource(R.string.subtitle_text_color),
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
                StyleSettingRow(label = stringResource(R.string.subtitle_outline)) {
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
                            text = stringResource(R.string.subtitle_outline_color),
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
                StyleSettingRow(label = stringResource(R.string.subtitle_bottom_offset)) {
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
                    text = stringResource(R.string.subtitle_reset_defaults),
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
        isFocused -> Modifier.border(2.dp, NexioColors.FocusRing, CircleShape)
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
            text = if (isEnabled) stringResource(R.string.subtitle_on) else stringResource(R.string.subtitle_off),
            style = MaterialTheme.typography.bodyMedium,
            color = if (isEnabled) Color.White else Color.White.copy(alpha = 0.5f),
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
        )
    }
}

// Shared TrackItem composable (used by both audio and subtitle dialogs)
@Composable
internal fun TrackItem(
    track: TrackInfo,
    isSelected: Boolean,
    onClick: () -> Unit,
    focusRequester: FocusRequester? = null
) {
    var isFocused by remember { mutableStateOf(false) }

    Card(
        onClick = onClick,
        modifier = Modifier
            .fillMaxWidth()
            .then(if (focusRequester != null) Modifier.focusRequester(focusRequester) else Modifier)
            .onFocusChanged { isFocused = it.isFocused },
        colors = CardDefaults.colors(
            containerColor = if (isSelected) Color.White.copy(alpha = 0.12f) else Color.White.copy(alpha = 0.05f),
            focusedContainerColor = Color.White.copy(alpha = 0.15f)
        ),
        shape = CardDefaults.shape(shape = RoundedCornerShape(12.dp))
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 14.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = track.name,
                    style = MaterialTheme.typography.bodyLarge,
                    color = if (isSelected) Color.White else Color.White.copy(alpha = 0.9f)
                )
                if (track.language != null) {
                    Text(
                        text = Subtitle.languageCodeToName(track.language),
                        style = MaterialTheme.typography.labelSmall,
                        color = Color.White.copy(alpha = 0.5f)
                    )
                }
            }

            if (isSelected) {
                Icon(
                    imageVector = Icons.Default.Check,
                    contentDescription = "Selected",
                    tint = NexioColors.Secondary,
                    modifier = Modifier.size(20.dp)
                )
            }
        }
    }
}
