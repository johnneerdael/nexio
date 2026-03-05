@file:OptIn(ExperimentalTvMaterial3Api::class)

package com.nexio.tv.ui.screens.settings

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import android.widget.Toast
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.tv.material3.Border
import androidx.tv.material3.Button
import androidx.tv.material3.ButtonDefaults
import androidx.tv.material3.Card
import androidx.tv.material3.CardDefaults
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.Icon
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.nexio.tv.R
import com.nexio.tv.core.locale.AppLocaleResolver
import com.nexio.tv.domain.model.AppFont
import com.nexio.tv.domain.model.AppTheme
import com.nexio.tv.ui.components.NexioDialog
import com.nexio.tv.ui.theme.NexioColors
import com.nexio.tv.ui.theme.ThemeColors
import com.nexio.tv.ui.theme.getFontFamily
import kotlinx.coroutines.delay

@Composable
fun ThemeSettingsScreen(
    viewModel: ThemeSettingsViewModel = hiltViewModel(),
    onBackPress: () -> Unit
) {
    BackHandler { onBackPress() }

    SettingsStandaloneScaffold(
        title = stringResource(R.string.appearance_title),
        subtitle = stringResource(R.string.appearance_subtitle)
    ) {
        ThemeSettingsContent(viewModel = viewModel)
    }
}

@Composable
fun ThemeSettingsContent(
    viewModel: ThemeSettingsViewModel = hiltViewModel(),
    initialFocusRequester: FocusRequester? = null
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    var showFontDialog by remember { mutableStateOf(false) }
    var showLanguageDialog by remember { mutableStateOf(false) }
    var pendingLanguageRestart by remember { mutableStateOf(false) }
    val context = LocalContext.current

    val supportedLocales = remember { AppLocaleResolver.supportedOptions }
    var selectedTag by remember {
        mutableStateOf(AppLocaleResolver.getStoredLocaleTag(context))
    }
    val currentLocaleName = supportedLocales
        .firstOrNull { it.tag == selectedTag }
        ?.displayName
        ?: stringResource(R.string.appearance_language_system)
    val strRestartHint = stringResource(R.string.appearance_language_restart_hint)

    LaunchedEffect(pendingLanguageRestart, showLanguageDialog) {
        if (pendingLanguageRestart && !showLanguageDialog) {
            // Let the dialog window detach before recreating the Activity to avoid focus/window ANRs.
            delay(150)
            context.findActivity()?.recreate()
                ?: Toast.makeText(context, strRestartHint, Toast.LENGTH_LONG).show()
            pendingLanguageRestart = false
        }
    }

    Column(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        SettingsDetailHeader(
            title = stringResource(R.string.appearance_title),
            subtitle = stringResource(R.string.appearance_subtitle)
        )

        SettingsGroupCard(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
        ) {
            LazyVerticalGrid(
                columns = GridCells.Fixed(3),
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(6.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                itemsIndexed(
                    items = uiState.availableThemes,
                    key = { _, theme -> theme.name }
                ) { index, theme ->
                    ThemeCard(
                        theme = theme,
                        isSelected = theme == uiState.selectedTheme,
                        onClick = { viewModel.onEvent(ThemeSettingsEvent.SelectTheme(theme)) },
                        modifier = if (index == 0 && initialFocusRequester != null) {
                            Modifier.focusRequester(initialFocusRequester)
                        } else {
                            Modifier
                        }
                    )
                }
            }
        }

        SettingsGroupCard(
            modifier = Modifier.fillMaxWidth()
        ) {
            SettingsActionRow(
                title = stringResource(R.string.appearance_font),
                subtitle = stringResource(R.string.appearance_font_subtitle),
                value = uiState.selectedFont.displayName,
                onClick = { showFontDialog = true }
            )
        }

        SettingsGroupCard(
            modifier = Modifier.fillMaxWidth()
        ) {
            SettingsActionRow(
                title = stringResource(R.string.appearance_language),
                subtitle = stringResource(R.string.appearance_language_subtitle),
                value = currentLocaleName,
                onClick = { showLanguageDialog = true }
            )
        }
    }

    if (showFontDialog) {
        val fontFocusRequester = remember { FocusRequester() }
        LaunchedEffect(Unit) { fontFocusRequester.requestFocus() }
        NexioDialog(
            onDismiss = { showFontDialog = false },
            title = stringResource(R.string.appearance_font_dialog_title),
            width = 400.dp,
            suppressFirstKeyUp = false
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(280.dp)
            ) {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    contentPadding = PaddingValues(vertical = 2.dp)
                ) {
                    val fonts = uiState.availableFonts
                    for (index in fonts.indices) {
                        val font = fonts[index]
                        item {
                            val isSelected = font == uiState.selectedFont
                            Button(
                                onClick = {
                                    viewModel.onEvent(ThemeSettingsEvent.SelectFont(font))
                                    showFontDialog = false
                                },
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .then(if (index == 0) Modifier.focusRequester(fontFocusRequester) else Modifier),
                                colors = ButtonDefaults.colors(
                                    containerColor = if (isSelected) NexioColors.FocusBackground else NexioColors.BackgroundCard,
                                    contentColor = NexioColors.TextPrimary
                                )
                            ) {
                                Text(
                                    text = font.displayName,
                                    fontFamily = getFontFamily(font)
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    if (showLanguageDialog) {
        val firstFocusRequester = remember { FocusRequester() }
        LaunchedEffect(Unit) { firstFocusRequester.requestFocus() }
        NexioDialog(
            onDismiss = { showLanguageDialog = false },
            title = stringResource(R.string.appearance_language_dialog_title),
            width = 400.dp,
            suppressFirstKeyUp = false
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(280.dp)
            ) {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    contentPadding = PaddingValues(vertical = 2.dp)
                ) {
                    for (index in supportedLocales.indices) {
                        val option = supportedLocales[index]
                        val tag = option.tag
                        val name = option.displayName
                        item {
                            val isSelected = tag == selectedTag
                            Button(
                                onClick = {
                                    val previousTag = selectedTag
                                    AppLocaleResolver.setStoredLocaleTag(context, tag)
                                    selectedTag = tag
                                    showLanguageDialog = false
                                    if (previousTag != tag) {
                                        pendingLanguageRestart = true
                                    }
                                },
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .then(if (index == 0) Modifier.focusRequester(firstFocusRequester) else Modifier),
                                colors = ButtonDefaults.colors(
                                    containerColor = if (isSelected) NexioColors.FocusBackground else NexioColors.BackgroundCard,
                                    contentColor = NexioColors.TextPrimary
                                )
                            ) {
                                Text(name)
                            }
                        }
                    }
                }
            }
        }
    }
}

private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}

@Composable
private fun ThemeCard(
    theme: AppTheme,
    isSelected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    var isFocused by remember { mutableStateOf(false) }
    val palette = ThemeColors.getColorPalette(theme)

    Card(
        onClick = onClick,
        modifier = modifier
            .fillMaxWidth()
            .onFocusChanged { state ->
                val nowFocused = state.isFocused
                if (isFocused != nowFocused) {
                    isFocused = nowFocused
                }
            },
        colors = CardDefaults.colors(
            containerColor = NexioColors.Background,
            focusedContainerColor = NexioColors.Background
        ),
        border = CardDefaults.border(
            border = if (isSelected) Border(
                border = BorderStroke(1.dp, NexioColors.FocusRing),
                shape = RoundedCornerShape(SettingsSecondaryCardRadius)
            ) else Border.None,
            focusedBorder = Border(
                border = BorderStroke(2.dp, NexioColors.FocusRing),
                shape = RoundedCornerShape(SettingsSecondaryCardRadius)
            )
        ),
        shape = CardDefaults.shape(RoundedCornerShape(SettingsSecondaryCardRadius)),
        scale = CardDefaults.scale(focusedScale = 1f)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(17.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Box(
                modifier = Modifier
                    .size(55.dp)
                    .clip(CircleShape)
                    .background(palette.secondary),
                contentAlignment = Alignment.Center
            ) {
                if (isSelected) {
                    Icon(
                        imageVector = Icons.Default.Check,
                        contentDescription = stringResource(R.string.cd_selected),
                        tint = palette.onSecondary,
                        modifier = Modifier.size(28.dp)
                    )
                }
            }

            Spacer(modifier = Modifier.height(11.dp))

            Text(
                text = theme.displayName,
                style = MaterialTheme.typography.titleMedium,
                color = if (isFocused || isSelected) NexioColors.TextPrimary else NexioColors.TextSecondary
            )

            Spacer(modifier = Modifier.height(7.dp))

            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(4.dp)
                    .clip(RoundedCornerShape(SettingsPillRadius))
                    .background(palette.focusRing)
            )
        }
    }
}
