package com.nexio.tv.core.integration

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class BootNetworkGovernor @Inject constructor() {
    private val bootActive = AtomicBoolean(false)
    private val totalStarts = AtomicInteger(0)
    private val providerStarts = ConcurrentHashMap<IntegrationProvider, AtomicInteger>()

    fun beginBootWindow() {
        bootActive.set(true)
        totalStarts.set(0)
        providerStarts.clear()
    }

    fun endBootWindow() {
        bootActive.set(false)
    }

    fun allow(provider: IntegrationProvider, workClass: IntegrationWorkClass): Boolean {
        if (!bootActive.get()) return true
        if (workClass == IntegrationWorkClass.USER_VISIBLE) return true

        val providerLimit = BOOT_PROVIDER_LIMITS[provider] ?: DEFAULT_PROVIDER_LIMIT
        val providerCount = providerStarts
            .getOrPut(provider) { AtomicInteger(0) }
            .incrementAndGet()
        val total = totalStarts.incrementAndGet()

        return total <= MAX_BOOT_NETWORK_STARTS && providerCount <= providerLimit
    }

    fun allow(spec: IntegrationSpec<*>): Boolean =
        allow(spec.provider, spec.workClass)

    fun allow(spec: IntegrationCallSpec<*>): Boolean =
        allow(spec.provider, spec.workClass)

    private companion object {
        const val MAX_BOOT_NETWORK_STARTS = 10
        const val DEFAULT_PROVIDER_LIMIT = 1

        val BOOT_PROVIDER_LIMITS = mapOf(
            IntegrationProvider.MDBLIST to 0,
            IntegrationProvider.CUSTOM_IMDB to 0,
            IntegrationProvider.TRAKT to 3,
            IntegrationProvider.SIMKL to 2,
            IntegrationProvider.TMDB to 2,
            IntegrationProvider.TVDB to 1,
            IntegrationProvider.KITSU to 1
        )
    }
}
