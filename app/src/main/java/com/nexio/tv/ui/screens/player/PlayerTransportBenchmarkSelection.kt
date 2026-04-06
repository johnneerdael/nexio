package com.nexio.tv.ui.screens.player

import com.nexio.tv.data.repository.benchmark.CapabilityEnvelope
import com.nexio.tv.data.repository.benchmark.DebridBenchmarkProvider
import com.nexio.tv.data.repository.benchmark.DebridConfigBenchmarkResult
import com.nexio.tv.data.repository.benchmark.HintFreshness
import com.nexio.tv.data.repository.benchmark.RuntimeTransportHintsV2
import com.nexio.tv.data.repository.benchmark.benchmarkProviderForServiceKey
import com.nexio.tv.data.repository.benchmark.toCapabilityEnvelope

internal data class SelectedTransportBenchmark(
    val capabilityEnvelope: CapabilityEnvelope?,
    val runtimeTransportHints: RuntimeTransportHintsV2?
)

internal fun selectTransportBenchmarkForServiceKey(
    serviceKey: String?,
    latestResults: Map<DebridBenchmarkProvider, DebridConfigBenchmarkResult?>,
    nowMs: Long = System.currentTimeMillis()
): SelectedTransportBenchmark? {
    val provider = benchmarkProviderForServiceKey(serviceKey) ?: return null
    val result = latestResults[provider] ?: return null
    val runtimeHints = result.summary.runtimeTransportHints?.takeIf { hints ->
        hints.isEligibleForSpecialization(nowMs)
    }
    return SelectedTransportBenchmark(
        capabilityEnvelope = result.summary.capabilityEnvelope ?: result.summary.toCapabilityEnvelope(result.measuredAtMs),
        runtimeTransportHints = runtimeHints
    )
}

internal fun selectCapabilityEnvelopeForServiceKey(
    serviceKey: String?,
    latestResults: Map<DebridBenchmarkProvider, DebridConfigBenchmarkResult?>,
    nowMs: Long = System.currentTimeMillis()
): CapabilityEnvelope? {
    return selectTransportBenchmarkForServiceKey(serviceKey, latestResults, nowMs)?.capabilityEnvelope
}
