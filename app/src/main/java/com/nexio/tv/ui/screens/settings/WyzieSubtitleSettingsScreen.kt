@file:OptIn(ExperimentalTvMaterial3Api::class)

package com.nexio.tv.ui.screens.settings

import android.view.KeyEvent
import androidx.compose.foundation.BorderStroke
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.runtime.Composable
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

@Composable
fun WyzieSubtitleSettingsContent(
    viewModel: WyzieSubtitleSettingsViewModel = hiltViewModel(),
    initialFocusRequester: FocusRequester? = null,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    var showApiKeyDialog by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        SettingsDetailHeader(
            title = stringResource(R.string.wyzie_subtitles_title),
            subtitle = stringResource(R.string.wyzie_subtitles_subtitle),
        )

        SettingsGroupCard(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f),
        ) {
            LazyColumn(
                contentPadding = PaddingValues(bottom = 8.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                item(key = "wyzie_enabled") {
                    SettingsToggleRow(
                        title = stringResource(R.string.wyzie_enable_title),
                        subtitle = stringResource(R.string.wyzie_enable_subtitle),
                        checked = state.enabled,
                        onToggle = { viewModel.onSetEnabled(!state.enabled) },
                        modifier = if (initialFocusRequester != null) {
                            Modifier.focusRequester(initialFocusRequester)
                        } else {
                            Modifier
                        },
                    )
                }

                item(key = "wyzie_api_key") {
                    SettingsActionRow(
                        title = stringResource(R.string.wyzie_api_key_title),
                        subtitle = stringResource(R.string.wyzie_api_key_subtitle),
                        value = maskWyzieKey(state.apiKey, stringResource(R.string.mdblist_not_set)),
                        onClick = { showApiKeyDialog = true },
                    )
                }
            }
        }
    }

    if (showApiKeyDialog) {
        WyzieApiKeyDialog(
            currentValue = state.apiKey.orEmpty(),
            onSave = { value ->
                viewModel.onSetApiKey(value)
                showApiKeyDialog = false
            },
            onClear = {
                viewModel.onClearApiKey()
                showApiKeyDialog = false
            },
            onDismiss = { showApiKeyDialog = false },
        )
    }
}

@Composable
private fun WyzieApiKeyDialog(
    currentValue: String,
    onSave: (String) -> Unit,
    onClear: () -> Unit,
    onDismiss: () -> Unit,
) {
    var value by remember(currentValue) { mutableStateOf(currentValue) }
    var isInputFocused by remember { mutableStateOf(false) }
    val inputFocusRequester = remember { FocusRequester() }
    val keyboardController = LocalSoftwareKeyboardController.current

    NexioDialog(
        onDismiss = onDismiss,
        title = stringResource(R.string.wyzie_dialog_title),
        subtitle = stringResource(R.string.wyzie_dialog_subtitle),
        width = 700.dp,
    ) {
        Card(
            onClick = { inputFocusRequester.requestFocus() },
            modifier = Modifier
                .fillMaxWidth()
                .onFocusChanged { isInputFocused = it.isFocused || it.hasFocus },
            colors = CardDefaults.colors(
                containerColor = NexioColors.BackgroundElevated,
                focusedContainerColor = NexioColors.BackgroundElevated,
            ),
            border = CardDefaults.border(
                border = Border(
                    border = BorderStroke(1.dp, NexioColors.Border),
                    shape = RoundedCornerShape(10.dp),
                ),
                focusedBorder = Border(
                    border = BorderStroke(2.dp, NexioColors.FocusRing),
                    shape = RoundedCornerShape(10.dp),
                ),
            ),
            shape = CardDefaults.shape(RoundedCornerShape(10.dp)),
            scale = CardDefaults.scale(focusedScale = 1f),
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
                        if (isInputFocused) NexioColors.Primary
                        else androidx.compose.ui.graphics.Color.Transparent
                    ),
                    decorationBox = { innerTextField ->
                        if (value.isBlank()) {
                            Text(
                                text = stringResource(R.string.wyzie_dialog_placeholder),
                                style = MaterialTheme.typography.bodyMedium,
                                color = NexioColors.TextTertiary,
                            )
                        }
                        innerTextField()
                    },
                )
            }
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.End,
        ) {
            Button(
                onClick = onDismiss,
                colors = ButtonDefaults.colors(
                    containerColor = NexioColors.BackgroundElevated,
                    contentColor = NexioColors.TextPrimary,
                ),
            ) { Text(stringResource(R.string.action_cancel)) }

            Spacer(modifier = Modifier.width(8.dp))
            Button(
                onClick = onClear,
                colors = ButtonDefaults.colors(
                    containerColor = NexioColors.BackgroundElevated,
                    contentColor = NexioColors.TextPrimary,
                ),
            ) { Text(stringResource(R.string.action_clear)) }

            Spacer(modifier = Modifier.width(8.dp))
            Button(
                onClick = { onSave(value) },
                colors = ButtonDefaults.colors(
                    containerColor = NexioColors.BackgroundCard,
                    contentColor = NexioColors.TextPrimary,
                ),
            ) { Text(stringResource(R.string.action_save)) }
        }
    }
}
