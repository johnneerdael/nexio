package com.nexio.tv.core.integration

import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

@Singleton
class ProviderRequestGate @Inject constructor(
    private val registry: IntegrationPolicyRegistry
) {
    private val mutexes = ConcurrentHashMap<IntegrationProvider, Mutex>()

    internal var acquireCount: Int = 0
        private set

    suspend fun <T> withPermit(provider: IntegrationProvider, block: suspend () -> T): T {
        acquireCount += 1
        val policy = registry.policyFor(provider)
        return if (policy.maxConcurrentNetworkStarts == 1) {
            mutexes.getOrPut(provider) { Mutex() }.withLock { block() }
        } else {
            block()
        }
    }
}
