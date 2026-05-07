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
import com.nexio.tv.ui.screens.player.spool.DiskSpoolStorageLocation
import com.nexio.tv.ui.screens.player.spool.DiskSpoolStorageResolver
import com.nexio.tv.ui.screens.player.spool.SpoolStoragePolicy
import com.nexio.tv.ui.screens.player.spool.SpoolStorageProbeResult
import com.nexio.tv.ui.theme.NexioColors
import java.util.Locale
import kotlin.math.min
import kotlin.math.roundToInt

internal fun LazyListScope.bufferAndNetworkSettingsItems(
    playerSettings: PlayerSettings,
    diskSpoolStorageProbeUiState: DiskSpoolStorageProbeUiState,
    onSetVodCacheSizeMode: (VodCacheSizeMode) -> Unit,
    onSetVodCacheSizeMb: (Int) -> Unit,
    onSetVodCacheWarmAheadEnabled: (Boolean) -> Unit,
    onSetUseParallelConnections: (Boolean) -> Unit,
    onSetProgressivePlaybackDiskMode: (ProgressivePlaybackDiskMode) -> Unit,
    onSetDiskSpoolStorageLocation: (DiskSpoolStorageLocation) -> Unit,
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
        val parallelSubtitle = when (
            resolveParallelConnectionsSubtitle(
                useParallelConnections = playerSettings.useParallelConnections,
                progressivePlaybackDiskMode = playerSettings.progressivePlaybackDiskMode
            )
        ) {
            ParallelConnectionsSubtitle.Default ->
                stringResource(R.string.playback_buffer_parallel_connections_sub)
            ParallelConnectionsSubtitle.WarningForDiskSpool ->
                stringResource(R.string.playback_buffer_parallel_connections_disk_spool_warning)
        }
        ToggleSettingsItem(
            icon = Icons.Default.Wifi,
            title = stringResource(R.string.playback_buffer_parallel_connections),
            subtitle = parallelSubtitle,
            isChecked = playerSettings.useParallelConnections,
            onCheckedChange = onSetUseParallelConnections,
            onFocused = onItemFocused
        )
    }

    item(key = "network_cache_disk_spool_mode") {
        val context = LocalContext.current
        val builtInSpoolDirectory = DiskSpoolStorageResolver.builtinSpoolDirectory(context)
        val externalSpoolDirectory = DiskSpoolStorageResolver.externalSpoolDirectoryOrNull(context)
        val builtInDiskSpoolPolicy = resolveDiskSpoolPolicy(
            DiskSpoolStorageResolver.usableSpaceForSpoolDirectory(builtInSpoolDirectory)
        )
        val externalDiskSpoolPolicy = externalSpoolDirectory?.let { directory ->
            resolveDiskSpoolPolicy(DiskSpoolStorageResolver.usableSpaceForSpoolDirectory(directory))
        }
        val effectiveLocation = resolveEffectiveDiskSpoolStorageLocation(
            preferred = playerSettings.diskSpoolStorageLocation,
            builtInSupported = builtInDiskSpoolPolicy != null,
            externalSupported = externalDiskSpoolPolicy != null
        )
        val diskSpoolPolicy = when (effectiveLocation) {
            DiskSpoolStorageLocation.BUILTIN -> builtInDiskSpoolPolicy
            DiskSpoolStorageLocation.EXTERNAL -> externalDiskSpoolPolicy
        }
        val diskSpoolSupported = diskSpoolPolicy != null
        val diskSpoolSubtitle = if (diskSpoolSupported) {
            stringResource(
                R.string.playback_buffer_disk_spool_mode_sub_auto,
                diskSpoolPolicy,
                FIXED_DISK_SPOOL_STARTUP_BUFFER_MB
            )
        } else {
            stringResource(R.string.playback_buffer_disk_spool_mode_sub_unsupported)
        }
        ToggleSettingsItem(
            icon = Icons.Default.Storage,
            title = stringResource(R.string.playback_buffer_disk_spool_mode),
            subtitle = diskSpoolSubtitle,
            isChecked = playerSettings.progressivePlaybackDiskMode == ProgressivePlaybackDiskMode.SPOOL,
            enabled = diskSpoolSupported,
            onCheckedChange = { enabled ->
                if (enabled && playerSettings.diskSpoolStorageLocation != effectiveLocation) {
                    onSetDiskSpoolStorageLocation(effectiveLocation)
                }
                onSetProgressivePlaybackDiskMode(
                    if (enabled) ProgressivePlaybackDiskMode.SPOOL else ProgressivePlaybackDiskMode.OFF
                )
            },
            onFocused = onItemFocused
        )
    }

    item(key = "network_cache_disk_spool_storage_location") {
        val context = LocalContext.current
        val builtInSpoolDirectory = DiskSpoolStorageResolver.builtinSpoolDirectory(context)
        val externalSpoolDirectory = DiskSpoolStorageResolver.externalSpoolDirectoryOrNull(context)
        val builtInDiskSpoolPolicy = resolveDiskSpoolPolicy(
            DiskSpoolStorageResolver.usableSpaceForSpoolDirectory(builtInSpoolDirectory)
        )
        val externalDiskSpoolPolicy = externalSpoolDirectory?.let { directory ->
            resolveDiskSpoolPolicy(DiskSpoolStorageResolver.usableSpaceForSpoolDirectory(directory))
        }
        val externalAvailable = externalSpoolDirectory != null
        val effectiveLocation = resolveEffectiveDiskSpoolStorageLocation(
            preferred = playerSettings.diskSpoolStorageLocation,
            builtInSupported = builtInDiskSpoolPolicy != null,
            externalSupported = externalDiskSpoolPolicy != null
        )
        SettingsActionRow(
            title = stringResource(R.string.playback_buffer_disk_spool_storage_location),
            subtitle = if (externalAvailable) {
                stringResource(R.string.playback_buffer_disk_spool_storage_location_sub)
            } else {
                stringResource(R.string.playback_buffer_disk_spool_storage_location_no_external)
            },
            value = when (effectiveLocation) {
                DiskSpoolStorageLocation.BUILTIN ->
                    stringResource(R.string.playback_buffer_disk_spool_storage_builtin)
                DiskSpoolStorageLocation.EXTERNAL ->
                    stringResource(R.string.playback_buffer_disk_spool_storage_external)
            },
            enabled = externalAvailable,
            onClick = {
                onSetDiskSpoolStorageLocation(
                    nextDiskSpoolStorageLocation(effectiveLocation, externalAvailable)
                )
            },
            onFocused = onItemFocused
        )
    }

    item(key = "network_cache_disk_spool_probe") {
        SettingsActionRow(
            title = stringResource(R.string.playback_buffer_disk_spool_diagnostic),
            subtitle = null,
            onClick = onRunDiskSpoolStorageProbe,
            onFocused = onItemFocused
        )
    }

    item(key = "network_cache_disk_spool_probe_status") {
        val context = LocalContext.current
        val builtInSpoolDirectory = DiskSpoolStorageResolver.builtinSpoolDirectory(context)
        val externalSpoolDirectory = DiskSpoolStorageResolver.externalSpoolDirectoryOrNull(context)
        val builtInDiskSpoolPolicy = resolveDiskSpoolPolicy(
            DiskSpoolStorageResolver.usableSpaceForSpoolDirectory(builtInSpoolDirectory)
        )
        val externalDiskSpoolPolicy = externalSpoolDirectory?.let { directory ->
            resolveDiskSpoolPolicy(DiskSpoolStorageResolver.usableSpaceForSpoolDirectory(directory))
        }
        val effectiveLocation = resolveEffectiveDiskSpoolStorageLocation(
            preferred = playerSettings.diskSpoolStorageLocation,
            builtInSupported = builtInDiskSpoolPolicy != null,
            externalSupported = externalDiskSpoolPolicy != null
        )
        val spoolDirectory = when (effectiveLocation) {
            DiskSpoolStorageLocation.BUILTIN -> builtInSpoolDirectory
            DiskSpoolStorageLocation.EXTERNAL -> externalSpoolDirectory ?: builtInSpoolDirectory
        }
        val status = resolveDiskSpoolDiagnosticStatus(
            result = SpoolStorageProbeResult.fromJsonOrNull(playerSettings.spoolStorageProbeResultJson),
            probeUiState = diskSpoolStorageProbeUiState,
            nowMs = System.currentTimeMillis(),
            spoolDirectoryPath = spoolDirectory.absolutePath
        )
        val statusText = when (status) {
            DiskSpoolDiagnosticStatus.NotChecked ->
                stringResource(R.string.playback_buffer_disk_spool_diagnostic_not_checked)
            DiskSpoolDiagnosticStatus.Running ->
                stringResource(R.string.playback_buffer_disk_spool_diagnostic_running)
            is DiskSpoolDiagnosticStatus.Failed ->
                stringResource(R.string.playback_buffer_disk_spool_diagnostic_failed, status.message)
            is DiskSpoolDiagnosticStatus.Measured -> {
                val recommendation = when (status.recommendation) {
                    DiskSpoolDiagnosticRecommendation.Recommended ->
                        stringResource(R.string.playback_buffer_disk_spool_diagnostic_recommended)
                    DiskSpoolDiagnosticRecommendation.NotRecommended ->
                        stringResource(R.string.playback_buffer_disk_spool_diagnostic_not_recommended)
                }
                val randomWriteMBps = status.randomWriteMBps
                if (randomWriteMBps != null) {
                    stringResource(
                        R.string.playback_buffer_disk_spool_diagnostic_measured_random,
                        recommendation,
                        status.underLoadWriteMBps,
                        status.underLoadReadMBps,
                        randomWriteMBps,
                        status.autoplayCapMbps,
                        status.p99ReadLatencyMs,
                        status.maxReadStallMs
                    )
                } else {
                    stringResource(
                        R.string.playback_buffer_disk_spool_diagnostic_measured,
                        recommendation,
                        status.underLoadWriteMBps,
                        status.underLoadReadMBps,
                        status.autoplayCapMbps,
                        status.p99ReadLatencyMs,
                        status.maxReadStallMs
                    )
                }
            }
            DiskSpoolDiagnosticStatus.Stale ->
                stringResource(R.string.playback_buffer_disk_spool_diagnostic_stale)
        }
        Text(
            text = statusText,
            style = MaterialTheme.typography.bodySmall,
            color = NexioColors.TextSecondary,
            modifier = Modifier.padding(bottom = 8.dp)
        )
    }
}

internal enum class ParallelConnectionsSubtitle {
    Default,
    WarningForDiskSpool
}

internal fun resolveParallelConnectionsSubtitle(
    useParallelConnections: Boolean,
    progressivePlaybackDiskMode: ProgressivePlaybackDiskMode
): ParallelConnectionsSubtitle {
    return if (useParallelConnections && progressivePlaybackDiskMode == ProgressivePlaybackDiskMode.SPOOL) {
        ParallelConnectionsSubtitle.WarningForDiskSpool
    } else {
        ParallelConnectionsSubtitle.Default
    }
}

internal fun nextDiskSpoolStorageLocation(
    current: DiskSpoolStorageLocation,
    externalAvailable: Boolean
): DiskSpoolStorageLocation {
    if (!externalAvailable) return DiskSpoolStorageLocation.BUILTIN
    return when (current) {
        DiskSpoolStorageLocation.BUILTIN -> DiskSpoolStorageLocation.EXTERNAL
        DiskSpoolStorageLocation.EXTERNAL -> DiskSpoolStorageLocation.BUILTIN
    }
}

internal fun resolveEffectiveDiskSpoolStorageLocation(
    preferred: DiskSpoolStorageLocation,
    builtInSupported: Boolean,
    externalSupported: Boolean
): DiskSpoolStorageLocation {
    return when {
        preferred == DiskSpoolStorageLocation.EXTERNAL && externalSupported ->
            DiskSpoolStorageLocation.EXTERNAL
        preferred == DiskSpoolStorageLocation.BUILTIN && builtInSupported ->
            DiskSpoolStorageLocation.BUILTIN
        externalSupported -> DiskSpoolStorageLocation.EXTERNAL
        else -> DiskSpoolStorageLocation.BUILTIN
    }
}

internal sealed class DiskSpoolDiagnosticStatus {
    object NotChecked : DiskSpoolDiagnosticStatus()
    object Running : DiskSpoolDiagnosticStatus()
    object Stale : DiskSpoolDiagnosticStatus()
    data class Failed(val message: String) : DiskSpoolDiagnosticStatus()
    data class Measured(
        val recommendation: DiskSpoolDiagnosticRecommendation,
        val autoplayCapMbps: Int,
        val underLoadWriteMBps: Int,
        val underLoadReadMBps: Int,
        val randomWriteMBps: Int?,
        val p99ReadLatencyMs: Long,
        val maxReadStallMs: Long
    ) : DiskSpoolDiagnosticStatus()
}

internal enum class DiskSpoolDiagnosticRecommendation {
    Recommended,
    NotRecommended
}

internal fun resolveDiskSpoolDiagnosticStatus(
    result: SpoolStorageProbeResult?,
    probeUiState: DiskSpoolStorageProbeUiState,
    nowMs: Long,
    spoolDirectoryPath: String
): DiskSpoolDiagnosticStatus {
    when (probeUiState) {
        DiskSpoolStorageProbeUiState.Running -> return DiskSpoolDiagnosticStatus.Running
        is DiskSpoolStorageProbeUiState.Failed ->
            return DiskSpoolDiagnosticStatus.Failed(probeUiState.message)
        DiskSpoolStorageProbeUiState.NotChecked -> Unit
    }
    if (result == null) return DiskSpoolDiagnosticStatus.NotChecked
    if (!SpoolStoragePolicy.isFresh(result, nowMs, spoolDirectoryPath)) {
        return DiskSpoolDiagnosticStatus.Stale
    }
    return DiskSpoolDiagnosticStatus.Measured(
        recommendation = if (SpoolStoragePolicy.isRecommended(result)) {
            DiskSpoolDiagnosticRecommendation.Recommended
        } else {
            DiskSpoolDiagnosticRecommendation.NotRecommended
        },
        autoplayCapMbps = SpoolStoragePolicy.recommendedAutoplayCapMbps(result),
        underLoadWriteMBps = mbpsToMBpsInt(SpoolStoragePolicy.underLoadWriteMbps(result)),
        underLoadReadMBps = mbpsToMBpsInt(SpoolStoragePolicy.underLoadReadMbps(result)),
        randomWriteMBps = SpoolStoragePolicy.underLoadRandomWriteMbps(result)?.let(::mbpsToMBpsInt),
        p99ReadLatencyMs = result.p99ReadLatencyMs,
        maxReadStallMs = result.maxReadStallMs
    )
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
    if (gb >= 10.0) return String.format(Locale.US, "%.0f GB", gb)
    if (gb >= 1.0) return String.format(Locale.US, "%.1f GB", gb)
    val mb = bytes / (1024.0 * 1024.0)
    return String.format(Locale.US, "%.0f MB", mb)
}

private fun mbpsToMBpsInt(mbps: Double): Int {
    return (mbps / 8.0).roundToInt()
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

internal fun resolveDiskSpoolPolicy(freeDiskBytes: Long): Int? {
    val freeDiskMb = freeDiskBytes.coerceAtLeast(0L) / (1024L * 1024L)
    if (freeDiskMb < MIN_DISK_SPOOL_FREE_SPACE_MB) return null
    val resolvedMb = min(
        PlayerSettings.MAX_DISK_SPOOL_SIZE_MB.toLong(),
        (freeDiskMb * 3L) / 4L
    )
    if (resolvedMb < PlayerSettings.MIN_DISK_SPOOL_SIZE_MB) return null
    return resolvedMb.toInt()
}

private const val VOD_CACHE_FREE_SPACE_RESERVE_MB = 1024L
private const val MIN_DISK_SPOOL_FREE_SPACE_MB = 768L
private const val FIXED_DISK_SPOOL_STARTUP_BUFFER_MB = 100
