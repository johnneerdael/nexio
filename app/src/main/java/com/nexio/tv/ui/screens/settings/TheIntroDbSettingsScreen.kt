@file:OptIn(ExperimentalTvMaterial3Api::class)

package com.nexio.tv.ui.screens.settings

import android.view.KeyEvent
import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
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
import kotlinx.coroutines.flow.collectLatest

@Composable
fun TheIntroDbSettingsContent(
    viewModel: TheIntroDbSettingsViewModel = hiltViewModel(),
    initialFocusRequester: FocusRequester? = null
) {
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    var showApiKeyDialog by remember { mutableStateOf(false) }
    val context = androidx.compose.ui.platform.LocalContext.current
    val missingApiKeyMessage = stringResource(R.string.theintrodb_missing_api_key)

    LaunchedEffect(Unit) {
        viewModel.validationError.collectLatest { error ->
            if (error == TheIntroDbValidationError.MissingApiKey) {
                Toast.makeText(context, missingApiKeyMessage, Toast.LENGTH_SHORT).show()
            }
        }
    }

    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
        SettingsDetailHeader(
            title = stringResource(R.string.theid_title),
            subtitle = stringResource(R.string.theid_subtitle)
        )

        SettingsGroupCard(
            modifier = Modifier.fillMaxWidth().weight(1f)
        ) {
            LazyColumn(
                contentPadding = PaddingValues(bottom = 8.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                item(key = "theintrodb_enabled") {
                    SettingsToggleRow(
                        title = stringResource(R.string.theid_enable_title),
                        subtitle = stringResource(R.string.theid_enable_subtitle),
                        checked = settings.enabled,
                        onToggle = { viewModel.setEnabled(!settings.enabled) },
                        modifier = if (initialFocusRequester != null) Modifier.focusRequester(initialFocusRequester) else Modifier
                    )
                }
                item(key = "theintrodb_api_key") {
                    SettingsActionRow(
                        title = stringResource(R.string.theid_api_key_title),
                        subtitle = stringResource(R.string.theid_api_key_subtitle),
                        value = maskApiKey(settings.apiKey, stringResource(R.string.mdblist_not_set)),
                        onClick = { showApiKeyDialog = true }
                    )
                }
                item(key = "theintrodb_show_intro") {
                    SettingsToggleRow(
                        title = stringResource(R.string.theid_show_intro_title),
                        subtitle = stringResource(R.string.theid_show_intro_subtitle),
                        checked = settings.showIntroButton,
                        onToggle = { viewModel.setShowIntroButton(!settings.showIntroButton) },
                        enabled = settings.enabled
                    )
                }
                item(key = "theintrodb_show_recap") {
                    SettingsToggleRow(
                        title = stringResource(R.string.theid_show_recap_title),
                        subtitle = stringResource(R.string.theid_show_recap_subtitle),
                        checked = settings.showRecapButton,
                        onToggle = { viewModel.setShowRecapButton(!settings.showRecapButton) },
                        enabled = settings.enabled
                    )
                }
                item(key = "theintrodb_show_credits") {
                    SettingsToggleRow(
                        title = stringResource(R.string.theid_show_credits_title),
                        subtitle = stringResource(R.string.theid_show_credits_subtitle),
                        checked = settings.showCreditsButton,
                        onToggle = { viewModel.setShowCreditsButton(!settings.showCreditsButton) },
                        enabled = settings.enabled
                    )
                }
                item(key = "theintrodb_show_preview") {
                    SettingsToggleRow(
                        title = stringResource(R.string.theid_show_preview_title),
                        subtitle = stringResource(R.string.theid_show_preview_subtitle),
                        checked = settings.showPreviewButton,
                        onToggle = { viewModel.setShowPreviewButton(!settings.showPreviewButton) },
                        enabled = settings.enabled
                    )
                }
            }
        }
    }

    if (showApiKeyDialog) {
        TheIntroDbApiKeyDialog(
            currentValue = settings.apiKey,
            onSave = {
                viewModel.setApiKey(it)
                showApiKeyDialog = false
            },
            onClear = {
                viewModel.setApiKey("")
                showApiKeyDialog = false
            },
            onDismiss = { showApiKeyDialog = false }
        )
    }
}

@Composable
private fun TheIntroDbApiKeyDialog(
    currentValue: String,
    onSave: (String) -> Unit,
    onClear: () -> Unit,
    onDismiss: () -> Unit
) {
    var value by remember(currentValue) { mutableStateOf(currentValue) }
    var isInputFocused by remember { mutableStateOf(false) }
    val inputFocusRequester = remember { FocusRequester() }
    val keyboardController = LocalSoftwareKeyboardController.current

    LaunchedEffect(Unit) {
        inputFocusRequester.requestFocus()
    }

    NexioDialog(
        onDismiss = onDismiss,
        title = stringResource(R.string.theid_dialog_title),
        subtitle = stringResource(R.string.theid_dialog_subtitle),
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
                    keyboardActions = KeyboardActions(onDone = { keyboardController?.hide() }),
                    textStyle = MaterialTheme.typography.bodyMedium.copy(color = NexioColors.TextPrimary),
                    cursorBrush = SolidColor(
                        if (isInputFocused) NexioColors.Primary else androidx.compose.ui.graphics.Color.Transparent
                    ),
                    decorationBox = { innerTextField ->
                        if (value.isBlank()) {
                            Text(
                                text = stringResource(R.string.theid_dialog_placeholder),
                                style = MaterialTheme.typography.bodyMedium,
                                color = NexioColors.TextTertiary
                            )
                        }
                        innerTextField()
                    }
                )
            }
        }

        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
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
                onClick = { onSave(value) },
                colors = ButtonDefaults.colors(
                    containerColor = NexioColors.BackgroundCard,
                    contentColor = NexioColors.TextPrimary
                )
            ) { Text(stringResource(R.string.action_save)) }
        }
    }
}

private fun maskApiKey(value: String, notSetLabel: String): String {
    val trimmed = value.trim()
    if (trimmed.isBlank()) return notSetLabel
    val suffix = trimmed.takeLast(4)
    return "Stored ••••$suffix"
}
