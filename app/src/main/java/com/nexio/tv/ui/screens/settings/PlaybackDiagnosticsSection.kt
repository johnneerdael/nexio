package com.nexio.tv.ui.screens.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/**
 * WP2 — placeholder UI section for the playback diagnostics trace toggle +
 * export actions. The entire composable is compile-time gated off via
 * `PLAYBACK_TRACE_UI_ENABLED` in `DebridSettingsContent.kt`, so this body is
 * a minimal stub for now. The real switch + SAF export wiring lands together
 * with WP9 when [com.nexio.tv.instrumentation.StutterClassifier] ships and
 * the flag is flipped to `true`.
 *
 * Parameters are placeholders; when the flag is enabled they will be wired to
 * `DebridSettingsViewModel.playbackTraceEnabled` and the export actions.
 */
@Composable
internal fun PlaybackDiagnosticsSection(
    enabled: Boolean,
    lastTraceSummary: TraceFileSummary?,
    onToggle: (Boolean) -> Unit,
    onExportLast: () -> Unit,
    onExportAll: () -> Unit,
    onCopyToDownloads: () -> Unit,
) {
    // Reference all parameters so the compiler does not warn about unused.
    val _unused = enabled to lastTraceSummary
    val _cb = listOf(onToggle, onExportLast, onExportAll, onCopyToDownloads)
    Column(modifier = Modifier.padding(8.dp)) {
        Text("Playback diagnostics — available in a future update")
    }
}
