@file:OptIn(ExperimentalTvMaterial3Api::class)

package com.nexio.tv.ui.screens.settings

import android.view.KeyEvent
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.tv.material3.Border
import androidx.tv.material3.Button
import androidx.tv.material3.ButtonDefaults
import androidx.tv.material3.Card
import androidx.tv.material3.CardDefaults
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.nexio.tv.R
import com.nexio.tv.ui.components.NexioDialog
import com.nexio.tv.ui.theme.NexioColors

@Composable
fun TmdbSettingsScreen(
    viewModel: TmdbSettingsViewModel = hiltViewModel(),
    onBackPress: () -> Unit
) {
    BackHandler { onBackPress() }

    SettingsStandaloneScaffold(
        title = stringResource(R.string.tmdb_title),
        subtitle = stringResource(R.string.tmdb_subtitle)
    ) {
        TmdbSettingsContent(viewModel = viewModel)
    }
}

@Composable
fun TmdbSettingsContent(
    viewModel: TmdbSettingsViewModel = hiltViewModel(),
    initialFocusRequester: FocusRequester? = null
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val validating by viewModel.validating.collectAsStateWithLifecycle()
    var showApiKeyDialog by remember { mutableStateOf(false) }
    val context = LocalContext.current
    val missingApiKeyMsg = stringResource(R.string.tmdb_missing_api_key)
    val invalidApiKeyMsg = stringResource(R.string.tmdb_invalid_api_key)

    LaunchedEffect(Unit) {
        viewModel.validationError.collect { error ->
            val text = when (error) {
                TmdbValidationError.MissingApiKey -> missingApiKeyMsg
                TmdbValidationError.InvalidApiKey -> invalidApiKeyMsg
            }
            Toast.makeText(context, text, Toast.LENGTH_SHORT).show()
        }
    }

    Column(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        SettingsDetailHeader(
            title = stringResource(R.string.tmdb_title),
            subtitle = stringResource(R.string.tmdb_subtitle)
        )

        SettingsGroupCard(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
        ) {
            LazyColumn(
                contentPadding = PaddingValues(bottom = 8.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                item(key = "tmdb_enabled") {
                    SettingsToggleRow(
                        title = stringResource(R.string.tmdb_enable_title),
                        subtitle = stringResource(R.string.tmdb_enable_subtitle),
                        checked = uiState.enabled,
                        onToggle = { viewModel.onEvent(TmdbSettingsEvent.ToggleEnabled(!uiState.enabled)) },
                        modifier = if (initialFocusRequester != null) {
                            Modifier.focusRequester(initialFocusRequester)
                        } else {
                            Modifier
                        }
                    )
                }

                item(key = "tmdb_api_key") {
                    SettingsActionRow(
                        title = stringResource(R.string.tmdb_api_key_title),
                        subtitle = stringResource(R.string.tmdb_api_key_subtitle),
                        value = maskApiKey(uiState.apiKey, stringResource(R.string.mdblist_not_set)),
                        enabled = true,
                        onClick = { showApiKeyDialog = true }
                    )
                }

                item(key = "tmdb_artwork") {
                    SettingsToggleRow(
                        title = stringResource(R.string.tmdb_artwork_title),
                        subtitle = stringResource(R.string.tmdb_artwork_subtitle),
                        checked = uiState.useArtwork,
                        enabled = uiState.isActive,
                        onToggle = { viewModel.onEvent(TmdbSettingsEvent.ToggleArtwork(!uiState.useArtwork)) }
                    )
                }

                item(key = "tmdb_basic_info") {
                    SettingsToggleRow(
                        title = stringResource(R.string.tmdb_basic_info_title),
                        subtitle = stringResource(R.string.tmdb_basic_info_subtitle),
                        checked = uiState.useBasicInfo,
                        enabled = uiState.isActive,
                        onToggle = { viewModel.onEvent(TmdbSettingsEvent.ToggleBasicInfo(!uiState.useBasicInfo)) }
                    )
                }

                item(key = "tmdb_details") {
                    SettingsToggleRow(
                        title = stringResource(R.string.tmdb_details_title),
                        subtitle = stringResource(R.string.tmdb_details_subtitle),
                        checked = uiState.useDetails,
                        enabled = uiState.isActive,
                        onToggle = { viewModel.onEvent(TmdbSettingsEvent.ToggleDetails(!uiState.useDetails)) }
                    )
                }

                item(key = "tmdb_credits") {
                    SettingsToggleRow(
                        title = stringResource(R.string.tmdb_credits_title),
                        subtitle = stringResource(R.string.tmdb_credits_subtitle),
                        checked = uiState.useCredits,
                        enabled = uiState.isActive,
                        onToggle = { viewModel.onEvent(TmdbSettingsEvent.ToggleCredits(!uiState.useCredits)) }
                    )
                }

                item(key = "tmdb_productions") {
                    SettingsToggleRow(
                        title = stringResource(R.string.tmdb_productions_title),
                        subtitle = stringResource(R.string.tmdb_productions_subtitle),
                        checked = uiState.useProductions,
                        enabled = uiState.isActive,
                        onToggle = { viewModel.onEvent(TmdbSettingsEvent.ToggleProductions(!uiState.useProductions)) }
                    )
                }

                item(key = "tmdb_networks") {
                    SettingsToggleRow(
                        title = stringResource(R.string.tmdb_networks_title),
                        subtitle = stringResource(R.string.tmdb_networks_subtitle),
                        checked = uiState.useNetworks,
                        enabled = uiState.isActive,
                        onToggle = { viewModel.onEvent(TmdbSettingsEvent.ToggleNetworks(!uiState.useNetworks)) }
                    )
                }

                item(key = "tmdb_episodes") {
                    SettingsToggleRow(
                        title = stringResource(R.string.tmdb_episodes_title),
                        subtitle = stringResource(R.string.tmdb_episodes_subtitle),
                        checked = uiState.useEpisodes,
                        enabled = uiState.isActive,
                        onToggle = { viewModel.onEvent(TmdbSettingsEvent.ToggleEpisodes(!uiState.useEpisodes)) }
                    )
                }

                item(key = "tmdb_more_like_this") {
                    SettingsToggleRow(
                        title = stringResource(R.string.tmdb_more_like_this_title),
                        subtitle = stringResource(R.string.tmdb_more_like_this_subtitle),
                        checked = uiState.useMoreLikeThis,
                        enabled = uiState.isActive,
                        onToggle = {
                            viewModel.onEvent(
                                TmdbSettingsEvent.ToggleMoreLikeThis(!uiState.useMoreLikeThis)
                            )
                        }
                    )
                }

                item(key = "tmdb_collections") {
                    SettingsToggleRow(
                        title = stringResource(R.string.tmdb_collections_title),
                        subtitle = stringResource(R.string.tmdb_collections_subtitle),
                        checked = uiState.useCollections,
                        enabled = uiState.isActive,
                        onToggle = {
                            viewModel.onEvent(
                                TmdbSettingsEvent.ToggleCollections(!uiState.useCollections)
                            )
                        }
                    )
                }
            }
        }
    }

    if (showApiKeyDialog) {
        TmdbApiKeyDialog(
            currentValue = uiState.apiKey,
            validating = validating,
            onSave = { value, onSuccess -> viewModel.validateAndSaveApiKey(value, onSuccess) },
            onClear = { viewModel.validateAndSaveApiKey("") {}; showApiKeyDialog = false },
            onDismiss = { showApiKeyDialog = false }
        )
    }
}

@Composable
private fun TmdbApiKeyDialog(
    currentValue: String,
    validating: Boolean,
    onSave: (String, onSuccess: () -> Unit) -> Unit,
    onClear: () -> Unit,
    onDismiss: () -> Unit
) {
    var value by remember(currentValue) { mutableStateOf(currentValue) }
    var isInputFocused by remember { mutableStateOf(false) }
    val inputFocusRequester = remember { FocusRequester() }
    val keyboardController = LocalSoftwareKeyboardController.current

    NexioDialog(
        onDismiss = onDismiss,
        title = stringResource(R.string.tmdb_dialog_title),
        subtitle = stringResource(R.string.tmdb_dialog_subtitle),
        width = 700.dp
    ) {
        Card(
            onClick = { inputFocusRequester.requestFocus() },
            modifier = Modifier
                .fillMaxWidth()
                .onFocusChanged { isInputFocused = it.isFocused || it.hasFocus },
            colors = CardDefaults.colors(
                containerColor = NexioColors.BackgroundElevated,
                focusedContainerColor = NexioColors.BackgroundElevated
            ),
            border = CardDefaults.border(
                border = Border(
                    border = androidx.compose.foundation.BorderStroke(1.dp, NexioColors.Border),
                    shape = androidx.compose.foundation.shape.RoundedCornerShape(10.dp)
                ),
                focusedBorder = Border(
                    border = androidx.compose.foundation.BorderStroke(2.dp, NexioColors.FocusRing),
                    shape = androidx.compose.foundation.shape.RoundedCornerShape(10.dp)
                )
            ),
            shape = CardDefaults.shape(androidx.compose.foundation.shape.RoundedCornerShape(10.dp)),
            scale = CardDefaults.scale(focusedScale = 1f)
        ) {
            Box(modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp)) {
                BasicTextField(
                    value = value,
                    onValueChange = { value = it },
                    modifier = Modifier
                        .fillMaxWidth()
                        .focusRequester(inputFocusRequester)
                        .onKeyEvent { event ->
                            event.nativeKeyEvent.keyCode == KeyEvent.KEYCODE_DPAD_CENTER &&
                                event.nativeKeyEvent.action == KeyEvent.ACTION_DOWN
                        },
                    singleLine = true,
                    keyboardActions = KeyboardActions(
                        onDone = { keyboardController?.hide() }
                    ),
                    textStyle = MaterialTheme.typography.bodyMedium.copy(color = NexioColors.TextPrimary),
                    cursorBrush = SolidColor(
                        if (isInputFocused) NexioColors.Primary
                        else androidx.compose.ui.graphics.Color.Transparent
                    ),
                    decorationBox = { innerTextField ->
                        if (value.isBlank()) {
                            Text(
                                text = stringResource(R.string.tmdb_dialog_placeholder),
                                style = MaterialTheme.typography.bodyMedium,
                                color = NexioColors.TextTertiary
                            )
                        }
                        innerTextField()
                    }
                )
            }
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.End
        ) {
            Button(
                onClick = onDismiss,
                colors = ButtonDefaults.colors(
                    containerColor = NexioColors.BackgroundElevated,
                    contentColor = NexioColors.TextPrimary
                )
            ) { Text(stringResource(R.string.action_cancel)) }

            Spacer(modifier = Modifier.width(8.dp))
            Button(
                onClick = onClear,
                colors = ButtonDefaults.colors(
                    containerColor = NexioColors.BackgroundElevated,
                    contentColor = NexioColors.TextPrimary
                )
            ) { Text(stringResource(R.string.action_clear)) }

            Spacer(modifier = Modifier.width(8.dp))
            Button(
                onClick = {
                    if (!validating) {
                        onSave(value) { onDismiss() }
                    }
                },
                colors = ButtonDefaults.colors(
                    containerColor = NexioColors.BackgroundCard,
                    contentColor = NexioColors.TextPrimary
                )
            ) { Text(if (validating) stringResource(R.string.action_saving) else stringResource(R.string.action_save)) }
        }
    }
}

private fun maskApiKey(key: String, notSetLabel: String): String {
    val trimmed = key.trim()
    if (trimmed.isBlank()) return notSetLabel
    return if (trimmed.length <= 4) "••••" else "••••••${trimmed.takeLast(4)}"
}
