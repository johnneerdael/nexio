package com.nexio.tv.core.trace

interface RuntimeTraceSink {
    fun emit(event: TraceEventEnvelope<*>)
    fun eventsWritten(): Long
    fun eventsDropped(): Long
}

object NoopRuntimeTraceSink : RuntimeTraceSink {
    override fun emit(event: TraceEventEnvelope<*>) = Unit
    override fun eventsWritten(): Long = 0L
    override fun eventsDropped(): Long = 0L
}
