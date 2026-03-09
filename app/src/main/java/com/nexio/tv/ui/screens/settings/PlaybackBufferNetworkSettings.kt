@file:OptIn(ExperimentalTvMaterial3Api::class)

package com.nexio.tv.ui.screens.settings

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Hub
import androidx.compose.material.icons.filled.Storage
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.media3.common.util.UnstableApi
import androidx.tv.material3.Border
import androidx.tv.material3.Button
import androidx.tv.material3.ButtonDefaults
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.nexio.tv.R
import com.nexio.tv.data.local.PlayerSettings
import com.nexio.tv.data.local.VodCacheSizeMode
import com.nexio.tv.ui.theme.NexioColors
import kotlin.math.min

@androidx.annotation.OptIn(UnstableApi::class)
internal fun LazyListScope.bufferAndNetworkSettingsItems(
    playerSettings: PlayerSettings,
    onSetVodCacheSizeMode: (VodCacheSizeMode) -> Unit,
    onSetVodCacheSizeMb: (Int) -> Unit,
    onSetUseParallelConnections: (Boolean) -> Unit,
    onSetParallelConnectionCount: (Int) -> Unit,
    onSetParallelChunkSizeMb: (Int) -> Unit,
    onResetNetworkToDefaults: () -> Unit
) {
    item {
        Text(
            text = stringResource(R.string.playback_buffer_disk_cache_title),
            style = MaterialTheme.typography.titleMedium,
            color = NexioColors.TextSecondary,
            modifier = Modifier.padding(vertical = 8.dp)
        )
    }

    item {
        val vodCacheEnabled = playerSettings.vodCacheSizeMode == VodCacheSizeMode.ON
        ToggleSettingsItem(
            icon = Icons.Default.Storage,
            title = stringResource(R.string.playback_buffer_enable_vod_cache),
            subtitle = stringResource(R.string.playback_buffer_enable_vod_cache_sub),
            isChecked = vodCacheEnabled,
            onCheckedChange = { enabled ->
                onSetVodCacheSizeMode(if (enabled) VodCacheSizeMode.ON else VodCacheSizeMode.OFF)
            }
        )
    }

    if (playerSettings.vodCacheSizeMode == VodCacheSizeMode.ON) {
        item {
            val context = LocalContext.current
            val freeDiskBytes = context.cacheDir.usableSpace.coerceAtLeast(0L)
            val maxManualCacheMb = resolveManualVodCacheMaxMb(freeDiskBytes)
            val manualCacheMb = playerSettings.vodCacheSizeMb.coerceIn(
                PlayerSettings.MIN_VOD_CACHE_SIZE_MB,
                maxManualCacheMb
            )
            SliderSettingsItem(
                icon = Icons.Default.Storage,
                title = stringResource(R.string.playback_buffer_vod_cache_size),
                subtitle = stringResource(R.string.playback_buffer_vod_cache_size_sub),
                value = manualCacheMb,
                valueText = "${manualCacheMb} MB",
                minValue = PlayerSettings.MIN_VOD_CACHE_SIZE_MB,
                maxValue = maxManualCacheMb,
                step = 50,
                onValueChange = onSetVodCacheSizeMb
            )
        }
    }

    item {
        val context = LocalContext.current
        val freeDiskBytes = context.cacheDir.usableSpace.coerceAtLeast(0L)
        val freeDiskLabel = formatStorageSize(freeDiskBytes)
        val maxManualCacheMb = resolveManualVodCacheMaxMb(freeDiskBytes)
        val cacheStateText = if (playerSettings.vodCacheSizeMode == VodCacheSizeMode.ON) {
            stringResource(R.string.subtitle_on)
        } else {
            stringResource(R.string.subtitle_off)
        }
        val infoText = stringResource(
            R.string.playback_buffer_info,
            cacheStateText,
            PlayerSettings.MIN_VOD_CACHE_SIZE_MB,
            maxManualCacheMb,
            VOD_CACHE_FREE_SPACE_RESERVE_MB,
            freeDiskLabel
        )
        Text(
            text = infoText,
            style = MaterialTheme.typography.bodySmall,
            color = NexioColors.TextSecondary,
            modifier = Modifier.padding(bottom = 8.dp)
        )
    }

    item {
        Text(
            text = stringResource(R.string.playback_buffer_network_title),
            style = MaterialTheme.typography.titleMedium,
            color = NexioColors.TextSecondary,
            modifier = Modifier.padding(vertical = 8.dp)
        )
    }

    item {
        ToggleSettingsItem(
            icon = Icons.Default.Wifi,
            title = stringResource(R.string.playback_buffer_parallel_connections),
            subtitle = stringResource(R.string.playback_buffer_parallel_connections_sub),
            isChecked = playerSettings.useParallelConnections,
            onCheckedChange = onSetUseParallelConnections
        )
    }

    if (playerSettings.useParallelConnections) {
        item {
            SliderSettingsItem(
                icon = Icons.Default.Hub,
                title = stringResource(R.string.playback_buffer_connection_count),
                subtitle = stringResource(R.string.playback_buffer_connection_count_sub),
                value = playerSettings.parallelConnectionCount,
                valueText = playerSettings.parallelConnectionCount.toString(),
                minValue = MemoryBudget.MIN_CONNECTIONS,
                maxValue = MemoryBudget.MAX_CONNECTIONS,
                step = 1,
                onValueChange = onSetParallelConnectionCount
            )
        }

        item {
            val maxChunkSizeMb = MemoryBudget.maxChunkMb(
                MemoryBudget.defaultBufferSizeMb,
                playerSettings.parallelConnectionCount
            )
            val chunkSizeMb = playerSettings.parallelChunkSizeMb.coerceAtMost(maxChunkSizeMb)
            SliderSettingsItem(
                icon = Icons.Default.Storage,
                title = stringResource(R.string.playback_buffer_chunk_size),
                subtitle = stringResource(R.string.playback_buffer_chunk_size_sub),
                value = chunkSizeMb,
                valueText = "$chunkSizeMb MB",
                minValue = MemoryBudget.MIN_CHUNK_MB,
                maxValue = maxChunkSizeMb,
                step = 8,
                onValueChange = onSetParallelChunkSizeMb
            )
        }
    }

    item {
        Button(
            onClick = onResetNetworkToDefaults,
            shape = ButtonDefaults.shape(shape = RoundedCornerShape(10.dp)),
            colors = ButtonDefaults.colors(
                containerColor = NexioColors.Background,
                focusedContainerColor = NexioColors.Background
            ),
            border = ButtonDefaults.border(
                focusedBorder = Border(
                    border = BorderStroke(1.dp, NexioColors.FocusRing),
                    shape = RoundedCornerShape(10.dp)
                )
            )
        ) {
            Text(
                text = stringResource(R.string.layout_reset_default),
                style = MaterialTheme.typography.labelLarge,
                color = NexioColors.TextPrimary
            )
        }
    }
}

private fun formatStorageSize(bytes: Long): String {
    val gb = bytes / (1024.0 * 1024.0 * 1024.0)
    if (gb >= 10.0) return String.format("%.0f GB", gb)
    if (gb >= 1.0) return String.format("%.1f GB", gb)
    val mb = bytes / (1024.0 * 1024.0)
    return String.format("%.0f MB", mb)
}

private fun resolveManualVodCacheMaxMb(freeDiskBytes: Long): Int {
    val freeDiskMb = freeDiskBytes.coerceAtLeast(0L) / (1024L * 1024L)
    val dynamicMaxMb = when {
        freeDiskMb > VOD_CACHE_FREE_SPACE_RESERVE_MB -> freeDiskMb - VOD_CACHE_FREE_SPACE_RESERVE_MB
        else -> (freeDiskMb * 8L) / 10L
    }
    val boundedMb = min(
        PlayerSettings.MAX_VOD_CACHE_SIZE_MB.toLong(),
        dynamicMaxMb.coerceAtLeast(PlayerSettings.MIN_VOD_CACHE_SIZE_MB.toLong())
    )
    return boundedMb.toInt()
}

private const val VOD_CACHE_FREE_SPACE_RESERVE_MB = 1024L
