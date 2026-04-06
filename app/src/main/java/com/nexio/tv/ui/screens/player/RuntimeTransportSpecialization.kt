package com.nexio.tv.ui.screens.player

import com.nexio.tv.data.repository.benchmark.RuntimeTransportHintsV2
import com.nexio.tv.data.repository.benchmark.TransportRetryMode
import com.nexio.tv.data.repository.benchmark.normalizedBenchmarkServiceKey

internal enum class RuntimeTransportRetryMode {
    DEFAULT,
    CONNECTION_CLOSE
}

internal data class RuntimeTransportSpecialization(
    val allowUrgentChunkAbove8MiB: Boolean = false,
    val connectionBudgetHint: Int? = null,
    val retryMode: RuntimeTransportRetryMode = RuntimeTransportRetryMode.DEFAULT,
    val recommendedPrefetchWorkers: Int? = null,
    val recommendedPrefetchChunkBytes: Long? = null
)

internal fun RuntimeTransportSpecialization.isSpecialized(): Boolean {
    return allowUrgentChunkAbove8MiB ||
        connectionBudgetHint != null ||
        retryMode != RuntimeTransportRetryMode.DEFAULT ||
        recommendedPrefetchWorkers != null ||
        recommendedPrefetchChunkBytes != null
}

internal fun resolveRuntimeTransportSpecialization(
    enabled: Boolean,
    activeServiceKey: String?,
    activeHostScope: String?,
    activeTransportClass: String?,
    runtimeHints: RuntimeTransportHintsV2?,
    nowMs: Long = System.currentTimeMillis()
): RuntimeTransportSpecialization {
    if (!enabled || runtimeHints == null) return RuntimeTransportSpecialization()
    if (!runtimeHints.isEligibleForSpecialization(nowMs)) return RuntimeTransportSpecialization()

    val normalizedActiveServiceKey = normalizedBenchmarkServiceKey(activeServiceKey)
    val confirmedServiceKey = normalizedBenchmarkServiceKey(runtimeHints.serviceKey)
    val confirmed = normalizedActiveServiceKey != null &&
        normalizedActiveServiceKey == confirmedServiceKey &&
        !activeHostScope.isNullOrBlank() &&
        activeHostScope == runtimeHints.observedHostScope &&
        !activeTransportClass.isNullOrBlank() &&
        !runtimeHints.observedTransportClass.isNullOrBlank() &&
        runtimeHints.observedTransportClass == activeTransportClass

    if (!confirmed) return RuntimeTransportSpecialization()

    // Derive prefetch settings from confirmed provider characteristics.
    // Both RD and PM confirmed paths get prefetchWorkers=1.
    // PM (keep-alive) gets the recommended chunk; RD (connection-close) gets 32MiB prefetch.
    val isConnectionClose = runtimeHints.typedRetryMode == TransportRetryMode.CONNECTION_CLOSE
    val prefetchWorkers = 1
    val prefetchChunkBytes = if (isConnectionClose) {
        32L * 1024L * 1024L
    } else {
        runtimeHints.recommendedUrgentChunkBytes?.let { it * 2 }
            ?: (32L * 1024L * 1024L)
    }

    return RuntimeTransportSpecialization(
        allowUrgentChunkAbove8MiB = (runtimeHints.recommendedUrgentChunkBytes ?: 0L) > (8L * 1024L * 1024L),
        connectionBudgetHint = runtimeHints.connectionBudgetHint,
        retryMode = runtimeHints.retryMode
            ?.trim()
            ?.takeIf { it.isNotBlank() }
            ?.lowercase()
            ?.let { mode ->
                when (mode) {
                    "connection_close" -> RuntimeTransportRetryMode.CONNECTION_CLOSE
                    else -> RuntimeTransportRetryMode.DEFAULT
                }
            } ?: RuntimeTransportRetryMode.DEFAULT,
        recommendedPrefetchWorkers = prefetchWorkers,
        recommendedPrefetchChunkBytes = prefetchChunkBytes
    )
}
