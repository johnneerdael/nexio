@file:OptIn(androidx.tv.material3.ExperimentalTvMaterial3Api::class)

package com.nexio.tv.ui.screens.settings

import android.view.KeyEvent
import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
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
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.nexio.tv.R
import com.nexio.tv.ui.components.NexioDialog
import com.nexio.tv.ui.theme.NexioColors

@Composable
fun PosterRatingsSettingsContent(
    viewModel: PosterRatingsSettingsViewModel = hiltViewModel(),
    initialFocusRequester: FocusRequester? = null
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val validatingRpdb by viewModel.validatingRpdb.collectAsStateWithLifecycle()
    val validatingTop by viewModel.validatingTopPosters.collectAsStateWithLifecycle()
    var showRpdbDialog by remember { mutableStateOf(false) }
    var showTopDialog by remember { mutableStateOf(false) }
    val context = LocalContext.current
    val rpdbError = stringResource(R.string.poster_ratings_rpdb_invalid_api_key)
    val topError = stringResource(R.string.poster_ratings_top_invalid_api_key)

    LaunchedEffect(Unit) {
        viewModel.validationError.collect { provider ->
            val text = when (provider) {
                PosterRatingsProviderType.RPDB -> rpdbError
                PosterRatingsProviderType.TOP_POSTERS -> topError
            }
            Toast.makeText(context, text, Toast.LENGTH_SHORT).show()
        }
    }

    val rpdbRowEnabled = !uiState.topPostersEnabled || uiState.rpdbEnabled
    val topRowEnabled = !uiState.rpdbEnabled || uiState.topPostersEnabled

    Column(
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        SettingsDetailHeader(
            title = stringResource(R.string.poster_ratings_title),
            subtitle = stringResource(R.string.poster_ratings_subtitle)
        )

        SettingsGroupCard(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
        ) {
            SettingsToggleRow(
                title = stringResource(R.string.poster_ratings_rpdb_title),
                subtitle = stringResource(R.string.poster_ratings_rpdb_subtitle),
                checked = uiState.rpdbEnabled,
                enabled = rpdbRowEnabled,
                onToggle = { viewModel.onEvent(PosterRatingsSettingsEvent.ToggleRpdb(!uiState.rpdbEnabled)) },
                modifier = if (initialFocusRequester != null) {
                    Modifier.focusRequester(initialFocusRequester)
                } else {
                    Modifier
                }
            )

            SettingsActionRow(
                title = stringResource(R.string.poster_ratings_api_key_title),
                subtitle = stringResource(R.string.poster_ratings_rpdb_api_key_subtitle),
                value = maskApiKey(uiState.rpdbApiKey, stringResource(R.string.mdblist_not_set)),
                enabled = uiState.rpdbEnabled,
                onClick = { showRpdbDialog = true }
            )

            SettingsToggleRow(
                title = stringResource(R.string.poster_ratings_top_title),
                subtitle = stringResource(R.string.poster_ratings_top_subtitle),
                checked = uiState.topPostersEnabled,
                enabled = topRowEnabled,
                onToggle = { viewModel.onEvent(PosterRatingsSettingsEvent.ToggleTopPosters(!uiState.topPostersEnabled)) }
            )

            SettingsActionRow(
                title = stringResource(R.string.poster_ratings_api_key_title),
                subtitle = stringResource(R.string.poster_ratings_top_api_key_subtitle),
                value = maskApiKey(uiState.topPostersApiKey, stringResource(R.string.mdblist_not_set)),
                enabled = uiState.topPostersEnabled,
                onClick = { showTopDialog = true }
            )
        }
    }

    if (showRpdbDialog) {
        PosterApiKeyDialog(
            title = stringResource(R.string.poster_ratings_rpdb_dialog_title),
            subtitle = stringResource(R.string.poster_ratings_rpdb_dialog_subtitle),
            placeholder = stringResource(R.string.poster_ratings_rpdb_dialog_placeholder),
            currentValue = uiState.rpdbApiKey,
            validating = validatingRpdb,
            onSave = { value, onSuccess -> viewModel.validateAndSaveRpdbApiKey(value, onSuccess) },
            onClear = { viewModel.validateAndSaveRpdbApiKey("") {}; showRpdbDialog = false },
            onDismiss = { showRpdbDialog = false }
        )
    }

    if (showTopDialog) {
        PosterApiKeyDialog(
            title = stringResource(R.string.poster_ratings_top_dialog_title),
            subtitle = stringResource(R.string.poster_ratings_top_dialog_subtitle),
            placeholder = stringResource(R.string.poster_ratings_top_dialog_placeholder),
            currentValue = uiState.topPostersApiKey,
            validating = validatingTop,
            onSave = { value, onSuccess -> viewModel.validateAndSaveTopPostersApiKey(value, onSuccess) },
            onClear = { viewModel.validateAndSaveTopPostersApiKey("") {}; showTopDialog = false },
            onDismiss = { showTopDialog = false }
        )
    }
}

@Composable
private fun PosterApiKeyDialog(
    title: String,
    subtitle: String,
    placeholder: String,
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
        title = title,
        subtitle = subtitle,
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
