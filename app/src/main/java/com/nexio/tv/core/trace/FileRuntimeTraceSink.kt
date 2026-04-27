package com.nexio.tv.core.trace

class FileRuntimeTraceSink(
    val sessionId: String,
    private val writer: JsonlTraceWriter,
    private val redactor: TraceRedactor
) : RuntimeTraceSink {
    @Volatile private var written: Long = 0L

    private val ringBuffer = ArrayDeque<TraceEventEnvelope<*>>()
    private val ringLock = Any()
    private val ringCapacity = 100

    override fun emit(event: TraceEventEnvelope<*>) {
        require(event.traceSessionId == sessionId) {
            "FileRuntimeTraceSink session mismatch: expected=$sessionId got=${event.traceSessionId}"
        }
        val priority = priorityFor(event.eventType)
        writer.append(event, priority)
        written++
        synchronized(ringLock) {
            if (ringBuffer.size >= ringCapacity) ringBuffer.removeFirst()
            ringBuffer.addLast(event)
        }
    }

    override fun eventsWritten(): Long = written
    override fun eventsDropped(): Long = writer.droppedCount()

    fun recent(): List<TraceEventEnvelope<*>> = synchronized(ringLock) { ringBuffer.toList() }

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
