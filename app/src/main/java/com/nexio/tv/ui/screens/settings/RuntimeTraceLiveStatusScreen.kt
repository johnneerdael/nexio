package com.nexio.tv.ui.screens.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel

@Composable
fun RuntimeTraceLiveStatusScreen(
    viewModel: RuntimeTraceLiveStatusViewModel = hiltViewModel()
) {
    val state by viewModel.uiState.collectAsState()

    Column(
        modifier = Modifier.fillMaxSize().padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Text("Runtime Trace Live Status")
        Text("Active: ${state.isActive}")
        Text("Session: ${state.sessionId ?: "—"}")
        Text("Events written: ${state.eventsWritten}")
        Text("Events dropped: ${state.eventsDropped}")
        Spacer(Modifier.height(12.dp))
        Text("Recent runtime calls (${state.recentRuntime.size})")
        state.recentRuntime.takeLast(20).forEach { Text("• ${it.eventType} #${it.sequence}") }
        Spacer(Modifier.height(12.dp))
        Text("Recent route decisions (${state.recentRoutes.size})")
        state.recentRoutes.takeLast(20).forEach { Text("• ${it.eventType} #${it.sequence}") }
        Spacer(Modifier.height(12.dp))
        Text("Recent policy violations (${state.recentPolicyViolations.size})")
        state.recentPolicyViolations.takeLast(20).forEach {
            Text("• ${it.eventType} #${it.sequence}", color = androidx.compose.ui.graphics.Color.Red)
        }
    }
}
