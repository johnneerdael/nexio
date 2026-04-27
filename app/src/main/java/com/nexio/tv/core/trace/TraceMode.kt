package com.nexio.tv.core.trace

import kotlinx.coroutines.flow.Flow

enum class TraceMode(
    val includesRuntime: Boolean,
    val includesHttpSummary: Boolean,
    val includesHttpBodies: Boolean
) {
    OFF(false, false, false),
    SAFE_METADATA_RUNTIME(true, false, false),
    INCLUDE_HTTP_SUMMARY(true, true, false),
    INCLUDE_HTTP_BODIES_INTERNAL_ONLY(true, true, true);

    companion object {
        fun parse(raw: String?): TraceMode =
            values().firstOrNull { it.name == raw } ?: OFF
    }
}

interface TraceModeProvider {
    val current: TraceMode
    fun observe(): Flow<TraceMode>
}
