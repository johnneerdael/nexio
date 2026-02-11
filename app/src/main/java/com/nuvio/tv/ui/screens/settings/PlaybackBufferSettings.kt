@file:OptIn(ExperimentalTvMaterial3Api::class)

package com.nuvio.tv.ui.screens.settings

import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.nuvio.tv.data.local.PlayerSettings
import com.nuvio.tv.ui.theme.NuvioColors

internal fun LazyListScope.bufferSettingsItems(
    playerSettings: PlayerSettings,
    maxBufferSizeMb: Int,
    onSetBufferMinBufferMs: (Int) -> Unit,
    onSetBufferMaxBufferMs: (Int) -> Unit,
    onSetBufferForPlaybackMs: (Int) -> Unit,
    onSetBufferForPlaybackAfterRebufferMs: (Int) -> Unit,
    onSetBufferTargetSizeMb: (Int) -> Unit,
    onSetUseParallelConnections: (Boolean) -> Unit,
    onSetBufferBackBufferDurationMs: (Int) -> Unit,
    onSetBufferRetainBackBufferFromKeyframe: (Boolean) -> Unit
) {
    item {
        Spacer(modifier = Modifier.height(16.dp))
        Text(
            text = "Buffering",
            style = MaterialTheme.typography.titleMedium,
            color = NuvioColors.TextSecondary,
            modifier = Modifier.padding(vertical = 8.dp)
        )
    }

    item {
        Text(
            text = "Buffer behavior is managed automatically using Media3 defaults for playback stability.",
            style = MaterialTheme.typography.bodyMedium,
            color = NuvioColors.TextSecondary
        )
    }

    item {
        ToggleSettingsItem(
            icon = Icons.Default.Wifi,
            title = "Parallel Connections",
            subtitle = "Use multiple TCP connections for faster progressive downloads. Disabled by default.",
            isChecked = playerSettings.bufferSettings.useParallelConnections,
            onCheckedChange = onSetUseParallelConnections
        )
    }
}
