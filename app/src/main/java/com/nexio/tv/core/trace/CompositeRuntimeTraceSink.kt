package com.nexio.tv.core.trace

class CompositeRuntimeTraceSink(
    private val sinks: List<RuntimeTraceSink>
) : RuntimeTraceSink {
    override fun emit(event: TraceEventEnvelope<*>) {
        for (sink in sinks) {
            try {
                sink.emit(event)
            } catch (_: Throwable) {
                // never let one sink's failure stop the others
            }
        }
    }

    override fun eventsWritten(): Long = sinks.maxOfOrNull { it.eventsWritten() } ?: 0L
    override fun eventsDropped(): Long = sinks.sumOf { it.eventsDropped() }
}
