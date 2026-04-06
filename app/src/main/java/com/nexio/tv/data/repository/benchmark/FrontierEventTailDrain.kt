package com.nexio.tv.data.repository.benchmark

internal fun appendTailDrainFrontierEvent(
    events: List<FrontierEvent>,
    runEndMs: Long,
    minGapMs: Long = 100L
): List<FrontierEvent> {
    if (events.isEmpty()) return events

    val sorted = events.sortedBy { it.tMs }
    val lastEvent = sorted.last()
    if (runEndMs <= lastEvent.tMs + minGapMs) return sorted

    return sorted + FrontierEvent(
        tMs = runEndMs,
        contiguousFrontierBytes = lastEvent.contiguousFrontierBytes,
        deltaContiguousBytes = 0L
    )
}
