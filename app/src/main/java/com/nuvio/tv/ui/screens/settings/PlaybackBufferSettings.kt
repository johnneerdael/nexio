@file:OptIn(ExperimentalTvMaterial3Api::class)

package com.nuvio.tv.ui.screens.settings

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Science
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material.icons.filled.Storage
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Border
import androidx.tv.material3.Card
import androidx.tv.material3.CardDefaults
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.Icon
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.nuvio.tv.data.local.PlayerSettings
import com.nuvio.tv.ui.theme.NuvioColors

internal fun LazyListScope.bufferSettingsItems(
    showAdvancedExperimental: Boolean,
    playerSettings: PlayerSettings,
    maxBufferSizeMb: Int,
    onToggleAdvancedExperimental: () -> Unit,
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
        Card(
            onClick = onToggleAdvancedExperimental,
            colors = CardDefaults.colors(
                containerColor = Color.Transparent,
                focusedContainerColor = NuvioColors.FocusBackground
            ),
            border = CardDefaults.border(
                focusedBorder = Border(
                    border = BorderStroke(2.dp, NuvioColors.FocusRing),
                    shape = RoundedCornerShape(12.dp)
                )
            ),
            scale = CardDefaults.scale(focusedScale = 1.02f),
            shape = CardDefaults.shape(shape = RoundedCornerShape(12.dp))
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 12.dp, horizontal = 4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    imageVector = Icons.Default.Science,
                    contentDescription = null,
                    tint = Color(0xFFFF9800),
                    modifier = Modifier.size(20.dp)
                )
                Spacer(modifier = Modifier.size(8.dp))
                Text(
                    text = "Advanced / Experimental",
                    style = MaterialTheme.typography.titleMedium,
                    color = NuvioColors.TextSecondary,
                    modifier = Modifier.weight(1f)
                )
                Icon(
                    imageVector = if (showAdvancedExperimental) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                    contentDescription = if (showAdvancedExperimental) "Collapse" else "Expand",
                    tint = NuvioColors.TextSecondary
                )
            }
        }
    }

    if (!showAdvancedExperimental) return

    item {
        Text(
            text = "These settings affect buffering behavior. Incorrect values may cause playback issues.",
            style = MaterialTheme.typography.bodySmall,
            color = Color(0xFFFF9800),
            modifier = Modifier.padding(bottom = 8.dp)
        )
    }

    item {
        Text(
            text = "Buffer Settings",
            style = MaterialTheme.typography.titleMedium,
            color = NuvioColors.TextSecondary,
            modifier = Modifier.padding(vertical = 8.dp)
        )
    }

    item {
        SliderSettingsItem(
            icon = Icons.Default.Speed,
            title = "Min Buffer Duration",
            subtitle = "Minimum amount of media to buffer. The player will try to ensure at least this much content is always buffered ahead of the current playback position.",
            value = playerSettings.bufferSettings.minBufferMs / 1000,
            valueText = "${playerSettings.bufferSettings.minBufferMs / 1000}s",
            minValue = 5,
            maxValue = 120,
            step = 5,
            onValueChange = { onSetBufferMinBufferMs(it * 1000) }
        )
    }

    item {
        val minBufferSeconds = playerSettings.bufferSettings.minBufferMs / 1000
        SliderSettingsItem(
            icon = Icons.Default.Speed,
            title = "Max Buffer Duration",
            subtitle = "Maximum amount of media to buffer. Higher values use more memory but provide smoother playback on unstable connections.",
            value = playerSettings.bufferSettings.maxBufferMs / 1000,
            valueText = "${playerSettings.bufferSettings.maxBufferMs / 1000}s",
            minValue = minBufferSeconds,
            maxValue = 120,
            step = 5,
            onValueChange = { onSetBufferMaxBufferMs(it * 1000) }
        )
    }

    item {
        SliderSettingsItem(
            icon = Icons.Default.PlayArrow,
            title = "Buffer for Playback",
            subtitle = "How much content must be buffered before playback starts. Lower values start faster but may cause initial stuttering on slow connections.",
            value = playerSettings.bufferSettings.bufferForPlaybackMs / 1000,
            valueText = "${playerSettings.bufferSettings.bufferForPlaybackMs / 1000}s",
            minValue = 1,
            maxValue = 30,
            step = 1,
            onValueChange = { onSetBufferForPlaybackMs(it * 1000) }
        )
    }

    item {
        SliderSettingsItem(
            icon = Icons.Default.Refresh,
            title = "Buffer After Rebuffer",
            subtitle = "How much content to buffer after playback stalls due to buffering. Higher values reduce repeated buffering interruptions.",
            value = playerSettings.bufferSettings.bufferForPlaybackAfterRebufferMs / 1000,
            valueText = "${playerSettings.bufferSettings.bufferForPlaybackAfterRebufferMs / 1000}s",
            minValue = 1,
            maxValue = 60,
            step = 1,
            onValueChange = { onSetBufferForPlaybackAfterRebufferMs(it * 1000) }
        )
    }

    item {
        val bufferSizeMb = playerSettings.bufferSettings.targetBufferSizeMb
        SliderSettingsItem(
            icon = Icons.Default.Storage,
            title = "Target Buffer Size",
            subtitle = "Maximum memory for buffering. 'Auto' calculates optimal size based on track bitrates (highly recommended). Max ${maxBufferSizeMb}MB based on device memory.",
            value = bufferSizeMb.coerceAtMost(maxBufferSizeMb),
            valueText = if (bufferSizeMb == 0) "Auto (recommended)" else "$bufferSizeMb MB",
            minValue = 0,
            maxValue = maxBufferSizeMb,
            step = 10,
            onValueChange = onSetBufferTargetSizeMb
        )
    }

    item {
        ToggleSettingsItem(
            icon = Icons.Default.Wifi,
            title = "Parallel Connections",
            subtitle = "Use multiple TCP connections for faster progressive downloads. Can multiply throughput on connections limited to ~100 Mbps per stream.",
            isChecked = playerSettings.bufferSettings.useParallelConnections,
            onCheckedChange = onSetUseParallelConnections
        )
    }

    item {
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = "Back Buffer",
            style = MaterialTheme.typography.titleMedium,
            color = NuvioColors.TextSecondary,
            modifier = Modifier.padding(vertical = 8.dp)
        )
    }

    item {
        SliderSettingsItem(
            icon = Icons.Default.History,
            title = "Back Buffer Duration",
            subtitle = "How much already-played content to keep in memory. Enables fast backward seeking without re-downloading. Set to 0 to disable and save memory.",
            value = playerSettings.bufferSettings.backBufferDurationMs / 1000,
            valueText = "${playerSettings.bufferSettings.backBufferDurationMs / 1000}s",
            minValue = 0,
            maxValue = 120,
            step = 5,
            onValueChange = { onSetBufferBackBufferDurationMs(it * 1000) }
        )
    }

    item {
        ToggleSettingsItem(
            icon = Icons.Default.Key,
            title = "Retain From Keyframe",
            subtitle = "Keep back buffer only from the nearest keyframe. More memory efficient but seeking may be slightly less precise.",
            isChecked = playerSettings.bufferSettings.retainBackBufferFromKeyframe,
            onCheckedChange = onSetBufferRetainBackBufferFromKeyframe
        )
    }
}
