@file:OptIn(ExperimentalTvMaterial3Api::class)

package com.nexio.tv.integrations.hyperhdr.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.tv.material3.Button
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Switch
import androidx.tv.material3.Text
import com.nexio.tv.integrations.hyperhdr.data.HdrMode

@Composable
fun HyperHdrSettingsContent(
    viewModel: HyperHdrSettingsViewModel = hiltViewModel(),
) {
    val cfg by viewModel.config.collectAsStateWithLifecycle()
    val testResult by viewModel.testResult.collectAsStateWithLifecycle()

    var hostField by remember(cfg.host) { mutableStateOf(TextFieldValue(cfg.host)) }
    var portField by remember(cfg.port) { mutableStateOf(TextFieldValue(cfg.port.toString())) }
    var jsonPortField by remember(cfg.jsonPort) {
        mutableStateOf(TextFieldValue(cfg.jsonPort.toString()))
    }
    var prioField by remember(cfg.priority) {
        mutableStateOf(TextFieldValue(cfg.priority.toString()))
    }

    Column(
        modifier = Modifier.fillMaxWidth().padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("HyperHDR ambilight", style = MaterialTheme.typography.headlineSmall)
        Text(
            "When enabled, decoded video frames are sent to a HyperHDR LED server " +
                "during playback. Default off — leaving disabled has zero performance impact.",
            style = MaterialTheme.typography.bodyMedium,
        )
        Spacer(Modifier.height(8.dp))

        Switch(checked = cfg.enabled, onCheckedChange = { viewModel.setEnabled(it) })

        Text("Server host (IP or hostname)")
        BasicTextField(
            value = hostField,
            onValueChange = { hostField = it; viewModel.setHost(it.text) },
            modifier = Modifier.fillMaxWidth(),
        )

        Text("FlatBuffer port (default 19400)")
        BasicTextField(
            value = portField,
            onValueChange = {
                portField = it
                it.text.toIntOrNull()?.let { p -> viewModel.setPort(p) }
            },
            modifier = Modifier.fillMaxWidth(),
        )

        Text("JSON-RPC port (default 19444)")
        BasicTextField(
            value = jsonPortField,
            onValueChange = {
                jsonPortField = it
                it.text.toIntOrNull()?.let { p -> viewModel.setJsonPort(p) }
            },
            modifier = Modifier.fillMaxWidth(),
        )

        Text("Priority (0–255, default 100)")
        BasicTextField(
            value = prioField,
            onValueChange = {
                prioField = it
                it.text.toIntOrNull()?.let { p -> viewModel.setPriority(p) }
            },
            modifier = Modifier.fillMaxWidth(),
        )

        Text("HDR mode")
        Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
            HdrModeRadio("Auto (detect from source)", cfg.hdrMode == HdrMode.Auto) {
                viewModel.setHdrMode(HdrMode.Auto)
            }
            Spacer(Modifier.height(4.dp))
            HdrModeRadio("Force SDR", cfg.hdrMode == HdrMode.ForceSdr) {
                viewModel.setHdrMode(HdrMode.ForceSdr)
            }
        }

        Spacer(Modifier.height(16.dp))
        Button(onClick = { viewModel.testConnection() }) { Text("Test connection") }
        Text(
            text = when (val r = testResult) {
                HyperHdrSettingsViewModel.TestResult.Idle -> ""
                HyperHdrSettingsViewModel.TestResult.Testing -> "Testing…"
                is HyperHdrSettingsViewModel.TestResult.Success ->
                    "✔ Connected to ${r.hostname}" +
                        (r.instanceName?.let { " · $it" } ?: "")
                is HyperHdrSettingsViewModel.TestResult.Failed -> "✘ ${r.message}"
            },
            style = MaterialTheme.typography.bodyMedium,
        )
    }
}

@Composable
private fun HdrModeRadio(label: String, selected: Boolean, onClick: () -> Unit) {
    Button(onClick = onClick) {
        Text(if (selected) "• $label" else "  $label")
    }
}
