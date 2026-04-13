@file:OptIn(ExperimentalTvMaterial3Api::class)

package com.nexio.tv.ui.screens.settings

import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Storage
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.nexio.tv.R
import com.nexio.tv.data.local.PlayerSettings
import com.nexio.tv.data.local.diskSpoolTargetBitrateMbps
import com.nexio.tv.data.local.ProgressivePlaybackDiskMode
import com.nexio.tv.data.local.VodCacheSizeMode
import com.nexio.tv.ui.screens.player.spool.SpoolStoragePolicy
import com.nexio.tv.ui.screens.player.spool.SpoolStorageProbeResult
import com.nexio.tv.ui.theme.NexioColors
import kotlin.math.min
import kotlin.math.roundToInt

internal fun LazyListScope.bufferAndNetworkSettingsItems(
    playerSettings: PlayerSettings,
    onSetVodCacheSizeMode: (VodCacheSizeMode) -> Unit,
    onSetVodCacheSizeMb: (Int) -> Unit,
    onSetVodCacheWarmAheadEnabled: (Boolean) -> Unit,
    onSetUseParallelConnections: (Boolean) -> Unit,
    onSetProgressivePlaybackDiskMode: (ProgressivePlaybackDiskMode) -> Unit,
    onRunDiskSpoolStorageProbe: () -> Unit,
    onItemFocused: () -> Unit
) {
    item(key = "network_cache_disk_header") {
        Text(
            text = stringResource(R.string.playback_buffer_disk_cache_title),
            style = MaterialTheme.typography.titleMedium,
            color = NexioColors.TextSecondary,
            modifier = Modifier.padding(vertical = 8.dp)
        )
    }

    item(key = "network_cache_vod_enabled") {
        val vodCacheEnabled = playerSettings.vodCacheSizeMode == VodCacheSizeMode.ON
        ToggleSettingsItem(
            icon = Icons.Default.Storage,
            title = stringResource(R.string.playback_buffer_enable_vod_cache),
            subtitle = stringResource(R.string.playback_buffer_enable_vod_cache_sub),
            isChecked = vodCacheEnabled,
            onCheckedChange = { enabled ->
                onSetVodCacheSizeMode(if (enabled) VodCacheSizeMode.ON else VodCacheSizeMode.OFF)
            },
            onFocused = onItemFocused
        )
    }

    if (playerSettings.vodCacheSizeMode == VodCacheSizeMode.ON) {
        item(key = "network_cache_vod_size") {
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
                onValueChange = onSetVodCacheSizeMb,
                onFocused = onItemFocused
            )
        }

        item(key = "network_cache_vod_warm_ahead") {
            ToggleSettingsItem(
                icon = Icons.Default.Wifi,
                title = stringResource(R.string.playback_buffer_vod_warm_ahead),
                subtitle = stringResource(R.string.playback_buffer_vod_warm_ahead_sub),
                isChecked = playerSettings.vodCacheWarmAheadEnabled,
                onCheckedChange = onSetVodCacheWarmAheadEnabled,
                onFocused = onItemFocused
            )
        }
    }

    item(key = "network_cache_vod_info") {
        val context = LocalContext.current
        val freeDiskBytes = context.cacheDir.usableSpace.coerceAtLeast(0L)
        val freeDiskLabel = formatStorageSize(freeDiskBytes)
        val maxManualCacheMb = resolveManualVodCacheMaxMb(freeDiskBytes)
        val cacheStateText = if (playerSettings.vodCacheSizeMode == VodCacheSizeMode.ON) {
            stringResource(R.string.subtitle_on)
        } else {
            stringResource(R.string.subtitle_off)
        }
        Text(
            text = stringResource(
                R.string.playback_buffer_info,
                cacheStateText,
                PlayerSettings.MIN_VOD_CACHE_SIZE_MB,
                maxManualCacheMb,
                VOD_CACHE_FREE_SPACE_RESERVE_MB,
                freeDiskLabel
            ),
            style = MaterialTheme.typography.bodySmall,
            color = NexioColors.TextSecondary,
            modifier = Modifier.padding(bottom = 8.dp)
        )
    }

    item(key = "network_cache_network_header") {
        Text(
            text = stringResource(R.string.playback_buffer_network_title),
            style = MaterialTheme.typography.titleMedium,
            color = NexioColors.TextSecondary,
            modifier = Modifier.padding(vertical = 8.dp)
        )
    }

    item(key = "network_cache_parallel_enabled") {
        ToggleSettingsItem(
            icon = Icons.Default.Wifi,
            title = stringResource(R.string.playback_buffer_parallel_connections),
            subtitle = stringResource(R.string.playback_buffer_parallel_connections_sub),
            isChecked = playerSettings.useParallelConnections,
            onCheckedChange = onSetUseParallelConnections,
            onFocused = onItemFocused
        )
    }

    item(key = "network_cache_disk_spool_mode") {
        ToggleSettingsItem(
            icon = Icons.Default.Storage,
            title = stringResource(R.string.playback_buffer_disk_spool_mode),
            subtitle = stringResource(R.string.playback_buffer_disk_spool_mode_sub),
            isChecked = playerSettings.progressivePlaybackDiskMode == ProgressivePlaybackDiskMode.SPOOL,
            onCheckedChange = { enabled ->
                onSetProgressivePlaybackDiskMode(
                    if (enabled) ProgressivePlaybackDiskMode.SPOOL else ProgressivePlaybackDiskMode.OFF
                )
            },
            onFocused = onItemFocused
        )
    }

    item(key = "network_cache_disk_spool_probe") {
        SettingsActionRow(
            title = stringResource(R.string.playback_buffer_disk_spool_probe),
            subtitle = null,
            onClick = onRunDiskSpoolStorageProbe,
            onFocused = onItemFocused
        )
    }

    item(key = "network_cache_disk_spool_probe_status") {
        val context = LocalContext.current
        val spoolDirectoryPath = context.cacheDir.resolve("player_disk_spool").absolutePath
        val status = resolveDiskSpoolProbeStatus(
            result = SpoolStorageProbeResult.fromJsonOrNull(playerSettings.spoolStorageProbeResultJson),
            progressivePlaybackDiskMode = playerSettings.progressivePlaybackDiskMode,
            nowMs = System.currentTimeMillis(),
            spoolDirectoryPath = spoolDirectoryPath,
            targetVideoMbps = SpoolStoragePolicy.targetBitrateMbps(
                streamBitrateMbps = null,
                userCapMbps = playerSettings.diskSpoolTargetBitrateMbps()
            )
        )
        val statusText = when (status) {
            DiskSpoolProbeStatus.Disabled ->
                stringResource(R.string.playback_buffer_disk_spool_probe_status_disabled)
            DiskSpoolProbeStatus.NotChecked ->
                stringResource(R.string.playback_buffer_disk_spool_probe_status_not_checked)
            is DiskSpoolProbeStatus.Passed ->
                stringResource(
                    R.string.playback_buffer_disk_spool_probe_status_passed,
                    status.combinedMbps,
                    status.p99ReadLatencyMs
                )
            is DiskSpoolProbeStatus.Failed ->
                stringResource(
                    R.string.playback_buffer_disk_spool_probe_status_failed,
                    status.combinedMbps,
                    status.p99ReadLatencyMs
                )
            DiskSpoolProbeStatus.Stale ->
                stringResource(R.string.playback_buffer_disk_spool_probe_status_stale)
        }
        Text(
            text = statusText,
            style = MaterialTheme.typography.bodySmall,
            color = NexioColors.TextSecondary,
            modifier = Modifier.padding(bottom = 8.dp)
        )
    }
}

internal sealed class DiskSpoolProbeStatus {
    object Disabled : DiskSpoolProbeStatus()
    object NotChecked : DiskSpoolProbeStatus()
    data class Passed(val combinedMbps: Int, val p99ReadLatencyMs: Long) : DiskSpoolProbeStatus()
    data class Failed(val combinedMbps: Int, val p99ReadLatencyMs: Long) : DiskSpoolProbeStatus()
    object Stale : DiskSpoolProbeStatus()
}

internal fun resolveDiskSpoolProbeStatus(
    result: SpoolStorageProbeResult?,
    progressivePlaybackDiskMode: ProgressivePlaybackDiskMode,
    nowMs: Long,
    spoolDirectoryPath: String,
    targetVideoMbps: Double
): DiskSpoolProbeStatus {
    if (progressivePlaybackDiskMode != ProgressivePlaybackDiskMode.SPOOL) {
        return DiskSpoolProbeStatus.Disabled
    }
    if (result == null) return DiskSpoolProbeStatus.NotChecked
    if (!SpoolStoragePolicy.isFresh(result, nowMs, spoolDirectoryPath)) {
        return DiskSpoolProbeStatus.Stale
    }

    val combinedMbps = result.combinedMbps.roundToInt()
    return if (SpoolStoragePolicy.canSustain(result, targetVideoMbps)) {
        DiskSpoolProbeStatus.Passed(
            combinedMbps = combinedMbps,
            p99ReadLatencyMs = result.p99ReadLatencyMs
        )
    } else {
        DiskSpoolProbeStatus.Failed(
            combinedMbps = combinedMbps,
            p99ReadLatencyMs = result.p99ReadLatencyMs
        )
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
