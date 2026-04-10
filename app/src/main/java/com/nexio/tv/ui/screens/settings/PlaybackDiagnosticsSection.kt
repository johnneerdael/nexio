package com.nexio.tv.ui.screens.settings

import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.nexio.tv.R
import com.nexio.tv.ui.theme.NexioColors
import com.nexio.tv.data.repository.benchmark.DiagnosticsUploadResult
import com.nexio.tv.instrumentation.TraceStatus

/**
 * WP2 / L7 — playback diagnostics trace section for the debrid settings
 * LazyColumn.
 *
 * Each interactive element lives in its **own** LazyColumn item so the
 * Fire TV d-pad can focus and activate it row-by-row. The previous version
 * of this section bundled everything into a single nested Surface + Row,
 * which Material3 `Switch` does not make accessible to d-pad focus — users
 * saw a visually-off switch that could not be flipped on the TV.
 *
 * Call from `DebridSettingsContent`'s LazyColumn scope:
 *
 * ```kotlin
 * playbackDiagnosticsItems(
 *     enabled = uiState.playbackTraceEnabled,
 *     adbControlEnabled = uiState.playbackTraceAdbControlEnabled,
 *     status = uiState.playbackTraceStatus,
 *     ...
 * )
 * ```
 */
internal fun LazyListScope.playbackDiagnosticsItems(
    enabled: Boolean,
    adbControlEnabled: Boolean,
    status: TraceStatus,
    onToggleEnabled: () -> Unit,
    onToggleAdbControl: () -> Unit,
    onExportLast: () -> Unit,
    onExportAllToDownloads: () -> Unit,
    onCopyToDownloads: (Uri) -> Unit,
    onClearAll: () -> Unit,
    onSendToDeveloper: () -> Unit = {},
    diagnosticsUploadInProgress: Boolean = false,
    diagnosticsUploadResult: DiagnosticsUploadResult? = null,
) {
    // Section title + status line. Non-interactive — acts as a visual header
    // separating the diagnostics block from the rest of the debrid settings.
    item(key = "playback_diagnostics_header") {
        Spacer(Modifier.height(6.dp))
        Text(
            text = stringResource(R.string.playback_diagnostics_section_title),
            style = MaterialTheme.typography.titleSmall,
            color = NexioColors.TextPrimary,
            modifier = Modifier.padding(horizontal = 18.dp),
        )
        Text(
            text = formatStatusLine(status),
            style = MaterialTheme.typography.bodySmall,
            color = NexioColors.TextSecondary,
            modifier = Modifier.padding(horizontal = 18.dp, vertical = 2.dp),
        )
        Spacer(Modifier.height(4.dp))
    }

    // Master toggle — uses the same SettingsToggleRow the rest of the
    // debrid settings use, so d-pad focus + SELECT works exactly the same
    // way as every other toggle on the screen.
    item(key = "playback_diagnostics_toggle") {
        SettingsToggleRow(
            title = stringResource(R.string.playback_diagnostics_trace_title),
            subtitle = stringResource(R.string.playback_diagnostics_trace_subtitle),
            checked = enabled,
            enabled = true,
            onToggle = onToggleEnabled,
        )
    }

    // Export actions. The main all-sessions path writes directly to
    // Downloads; the Card's onClick guard handles the "has sessions"
    // case, and we grey the value text out for visual feedback.
    item(key = "playback_diagnostics_export_last") {
        SettingsActionRow(
            title = stringResource(R.string.playback_diagnostics_export_last_title),
            subtitle = stringResource(R.string.playback_diagnostics_export_last_subtitle),
            value = status.lastSessionFileName?.let { "${status.lastSessionSizeBytes / 1024L} KiB" },
            enabled = status.lastSessionFileName != null,
            onClick = onExportLast,
        )
    }

    item(key = "playback_diagnostics_send_to_developer") {
        val valueText = when {
            diagnosticsUploadInProgress -> stringResource(R.string.playback_diagnostics_uploading)
            diagnosticsUploadResult is DiagnosticsUploadResult.Success ->
                stringResource(R.string.playback_diagnostics_upload_sent)
            diagnosticsUploadResult is DiagnosticsUploadResult.NoTrace ->
                stringResource(R.string.playback_diagnostics_upload_no_trace)
            diagnosticsUploadResult is DiagnosticsUploadResult.Error ->
                stringResource(
                    R.string.playback_diagnostics_upload_failed,
                    diagnosticsUploadResult.message
                )
            else -> null
        }
        SettingsActionRow(
            title = stringResource(R.string.playback_diagnostics_send_to_developer_title),
            subtitle = stringResource(R.string.playback_diagnostics_send_to_developer_subtitle),
            value = valueText,
            enabled = status.lastSessionFileName != null && !diagnosticsUploadInProgress,
            onClick = onSendToDeveloper,
        )
    }

    item(key = "playback_diagnostics_export_all") {
        SettingsActionRow(
            title = stringResource(R.string.playback_diagnostics_export_all_title),
            subtitle = stringResource(R.string.playback_diagnostics_export_all_subtitle),
            value = if (status.sessionCount > 0) {
                stringResource(
                    R.string.playback_diagnostics_export_all_value_format,
                    status.sessionCount,
                    status.totalBytes / (1024.0 * 1024.0)
                )
            } else null,
            enabled = status.sessionCount > 0,
            onClick = onExportAllToDownloads,
        )
    }

    // SAF "copy to…" path. The Composable owns the launcher; the ViewModel
    // writes bytes to the picked Uri.
    item(key = "playback_diagnostics_copy_to") {
        val createDocumentLauncher = rememberLauncherForActivityResult(
            contract = ActivityResultContracts.CreateDocument("application/json")
        ) { uri ->
            if (uri != null) onCopyToDownloads(uri)
        }
        val suggestedName = remember(status.lastSessionFileName) {
            status.lastSessionFileName ?: "playback-trace.jsonl"
        }
        SettingsActionRow(
            title = stringResource(R.string.playback_diagnostics_copy_latest_title),
            subtitle = stringResource(R.string.playback_diagnostics_copy_latest_subtitle),
            enabled = status.lastSessionFileName != null,
            onClick = { createDocumentLauncher.launch(suggestedName) },
        )
    }

    item(key = "playback_diagnostics_clear") {
        SettingsActionRow(
            title = stringResource(R.string.playback_diagnostics_clear_title),
            subtitle = stringResource(R.string.playback_diagnostics_clear_subtitle),
            enabled = status.sessionCount > 0,
            onClick = onClearAll,
        )
    }

    // ADB control plane — second opt-in. Off by default; must be explicitly
    // enabled before `am broadcast` commands from adb will do anything.
    item(key = "playback_diagnostics_adb_toggle") {
        SettingsToggleRow(
            title = stringResource(R.string.playback_diagnostics_adb_title),
            subtitle = stringResource(R.string.playback_diagnostics_adb_subtitle),
            checked = adbControlEnabled,
            enabled = true,
            onToggle = onToggleAdbControl,
        )
        Spacer(Modifier.height(6.dp))
    }
}

/**
 * Legacy entry point kept for source-compat with the DebridSettingsContent
 * call site that expects a single composable. New call sites should prefer
 * [playbackDiagnosticsItems] above so each element is its own LazyColumn
 * item with its own d-pad focus surface.
 */
@Composable
@Suppress("UNUSED_PARAMETER")
internal fun PlaybackDiagnosticsSection(
    enabled: Boolean,
    adbControlEnabled: Boolean,
    status: TraceStatus,
    onToggleEnabled: (Boolean) -> Unit,
    onToggleAdbControl: (Boolean) -> Unit,
    onExportLast: () -> Unit,
    onExportAllToDownloads: () -> Unit,
    onCopyToDownloads: (Uri) -> Unit,
    onClearAll: () -> Unit,
    onShareIntent: (Intent) -> Unit,
) {
    // Deliberately empty — the DebridSettingsContent call site below has
    // been migrated to [playbackDiagnosticsItems]. This stub exists to keep
    // any stale caller compiling while the migration lands.
    Text(
        "Use playbackDiagnosticsItems(...) from a LazyListScope instead.",
        style = MaterialTheme.typography.bodySmall,
    )
}

@Composable
private fun formatStatusLine(status: TraceStatus): String {
    val state = if (status.enabled) {
        stringResource(R.string.playback_diagnostics_status_enabled)
    } else {
        stringResource(R.string.playback_diagnostics_status_disabled)
    }
    val sessionText = pluralStringResource(
        R.plurals.playback_diagnostics_status_session_count,
        status.sessionCount,
        status.sessionCount
    )
    return buildString {
        append(stringResource(R.string.playback_diagnostics_status_prefix))
        append(' ')
        append(state)
        append(" · ")
        append(sessionText)
        if (status.sessionCount > 0) {
            append(" · ")
            append(
                stringResource(
                    R.string.playback_diagnostics_status_total_format,
                    status.totalBytes / (1024.0 * 1024.0)
                )
            )
        }
    }
}
