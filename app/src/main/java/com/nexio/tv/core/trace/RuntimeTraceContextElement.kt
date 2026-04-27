package com.nexio.tv.core.trace

import kotlinx.coroutines.ThreadContextElement
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext

/**
 * Coroutine context element carrying a [RuntimeTraceContext] for the duration of a runtime
 * operation. Doubles as a [ThreadContextElement] so the context is available via a thread-local
 * to non-coroutine code paths (notably the OkHttp interceptor stack), bridged into
 * `Request.tag(RuntimeTraceContext::class.java)` by [RuntimeTraceContextRequestTaggingInterceptor].
 */
class RuntimeTraceContextElement(
    val context: RuntimeTraceContext
) : AbstractCoroutineContextElement(Key), ThreadContextElement<RuntimeTraceContext?> {

    override val key: CoroutineContext.Key<*> get() = Key

    override fun updateThreadContext(context: CoroutineContext): RuntimeTraceContext? {
        val previous = threadLocal.get()
        threadLocal.set(this.context)
        return previous
    }

    override fun restoreThreadContext(context: CoroutineContext, oldState: RuntimeTraceContext?) {
        threadLocal.set(oldState)
    }

    companion object Key : CoroutineContext.Key<RuntimeTraceContextElement> {
        private val threadLocal = ThreadLocal<RuntimeTraceContext?>()

        fun current(coroutineContext: CoroutineContext): RuntimeTraceContext? =
            coroutineContext[Key]?.context

        /**
         * Returns the [RuntimeTraceContext] active on the current thread, or null if no
         * coroutine carrying [RuntimeTraceContextElement] is currently resumed here.
         */
        @JvmStatic
        fun activeOnThread(): RuntimeTraceContext? = threadLocal.get()
    }
}
