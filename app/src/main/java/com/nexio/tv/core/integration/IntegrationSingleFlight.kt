package com.nexio.tv.core.integration

import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

@Singleton
class IntegrationSingleFlight @Inject constructor() {
    private val mutex = Mutex()
    private val inFlight = mutableMapOf<String, CompletableDeferred<IntegrationFetchResult<*>>>()

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
