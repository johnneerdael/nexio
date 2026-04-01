package com.nexio.tv.data.repository

enum class DebridBenchmarkProvider(
    val storageKey: String
) {
    REAL_DEBRID(storageKey = "real_debrid"),
    PREMIUMIZE(storageKey = "premiumize")
}

data class DebridBenchmarkSummary(
    val startupTimeMs: Long? = null,
    val sustainedThroughputMbps: Double? = null,
    val transferredBytes: Long = 0L,
    val elapsedMs: Long = 0L
)

enum class DebridBenchmarkTerminationReason {
    COMPLETED,
    NO_PLAYABLE_LIBRARY_ITEM,
    CANCELED,
    TIMEOUT,
    FAILED
}

data class DebridBenchmarkResult(
    val provider: DebridBenchmarkProvider,
    val measuredAtMs: Long,
    val summary: DebridBenchmarkSummary,
    val terminationReason: DebridBenchmarkTerminationReason
)
