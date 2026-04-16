@file:OptIn(ExperimentalTvMaterial3Api::class)

package com.nexio.tv.ui.screens.settings

import android.view.KeyEvent
import androidx.activity.compose.BackHandler
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
import androidx.compose.ui.graphics.Color
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
import com.nexio.tv.core.tvdb.TvdbValidationStatus
import com.nexio.tv.ui.components.NexioDialog
import com.nexio.tv.ui.theme.NexioColors

@Composable
fun TvdbSettingsScreen(
    viewModel: TvdbSettingsViewModel = hiltViewModel(),
    onBackPress: () -> Unit
) {
    BackHandler { onBackPress() }

    SettingsStandaloneScaffold(
        title = stringResource(R.string.tvdb_title),
        subtitle = stringResource(R.string.tvdb_subtitle)
    ) {
        TvdbSettingsContent(viewModel = viewModel)
    }
}

@Composable
fun TvdbSettingsContent(
    viewModel: TvdbSettingsViewModel = hiltViewModel(),
    initialFocusRequester: FocusRequester? = null
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    var showCredentialsDialog by remember { mutableStateOf(false) }
    var validationMessage by remember { mutableStateOf<String?>(null) }
    val missingApiKeyMessage = stringResource(R.string.tvdb_missing_api_key)
    val invalidCredentialsMessage = stringResource(R.string.tvdb_invalid_credentials)

    LaunchedEffect(Unit) {
        viewModel.validationError.collect { error ->
            validationMessage = when (error) {
                TvdbValidationError.MissingApiKey -> missingApiKeyMessage
                TvdbValidationError.InvalidCredentials -> invalidCredentialsMessage
            }
        }
    }

    Column(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        SettingsDetailHeader(
            title = stringResource(R.string.tvdb_title),
            subtitle = stringResource(R.string.tvdb_subtitle)
        )

        SettingsGroupCard(
            modifier = Modifier.fillMaxWidth()
        ) {
            LazyColumn(
                contentPadding = PaddingValues(bottom = 8.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                item(key = "tvdb_credentials") {
                    SettingsActionRow(
                        title = stringResource(R.string.tvdb_credentials_title),
                        subtitle = stringResource(R.string.tvdb_credentials_subtitle),
                        value = uiState.credentialDisplayValue,
                        enabled = true,
                        onClick = {
                            validationMessage = null
                            showCredentialsDialog = true
                        },
                        modifier = if (initialFocusRequester != null) {
                            Modifier.focusRequester(initialFocusRequester)
                        } else {
                            Modifier
                        }
                    )
                }

                item(key = "tvdb_status") {
                    SettingsActionRow(
                        title = stringResource(R.string.tvdb_status_title),
                        subtitle = tvdbStatusSubtitle(uiState),
                        value = tvdbStatusValue(uiState.validationStatus),
                        enabled = false,
                        onClick = {}
                    )
                }

                uiState.tvdbStatusLine?.let { statusLine ->
                    item(key = "tvdb_diagnostics_status") {
                        SettingsActionRow(
                            title = stringResource(R.string.tvdb_diagnostics_status_title),
                            subtitle = statusLine,
                            value = "",
                            enabled = false,
                            onClick = {}
                        )
                    }
                }
            }
        }

        SettingsGroupCard(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f),
            title = stringResource(R.string.tvdb_provider_precedence_title)
        ) {
            Text(
                text = stringResource(R.string.provider_precedence_summary),
                style = MaterialTheme.typography.bodyMedium,
                color = NexioColors.TextSecondary
            )

            validationMessage?.let { message ->
                Text(
                    text = message,
                    style = MaterialTheme.typography.bodySmall,
                    color = NexioColors.Error
                )
            }
        }
    }

    if (showCredentialsDialog) {
        TvdbCredentialsDialog(
            currentApiKey = uiState.apiKey,
            validating = uiState.validationStatus == TvdbValidationStatus.VALIDATING,
            validationMessage = validationMessage,
            onSave = { apiKey, subscriberPin ->
                validationMessage = null
                viewModel.saveCredentials(
                    apiKey = apiKey,
                    subscriberPin = subscriberPin,
                    onSuccess = { showCredentialsDialog = false }
                )
            },
            onClear = {
                validationMessage = null
                viewModel.clearCredentials()
                showCredentialsDialog = false
            },
            onDismiss = { showCredentialsDialog = false }
        )
    }
}

@Composable
private fun TvdbCredentialsDialog(
    currentApiKey: String,
    validating: Boolean,
    validationMessage: String?,
    onSave: (apiKey: String, subscriberPin: String) -> Unit,
    onClear: () -> Unit,
    onDismiss: () -> Unit
) {
    var apiKey by remember(currentApiKey) { mutableStateOf(currentApiKey) }
    var subscriberPin by remember { mutableStateOf("") }

    NexioDialog(
        onDismiss = onDismiss,
        title = stringResource(R.string.tvdb_dialog_title),
        subtitle = stringResource(R.string.tvdb_dialog_subtitle),
        width = 700.dp
    ) {
        TvdbCredentialField(
            value = apiKey,
            placeholder = stringResource(R.string.tvdb_api_key_placeholder),
            onValueChange = { apiKey = it }
        )

        TvdbCredentialField(
            value = subscriberPin,
            placeholder = stringResource(R.string.tvdb_pin_placeholder),
            onValueChange = { subscriberPin = it }
        )

        validationMessage?.let { message ->
            Text(
                text = message,
                style = MaterialTheme.typography.bodySmall,
                color = NexioColors.Error
            )
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.End
        ) {
            Button(
                onClick = onDismiss,
                enabled = !validating,
                colors = ButtonDefaults.colors(
                    containerColor = NexioColors.BackgroundElevated,
                    contentColor = NexioColors.TextPrimary
                )
            ) { Text(stringResource(R.string.tvdb_dialog_close_action)) }

            Spacer(modifier = Modifier.width(8.dp))

            Button(
                onClick = onClear,
                enabled = !validating,
                colors = ButtonDefaults.colors(
                    containerColor = NexioColors.BackgroundElevated,
                    contentColor = NexioColors.Error
                )
            ) { Text(stringResource(R.string.tvdb_dialog_clear_action)) }

            Spacer(modifier = Modifier.width(8.dp))

            Button(
                onClick = {
                    if (!validating) {
                        onSave(apiKey, subscriberPin)
                    }
                },
                enabled = !validating,
                colors = ButtonDefaults.colors(
                    containerColor = NexioColors.BackgroundCard,
                    contentColor = NexioColors.TextPrimary,
                    disabledContainerColor = NexioColors.BackgroundCard,
                    disabledContentColor = NexioColors.TextSecondary
                )
            ) {
                Text(
                    if (validating) {
                        stringResource(R.string.tvdb_dialog_saving)
                    } else {
                        stringResource(R.string.tvdb_dialog_save_action)
                    }
                )
            }
        }
    }
}

@Composable
private fun TvdbCredentialField(
    value: String,
    placeholder: String,
    onValueChange: (String) -> Unit
) {
    var isInputFocused by remember { mutableStateOf(false) }
    val inputFocusRequester = remember { FocusRequester() }
    val keyboardController = LocalSoftwareKeyboardController.current

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
                border = BorderStroke(1.dp, NexioColors.Border),
                shape = androidx.compose.foundation.shape.RoundedCornerShape(10.dp)
            ),
            focusedBorder = Border(
                border = BorderStroke(2.dp, NexioColors.FocusRing),
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
                    if (isInputFocused) NexioColors.Primary else Color.Transparent
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

@Composable
private fun tvdbStatusSubtitle(uiState: TvdbSettingsUiState): String {
    val base = when (uiState.validationStatus) {
        TvdbValidationStatus.NOT_CONFIGURED -> stringResource(R.string.tvdb_status_subtitle_not_configured)
        TvdbValidationStatus.VALIDATING -> stringResource(R.string.tvdb_status_subtitle_validating)
        TvdbValidationStatus.VALID -> stringResource(R.string.tvdb_status_subtitle_valid)
        TvdbValidationStatus.INVALID -> stringResource(R.string.tvdb_status_subtitle_invalid)
        TvdbValidationStatus.FALLBACK_ACTIVE -> stringResource(R.string.tvdb_status_subtitle_fallback_active)
    }

    return if (uiState.lastFailure.isNotBlank()) {
        "${stringResource(R.string.tvdb_last_failure_prefix)} ${uiState.lastFailure}"
    } else {
        base
    }
}

@Composable
private fun tvdbStatusValue(status: TvdbValidationStatus): String {
    return when (status) {
        TvdbValidationStatus.NOT_CONFIGURED -> stringResource(R.string.tvdb_status_not_configured)
        TvdbValidationStatus.VALIDATING -> stringResource(R.string.tvdb_status_validating)
        TvdbValidationStatus.VALID -> stringResource(R.string.tvdb_status_valid)
        TvdbValidationStatus.INVALID -> stringResource(R.string.tvdb_status_invalid)
        TvdbValidationStatus.FALLBACK_ACTIVE -> stringResource(R.string.tvdb_status_fallback_active)
    }
}
