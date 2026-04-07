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

/** 2 MiB — STARTUP clamp. Urgent chunk is small here because seek TTFB is unknown and
 *  over-committing a large chunk before the CDN confirms the range wastes bandwidth.
 *  Post-seek the envelope's full urgent chunk is used via the SEEK preset. */
internal const val STARTUP_URGENT_CHUNK_BYTES = 2L * 1024L * 1024L

internal enum class TransportState {
    STARTUP,
    SEEK,
    REBUFFER,
    STABILIZING,
    STEADY
}

internal class TransportPolicyController(
    private var envelope: CapabilityEnvelope = CapabilityEnvelope.DEFAULT,
    provider: String? = null
) {
    init {
        if (provider != null) {
            val locked = CapabilityEnvelope.lockedFor(provider)
            if (locked != null && !locked.matchesLockedShape(envelope)) {
                throw IllegalStateException("CapabilityEnvelope shape drift for provider=$provider")
            }
        }
    }

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
        val prev = state
        state = TransportState.SEEK
        PlayerTransportTelemetry.log(
            "tpc.policy",
            mapOf("from" to prev.name, "to" to TransportState.SEEK.name, "trigger" to "seek")
        )
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
        TransportState.STARTUP, TransportState.REBUFFER -> TransportPolicy(
            urgentWorkers = envelope.maxSafeUrgentWorkers,
            prefetchWorkers = 0,
            urgentChunkBytes = minOf(envelope.maxSafeUrgentChunkBytes, STARTUP_URGENT_CHUNK_BYTES),
            prefetchChunkBytes = 0L,
            warmAheadEnabled = false
        )
        // Post-seek: use the full envelope urgent chunk so throughput recovers immediately on
        // reopen. Prefetch is suppressed (prefetchWorkers=0) so urgent ranges run without
        // contention until onFirstFrame() transitions us to STABILIZING. The next open()
        // latches activeChunkSize from this policy's urgentChunkBytes — no reconfigure needed.
        TransportState.SEEK -> TransportPolicy(
            urgentWorkers = envelope.maxSafeUrgentWorkers,
            prefetchWorkers = 0,
            urgentChunkBytes = envelope.maxSafeUrgentChunkBytes,
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
