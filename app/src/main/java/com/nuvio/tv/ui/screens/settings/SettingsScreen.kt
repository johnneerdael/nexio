@file:OptIn(ExperimentalTvMaterial3Api::class)

package com.nuvio.tv.ui.screens.settings

import androidx.annotation.RawRes
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.GridView
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Tune
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.tv.material3.ExperimentalTvMaterial3Api
import com.nuvio.tv.R
import com.nuvio.tv.ui.screens.plugin.PluginScreenContent
import com.nuvio.tv.ui.screens.plugin.PluginViewModel
import com.nuvio.tv.ui.theme.NuvioColors
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

internal enum class SettingsCategory {
    APPEARANCE,
    LAYOUT,
    PLUGINS,
    TMDB,
    PLAYBACK,
    TRAKT,
    ABOUT
}

internal enum class SettingsSectionDestination {
    Inline,
    External
}

internal data class SettingsSectionSpec(
    val category: SettingsCategory,
    val title: String,
    val icon: ImageVector? = null,
    @param:RawRes val rawIconRes: Int? = null,
    val subtitle: String,
    val destination: SettingsSectionDestination
)

@Composable
fun SettingsScreen(
    showBuiltInHeader: Boolean = true,
    onNavigateToTrakt: () -> Unit = {}
) {
    val sectionSpecs = remember {
        listOf(
            SettingsSectionSpec(
                category = SettingsCategory.APPEARANCE,
                title = "Appearance",
                icon = Icons.Default.Palette,
                subtitle = "Theme and color tuning.",
                destination = SettingsSectionDestination.Inline
            ),
            SettingsSectionSpec(
                category = SettingsCategory.LAYOUT,
                title = "Layout",
                icon = Icons.Default.GridView,
                subtitle = "Home structure and poster styles.",
                destination = SettingsSectionDestination.Inline
            ),
            SettingsSectionSpec(
                category = SettingsCategory.PLUGINS,
                title = "Plugins",
                icon = Icons.Default.Build,
                subtitle = "Repositories and providers.",
                destination = SettingsSectionDestination.Inline
            ),
            SettingsSectionSpec(
                category = SettingsCategory.TMDB,
                title = "TMDB",
                icon = Icons.Default.Tune,
                subtitle = "Metadata enrichment controls.",
                destination = SettingsSectionDestination.Inline
            ),
            SettingsSectionSpec(
                category = SettingsCategory.PLAYBACK,
                title = "Playback",
                icon = Icons.Default.Settings,
                subtitle = "Player, subtitles, and auto-play.",
                destination = SettingsSectionDestination.Inline
            ),
            SettingsSectionSpec(
                category = SettingsCategory.TRAKT,
                title = "Trakt",
                rawIconRes = R.raw.trakt_tv_glyph,
                subtitle = "Open Trakt connection screen.",
                destination = SettingsSectionDestination.External
            ),
            SettingsSectionSpec(
                category = SettingsCategory.ABOUT,
                title = "About",
                icon = Icons.Default.Info,
                subtitle = "Version and policies.",
                destination = SettingsSectionDestination.Inline
            )
        )
    }

    var selectedCategory by remember { mutableStateOf(SettingsCategory.APPEARANCE) }
    val railFocusRequesters = remember {
        sectionSpecs.associate { it.category to FocusRequester() }
    }
    val contentFocusRequesters = remember {
        mapOf(
            SettingsCategory.APPEARANCE to FocusRequester(),
            SettingsCategory.LAYOUT to FocusRequester(),
            SettingsCategory.TMDB to FocusRequester(),
            SettingsCategory.PLAYBACK to FocusRequester(),
            SettingsCategory.ABOUT to FocusRequester()
        )
    }

    val focusManager = LocalFocusManager.current
    val coroutineScope = rememberCoroutineScope()

    val pluginViewModel: PluginViewModel = hiltViewModel()
    val pluginUiState by pluginViewModel.uiState.collectAsState()

    LaunchedEffect(Unit) {
        railFocusRequesters[selectedCategory]?.requestFocus()
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(NuvioColors.Background)
            .padding(
                start = 32.dp,
                end = 32.dp,
                top = if (showBuiltInHeader) 24.dp else 68.dp,
                bottom = 24.dp
            )
    ) {
        SettingsWorkspaceSurface(
            modifier = Modifier
                .fillMaxSize()
        ) {
            androidx.compose.foundation.layout.Row(
                modifier = Modifier.fillMaxSize(),
                horizontalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                var railHadFocus by remember { mutableStateOf(false) }

                LazyColumn(
                    modifier = Modifier
                        .width(282.dp)
                        .fillMaxHeight()
                        .onFocusChanged { state ->
                            val justGainedFocus = !railHadFocus && state.hasFocus
                            railHadFocus = state.hasFocus
                            if (justGainedFocus) {
                                railFocusRequesters[selectedCategory]?.requestFocus()
                            }
                        }
                        .onPreviewKeyEvent { event ->
                            if (event.type == KeyEventType.KeyDown && event.key == Key.DirectionRight) {
                                focusManager.moveFocus(FocusDirection.Right)
                                true
                            } else {
                                false
                            }
                        },
                    verticalArrangement = Arrangement.spacedBy(10.dp, Alignment.CenterVertically)
                ) {
                    items(sectionSpecs) { section ->
                        SettingsRailButton(
                            title = section.title,
                            icon = section.icon,
                            rawIconRes = section.rawIconRes,
                            isSelected = selectedCategory == section.category,
                            focusRequester = railFocusRequesters[section.category],
                            onClick = {
                                if (section.destination == SettingsSectionDestination.External) {
                                    when (section.category) {
                                        SettingsCategory.TRAKT -> onNavigateToTrakt()
                                        else -> Unit
                                    }
                                } else {
                                    selectedCategory = section.category
                                    coroutineScope.launch {
                                        // Wait for detail content to settle before requesting first content focus.
                                        delay(120)
                                        contentFocusRequesters[section.category]?.requestFocus()
                                    }
                                }
                            }
                        )
                    }
                }

                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxHeight()
                ) {
                    AnimatedContent(
                        targetState = selectedCategory,
                        transitionSpec = {
                            val direction = if (targetState.ordinal >= initialState.ordinal) 1 else -1
                            (slideInHorizontally(
                                initialOffsetX = { fullWidth -> direction * (fullWidth / 6) },
                                animationSpec = tween(200)
                            ) + fadeIn(animationSpec = tween(200)))
                                .togetherWith(
                                    slideOutHorizontally(
                                        targetOffsetX = { fullWidth -> -direction * (fullWidth / 6) },
                                        animationSpec = tween(180)
                                    ) + fadeOut(animationSpec = tween(180))
                                )
                        },
                        label = "settings_split_detail"
                    ) { category ->
                        when (category) {
                            SettingsCategory.APPEARANCE -> ThemeSettingsContent(
                                initialFocusRequester = contentFocusRequesters[SettingsCategory.APPEARANCE]
                            )
                            SettingsCategory.LAYOUT -> LayoutSettingsContent(
                                initialFocusRequester = contentFocusRequesters[SettingsCategory.LAYOUT]
                            )
                            SettingsCategory.PLAYBACK -> PlaybackSettingsContent(
                                initialFocusRequester = contentFocusRequesters[SettingsCategory.PLAYBACK]
                            )
                            SettingsCategory.TMDB -> TmdbSettingsContent(
                                initialFocusRequester = contentFocusRequesters[SettingsCategory.TMDB]
                            )
                            SettingsCategory.ABOUT -> AboutSettingsContent(
                                initialFocusRequester = contentFocusRequesters[SettingsCategory.ABOUT]
                            )
                            SettingsCategory.PLUGINS -> {
                                Column(
                                    modifier = Modifier.fillMaxSize(),
                                    verticalArrangement = Arrangement.spacedBy(12.dp)
                                ) {
                                    SettingsDetailHeader(
                                        title = "Plugins",
                                        subtitle = "Manage repositories, providers, and plugin states."
                                    )
                                    SettingsGroupCard(modifier = Modifier.fillMaxSize()) {
                                        Box(
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .weight(1f),
                                            contentAlignment = Alignment.TopStart
                                        ) {
                                            PluginScreenContent(
                                                uiState = pluginUiState,
                                                viewModel = pluginViewModel,
                                                showHeader = false
                                            )
                                        }
                                    }
                                }
                            }
                            SettingsCategory.TRAKT -> Unit
                        }
                    }
                }
            }
        }
    }
}
