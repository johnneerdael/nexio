package com.nexio.tv.core.trace

class FileRuntimeTraceSink(
    val sessionId: String,
    private val writer: JsonlTraceWriter,
    private val redactor: TraceRedactor
) : RuntimeTraceSink {
    @Volatile private var written: Long = 0L

    override fun emit(event: TraceEventEnvelope<*>) {
        require(event.traceSessionId == sessionId) {
            "FileRuntimeTraceSink session mismatch: expected=$sessionId got=${event.traceSessionId}"
        }
        val priority = priorityFor(event.eventType)
        writer.append(event, priority)
        written++
    }

    override fun eventsWritten(): Long = written
    override fun eventsDropped(): Long = writer.droppedCount()

    fun close() = writer.close()

    private fun priorityFor(eventType: String): TraceEventPriority = when {
        eventType.startsWith("policy.") -> TraceEventPriority.BLOCKER
        eventType.startsWith("runtime.") -> TraceEventPriority.HIGH
        eventType.startsWith("metadata.") -> TraceEventPriority.HIGH
        eventType.startsWith("http.") -> TraceEventPriority.MEDIUM
        eventType == "trace.body_sample" -> TraceEventPriority.LOW
        else -> TraceEventPriority.MEDIUM
    }
}
