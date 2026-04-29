package com.nexio.tv.core.integration

import androidx.annotation.VisibleForTesting
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Typed wrapper for single-flight keys that incorporates the codec mimeType so that
 * two specs sharing a [cacheKey] but producing different result types (different `T`)
 * never collide in the in-flight map. Closes F-D-05 (UNCHECKED_CAST -> ClassCastException).
 */
data class TypedSingleFlightKey(
    val cacheKey: String,
    val mimeType: String
)

@Singleton
class IntegrationSingleFlight @Inject constructor() {
    private val mutex = Mutex()
    private val inFlight = mutableMapOf<String, CompletableDeferred<IntegrationFetchResult<*>>>()
    private val inFlightCalls = mutableMapOf<String, CompletableDeferred<IntegrationCallResult<*>>>()

    /**
     * Single-flight entry point for non-cache call paths (F-A-02). Mirrors [run] but operates on
     * [IntegrationCallResult] so callers like [DefaultIntegrationRuntime.callInternal] can opt in
     * via [IntegrationCallSpec.coalesceConcurrent] without wrapping their results in fetch shapes.
     */
    @Suppress("UNCHECKED_CAST")
    suspend fun <T> runCall(
        key: TypedSingleFlightKey,
        block: suspend () -> IntegrationCallResult<T>
    ): IntegrationCallResult<T> {
        val composedKey = "${key.cacheKey}|${key.mimeType}"
        val existing = mutex.withLock {
            inFlightCalls[composedKey] as? CompletableDeferred<IntegrationCallResult<T>>
        }
        if (existing != null) return existing.await()

        val deferred = CompletableDeferred<IntegrationCallResult<T>>()
        val shouldRun = mutex.withLock {
            val found = inFlightCalls[composedKey] as? CompletableDeferred<IntegrationCallResult<T>>
            if (found != null) {
                false
            } else {
                @Suppress("UNCHECKED_CAST")
                val typed = deferred as CompletableDeferred<IntegrationCallResult<*>>
                inFlightCalls[composedKey] = typed
                true
            }
        }
        if (!shouldRun) {
            return mutex.withLock {
                inFlightCalls[composedKey] as CompletableDeferred<IntegrationCallResult<T>>
            }.await()
        }

        return try {
            block().also { deferred.complete(it) }
        } catch (error: Throwable) {
            deferred.completeExceptionally(error)
            throw error
        } finally {
            mutex.withLock { inFlightCalls.remove(composedKey) }
        }
    }

    /**
     * Typed entry point: composes a string key from [TypedSingleFlightKey] so that
     * specs with the same cacheKey but different codecs (different mimeTypes / `T`)
     * get separate in-flight slots and cannot trip the `UNCHECKED_CAST` below.
     */
    suspend fun <T> run(
        key: TypedSingleFlightKey,
        block: suspend () -> IntegrationFetchResult<T>
    ): IntegrationFetchResult<T> {
        val composedKey = "${key.cacheKey}|${key.mimeType}"
        return run(composedKey, block)
    }

    @VisibleForTesting
    @Suppress("UNCHECKED_CAST")
    suspend fun <T> run(
        cacheKey: String,
        block: suspend () -> IntegrationFetchResult<T>
    ): IntegrationFetchResult<T> {
        val existing = mutex.withLock {
            inFlight[cacheKey] as? CompletableDeferred<IntegrationFetchResult<T>>
        }
        if (existing != null) return existing.await()

        val deferred = CompletableDeferred<IntegrationFetchResult<T>>()
        val shouldRun = mutex.withLock {
            val found = inFlight[cacheKey] as? CompletableDeferred<IntegrationFetchResult<T>>
            if (found != null) {
                false
            } else {
                @Suppress("UNCHECKED_CAST")
                val typed = deferred as CompletableDeferred<IntegrationFetchResult<*>>
                inFlight[cacheKey] = typed
                true
            }
        }
        if (!shouldRun) {
            return mutex.withLock {
                inFlight[cacheKey] as CompletableDeferred<IntegrationFetchResult<T>>
            }.await()
        }

        return try {
            block().also { deferred.complete(it) }
        } catch (error: Throwable) {
            deferred.completeExceptionally(error)
            throw error
        } finally {
            mutex.withLock { inFlight.remove(cacheKey) }
        }
    }
}
