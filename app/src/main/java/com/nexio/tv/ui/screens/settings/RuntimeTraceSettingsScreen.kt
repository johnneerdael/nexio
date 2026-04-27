package com.nexio.tv.ui.screens.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.nexio.tv.core.trace.TraceMode

// TODO(trace): wire Clear (deletes traces dir) and Export (calls TraceBundleExporter + share intent).
@Composable
fun RuntimeTraceSettingsScreen(
    viewModel: RuntimeTraceSettingsViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsState()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text("Runtime & Metadata Trace")
        Spacer(Modifier.height(8.dp))
        Text("Mode")
        TraceMode.values().forEach { mode ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                RadioButton(
                    selected = uiState.mode == mode,
                    onClick = { viewModel.selectMode(mode) }
                )
                Text(mode.name, modifier = Modifier.padding(start = 8.dp))
            }
        }
        Spacer(Modifier.height(16.dp))
        Text("Status")
        Text(if (uiState.isSessionActive) "Session active" else "Idle")
        Text("Session id: ${uiState.sessionId ?: "—"}")
        Text("Events written: ${uiState.eventsWritten}")
        Text("Events dropped: ${uiState.eventsDropped}")
        Spacer(Modifier.height(16.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Button(
                onClick = { viewModel.startSession() },
                enabled = !uiState.isSessionActive
            ) {
                Text("Start")
            }
            Button(
                onClick = { viewModel.stopSession() },
                enabled = uiState.isSessionActive
            ) {
                Text("Stop")
            }
            Button(onClick = { viewModel.refreshCounters() }) {
                Text("Refresh")
            }
        }
    }
}
