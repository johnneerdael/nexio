package com.nexio.tv.ui.screens.player

import com.nexio.tv.data.repository.benchmark.CapabilityEnvelope
import com.nexio.tv.data.repository.benchmark.DebridBenchmarkProvider
import com.nexio.tv.data.repository.benchmark.DebridBenchmarkResult
import com.nexio.tv.data.repository.benchmark.DebridConfigBenchmarkResult
import com.nexio.tv.data.repository.benchmark.RuntimeTransportHintsV2
import com.nexio.tv.data.repository.benchmark.benchmarkProviderForServiceKey
import com.nexio.tv.data.repository.benchmark.toCapabilityEnvelope

internal enum class SelectedTransportBenchmarkProvenance(val traceSource: String) {
    CONFIG_BENCHMARK("config_benchmark"),
    FALLBACK_BENCHMARK("fallback_benchmark"),
    LOCKED_DEFAULT("locked_default"),
    NONE("none")
}

internal data class SelectedTransportBenchmark(
    val capabilityEnvelope: CapabilityEnvelope?,
    val runtimeTransportHints: RuntimeTransportHintsV2?,
    val benchmarkResultId: String?,
    val providerStorageKey: String?,
    val provenance: SelectedTransportBenchmarkProvenance
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
            runtimeTransportHints = runtimeHints,
            benchmarkResultId = "${primaryResult.provider.storageKey}:${primaryResult.measuredAtMs}",
            providerStorageKey = primaryResult.provider.storageKey,
            provenance = SelectedTransportBenchmarkProvenance.CONFIG_BENCHMARK
        )
    }

    // Fallback: DebridBenchmarkStore attached envelope (bridge path, Phase A2)
    val fallbackResult = fallbackResults[provider]
    val fallbackEnvelope = fallbackResult?.capabilityEnvelope
    if (fallbackEnvelope != null) {
        return SelectedTransportBenchmark(
            capabilityEnvelope = fallbackEnvelope,
            runtimeTransportHints = null,
            benchmarkResultId = "${provider.storageKey}:${fallbackResult.measuredAtMs}",
            providerStorageKey = provider.storageKey,
            provenance = SelectedTransportBenchmarkProvenance.FALLBACK_BENCHMARK
        )
    }

    return null
}

internal fun selectedBenchmarkProvenanceForServiceKey(
    serviceKey: String?,
    selectedTransportBenchmark: SelectedTransportBenchmark?,
    lockedEnvelope: CapabilityEnvelope?
): SelectedTransportBenchmarkProvenance {
    return when {
        selectedTransportBenchmark != null -> selectedTransportBenchmark.provenance
        benchmarkProviderForServiceKey(serviceKey) != null && lockedEnvelope != null ->
            SelectedTransportBenchmarkProvenance.LOCKED_DEFAULT
        else -> SelectedTransportBenchmarkProvenance.NONE
    }
}

internal fun SelectedTransportBenchmarkProvenance.toTraceSourceOrNull(): String? {
    return if (this == SelectedTransportBenchmarkProvenance.NONE) null else traceSource
}

internal fun selectCapabilityEnvelopeForServiceKey(
    serviceKey: String?,
    latestResults: Map<DebridBenchmarkProvider, DebridConfigBenchmarkResult?>,
    fallbackResults: Map<DebridBenchmarkProvider, DebridBenchmarkResult?> = emptyMap(),
    nowMs: Long = System.currentTimeMillis()
): CapabilityEnvelope? {
    return selectTransportBenchmarkForServiceKey(serviceKey, latestResults, fallbackResults, nowMs)?.capabilityEnvelope
}
