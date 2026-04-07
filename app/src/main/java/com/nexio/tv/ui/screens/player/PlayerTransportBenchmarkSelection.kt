package com.nexio.tv.ui.screens.player

import com.nexio.tv.data.repository.benchmark.CapabilityEnvelope
import com.nexio.tv.data.repository.benchmark.DebridBenchmarkProvider
import com.nexio.tv.data.repository.benchmark.DebridBenchmarkResult
import com.nexio.tv.data.repository.benchmark.DebridConfigBenchmarkResult
import com.nexio.tv.data.repository.benchmark.HintFreshness
import com.nexio.tv.data.repository.benchmark.RuntimeTransportHintsV2
import com.nexio.tv.data.repository.benchmark.benchmarkProviderForServiceKey
import com.nexio.tv.data.repository.benchmark.toCapabilityEnvelope

internal data class SelectedTransportBenchmark(
    val capabilityEnvelope: CapabilityEnvelope?,
    val runtimeTransportHints: RuntimeTransportHintsV2?
)

/**
 * Selects the best available [SelectedTransportBenchmark] for [serviceKey].
 *
 * Precedence (Phase A2 bridge):
 * 1. [DebridConfigBenchmarkStore] result ([latestResults]) — wins if present.
 * 2. [DebridBenchmarkStore] result ([fallbackResults]) — used if primary is absent and the
 *    stored result carries an attached [CapabilityEnvelope] (computed at benchmark run via the
 *    merge-only path).
 * 3. Absent from both — returns null; caller falls back to cold-start locked shape.
 */
internal fun selectTransportBenchmarkForServiceKey(
    serviceKey: String?,
    latestResults: Map<DebridBenchmarkProvider, DebridConfigBenchmarkResult?>,
    fallbackResults: Map<DebridBenchmarkProvider, DebridBenchmarkResult?> = emptyMap(),
    nowMs: Long = System.currentTimeMillis()
): SelectedTransportBenchmark? {
    val provider = benchmarkProviderForServiceKey(serviceKey) ?: return null

    // Primary: DebridConfigBenchmarkStore
    val primaryResult = latestResults[provider]
    if (primaryResult != null) {
        val runtimeHints = primaryResult.summary.runtimeTransportHints?.takeIf { hints ->
            hints.isEligibleForSpecialization(nowMs)
        }
        return SelectedTransportBenchmark(
            capabilityEnvelope = primaryResult.summary.capabilityEnvelope
                ?: primaryResult.summary.toCapabilityEnvelope(primaryResult.provider.storageKey, primaryResult.measuredAtMs),
            runtimeTransportHints = runtimeHints
        )
    }

    // Fallback: DebridBenchmarkStore attached envelope (bridge path, Phase A2)
    val fallbackEnvelope = fallbackResults[provider]?.capabilityEnvelope
    if (fallbackEnvelope != null) {
        return SelectedTransportBenchmark(
            capabilityEnvelope = fallbackEnvelope,
            runtimeTransportHints = null
        )
    }

    return null
}

internal fun selectCapabilityEnvelopeForServiceKey(
    serviceKey: String?,
    latestResults: Map<DebridBenchmarkProvider, DebridConfigBenchmarkResult?>,
    fallbackResults: Map<DebridBenchmarkProvider, DebridBenchmarkResult?> = emptyMap(),
    nowMs: Long = System.currentTimeMillis()
): CapabilityEnvelope? {
    return selectTransportBenchmarkForServiceKey(serviceKey, latestResults, fallbackResults, nowMs)?.capabilityEnvelope
}
