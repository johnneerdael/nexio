package com.nexio.tv.ui.screens.player

import com.nexio.tv.data.repository.benchmark.CapabilityEnvelope

internal data class TransportPolicy(
    val urgentWorkers: Int,
    val prefetchWorkers: Int,
    val urgentChunkBytes: Long,
    val prefetchChunkBytes: Long,
    val warmAheadEnabled: Boolean,
    val connectionBudgetHint: Int? = null,
    val retryMode: RuntimeTransportRetryMode = RuntimeTransportRetryMode.DEFAULT,
    val warmAheadBudgetMax: Int? = null
)

internal enum class TransportState {
    STARTUP,
    SEEK,
    REBUFFER,
    STABILIZING,
    STEADY
}

internal class TransportPolicyController(
    private var envelope: CapabilityEnvelope = CapabilityEnvelope.DEFAULT
) {
    var state: TransportState = TransportState.STARTUP
        private set

    val currentPolicy: TransportPolicy
        get() = policyForState(state)

    fun onFirstFrame() {
        if (state == TransportState.STARTUP) {
            state = TransportState.STABILIZING
        }
    }

    fun onSeek() {
        state = TransportState.STARTUP
    }

    fun onRebuffer() {
        state = TransportState.REBUFFER
    }

    fun onStable(bufferAheadMs: Long) {
        if (bufferAheadMs > 5000 && (state == TransportState.STARTUP || state == TransportState.REBUFFER)) {
            state = TransportState.STABILIZING
        }
    }

    fun onSteady(bufferAheadMs: Long) {
        if (bufferAheadMs > 15000 && state == TransportState.STABILIZING) {
            state = TransportState.STEADY
        }
    }

    fun updateEnvelope(newEnvelope: CapabilityEnvelope) {
        envelope = newEnvelope
    }

    private fun policyForState(state: TransportState): TransportPolicy = when (state) {
        TransportState.STARTUP, TransportState.SEEK, TransportState.REBUFFER -> TransportPolicy(
            urgentWorkers = envelope.maxSafeUrgentWorkers,
            prefetchWorkers = 0,
            urgentChunkBytes = minOf(envelope.maxSafeUrgentChunkBytes, 2L * 1024L * 1024L),
            prefetchChunkBytes = 0L,
            warmAheadEnabled = false
        )
        TransportState.STABILIZING -> TransportPolicy(
            urgentWorkers = envelope.maxSafeUrgentWorkers,
            prefetchWorkers = maxOf(1, envelope.maxSafePrefetchWorkers / 2),
            urgentChunkBytes = envelope.maxSafeUrgentChunkBytes,
            prefetchChunkBytes = envelope.maxSafePrefetchChunkBytes,
            warmAheadEnabled = false
        )
        TransportState.STEADY -> TransportPolicy(
            urgentWorkers = maxOf(1, envelope.maxSafeUrgentWorkers - 1),
            prefetchWorkers = envelope.maxSafePrefetchWorkers,
            urgentChunkBytes = envelope.maxSafeUrgentChunkBytes,
            prefetchChunkBytes = envelope.maxSafePrefetchChunkBytes,
            warmAheadEnabled = true
        )
    }
}

internal fun TransportPolicy.applyRuntimeTransportSpecialization(
    specialization: RuntimeTransportSpecialization,
    runtimeHints: com.nexio.tv.data.repository.benchmark.RuntimeTransportHintsV2?
): TransportPolicy {
    val baselineUrgentChunkBytes = minOf(urgentChunkBytes, 8L * 1024L * 1024L)
    if (!specialization.allowUrgentChunkAbove8MiB) {
        return copy(
            urgentChunkBytes = baselineUrgentChunkBytes,
            connectionBudgetHint = null,
            retryMode = RuntimeTransportRetryMode.DEFAULT
        )
    }

    val specializedUrgentChunkBytes = runtimeHints?.recommendedUrgentChunkBytes
        ?.takeIf { it > 8L * 1024L * 1024L }
        ?: urgentChunkBytes

    val specializedUrgentWorkers = runtimeHints?.recommendedUrgentWorkers
        ?.takeIf { it > 0 }
        ?: urgentWorkers

    val specializedPrefetchWorkers = specialization.recommendedPrefetchWorkers
        ?.takeIf { it > 0 }
        ?: prefetchWorkers

    val specializedPrefetchChunkBytes = specialization.recommendedPrefetchChunkBytes
        ?.takeIf { it > 0L }
        ?: prefetchChunkBytes

    // RD (has connection budget): warm-ahead max 1, counted against budget
    // PM (no budget): warm-ahead follows existing policy
    val warmAheadMax = if (specialization.connectionBudgetHint != null) 1 else null

    return copy(
        urgentWorkers = specializedUrgentWorkers,
        prefetchWorkers = specializedPrefetchWorkers,
        urgentChunkBytes = specializedUrgentChunkBytes,
        prefetchChunkBytes = specializedPrefetchChunkBytes,
        connectionBudgetHint = specialization.connectionBudgetHint,
        retryMode = specialization.retryMode,
        warmAheadBudgetMax = warmAheadMax
    )
}
