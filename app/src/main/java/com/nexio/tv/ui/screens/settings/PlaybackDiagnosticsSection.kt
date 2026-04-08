package com.nexio.tv.ui.screens.settings

import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.nexio.tv.instrumentation.TraceStatus

/**
 * Debrid-settings section for the playback diagnostics trace (WP2 + L7).
 *
 *  - Master switch backed by [com.nexio.tv.instrumentation.PlaybackTraceToggle]
 *    via the ViewModel. Toggle-off mid-session flushes the current
 *    `MediaSourceSession` and writes a final `playback_session_ended` event
 *    before the boolean flag flips (spec §A.4).
 *  - **Allow ADB control** secondary opt-in. Default off. When on, the
 *    [com.nexio.tv.instrumentation.PlaybackTraceAdbReceiver] will accept
 *    `adb shell am broadcast` commands to drive the same operations from a
 *    workstation. Off by default per the spec's security guidance — leaving
 *    a permanently exported diagnostics control surface open is broader
 *    than the in-app toggle.
 *  - Three export actions: share the latest session, share a `.zip` of
 *    every retained session, copy the latest session into a SAF-picked
 *    destination (Downloads / external storage / cloud).
 *  - Clear button — wipes every retained `.jsonl` file under
 *    `filesDir/playback-traces/`.
 *  - Read-only status line: `N sessions · X.X MiB · last <sessionId> Y KiB`.
 */
@Composable
internal fun PlaybackDiagnosticsSection(
    enabled: Boolean,
    adbControlEnabled: Boolean,
    status: TraceStatus,
    onToggleEnabled: (Boolean) -> Unit,
    onToggleAdbControl: (Boolean) -> Unit,
    onExportLast: () -> Unit,
    onExportAll: () -> Unit,
    onCopyToDownloads: (Uri) -> Unit,
    onClearAll: () -> Unit,
    onShareIntent: (Intent) -> Unit,
) {
    // SAF launcher: ACTION_CREATE_DOCUMENT for the "Copy to Downloads" path.
    // The Composable owns the launcher and forwards the picked Uri to the
    // ViewModel's copy action.
    val createDocumentLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("application/json")
    ) { uri ->
        if (uri != null) onCopyToDownloads(uri)
    }

    Surface(
        color = MaterialTheme.colorScheme.surfaceVariant,
        shape = RoundedCornerShape(8.dp),
        modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp),
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            // Header row: title + master switch
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        "Playback diagnostics trace",
                        style = MaterialTheme.typography.titleMedium,
                    )
                    Text(
                        "Records every playback session as a JSONL file you can export for support",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Switch(
                    checked = enabled,
                    onCheckedChange = onToggleEnabled,
                )
            }

            Spacer(Modifier.height(8.dp))

            // Status line — refreshed via the ViewModel's status flow.
            Text(
                text = formatStatusLine(status),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            Spacer(Modifier.height(12.dp))

            // Export action row.
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Button(
                    onClick = onExportLast,
                    enabled = status.lastSessionFileName != null,
                ) { Text("Export last") }
                Button(
                    onClick = onExportAll,
                    enabled = status.sessionCount > 0,
                ) { Text("Export all (.zip)") }
                Button(
                    onClick = {
                        val suggestedName = status.lastSessionFileName ?: "playback-trace.jsonl"
                        createDocumentLauncher.launch(suggestedName)
                    },
                    enabled = status.lastSessionFileName != null,
                ) { Text("Copy to…") }
            }

            Spacer(Modifier.height(8.dp))

            // Secondary controls row: clear + ADB toggle.
            Row(verticalAlignment = Alignment.CenterVertically) {
                OutlinedButton(
                    onClick = onClearAll,
                    enabled = status.sessionCount > 0,
                ) { Text("Clear traces") }

                Spacer(Modifier.weight(1f))

                Text(
                    "Allow ADB control",
                    style = MaterialTheme.typography.bodySmall,
                )
                Spacer(Modifier.height(0.dp))
                Switch(
                    checked = adbControlEnabled,
                    onCheckedChange = onToggleAdbControl,
                )
            }
        }
    }
}

private fun formatStatusLine(status: TraceStatus): String {
    val totalMib = status.totalBytes.toDouble() / (1024.0 * 1024.0)
    val sessions = status.sessionCount
    val sessionWord = if (sessions == 1) "session" else "sessions"
    val state = if (status.enabled) "enabled" else "disabled"
    val sb = StringBuilder()
    sb.append("Tracer is ").append(state).append(" · ")
    sb.append(sessions).append(' ').append(sessionWord)
    if (sessions > 0) {
        sb.append(" · ").append("%.1f".format(totalMib)).append(" MiB total")
    }
    val last = status.lastSessionFileName
    if (last != null) {
        val lastKib = (status.lastSessionSizeBytes / 1024L).coerceAtLeast(0L)
        sb.append("\nLatest: ").append(last).append(" (").append(lastKib).append(" KiB)")
    }
    return sb.toString()
}
