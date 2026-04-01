package com.nexio.tv.data.repository.benchmark

import com.nexio.tv.data.repository.DebridLibraryService

enum class DebridBenchmarkProvider(
    val storageKey: String,
    val listKey: String
) {
    REAL_DEBRID(
        storageKey = "real_debrid",
        listKey = DebridLibraryService.REAL_DEBRID_LIST_KEY
    ),
    PREMIUMIZE(
        storageKey = "premiumize",
        listKey = DebridLibraryService.PREMIUMIZE_LIST_KEY
    );

    companion object {
        private val byStorageKey = entries.associateBy { it.storageKey }

        fun fromStorageKey(storageKey: String): DebridBenchmarkProvider? {
            return byStorageKey[storageKey]
        }
    }
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
    FAILED;

    val wireKey: String
        get() = when (this) {
            COMPLETED -> "completed"
            NO_PLAYABLE_LIBRARY_ITEM -> "no_playable_library_item"
            CANCELED -> "canceled"
            TIMEOUT -> "timeout"
            FAILED -> "failed"
        }

    companion object {
        private val byWireKey = entries.associateBy { it.wireKey }

        fun fromWireKey(wireKey: String): DebridBenchmarkTerminationReason? {
            return byWireKey[wireKey]
        }
    }
}

data class DebridBenchmarkResult(
    val provider: DebridBenchmarkProvider,
    val measuredAtMs: Long,
    val summary: DebridBenchmarkSummary,
    val terminationReason: DebridBenchmarkTerminationReason
)

data class DebridBenchmarkCandidate(
    val provider: DebridBenchmarkProvider,
    val directUrl: String,
    val headers: Map<String, String>,
    val filename: String?,
    val sourceSizeBytes: Long?
)
