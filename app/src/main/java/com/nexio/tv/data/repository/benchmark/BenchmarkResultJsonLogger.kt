package com.nexio.tv.data.repository.benchmark

import android.util.Log
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton

private const val BENCHMARK_JSON_TAG = "BenchmarkJson"
private const val BENCHMARK_SUMMARY_TAG = "BenchmarkSummary"

@Singleton
class BenchmarkResultJsonLogger internal constructor(
    private val logger: (String, String) -> Unit
) {

    @Inject
    constructor() : this(logger = { tag, message -> Log.i(tag, message) })

    fun logCompleted(result: DebridBenchmarkResult) {
        runCatching {
            logger(BENCHMARK_JSON_TAG, buildCompletedEventJson(result))
        }.onFailure {
            logger(BENCHMARK_SUMMARY_TAG, buildSummaryLine(result) + " serialization=failed")
        }.onSuccess {
            logger(BENCHMARK_SUMMARY_TAG, buildSummaryLine(result))
        }
    }

    fun logOutcome(
        provider: DebridBenchmarkProvider,
        terminationReason: DebridBenchmarkTerminationReason,
        summary: DebridBenchmarkSummary,
        failureDetails: DebridBenchmarkFailureDetails? = null
    ) {
        runCatching {
            logger(
                BENCHMARK_JSON_TAG,
                buildOutcomeEventJson(
                    provider = provider,
                    terminationReason = terminationReason,
                    summary = summary,
                    failureDetails = failureDetails
                )
            )
        }.onFailure {
            logger(
                BENCHMARK_SUMMARY_TAG,
                buildOutcomeSummaryLine(
                    provider = provider,
                    terminationReason = terminationReason,
                    summary = summary,
                    failureDetails = failureDetails
                ) + " serialization=failed"
            )
        }.onSuccess {
            logger(
                BENCHMARK_SUMMARY_TAG,
                buildOutcomeSummaryLine(
                    provider = provider,
                    terminationReason = terminationReason,
                    summary = summary,
                    failureDetails = failureDetails
                )
            )
        }
    }

    internal fun buildCompletedEventJson(result: DebridBenchmarkResult): String {
        return JsonObject().apply {
            addProperty("event_version", 1)
            addProperty("event_type", "benchmark_session_completed")
            add("result", result.toJsonObject())
        }.toString()
    }

    internal fun buildOutcomeEventJson(
        provider: DebridBenchmarkProvider,
        terminationReason: DebridBenchmarkTerminationReason,
        summary: DebridBenchmarkSummary,
        failureDetails: DebridBenchmarkFailureDetails? = null
    ): String {
        return JsonObject().apply {
            addProperty("event_version", 1)
            addProperty("event_type", "benchmark_session_outcome")
            addProperty("provider", provider.storageKey)
            addProperty("terminationReason", terminationReason.wireKey)
            add("summary", summary.toJsonObject())
            failureDetails?.let { details ->
                add("failure", JsonObject().apply {
                    details.failedTransport?.let { addProperty("failedTransport", it.wireKey) }
                    details.transportFailure?.let { failure ->
                        add("transportFailure", JsonObject().apply {
                            failure.exceptionClass?.let { addProperty("exceptionClass", it) }
                            failure.message?.let { addProperty("message", it) }
                            failure.chunkIndex?.let { addProperty("chunkIndex", it) }
                        })
                    }
                    details.candidate?.let { add("candidate", it.toJsonObject()) }
                    details.direct?.let { add("direct", it.toJsonObject()) }
                    details.optimized?.let { add("optimized", it.toJsonObject()) }
                })
            }
        }.toString()
    }

    internal fun buildSummaryLine(result: DebridBenchmarkResult): String {
        val directBudget = result.direct.safeSustainedBudgetMbps()
        val optimizedBudget = result.optimized.safeSustainedBudgetMbps()
        return buildString {
            append("provider=")
            append(result.provider.storageKey)
            append(" sustained_winner=")
            append(result.comparison?.sustainedWinner?.wireKey ?: "unknown")
            append(" seek_winner=")
            append(result.comparison?.seekWinner?.wireKey ?: "unknown")
            append(" stability_winner=")
            append(result.comparison?.stabilityWinner?.wireKey ?: "unknown")
            directBudget?.let {
                append(" direct_safe_budget=")
                append(String.format(Locale.US, "%.1f", it))
            }
            optimizedBudget?.let {
                append(" optimized_safe_budget=")
                append(String.format(Locale.US, "%.1f", it))
            }
        }
    }

    internal fun buildOutcomeSummaryLine(
        provider: DebridBenchmarkProvider,
        terminationReason: DebridBenchmarkTerminationReason,
        summary: DebridBenchmarkSummary,
        failureDetails: DebridBenchmarkFailureDetails? = null
    ): String {
        return buildString {
            append("provider=")
            append(provider.storageKey)
            append(" termination_reason=")
            append(terminationReason.wireKey)
            failureDetails?.failedTransport?.let {
                append(" failed_transport=")
                append(it.wireKey)
            }
            failureDetails?.transportFailure?.exceptionClass?.let {
                append(" failure_class=")
                append(it)
            }
            failureDetails?.transportFailure?.chunkIndex?.let {
                append(" chunk_index=")
                append(it)
            }
            summary.startupTimeMs?.let {
                append(" startup_ms=")
                append(it)
            }
            summary.sustainedThroughputMbps?.let {
                append(" sustained_mbps=")
                append(String.format(Locale.US, "%.1f", it))
            }
            append(" transferred_bytes=")
            append(summary.transferredBytes)
            append(" elapsed_ms=")
            append(summary.elapsedMs)
            failureDetails?.candidate?.host?.let {
                append(" host=")
                append(it)
            }
            failureDetails?.candidate?.filename?.let {
                append(" filename=")
                append(it)
            }
            failureDetails?.transportFailure?.message?.let {
                append(" failure_message=")
                append(it.replace(' ', '_'))
            }
        }
    }
}

internal fun DebridBenchmarkResult.toJsonObject(): JsonObject {
    return JsonObject().apply {
        addProperty("provider", provider.storageKey)
        addProperty("measuredAtMs", measuredAtMs)
        add("summary", summary.toJsonObject())
        addProperty("terminationReason", terminationReason.wireKey)
        candidate?.let { candidate -> add("candidate", candidate.toJsonObject()) }
        device?.let { add("device", it.toJsonObject()) }
        session?.let { session ->
            add("session", JsonObject().apply {
                addProperty("benchmarkVersion", session.benchmarkVersion)
                add("executionOrder", JsonArray().apply {
                    session.executionOrder.forEach { phase ->
                        add(JsonObject().apply {
                            addProperty("phase", phase.phase.wireKey)
                            add("order", JsonArray().apply {
                                phase.order.forEach { mode -> add(mode.wireKey) }
                            })
                        })
                    }
                })
                session.totalElapsedMs?.let { addProperty("totalElapsedMs", it) }
            })
        }
        direct?.let { add("direct", it.toJsonObject()) }
        optimized?.let { add("optimized", it.toJsonObject()) }
        comparison?.let { comparison ->
            add("comparison", JsonObject().apply {
                comparison.sustainedWinner?.let { addProperty("sustainedWinner", it.wireKey) }
                comparison.seekWinner?.let { addProperty("seekWinner", it.wireKey) }
                comparison.stabilityWinner?.let { addProperty("stabilityWinner", it.wireKey) }
            })
        }
    }
}

internal fun DebridBenchmarkSummary.toJsonObject(): JsonObject {
    return JsonObject().apply {
        startupTimeMs?.let { addProperty("startupTimeMs", it) }
        sustainedThroughputMbps?.let { addProperty("sustainedThroughputMbps", it) }
        addProperty("transferredBytes", transferredBytes)
        addProperty("elapsedMs", elapsedMs)
    }
}

internal fun DebridBenchmarkCandidateMetadata.toJsonObject(): JsonObject {
    return JsonObject().apply {
        filename?.let { addProperty("filename", it) }
        sizeBytes?.let { addProperty("sizeBytes", it) }
        host?.let { addProperty("host", it) }
        directUrlFingerprint?.let { addProperty("directUrlFingerprint", it) }
    }
}

internal fun DeviceCapabilitySnapshot.toJsonObject(): JsonObject {
    return JsonObject().apply {
        model?.let { addProperty("model", it) }
        manufacturer?.let { addProperty("manufacturer", it) }
        addProperty("sdkInt", sdkInt)
        add("displayHdrTypes", JsonArray().apply {
            displayHdrTypes.sortedBy { it.wireKey }.forEach { add(it.wireKey) }
        })
        add("videoDecode", JsonObject().apply {
            videoDecode.h264?.let { add("h264", it.toJsonObject()) }
            videoDecode.hevc?.let { add("hevc", it.toJsonObject()) }
            videoDecode.av1?.let { add("av1", it.toJsonObject()) }
            videoDecode.dolbyVision?.let { add("dolbyVision", it.toJsonObject()) }
        })
        add("audioOutput", JsonObject().apply {
            add("ac3", audioOutput.ac3.toJsonObject())
            add("eac3", audioOutput.eac3.toJsonObject())
            add("truehd", audioOutput.truehd.toJsonObject())
            add("dts", audioOutput.dts.toJsonObject())
            add("dtshd", audioOutput.dtshd.toJsonObject())
        })
        addProperty("capturedAtMs", capturedAtMs)
    }
}

private fun DebridBenchmarkTransportProfile.toJsonObject(): JsonObject {
    return JsonObject().apply {
        add("startup", JsonObject().apply {
            startup.initialTtfbMs?.let { addProperty("initialTtfbMs", it) }
            startup.startupFailureRate?.let { addProperty("startupFailureRate", it) }
        })
        add("sustained", JsonObject().apply {
            addProperty("collectorVersion", sustained.collectorVersion)
            sustained.samplingMode?.let { addProperty("samplingMode", it) }
            sustained.bucketMs?.let { addProperty("bucketMs", it) }
            sustained.averageThroughputMbps?.let { addProperty("averageThroughputMbps", it) }
            sustained.derivedAverageThroughputMbps?.let { addProperty("derivedAverageThroughputMbps", it) }
            addProperty("actionable", sustained.actionable)
            sustained.p10ThroughputMbps?.let { addProperty("p10ThroughputMbps", it) }
            sustained.p50ThroughputMbps?.let { addProperty("p50ThroughputMbps", it) }
            sustained.peakThroughputMbps?.let { addProperty("peakThroughputMbps", it) }
            sustained.throughputStddevMbps?.let { addProperty("throughputStddevMbps", it) }
            sustained.throughputCv?.let { addProperty("throughputCv", it) }
            sustained.stallCount?.let { addProperty("stallCount", it) }
            sustained.maxReadGapMs?.let { addProperty("maxReadGapMs", it) }
            sustained.bytesTransferred?.let { addProperty("bytesTransferred", it) }
            sustained.elapsedMs?.let { addProperty("elapsedMs", it) }
        })
        add("seek", JsonObject().apply {
            seek.seekTtfbP50Ms?.let { addProperty("seekTtfbP50Ms", it) }
            seek.seekTtfbP95Ms?.let { addProperty("seekTtfbP95Ms", it) }
            seek.seekTtfbP99Ms?.let { addProperty("seekTtfbP99Ms", it) }
            seek.seekTtfbStddevMs?.let { addProperty("seekTtfbStddevMs", it) }
            seek.seekFailRate?.let { addProperty("seekFailRate", it) }
        })
        configSnapshot?.let { config ->
            add("configSnapshot", JsonObject().apply {
                config.useParallelConnections?.let { addProperty("useParallelConnections", it) }
                config.parallelConnectionCount?.let { addProperty("parallelConnectionCount", it) }
                config.parallelChunkSizeMb?.let { addProperty("parallelChunkSizeMb", it) }
            })
        }
        add("rawSamples", JsonObject().apply {
            add("throughputWindowsMbps", JsonArray().apply {
                rawSamples.throughputWindowsMbps.forEach { add(it) }
            })
            add("throughputBuckets", JsonArray().apply {
                rawSamples.throughputBuckets.forEach { bucket ->
                    add(JsonObject().apply {
                        addProperty("startOffsetMs", bucket.startOffsetMs)
                        addProperty("durationMs", bucket.durationMs)
                        addProperty("bytesTransferred", bucket.bytesTransferred)
                        addProperty("throughputMbps", bucket.throughputMbps)
                        addProperty("complete", bucket.complete)
                    })
                }
            })
            add("seekSamples", JsonArray().apply {
                rawSamples.seekSamples.forEach { sample ->
                    add(JsonObject().apply {
                        addProperty("targetOffsetBytes", sample.targetOffsetBytes)
                        sample.ttfbMs?.let { addProperty("ttfbMs", it) }
                        addProperty("succeeded", sample.succeeded)
                    })
                }
            })
        })
    }
}

private fun CodecSupport.toJsonObject(): JsonObject {
    return JsonObject().apply {
        addProperty("hardwareAccelerated", hardwareAccelerated)
        addProperty("softwareOnlyAvailable", softwareOnlyAvailable)
        addProperty("secureSupported", secureSupported)
    }
}

private fun AudioEncodingSupport.toJsonObject(): JsonObject {
    return JsonObject().apply {
        addProperty("supported", supported)
        addProperty("passthroughLikely", passthroughLikely)
    }
}

internal fun DebridBenchmarkTransportProfile?.safeSustainedBudgetMbps(): Double? {
    val sustained = this?.sustained ?: return null
    if (!sustained.actionable) return null
    return sustained.p10ThroughputMbps?.times(0.85)
}
