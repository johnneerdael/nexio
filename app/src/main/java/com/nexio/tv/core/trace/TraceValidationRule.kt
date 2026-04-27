package com.nexio.tv.core.trace

interface TraceValidationRule {
    val id: String
    fun apply(events: List<TraceEventEnvelope<*>>): List<TraceValidationFailure>
}
