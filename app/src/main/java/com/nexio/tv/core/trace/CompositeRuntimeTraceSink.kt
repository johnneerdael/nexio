package com.nexio.tv.core.trace

import kotlinx.coroutines.CancellationException

class CompositeRuntimeTraceSink(
    private val sinks: List<RuntimeTraceSink>
) : RuntimeTraceSink {
    override fun activeTraceSessionId(): String? =
        sinks.firstNotNullOfOrNull { it.activeTraceSessionId() }

    override fun emit(event: TraceEventEnvelope<*>) {
        for (sink in sinks) {
            if (event.traceSessionId == LOGCAT_ONLY_TRACE_SESSION_ID && sink.activeTraceSessionId() != null) {
                continue
            }
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

    private companion object {
        const val LOGCAT_ONLY_TRACE_SESSION_ID = "logcat-only"
    }
}
