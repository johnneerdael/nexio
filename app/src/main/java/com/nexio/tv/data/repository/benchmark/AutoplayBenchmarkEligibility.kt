package com.nexio.tv.data.repository.benchmark

import com.nexio.tv.data.local.PlayerSettings
import com.nexio.tv.data.local.VodCacheSizeMode

internal fun DebridBenchmarkResult.hasValidAutoplayBenchmarkFor(settings: PlayerSettings): Boolean {
    if (terminationReason != DebridBenchmarkTerminationReason.COMPLETED) return false
    val profile = optimized ?: return false
    profile.safeSustainedBudgetMbps()
        ?.takeIf { it.isFinite() && it > 0.0 }
        ?: return false
    val configSnapshot = profile.configSnapshot ?: return false
    return configSnapshot.matchesAutoplayTransportSettings(settings)
}

internal fun DebridBenchmarkTransportConfigSnapshot.matchesAutoplayTransportSettings(
    settings: PlayerSettings
): Boolean {
    return vodCacheEnabled == (settings.vodCacheSizeMode == VodCacheSizeMode.ON) &&
        parallelConnectionsEnabled == settings.useParallelConnections &&
        parallelConnectionCount == settings.parallelConnectionCount &&
        parallelChunkSizeMb == settings.parallelChunkSizeMb
}
