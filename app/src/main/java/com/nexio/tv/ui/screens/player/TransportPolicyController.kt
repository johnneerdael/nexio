package com.nexio.tv.ui.screens.player

import com.nexio.tv.data.repository.benchmark.CapabilityEnvelope
import com.nexio.tv.instrumentation.EventFamily
import com.nexio.tv.instrumentation.PlaybackTracer

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

/** Unknown-provider startup preset: 1 urgent worker at 24 MiB. */
internal const val DEFAULT_STARTUP_URGENT_CHUNK_BYTES = 24L * 1024L * 1024L
internal const val DEFAULT_STARTUP_URGENT_WORKERS = 1

internal enum class TransportState {
    STARTUP,
    SEEK,
    REBUFFER,
    STABILIZING,
    STEADY
}

internal class TransportPolicyController(
    private var envelope: CapabilityEnvelope = CapabilityEnvelope.DEFAULT,
    private val provider: String? = null
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
            transitionTo(TransportState.STABILIZING, trigger = "first_frame")
        }
    }

    fun onSeek() {
        transitionTo(TransportState.SEEK, trigger = "seek")
    }

    fun onRebuffer() {
        transitionTo(TransportState.REBUFFER, trigger = "rebuffer")
    }

    fun onStable(bufferAheadMs: Long) {
        if (bufferAheadMs > 5000 && (state == TransportState.STARTUP || state == TransportState.REBUFFER)) {
            transitionTo(TransportState.STABILIZING, trigger = "stable", bufferAheadMs = bufferAheadMs)
        }
    }

    fun onSteady(bufferAheadMs: Long) {
        if (bufferAheadMs > 15000 && state == TransportState.STABILIZING) {
            transitionTo(TransportState.STEADY, trigger = "steady", bufferAheadMs = bufferAheadMs)
        }
    }

    fun updateEnvelope(newEnvelope: CapabilityEnvelope) {
        envelope = newEnvelope
    }

    private fun startupPolicy(): TransportPolicy {
        val locked = provider?.let { CapabilityEnvelope.lockedFor(it) }
        return if (locked != null) {
            TransportPolicy(
                urgentWorkers = locked.maxSafeUrgentWorkers,
                prefetchWorkers = 0,
                urgentChunkBytes = locked.maxSafeUrgentChunkBytes,
                prefetchChunkBytes = 0L,
                warmAheadEnabled = false
            )
        } else {
            TransportPolicy(
                urgentWorkers = DEFAULT_STARTUP_URGENT_WORKERS,
                prefetchWorkers = 0,
                urgentChunkBytes = DEFAULT_STARTUP_URGENT_CHUNK_BYTES,
                prefetchChunkBytes = 0L,
                warmAheadEnabled = false
            )
        }
    }

    private fun transitionTo(next: TransportState, trigger: String, bufferAheadMs: Long? = null) {
        val previous = state
        if (previous == next) return
        state = next
        val policy = currentPolicy
        val fields = mutableMapOf<String, Any?>(
            "from" to previous.name,
            "to" to next.name,
            "trigger" to trigger,
            "provider" to provider,
            "urgentWorkers" to policy.urgentWorkers,
            "prefetchWorkers" to policy.prefetchWorkers,
            "urgentChunkBytes" to policy.urgentChunkBytes,
            "prefetchChunkBytes" to policy.prefetchChunkBytes,
            "warmAheadEnabled" to policy.warmAheadEnabled,
        )
        bufferAheadMs?.let { fields["bufferAheadMs"] = it }
        PlayerTransportTelemetry.log("tpc.policy", fields)
        if (!PlaybackTracer.enabled) return
        PlaybackTracer.emit(EventFamily.POLICY, "transport_policy_state") {
            putString("from", previous.name)
            putString("to", next.name)
            putString("trigger", trigger)
            putString("provider", provider)
            bufferAheadMs?.let { putLong("bufferAheadMs", it) }
            putInt("urgentWorkers", policy.urgentWorkers)
            putInt("prefetchWorkers", policy.prefetchWorkers)
            putLong("urgentChunkBytes", policy.urgentChunkBytes)
            putLong("prefetchChunkBytes", policy.prefetchChunkBytes)
            putBool("warmAheadEnabled", policy.warmAheadEnabled)
        }
    }

    private fun policyForState(state: TransportState): TransportPolicy = when (state) {
        TransportState.STARTUP, TransportState.REBUFFER -> startupPolicy()
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
    // When no runtime specialization is confirmed, preserve the urgentChunkBytes from the
    // policy's envelope unchanged. The previous 8 MiB cap here was intended as a guard for
    // unknown servers, but it silently overrides locked-envelope chunk sizes (e.g.
    // LOCKED_REAL_DEBRID = 32 MiB) causing a per-chunk TCP+TLS handshake stall on every
    // chunk boundary for connection-close HTTP/1.1 providers. The provider-aware STARTUP preset is
    // already applied by policyForState(STARTUP) before we reach here, so no extra cap is
    // needed at this layer.
    if (!specialization.allowUrgentChunkAbove8MiB) {
        return copy(
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
