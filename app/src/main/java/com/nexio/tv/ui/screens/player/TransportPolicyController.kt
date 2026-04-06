package com.nexio.tv.ui.screens.player

import com.nexio.tv.data.repository.benchmark.CapabilityEnvelope

internal data class TransportPolicy(
    val urgentWorkers: Int,
    val prefetchWorkers: Int,
    val urgentChunkBytes: Long,
    val prefetchChunkBytes: Long,
    val warmAheadEnabled: Boolean
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
