@file:OptIn(ExperimentalTvMaterial3Api::class)

package com.nexio.tv.ui.screens.settings

import androidx.activity.compose.BackHandler
import android.view.KeyEvent
import android.widget.Toast
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
fun ImdbSettingsScreen(
    viewModel: ImdbSettingsViewModel = hiltViewModel(),
    onBackPress: () -> Unit
) {
    BackHandler { onBackPress() }

    SettingsStandaloneScaffold(
        title = stringResource(R.string.imdb_title),
        subtitle = stringResource(R.string.imdb_subtitle)
    ) {
        ImdbSettingsContent(viewModel = viewModel)
    }
}

@Composable
fun ImdbSettingsContent(
    viewModel: ImdbSettingsViewModel = hiltViewModel(),
    initialFocusRequester: FocusRequester? = null
) {
    val uiState = viewModel.uiState.collectAsStateWithLifecycle().value
    val validating by viewModel.validating.collectAsStateWithLifecycle()
    var showConnectionDialog by remember { mutableStateOf(false) }
    val context = LocalContext.current
    val invalidConfigurationMessage = stringResource(R.string.imdb_invalid_configuration)

    LaunchedEffect(Unit) {
        viewModel.validationError.collect { error ->
            if (error == ImdbValidationError.InvalidConfiguration) {
                Toast.makeText(context, invalidConfigurationMessage, Toast.LENGTH_SHORT).show()
            }
        }
    }

    Column(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        SettingsDetailHeader(
            title = stringResource(R.string.imdb_title),
            subtitle = stringResource(R.string.imdb_subtitle)
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
                item(key = "imdb_enabled") {
                    SettingsToggleRow(
                        title = stringResource(R.string.imdb_enable_title),
                        subtitle = stringResource(R.string.imdb_enable_subtitle),
                        checked = uiState.enabled,
                        onToggle = { viewModel.setEnabled(!uiState.enabled) },
                        modifier = if (initialFocusRequester != null) {
                            Modifier.focusRequester(initialFocusRequester)
                        } else {
                            Modifier
                        }
                    )
                }

                item(key = "imdb_connection") {
                    SettingsActionRow(
                        title = stringResource(R.string.imdb_connection_title),
                        subtitle = stringResource(R.string.imdb_connection_subtitle),
                        value = imdbConnectionSummary(
                            baseUrl = uiState.baseUrl,
                            apiKey = uiState.apiKey,
                            notSetLabel = stringResource(R.string.mdblist_not_set)
                        ),
                        enabled = true,
                        onClick = { showConnectionDialog = true }
                    )
                }
            }
        }
    }

    if (showConnectionDialog) {
        ImdbConnectionDialog(
            currentBaseUrl = uiState.baseUrl,
            currentApiKey = uiState.apiKey,
            validating = validating,
            onSave = { baseUrl, apiKey, onSuccess ->
                viewModel.validateAndSaveConfiguration(baseUrl, apiKey, onSuccess)
            },
            onClear = {
                viewModel.validateAndSaveConfiguration("", "") {}
                showConnectionDialog = false
            },
            onDismiss = { showConnectionDialog = false }
        )
    }
}

@Composable
private fun ImdbConnectionDialog(
    currentBaseUrl: String,
    currentApiKey: String,
    validating: Boolean,
    onSave: (String, String, onSuccess: () -> Unit) -> Unit,
    onClear: () -> Unit,
    onDismiss: () -> Unit
) {
    var baseUrl by remember(currentBaseUrl) { mutableStateOf(currentBaseUrl) }
    var apiKey by remember(currentApiKey) { mutableStateOf(currentApiKey) }

    NexioDialog(
        onDismiss = onDismiss,
        title = stringResource(R.string.imdb_dialog_title),
        subtitle = stringResource(R.string.imdb_dialog_subtitle),
        width = 760.dp
    ) {
        Column(
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            ImdbDialogField(
                value = baseUrl,
                placeholder = stringResource(R.string.imdb_base_url_placeholder),
                label = stringResource(R.string.imdb_base_url_label),
                onValueChange = { baseUrl = it }
            )
            ImdbDialogField(
                value = apiKey,
                placeholder = stringResource(R.string.imdb_api_key_placeholder),
                label = stringResource(R.string.imdb_api_key_label),
                onValueChange = { apiKey = it }
            )
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
                        onSave(baseUrl, apiKey) { onDismiss() }
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

@Composable
private fun ImdbDialogField(
    value: String,
    label: String,
    placeholder: String,
    onValueChange: (String) -> Unit
) {
    var isInputFocused by remember { mutableStateOf(false) }
    val inputFocusRequester = remember { FocusRequester() }
    val keyboardController = LocalSoftwareKeyboardController.current

    Column(
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            color = NexioColors.TextSecondary
        )

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
                    onValueChange = onValueChange,
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
                                text = placeholder,
                                style = MaterialTheme.typography.bodyMedium,
                                color = NexioColors.TextTertiary
                            )
                        }
                        innerTextField()
                    }
                )
            }
        }
    }
}

private fun imdbConnectionSummary(
    baseUrl: String,
    apiKey: String,
    notSetLabel: String
): String {
    val normalizedBaseUrl = baseUrl.trim()
    if (normalizedBaseUrl.isBlank() && apiKey.isBlank()) return notSetLabel
    val maskedApiKey = if (apiKey.trim().isBlank()) "key missing" else "••••${apiKey.trim().takeLast(4)}"
    return listOf(normalizedBaseUrl.ifBlank { "base URL missing" }, maskedApiKey).joinToString(" | ")
}
