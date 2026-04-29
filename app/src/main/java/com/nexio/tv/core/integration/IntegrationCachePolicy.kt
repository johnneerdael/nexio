package com.nexio.tv.core.integration

sealed interface IntegrationCachePolicy {
    data object Disabled : IntegrationCachePolicy

    /**
     * Network pass-through policy: requests bypass the cache layer entirely. Behaviourally
     * identical to [Disabled] but conveys intent — "we observe the response without storing it"
     * vs "we don't use the cache for this endpoint at all". Used for endpoints that emit
     * observe-only trace events where caching would suppress subsequent emissions.
     *
     * The [reason] field is surfaced in trace events to aid debugging.
     */
    data class ObserveOnly(val reason: String) : IntegrationCachePolicy

    data class CacheFirst(
        val ttlMs: Long,
        val staleAfterExpiryMs: Long = 0L
    ) : IntegrationCachePolicy

    data object Mutation : IntegrationCachePolicy
}
