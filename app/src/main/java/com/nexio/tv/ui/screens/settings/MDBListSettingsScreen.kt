@file:OptIn(ExperimentalTvMaterial3Api::class)

package com.nexio.tv.ui.screens.settings

import android.view.KeyEvent
import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
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
import com.nexio.tv.R
import androidx.tv.material3.Button
import androidx.tv.material3.ButtonDefaults
import androidx.tv.material3.Card
import androidx.tv.material3.CardDefaults
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.nexio.tv.ui.components.NexioDialog
import com.nexio.tv.ui.theme.NexioColors

@Composable
fun MDBListSettingsContent(
    viewModel: MDBListSettingsViewModel = hiltViewModel(),
    initialFocusRequester: FocusRequester? = null
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    var showApiKeyDialog by remember { mutableStateOf(false) }
    var showCatalogDialog by remember { mutableStateOf(false) }

    Column(
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        SettingsDetailHeader(
            title = stringResource(R.string.mdblist_title),
            subtitle = stringResource(R.string.mdblist_subtitle)
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
                item(key = "mdblist_enabled") {
                    SettingsToggleRow(
                        title = stringResource(R.string.mdblist_enable_title),
                        subtitle = stringResource(R.string.mdblist_enable_subtitle),
                        checked = uiState.enabled,
                        onToggle = { viewModel.onEvent(MDBListSettingsEvent.ToggleEnabled(!uiState.enabled)) },
                        modifier = if (initialFocusRequester != null) {
                            Modifier.focusRequester(initialFocusRequester)
                        } else {
                            Modifier
                        }
                    )
                }

                item(key = "mdblist_api_key") {
                    SettingsActionRow(
                        title = stringResource(R.string.mdblist_api_key_title),
                        subtitle = stringResource(R.string.mdblist_api_key_subtitle),
                        value = maskApiKey(uiState.apiKey, stringResource(R.string.mdblist_not_set)),
                        onClick = { showApiKeyDialog = true },
                        enabled = uiState.enabled
                    )
                }

                item(key = "mdblist_catalogs") {
                    val activeCount = uiState.activeCatalogKeys().size
                    SettingsActionRow(
                        title = stringResource(R.string.mdblist_catalogs_title),
                        subtitle = stringResource(R.string.mdblist_catalogs_subtitle),
                        value = stringResource(R.string.mdblist_catalogs_enabled_count, activeCount),
                        onClick = {
                            viewModel.onEvent(MDBListSettingsEvent.CatalogManagementOpened)
                            showCatalogDialog = true
                        },
                        enabled = uiState.enabled && uiState.apiKey.isNotBlank()
                    )
                }

                item(key = "mdblist_trakt") {
                    SettingsToggleRow(
                        title = stringResource(R.string.mdblist_trakt_title),
                        subtitle = stringResource(R.string.mdblist_trakt_subtitle),
                        checked = uiState.showTrakt,
                        enabled = uiState.enabled,
                        onToggle = { viewModel.onEvent(MDBListSettingsEvent.ToggleTrakt(!uiState.showTrakt)) }
                    )
                }

                item(key = "mdblist_tmdb") {
                    SettingsToggleRow(
                        title = stringResource(R.string.mdblist_tmdb_title),
                        subtitle = stringResource(R.string.mdblist_tmdb_subtitle),
                        checked = uiState.showTmdb,
                        enabled = uiState.enabled,
                        onToggle = { viewModel.onEvent(MDBListSettingsEvent.ToggleTmdb(!uiState.showTmdb)) }
                    )
                }

                item(key = "mdblist_letterboxd") {
                    SettingsToggleRow(
                        title = stringResource(R.string.mdblist_letterboxd_title),
                        subtitle = stringResource(R.string.mdblist_letterboxd_subtitle),
                        checked = uiState.showLetterboxd,
                        enabled = uiState.enabled,
                        onToggle = { viewModel.onEvent(MDBListSettingsEvent.ToggleLetterboxd(!uiState.showLetterboxd)) }
                    )
                }

                item(key = "mdblist_tomatoes") {
                    SettingsToggleRow(
                        title = stringResource(R.string.mdblist_tomatoes_title),
                        subtitle = stringResource(R.string.mdblist_tomatoes_subtitle),
                        checked = uiState.showTomatoes,
                        enabled = uiState.enabled,
                        onToggle = { viewModel.onEvent(MDBListSettingsEvent.ToggleTomatoes(!uiState.showTomatoes)) }
                    )
                }

                item(key = "mdblist_audience") {
                    SettingsToggleRow(
                        title = stringResource(R.string.mdblist_audience_title),
                        subtitle = stringResource(R.string.mdblist_audience_subtitle),
                        checked = uiState.showAudience,
                        enabled = uiState.enabled,
                        onToggle = { viewModel.onEvent(MDBListSettingsEvent.ToggleAudience(!uiState.showAudience)) }
                    )
                }

                item(key = "mdblist_metacritic") {
                    SettingsToggleRow(
                        title = stringResource(R.string.mdblist_metacritic_title),
                        subtitle = stringResource(R.string.mdblist_metacritic_subtitle),
                        checked = uiState.showMetacritic,
                        enabled = uiState.enabled,
                        onToggle = { viewModel.onEvent(MDBListSettingsEvent.ToggleMetacritic(!uiState.showMetacritic)) }
                    )
                }
            }
        }
    }

    if (showApiKeyDialog) {
        MDBListApiKeyDialog(
            currentValue = uiState.apiKey,
            viewModel = viewModel,
            onSaved = { showApiKeyDialog = false },
            onClear = { viewModel.validateAndSaveApiKey("") {}; showApiKeyDialog = false },
            onDismiss = { showApiKeyDialog = false }
        )
    }

    if (showCatalogDialog && uiState.enabled && uiState.apiKey.isNotBlank()) {
        var topListSearch by remember { mutableStateOf("") }
        val filteredTopLists = remember(uiState.topLists, topListSearch) {
            val query = topListSearch.trim().lowercase()
            if (query.isBlank()) {
                uiState.topLists
            } else {
                uiState.topLists.filter { option ->
                    option.title.lowercase().contains(query) ||
                        option.owner.lowercase().contains(query) ||
                        option.listId.lowercase().contains(query)
                }
            }
        }

        NexioDialog(
            onDismiss = { showCatalogDialog = false },
            title = stringResource(R.string.mdblist_catalogs_title),
            subtitle = stringResource(R.string.mdblist_catalogs_dialog_subtitle),
            width = 940.dp,
            suppressFirstKeyUp = false
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                LazyColumn(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 540.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    item(key = "personal_header") {
                        Spacer(modifier = Modifier.width(2.dp))
                        Text(
                            text = stringResource(R.string.mdblist_personal_lists_title),
                            style = MaterialTheme.typography.titleMedium,
                            color = NexioColors.TextPrimary
                        )
                    }

                    items(items = uiState.personalLists, key = { it.key }) { option ->
                        val enabled = uiState.catalogPreferences.isPersonalListEnabled(option.key)
                        SettingsToggleRow(
                            title = option.title,
                            subtitle = stringResource(
                                R.string.mdblist_list_item_count_subtitle,
                                option.itemCount
                            ),
                            checked = enabled,
                            onToggle = {
                                viewModel.onEvent(
                                    MDBListSettingsEvent.TogglePersonalList(
                                        listKey = option.key,
                                        enabled = !enabled
                                    )
                                )
                            }
                        )
                    }

                    item(key = "top_header") {
                        Spacer(modifier = Modifier.width(2.dp))
                        Text(
                            text = stringResource(R.string.mdblist_top_lists_title),
                            style = MaterialTheme.typography.titleMedium,
                            color = NexioColors.TextPrimary
                        )
                        Text(
                            text = stringResource(R.string.mdblist_top_lists_subtitle),
                            style = MaterialTheme.typography.bodySmall,
                            color = NexioColors.TextSecondary
                        )
                        Card(
                            onClick = {},
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
                            scale = CardDefaults.scale(focusedScale = 1f),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Box(modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp)) {
                                BasicTextField(
                                    value = topListSearch,
                                    onValueChange = { topListSearch = it },
                                    textStyle = MaterialTheme.typography.bodyMedium.copy(color = NexioColors.TextPrimary),
                                    cursorBrush = SolidColor(NexioColors.Primary),
                                    singleLine = true,
                                    decorationBox = { inner ->
                                        if (topListSearch.isBlank()) {
                                            Text(
                                                text = stringResource(R.string.mdblist_top_lists_search_hint),
                                                style = MaterialTheme.typography.bodyMedium,
                                                color = NexioColors.TextTertiary
                                            )
                                        }
                                        inner()
                                    },
                                    modifier = Modifier.fillMaxWidth()
                                )
                            }
                        }
                    }

                    items(items = filteredTopLists, key = { it.key }) { option ->
                        val selected = uiState.catalogPreferences.isTopListSelected(option.key)
                        SettingsToggleRow(
                            title = option.title,
                            subtitle = stringResource(
                                R.string.mdblist_list_item_count_subtitle,
                                option.itemCount
                            ),
                            checked = selected,
                            onToggle = {
                                viewModel.onEvent(
                                    MDBListSettingsEvent.ToggleTopList(
                                        listKey = option.key,
                                        enabled = !selected
                                    )
                                )
                            }
                        )
                    }
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End
                ) {
                    Button(
                        onClick = { showCatalogDialog = false },
                        colors = ButtonDefaults.colors(
                            containerColor = NexioColors.BackgroundCard,
                            contentColor = NexioColors.TextPrimary
                        )
                    ) {
                        Text(stringResource(R.string.action_close))
                    }
                }
            }
        }
    }
}

@Composable
private fun MDBListApiKeyDialog(
    currentValue: String,
    viewModel: MDBListSettingsViewModel,
    onSaved: () -> Unit,
    onClear: () -> Unit,
    onDismiss: () -> Unit
) {
    var value by remember(currentValue) { mutableStateOf(currentValue) }
    var isInputFocused by remember { mutableStateOf(false) }
    val inputFocusRequester = remember { FocusRequester() }
    val keyboardController = LocalSoftwareKeyboardController.current
    val validating by viewModel.validating.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val invalidApiKeyMsg = stringResource(R.string.mdblist_invalid_api_key)

    LaunchedEffect(Unit) {
        viewModel.validationError.collect {
            Toast.makeText(context, invalidApiKeyMsg, Toast.LENGTH_SHORT).show()
        }
    }

    NexioDialog(
        onDismiss = onDismiss,
        title = stringResource(R.string.mdblist_dialog_title),
        subtitle = stringResource(R.string.mdblist_dialog_subtitle),
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
                                text = stringResource(R.string.mdblist_dialog_placeholder),
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
            ) {
                Text(stringResource(R.string.action_cancel))
            }
            Spacer(modifier = Modifier.width(8.dp))
            Button(
                onClick = onClear,
                colors = ButtonDefaults.colors(
                    containerColor = NexioColors.BackgroundElevated,
                    contentColor = NexioColors.TextPrimary
                )
            ) {
                Text(stringResource(R.string.action_clear))
            }
            Spacer(modifier = Modifier.width(8.dp))
            Button(
                onClick = { if (!validating) viewModel.validateAndSaveApiKey(value, onSaved) },
                colors = ButtonDefaults.colors(
                    containerColor = NexioColors.BackgroundCard,
                    contentColor = NexioColors.TextPrimary
                )
            ) {
                Text(if (validating) stringResource(R.string.action_saving) else stringResource(R.string.action_save))
            }
        }
    }
}

private fun maskApiKey(key: String, notSetLabel: String): String {
    val trimmed = key.trim()
    if (trimmed.isBlank()) return notSetLabel
    return if (trimmed.length <= 4) "••••" else "••••••${trimmed.takeLast(4)}"
}
