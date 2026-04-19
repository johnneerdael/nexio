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
import com.nexio.tv.domain.model.SubtitleTranslationProvider
import com.nexio.tv.ui.components.NexioDialog
import com.nexio.tv.ui.theme.NexioColors

private val providerOptions = listOf(
    SubtitleTranslationProvider.OPENAI to "OpenAI-compatible",
    SubtitleTranslationProvider.ANTHROPIC to "Anthropic-compatible",
    SubtitleTranslationProvider.GEMINI to "Google Gemini",
    SubtitleTranslationProvider.DASHSCOPE to "Alibaba DashScope (Qwen-MT)"
)

private enum class SubtitleTranslationSettingsDialog {
    Provider,
    Model,
    Endpoint,
    ApiKey
}

@Composable
fun SubtitleTranslationSettingsScreen(
    viewModel: SubtitleTranslationSettingsViewModel = hiltViewModel(),
    onBackPress: () -> Unit
) {
    BackHandler { onBackPress() }

    SettingsStandaloneScaffold(
        title = stringResource(R.string.subtitle_translation_title),
        subtitle = stringResource(R.string.subtitle_translation_subtitle)
    ) {
        SubtitleTranslationSettingsContent(viewModel = viewModel)
    }
}

@Composable
fun SubtitleTranslationSettingsContent(
    viewModel: SubtitleTranslationSettingsViewModel = hiltViewModel(),
    initialFocusRequester: FocusRequester? = null
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    var activeDialog by remember { mutableStateOf<SubtitleTranslationSettingsDialog?>(null) }
    val context = LocalContext.current
    val missingApiKeyMsg = stringResource(R.string.subtitle_translation_missing_api_key)
    val missingModelMsg = stringResource(R.string.subtitle_translation_missing_model)
    val invalidEndpointMsg = stringResource(R.string.subtitle_translation_invalid_endpoint)

    LaunchedEffect(Unit) {
        viewModel.validationError.collect { error ->
            val text = when (error) {
                SubtitleTranslationValidationError.MissingApiKey -> missingApiKeyMsg
                SubtitleTranslationValidationError.MissingModel -> missingModelMsg
                SubtitleTranslationValidationError.InvalidEndpoint -> invalidEndpointMsg
            }
            Toast.makeText(context, text, Toast.LENGTH_SHORT).show()
        }
    }

    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
        SettingsDetailHeader(
            title = stringResource(R.string.subtitle_translation_title),
            subtitle = stringResource(R.string.subtitle_translation_subtitle)
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
                item(key = "subtitle_translation_enabled") {
                    SettingsToggleRow(
                        title = stringResource(R.string.subtitle_translation_enable_title),
                        subtitle = stringResource(R.string.subtitle_translation_enable_subtitle),
                        checked = uiState.enabled,
                        onToggle = { viewModel.setEnabled(!uiState.enabled) },
                        modifier = if (initialFocusRequester != null) {
                            Modifier.focusRequester(initialFocusRequester)
                        } else {
                            Modifier
                        }
                    )
                }

                item(key = "subtitle_translation_provider") {
                    SettingsActionRow(
                        title = stringResource(R.string.subtitle_translation_provider_title),
                        subtitle = stringResource(R.string.subtitle_translation_provider_subtitle),
                        value = providerLabel(uiState.provider),
                        onClick = { activeDialog = SubtitleTranslationSettingsDialog.Provider }
                    )
                }

                item(key = "subtitle_translation_model") {
                    SettingsActionRow(
                        title = stringResource(R.string.subtitle_translation_model_title),
                        subtitle = stringResource(R.string.subtitle_translation_model_subtitle),
                        value = uiState.model.ifBlank { stringResource(R.string.mdblist_not_set) },
                        onClick = { activeDialog = SubtitleTranslationSettingsDialog.Model }
                    )
                }

                item(key = "subtitle_translation_endpoint") {
                    SettingsActionRow(
                        title = stringResource(R.string.subtitle_translation_endpoint_title),
                        subtitle = stringResource(R.string.subtitle_translation_endpoint_subtitle),
                        value = uiState.baseUrl.ifBlank { stringResource(R.string.mdblist_not_set) },
                        onClick = { activeDialog = SubtitleTranslationSettingsDialog.Endpoint }
                    )
                }

                item(key = "subtitle_translation_api_key") {
                    SettingsActionRow(
                        title = stringResource(R.string.subtitle_translation_api_key_title),
                        subtitle = "",
                        value = maskSubtitleTranslationApiKey(uiState.apiKey, stringResource(R.string.mdblist_not_set)),
                        onClick = { activeDialog = SubtitleTranslationSettingsDialog.ApiKey }
                    )
                }

                item(key = "subtitle_translation_ass_ssa_system_prompt") {
                    SettingsToggleRow(
                        title = stringResource(R.string.subtitle_translation_ass_ssa_system_prompt_title),
                        subtitle = stringResource(R.string.subtitle_translation_ass_ssa_system_prompt_subtitle),
                        checked = uiState.assSsaSystemPromptEnabled,
                        onToggle = {
                            viewModel.setAssSsaSystemPromptEnabled(!uiState.assSsaSystemPromptEnabled)
                        }
                    )
                }

                item(key = "subtitle_translation_subrip_system_prompt") {
                    SettingsToggleRow(
                        title = stringResource(R.string.subtitle_translation_subrip_system_prompt_title),
                        subtitle = stringResource(R.string.subtitle_translation_subrip_system_prompt_subtitle),
                        checked = uiState.subRipSystemPromptEnabled,
                        onToggle = {
                            viewModel.setSubRipSystemPromptEnabled(!uiState.subRipSystemPromptEnabled)
                        }
                    )
                }
            }
        }
    }

    when (activeDialog) {
        SubtitleTranslationSettingsDialog.Provider -> {
            SubtitleTranslationProviderDialog(
                currentProvider = uiState.provider,
                onProviderSelected = { provider ->
                    viewModel.setProvider(provider)
                    activeDialog = null
                },
                onDismiss = { activeDialog = null }
            )
        }
        SubtitleTranslationSettingsDialog.Model -> {
            SubtitleTranslationValueDialog(
                title = stringResource(R.string.subtitle_translation_model_title),
                subtitle = stringResource(R.string.subtitle_translation_model_subtitle),
                currentValue = uiState.model,
                placeholder = stringResource(R.string.subtitle_translation_model_title),
                onSave = { value, onSuccess -> viewModel.saveModel(value, onSuccess) },
                onClear = { viewModel.saveModel("") {}; activeDialog = null },
                onDismiss = { activeDialog = null }
            )
        }
        SubtitleTranslationSettingsDialog.Endpoint -> {
            SubtitleTranslationValueDialog(
                title = stringResource(R.string.subtitle_translation_endpoint_title),
                subtitle = stringResource(R.string.subtitle_translation_endpoint_subtitle),
                currentValue = uiState.baseUrl,
                placeholder = stringResource(R.string.subtitle_translation_endpoint_title),
                onSave = { value, onSuccess -> viewModel.saveBaseUrl(value, onSuccess) },
                onClear = { viewModel.saveBaseUrl("") {}; activeDialog = null },
                onDismiss = { activeDialog = null }
            )
        }
        SubtitleTranslationSettingsDialog.ApiKey -> {
            SubtitleTranslationValueDialog(
                title = stringResource(R.string.subtitle_translation_api_key_title),
                subtitle = null,
                currentValue = uiState.apiKey,
                placeholder = stringResource(R.string.subtitle_translation_api_key_title),
                onSave = { value, onSuccess -> viewModel.saveApiKey(value, onSuccess) },
                onClear = { viewModel.saveApiKey("") {}; activeDialog = null },
                onDismiss = { activeDialog = null }
            )
        }
        null -> Unit
    }
}

@Composable
private fun SubtitleTranslationProviderDialog(
    currentProvider: SubtitleTranslationProvider,
    onProviderSelected: (SubtitleTranslationProvider) -> Unit,
    onDismiss: () -> Unit
) {
    NexioDialog(
        onDismiss = onDismiss,
        title = stringResource(R.string.subtitle_translation_provider_title),
        subtitle = stringResource(R.string.subtitle_translation_provider_subtitle),
        width = 620.dp,
        suppressFirstKeyUp = false
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            providerOptions.forEach { (provider, label) ->
                val selected = currentProvider == provider
                Button(
                    onClick = { onProviderSelected(provider) },
                    colors = ButtonDefaults.colors(
                        containerColor = if (selected) NexioColors.Primary else NexioColors.BackgroundCard,
                        contentColor = if (selected) Color.Black else NexioColors.TextPrimary
                    ),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(label)
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End
            ) {
                Button(
                    onClick = onDismiss,
                    colors = ButtonDefaults.colors(
                        containerColor = NexioColors.BackgroundCard,
                        contentColor = NexioColors.TextPrimary
                    )
                ) {
                    Text(stringResource(R.string.action_cancel))
                }
            }
        }
    }
}

@Composable
private fun SubtitleTranslationValueDialog(
    title: String,
    subtitle: String?,
    currentValue: String,
    placeholder: String,
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
                    keyboardActions = KeyboardActions(onDone = { keyboardController?.hide() }),
                    textStyle = MaterialTheme.typography.bodyMedium.copy(color = NexioColors.TextPrimary),
                    cursorBrush = SolidColor(
                        if (isInputFocused) NexioColors.Primary
                        else Color.Transparent
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
                onClick = { onSave(value) { onDismiss() } },
                colors = ButtonDefaults.colors(
                    containerColor = NexioColors.BackgroundCard,
                    contentColor = NexioColors.TextPrimary
                )
            ) { Text(stringResource(R.string.action_save)) }
        }
    }
}

private fun providerLabel(provider: SubtitleTranslationProvider): String {
    return providerOptions.first { it.first == provider }.second
}

private fun maskSubtitleTranslationApiKey(key: String, notSetLabel: String): String {
    val trimmed = key.trim()
    if (trimmed.isBlank()) return notSetLabel
    return if (trimmed.length <= 4) "****" else "******${trimmed.takeLast(4)}"
}
