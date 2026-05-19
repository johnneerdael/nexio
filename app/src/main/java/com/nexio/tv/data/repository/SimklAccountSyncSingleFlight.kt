package com.nexio.tv.data.repository

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class SimklAccountSyncSingleFlight @Inject constructor() {
    private val mutex = Mutex()
    private val inFlight = mutableMapOf<String, Deferred<Any?>>()

    @Suppress("UNCHECKED_CAST")
    suspend fun <T : Any> run(key: String, block: suspend CoroutineScope.() -> T): T {
        val existing = mutex.withLock { inFlight[key] as? Deferred<T> }
        if (existing != null) return existing.await()

        return coroutineScope {
            val deferred = async(start = CoroutineStart.LAZY) { block() }
            mutex.withLock { inFlight[key] = deferred as Deferred<Any?> }
            try {
                deferred.start()
                deferred.await()
            } finally {
                mutex.withLock { inFlight.remove(key) }
            }
        }
    }
}
