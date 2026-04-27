package com.nexio.tv.core.trace

import okhttp3.Interceptor
import okhttp3.Response

/**
 * Bridges [RuntimeTraceContextElement.activeOnThread] into the OkHttp request tag chain.
 *
 * Required because [RuntimeTraceInterceptor] runs as a network interceptor (after auth/header
 * application interceptors) and reads the [RuntimeTraceContext] from `Request.tag(...)`. The
 * runtime publishes its context only as a [kotlin.coroutines.CoroutineContext.Element]; this
 * interceptor pulls it from the thread-local that [RuntimeTraceContextElement] maintains and
 * attaches it to the request before the chain proceeds.
 *
 * Must be registered as an *application* interceptor (so it runs on the calling coroutine's
 * thread, where the thread-local is set) and BEFORE any other interceptor that wants to read
 * the tag.
 */
class RuntimeTraceContextRequestTaggingInterceptor : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        if (request.tag(RuntimeTraceContext::class.java) != null) {
            return chain.proceed(request)
        }
        val context = RuntimeTraceContextElement.activeOnThread()
            ?: return chain.proceed(request)
        val tagged = request.newBuilder()
            .tag(RuntimeTraceContext::class.java, context)
            .build()
        return chain.proceed(tagged)
    }
}
