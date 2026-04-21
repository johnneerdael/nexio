package com.nexio.tv.core.integration

sealed interface IntegrationCachePolicy {
    data object Disabled : IntegrationCachePolicy

    data class ObserveOnly(val reason: String) : IntegrationCachePolicy

    data class CacheFirst(
        val ttlMs: Long,
        val staleAfterExpiryMs: Long = 0L
    ) : IntegrationCachePolicy

    data object Mutation : IntegrationCachePolicy
}
