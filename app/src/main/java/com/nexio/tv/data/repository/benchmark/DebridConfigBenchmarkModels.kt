package com.nexio.tv.data.repository.benchmark

import com.google.gson.JsonArray
import com.google.gson.JsonObject

enum class DebridConfigBenchmarkStatus {
    SUCCESS,
    FAILED,
    UNSUPPORTED;

    val wireKey: String
        get() = when (this) {
            SUCCESS -> "success"
            FAILED -> "failed"
            UNSUPPORTED -> "unsupported"
        }

    companion object {
        private val byWireKey = entries.associateBy { it.wireKey }

        fun fromWireKey(wireKey: String): DebridConfigBenchmarkStatus? = byWireKey[wireKey]
    }
}

data class DebridConfigBenchmarkProfileMetadata(
    val parallelConnectionCount: Int,
    val chunkSizeMb: Int
)

data class DebridConfigBenchmarkProfileResult(
    val parallelConnectionCount: Int,
    val chunkSizeMb: Int,
    val status: DebridConfigBenchmarkStatus,
    val averageThroughputMbps: Double? = null,
    val transferredBytes: Long? = null,
    val elapsedMs: Long? = null,
    val failureReason: String? = null,
    val unsupportedReason: String? = null,
    val configSnapshot: DebridBenchmarkTransportConfigSnapshot? = null
) {
    constructor(
        profile: DebridConfigBenchmarkProfileMetadata,
        status: DebridConfigBenchmarkStatus,
        averageThroughputMbps: Double? = null,
        transferredBytes: Long? = null,
        elapsedMs: Long? = null,
        failureReason: String? = null,
        unsupportedReason: String? = null,
        configSnapshot: DebridBenchmarkTransportConfigSnapshot? = null
    ) : this(
        parallelConnectionCount = profile.parallelConnectionCount,
        chunkSizeMb = profile.chunkSizeMb,
        status = status,
        averageThroughputMbps = averageThroughputMbps,
        transferredBytes = transferredBytes,
        elapsedMs = elapsedMs,
        failureReason = failureReason,
        unsupportedReason = unsupportedReason,
        configSnapshot = configSnapshot
    )

    val profile: DebridConfigBenchmarkProfileMetadata
        get() = DebridConfigBenchmarkProfileMetadata(
            parallelConnectionCount = parallelConnectionCount,
            chunkSizeMb = chunkSizeMb
        )
}

data class DebridConfigBenchmarkSessionSummary(
    val totalProfileCount: Int,
    val successfulProfileCount: Int,
    val failedProfileCount: Int,
    val unsupportedProfileCount: Int,
    val totalElapsedMs: Long? = null,
    val bestProfile: DebridConfigBenchmarkProfileResult? = null
) {
    val totalProfiles: Int
        get() = totalProfileCount
    val successfulProfiles: Int
        get() = successfulProfileCount
    val failedProfiles: Int
        get() = failedProfileCount
    val unsupportedProfiles: Int
        get() = unsupportedProfileCount
}

data class DebridConfigBenchmarkResult(
    val provider: DebridBenchmarkProvider,
    val measuredAtMs: Long,
    val candidate: DebridBenchmarkCandidateMetadata = DebridBenchmarkCandidateMetadata(),
    val summary: DebridConfigBenchmarkSessionSummary,
    val profiles: List<DebridConfigBenchmarkProfileResult>
) {
    val orderedProfileResults: List<DebridConfigBenchmarkProfileResult>
        get() = profiles
    val bestProfile: DebridConfigBenchmarkProfileResult?
        get() = summary.bestProfile
}

sealed interface DebridConfigBenchmarkRuntimeState {
    data object Idle : DebridConfigBenchmarkRuntimeState

    data class Running(
        val provider: DebridBenchmarkProvider,
        val currentProfile: DebridConfigBenchmarkProfileMetadata? = null,
        val completedProfiles: Int = 0,
        val totalProfiles: Int = 0,
        val summary: DebridBenchmarkSummary? = null
    ) : DebridConfigBenchmarkRuntimeState
}

data class DebridConfigBenchmarkOutcome(
    val provider: DebridBenchmarkProvider,
    val terminationReason: DebridBenchmarkTerminationReason,
    val result: DebridConfigBenchmarkResult? = null
)

internal fun DebridConfigBenchmarkResult.toJsonObject(): JsonObject {
    return JsonObject().apply {
        addProperty("provider", provider.storageKey)
        addProperty("measuredAtMs", measuredAtMs)
        add("candidate", candidate.toJsonObject())
        add("summary", summary.toJsonObject())
        add("orderedProfileResults", JsonArray().apply {
            orderedProfileResults.forEach { add(it.toJsonObject()) }
        })
        bestProfile?.let { add("bestProfile", it.toJsonObject()) }
    }
}

private fun DebridConfigBenchmarkSessionSummary.toJsonObject(): JsonObject {
    return JsonObject().apply {
        addProperty("totalProfiles", totalProfiles)
        addProperty("successfulProfiles", successfulProfiles)
        addProperty("failedProfiles", failedProfiles)
        addProperty("unsupportedProfiles", unsupportedProfiles)
        totalElapsedMs?.let { addProperty("totalElapsedMs", it) }
        bestProfile?.let { add("bestProfile", it.toJsonObject()) }
    }
}

private fun DebridConfigBenchmarkProfileResult.toJsonObject(): JsonObject {
    return JsonObject().apply {
        add("profile", profile.toJsonObject())
        addProperty("status", status.wireKey)
        averageThroughputMbps?.let { addProperty("averageThroughputMbps", it) }
        transferredBytes?.let { addProperty("transferredBytes", it) }
        elapsedMs?.let { addProperty("elapsedMs", it) }
        failureReason?.let { addProperty("failureReason", it) }
        unsupportedReason?.let { addProperty("unsupportedReason", it) }
        configSnapshot?.let { config ->
            add("configSnapshot", JsonObject().apply {
                config.useParallelConnections?.let { addProperty("useParallelConnections", it) }
                config.parallelConnectionCount?.let { addProperty("parallelConnectionCount", it) }
                config.parallelChunkSizeMb?.let { addProperty("parallelChunkSizeMb", it) }
            })
        }
    }
}

private fun DebridConfigBenchmarkProfileMetadata.toJsonObject(): JsonObject {
    return JsonObject().apply {
        addProperty("parallelConnectionCount", parallelConnectionCount)
        addProperty("chunkSizeMb", chunkSizeMb)
    }
}
