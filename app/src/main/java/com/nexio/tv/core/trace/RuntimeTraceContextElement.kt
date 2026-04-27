package com.nexio.tv.core.trace

import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext

class RuntimeTraceContextElement(
    val context: RuntimeTraceContext
) : AbstractCoroutineContextElement(Key) {
    companion object Key : CoroutineContext.Key<RuntimeTraceContextElement> {
        fun current(coroutineContext: CoroutineContext): RuntimeTraceContext? =
            coroutineContext[Key]?.context
    }
}
