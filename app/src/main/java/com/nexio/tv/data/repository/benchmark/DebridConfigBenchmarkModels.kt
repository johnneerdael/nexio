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
    val bestProfile: DebridConfigBenchmarkProfileResult? = null,
    val capabilityEnvelope: CapabilityEnvelope? = null,
    val runtimeTransportHints: RuntimeTransportHintsV2? = null
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

enum class TransportClass(val wireKey: String) {
    KEEP_ALIVE("KEEP_ALIVE"),
    CONNECTION_CLOSE("CONNECTION_CLOSE"),
    UNKNOWN("UNKNOWN");

    companion object {
        private val byWireKey = entries.associateBy { it.wireKey }

        fun fromWireKey(wireKey: String?): TransportClass? =
            wireKey?.let { byWireKey[it.uppercase().trim()] }

        fun fromWireKeyOrUnknown(wireKey: String?): TransportClass =
            fromWireKey(wireKey) ?: UNKNOWN
    }
}

enum class TransportRetryMode(val wireKey: String) {
    DEFAULT("DEFAULT"),
    CONNECTION_CLOSE("CONNECTION_CLOSE");

    companion object {
        private val byWireKey = entries.associateBy { it.wireKey }

        fun fromWireKey(wireKey: String?): TransportRetryMode =
            wireKey?.let { byWireKey[it.uppercase().trim()] } ?: DEFAULT
    }
}

enum class HintFreshness {
    FRESH,
    STALE,
    EXPIRED;

    companion object {
        private const val FRESH_THRESHOLD_MS = 7L * 24L * 60L * 60L * 1000L
        private const val STALE_THRESHOLD_MS = 30L * 24L * 60L * 60L * 1000L

        fun of(measuredAtMs: Long, nowMs: Long = System.currentTimeMillis()): HintFreshness {
            val ageMs = nowMs - measuredAtMs
            return when {
                ageMs <= FRESH_THRESHOLD_MS -> FRESH
                ageMs <= STALE_THRESHOLD_MS -> STALE
                else -> EXPIRED
            }
        }
    }
}

data class RuntimeTransportHintsV2(
    val artifactVersion: Int,
    val serviceKey: String,
    val measuredAtMs: Long,
    val observedTransportClass: String? = null,
    val observedHostScope: String? = null,
    val recommendedUrgentChunkBytes: Long? = null,
    val recommendedUrgentWorkers: Int? = null,
    val connectionBudgetHint: Int? = null,
    val retryMode: String? = null
) {
    val typedTransportClass: TransportClass
        get() = TransportClass.fromWireKeyOrUnknown(observedTransportClass)

    val typedRetryMode: TransportRetryMode
        get() = TransportRetryMode.fromWireKey(retryMode)

    fun freshness(nowMs: Long = System.currentTimeMillis()): HintFreshness =
        HintFreshness.of(measuredAtMs, nowMs)

    val isV2: Boolean get() = artifactVersion >= 2

    val isLegacyUnconfirmed: Boolean get() = artifactVersion < 2

    fun isEligibleForSpecialization(nowMs: Long = System.currentTimeMillis()): Boolean =
        isV2 && freshness(nowMs) == HintFreshness.FRESH

    companion object {
        const val CURRENT_ARTIFACT_VERSION = 2

        const val GENERIC_SAFE_URGENT_CHUNK_BYTES = 8L * 1024L * 1024L

        fun genericSafeDefaults(serviceKey: String, measuredAtMs: Long): RuntimeTransportHintsV2 =
            RuntimeTransportHintsV2(
                artifactVersion = CURRENT_ARTIFACT_VERSION,
                serviceKey = serviceKey,
                measuredAtMs = measuredAtMs,
                observedTransportClass = TransportClass.UNKNOWN.wireKey,
                recommendedUrgentChunkBytes = GENERIC_SAFE_URGENT_CHUNK_BYTES,
                recommendedUrgentWorkers = 2,
                connectionBudgetHint = null,
                retryMode = TransportRetryMode.DEFAULT.wireKey
            )
    }
}

data class BenchmarkTransportCharacteristics(
    val negotiatedProtocol: String? = null,
    val connectionBehavior: String? = null,
    val connectionReuseDetected: Boolean? = null,
    val requestsPerConnection: Double? = null,
    val maxConnectionsPerSecond: Double? = null,
    val newConnectionRateTolerance: Double? = null
)

data class DebridConfigBenchmarkResult(
    val provider: DebridBenchmarkProvider,
    val measuredAtMs: Long,
    val candidate: DebridBenchmarkCandidateMetadata = DebridBenchmarkCandidateMetadata(),
    val summary: DebridConfigBenchmarkSessionSummary,
    val profiles: List<DebridConfigBenchmarkProfileResult>,
    val transportCharacteristics: BenchmarkTransportCharacteristics? = null
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

fun DebridConfigBenchmarkSessionSummary.toCapabilityEnvelope(measuredAtMs: Long): CapabilityEnvelope? {
    val best = bestProfile ?: return null
    if (best.status != DebridConfigBenchmarkStatus.SUCCESS) return null
    val throughput = best.averageThroughputMbps ?: return null

    return CapabilityEnvelope(
        maxSafeUrgentWorkers = minOf(best.parallelConnectionCount, 3),
        maxSafePrefetchWorkers = 1,
        maxSafeUrgentChunkBytes = minOf(best.chunkSizeMb.toLong(), 8L) * 1024L * 1024L,
        maxSafePrefetchChunkBytes = best.chunkSizeMb.toLong() * 1024L * 1024L,
        sustainedThroughputMbps = throughput,
        stabilityPenalty = 0.0,
        measuredAtMs = measuredAtMs,
        legacyBestProfile = best
    )
}

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
        transportCharacteristics?.let { chars ->
            add("transportCharacteristics", JsonObject().apply {
                chars.negotiatedProtocol?.let { addProperty("negotiatedProtocol", it) }
                chars.connectionBehavior?.let { addProperty("connectionBehavior", it) }
                chars.connectionReuseDetected?.let { addProperty("connectionReuseDetected", it) }
                chars.requestsPerConnection?.let { addProperty("requestsPerConnection", it) }
                chars.maxConnectionsPerSecond?.let { addProperty("maxConnectionsPerSecond", it) }
                chars.newConnectionRateTolerance?.let { addProperty("newConnectionRateTolerance", it) }
            })
        }
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
        capabilityEnvelope?.let { add("capabilityEnvelope", com.google.gson.JsonParser.parseString(it.toJson())) }
        runtimeTransportHints?.let { hints ->
            add("runtimeTransportHints", JsonObject().apply {
                addProperty("artifactVersion", hints.artifactVersion)
                addProperty("serviceKey", hints.serviceKey)
                addProperty("measuredAtMs", hints.measuredAtMs)
                hints.observedTransportClass?.let { addProperty("observedTransportClass", it) }
                hints.observedHostScope?.let { addProperty("observedHostScope", it) }
                hints.recommendedUrgentChunkBytes?.let { addProperty("recommendedUrgentChunkBytes", it) }
                hints.recommendedUrgentWorkers?.let { addProperty("recommendedUrgentWorkers", it) }
                hints.connectionBudgetHint?.let { addProperty("connectionBudgetHint", it) }
                hints.retryMode?.let { addProperty("retryMode", it) }
            })
        }
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
