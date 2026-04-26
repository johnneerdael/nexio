package com.nexio.tv.core.integration

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.sync.Semaphore

@Singleton
class ProviderRequestGate @Inject constructor(
    private val registry: IntegrationPolicyRegistry
) {
    private val semaphores = ConcurrentHashMap<IntegrationProvider, Semaphore>()
    private val acquireCounter = AtomicInteger(0)

    internal val acquireCount: Int
        get() = acquireCounter.get()

    suspend fun <T> withPermit(provider: IntegrationProvider, block: suspend () -> T): T {
        val policy = registry.policyFor(provider)
        val permits = policy.maxConcurrentNetworkStarts.coerceAtLeast(1)
        val semaphore = semaphores.getOrPut(provider) { Semaphore(permits = permits) }

        acquireCounter.incrementAndGet()
        semaphore.acquire()
        return try {
            block()
        } finally {
            semaphore.release()
        }
    }
}
