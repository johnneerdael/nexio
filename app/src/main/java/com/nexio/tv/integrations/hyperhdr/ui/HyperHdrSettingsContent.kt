@file:OptIn(ExperimentalTvMaterial3Api::class)

package com.nexio.tv.integrations.hyperhdr.ui

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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
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
import androidx.compose.ui.text.input.KeyboardType
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
import com.nexio.tv.integrations.hyperhdr.data.HdrMode
import com.nexio.tv.ui.components.NexioDialog
import com.nexio.tv.ui.screens.settings.SettingsActionRow
import com.nexio.tv.ui.screens.settings.SettingsDetailHeader
import com.nexio.tv.ui.screens.settings.SettingsGroupCard
import com.nexio.tv.ui.screens.settings.SettingsToggleRow
import com.nexio.tv.ui.theme.NexioColors

@Composable
fun HyperHdrSettingsContent(
    viewModel: HyperHdrSettingsViewModel = hiltViewModel(),
    initialFocusRequester: FocusRequester? = null,
) {
    val cfg by viewModel.config.collectAsStateWithLifecycle()
    val testResult by viewModel.testResult.collectAsStateWithLifecycle()

    var showHostDialog by remember { mutableStateOf(false) }
    var showPortDialog by remember { mutableStateOf(false) }
    var showJsonPortDialog by remember { mutableStateOf(false) }
    var showPriorityDialog by remember { mutableStateOf(false) }
    var showHdrModeDialog by remember { mutableStateOf(false) }
    var showTokenDialog by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        SettingsDetailHeader(
            title = "HyperHDR ambilight",
            subtitle = "Stream decoded video frames to a HyperHDR LED server during playback. Default off."
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
                item(key = "hyperhdr_enabled") {
                    SettingsToggleRow(
                        title = "Enabled",
                        subtitle = "Capture and send frames during playback",
                        checked = cfg.enabled,
                        onToggle = { viewModel.setEnabled(!cfg.enabled) },
                        modifier = if (initialFocusRequester != null) {
                            Modifier.focusRequester(initialFocusRequester)
                        } else {
                            Modifier
                        }
                    )
                }

                item(key = "hyperhdr_host") {
                    SettingsActionRow(
                        title = "Host",
                        subtitle = "HyperHDR server IP or hostname",
                        value = cfg.host.ifEmpty { "Not set" },
                        onClick = { showHostDialog = true }
                    )
                }

                item(key = "hyperhdr_port") {
                    SettingsActionRow(
                        title = "FlatBuffer port",
                        subtitle = "Default 19400",
                        value = cfg.port.toString(),
                        onClick = { showPortDialog = true }
                    )
                }

                item(key = "hyperhdr_json_port") {
                    SettingsActionRow(
                        title = "JSON-RPC port",
                        subtitle = "Default 19444",
                        value = cfg.jsonPort.toString(),
                        onClick = { showJsonPortDialog = true }
                    )
                }

                item(key = "hyperhdr_priority") {
                    SettingsActionRow(
                        title = "Priority",
                        subtitle = "0–255 (default 100, lower preempts higher)",
                        value = cfg.priority.toString(),
                        onClick = { showPriorityDialog = true }
                    )
                }

                item(key = "hyperhdr_token") {
                    SettingsActionRow(
                        title = "JSON API token",
                        subtitle = "Optional — required only if HyperHDR has token auth enabled",
                        value = when {
                            cfg.jsonToken.isBlank() -> "Not set"
                            cfg.jsonToken.length <= 4 -> "Set"
                            else -> "•••••" + cfg.jsonToken.takeLast(4)
                        },
                        onClick = { showTokenDialog = true },
                    )
                }

                item(key = "hyperhdr_hdr_mode") {
                    if (viewModel.composesWideColor) {
                        SettingsActionRow(
                            title = "HDR mode",
                            subtitle = "Choose between auto-detect and forced SDR",
                            value = if (cfg.hdrMode == HdrMode.Auto) "Auto" else "Force SDR",
                            onClick = { showHdrModeDialog = true }
                        )
                    } else {
                        SettingsActionRow(
                            title = "HDR mode",
                            subtitle = "Compositor is sRGB-only; ambilight runs in Force SDR. HDR ambilight requires a wide-color-capable Android TV (Google TV Streamer, Shield, or any Android 13+ device whose Display reports a BT2020/BT2100 mode).",
                            value = "Force SDR (locked)",
                            enabled = false,
                            onClick = {}
                        )
                    }
                }

                item(key = "hyperhdr_test_connection") {
                    SettingsActionRow(
                        title = "Test connection",
                        subtitle = "Validate FlatBuffer + JSON ports against the server",
                        value = null,
                        onClick = { viewModel.testConnection() }
                    )
                }

                val result = testResult
                if (result != HyperHdrSettingsViewModel.TestResult.Idle) {
                    item(key = "hyperhdr_test_result") {
                        when (result) {
                            HyperHdrSettingsViewModel.TestResult.Testing -> {
                                SettingsActionRow(
                                    title = "Testing…",
                                    subtitle = null,
                                    enabled = false,
                                    onClick = {}
                                )
                            }
                            is HyperHdrSettingsViewModel.TestResult.Success -> {
                                SettingsActionRow(
                                    title = "✔ Connected",
                                    subtitle = "${result.hostname}${if (result.instanceName != null) " · ${result.instanceName}" else ""}",
                                    enabled = false,
                                    onClick = {}
                                )
                            }
                            is HyperHdrSettingsViewModel.TestResult.Failed -> {
                                SettingsActionRow(
                                    title = "✘ Failed",
                                    subtitle = result.message,
                                    enabled = false,
                                    onClick = {}
                                )
                            }
                            HyperHdrSettingsViewModel.TestResult.Idle -> {
                                // Excluded by the outer if-check; unreachable here
                            }
                        }
                    }
                }
            }
        }
    }

    if (showHostDialog) {
        HyperHdrTextInputDialog(
            title = "Host",
            subtitle = "HyperHDR server IP or hostname",
            initialValue = cfg.host,
            keyboardNumeric = false,
            onSave = { viewModel.setHost(it) },
            onDismiss = { showHostDialog = false }
        )
    }

    if (showPortDialog) {
        HyperHdrTextInputDialog(
            title = "FlatBuffer port",
            subtitle = "Default 19400",
            initialValue = cfg.port.toString(),
            keyboardNumeric = true,
            onSave = { it.toIntOrNull()?.let { p -> viewModel.setPort(p) } },
            onDismiss = { showPortDialog = false }
        )
    }

    if (showJsonPortDialog) {
        HyperHdrTextInputDialog(
            title = "JSON-RPC port",
            subtitle = "Default 19444",
            initialValue = cfg.jsonPort.toString(),
            keyboardNumeric = true,
            onSave = { it.toIntOrNull()?.let { p -> viewModel.setJsonPort(p) } },
            onDismiss = { showJsonPortDialog = false }
        )
    }

    if (showPriorityDialog) {
        HyperHdrTextInputDialog(
            title = "Priority",
            subtitle = "0–255 (default 100, lower preempts higher)",
            initialValue = cfg.priority.toString(),
            keyboardNumeric = true,
            onSave = { it.toIntOrNull()?.let { p -> viewModel.setPriority(p) } },
            onDismiss = { showPriorityDialog = false }
        )
    }

    if (showHdrModeDialog) {
        HyperHdrHdrModeDialog(
            current = cfg.hdrMode,
            onSelect = { viewModel.setHdrMode(it) },
            onDismiss = { showHdrModeDialog = false }
        )
    }

    if (showTokenDialog) {
        HyperHdrTextInputDialog(
            title = "JSON API token",
            subtitle = "Leave blank if HyperHDR doesn't require a token",
            initialValue = cfg.jsonToken,
            keyboardNumeric = false,
            onSave = { viewModel.setJsonToken(it) },
            onDismiss = { showTokenDialog = false },
        )
    }
}

@Composable
private fun HyperHdrTextInputDialog(
    title: String,
    subtitle: String?,
    initialValue: String,
    keyboardNumeric: Boolean,
    onSave: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var currentText by remember(initialValue) { mutableStateOf(initialValue) }
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
                    border = BorderStroke(1.dp, NexioColors.Border),
                    shape = RoundedCornerShape(10.dp)
                ),
                focusedBorder = Border(
                    border = BorderStroke(2.dp, NexioColors.FocusRing),
                    shape = RoundedCornerShape(10.dp)
                )
            ),
            shape = CardDefaults.shape(RoundedCornerShape(10.dp)),
            scale = CardDefaults.scale(focusedScale = 1f)
        ) {
            Box(modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp)) {
                BasicTextField(
                    value = currentText,
                    onValueChange = { currentText = it },
                    modifier = Modifier
                        .fillMaxWidth()
                        .focusRequester(inputFocusRequester)
                        .onKeyEvent { event ->
                            event.nativeKeyEvent.keyCode == KeyEvent.KEYCODE_DPAD_CENTER &&
                                event.nativeKeyEvent.action == KeyEvent.ACTION_DOWN
                        },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(
                        keyboardType = if (keyboardNumeric) KeyboardType.Number else KeyboardType.Text
                    ),
                    keyboardActions = KeyboardActions(
                        onDone = { keyboardController?.hide() }
                    ),
                    textStyle = MaterialTheme.typography.bodyMedium.copy(color = NexioColors.TextPrimary),
                    cursorBrush = SolidColor(
                        if (isInputFocused) NexioColors.Primary
                        else androidx.compose.ui.graphics.Color.Transparent
                    ),
                    decorationBox = { innerTextField ->
                        if (currentText.isBlank()) {
                            Text(
                                text = title,
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
            ) { Text("Cancel") }

            Spacer(modifier = Modifier.width(8.dp))
            Button(
                onClick = {
                    onSave(currentText)
                    onDismiss()
                },
                colors = ButtonDefaults.colors(
                    containerColor = NexioColors.BackgroundCard,
                    contentColor = NexioColors.TextPrimary
                )
            ) { Text("Save") }
        }
    }

    LaunchedEffect(Unit) {
        inputFocusRequester.requestFocus()
    }
}

@Composable
private fun HyperHdrHdrModeDialog(
    current: HdrMode,
    onSelect: (HdrMode) -> Unit,
    onDismiss: () -> Unit,
) {
    NexioDialog(
        onDismiss = onDismiss,
        title = "HDR mode",
        subtitle = "Choose how frames are encoded for HyperHDR",
        width = 520.dp
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            SettingsActionRow(
                title = "Auto",
                subtitle = "Detect HDR from source (HDR10/HLG → P010, SDR → NV12)",
                value = if (current == HdrMode.Auto) "Selected" else null,
                onClick = {
                    onSelect(HdrMode.Auto)
                    onDismiss()
                }
            )
            SettingsActionRow(
                title = "Force SDR",
                subtitle = "Always send NV12 regardless of source colorimetry",
                value = if (current == HdrMode.ForceSdr) "Selected" else null,
                onClick = {
                    onSelect(HdrMode.ForceSdr)
                    onDismiss()
                }
            )

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
                ) { Text("Cancel") }
            }
        }
    }
}
