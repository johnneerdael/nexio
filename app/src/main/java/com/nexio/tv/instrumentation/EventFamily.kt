package com.nexio.tv.instrumentation

/**
 * 12-family taxonomy used by [PlaybackTracer] to classify every emitted event.
 * Order is informational only — analysis tooling keys off the enum name.
 */
enum class EventFamily {
    SESSION,
    POLICY,
    BRANCH,
    PRDS,
    RANGE,
    FRONTIER,
    READ_WAIT,
    CACHE,
    REBUFFER,
    DECODE,
    DEVICE,
    TRACER
}
