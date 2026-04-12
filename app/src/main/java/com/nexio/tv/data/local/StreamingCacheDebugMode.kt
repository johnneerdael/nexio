package com.nexio.tv.data.local

enum class StreamingCacheDebugMode(
    val storageValue: String,
    val label: String
) {
    PHASE4_COVERAGE_WITH_FILL(
        storageValue = "phase4_coverage_with_fill",
        label = "Phase 4: coverage + fill"
    ),
    PHASE3_CACHE_WITH_FILL(
        storageValue = "phase3_cache_with_fill",
        label = "Phase 3: cache + fill"
    ),
    COVERAGE_ONLY(
        storageValue = "coverage_only",
        label = "Coverage only: no fill"
    );

    fun next(): StreamingCacheDebugMode {
        val modes = entries
        return modes[(ordinal + 1) % modes.size]
    }

    companion object {
        fun fromStorageValue(value: String?): StreamingCacheDebugMode {
            return entries.firstOrNull { mode -> mode.storageValue == value }
                ?: PHASE4_COVERAGE_WITH_FILL
        }
    }
}
