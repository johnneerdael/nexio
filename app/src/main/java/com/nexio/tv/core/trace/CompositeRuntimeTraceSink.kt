package com.nexio.tv.core.trace

import kotlinx.coroutines.CancellationException

class CompositeRuntimeTraceSink(
    private val sinks: List<RuntimeTraceSink>
) : RuntimeTraceSink {
    override fun emit(event: TraceEventEnvelope<*>) {
        for (sink in sinks) {
            try {
                sink.emit(event)
            } catch (ce: CancellationException) {
                throw ce
            } catch (_: Exception) {
                // never let one sink's failure stop the others
            }
        }
    }

    override fun eventsWritten(): Long = sinks.maxOfOrNull { it.eventsWritten() } ?: 0L
    override fun eventsDropped(): Long = sinks.sumOf { it.eventsDropped() }
}
