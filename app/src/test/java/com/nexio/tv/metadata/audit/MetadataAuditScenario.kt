package com.nexio.tv.metadata.audit

import com.nexio.tv.core.metadata.router.MetadataDepth

data class MetadataAuditScenario(
    val name: String,
    val depth: MetadataDepth,
    val language: String = "en",
    val region: String = "US",
    val visibleItemIds: Set<String> = emptySet(),
    val premiumArtworkProvider: String? = null,
    val cacheMode: AuditCacheMode = AuditCacheMode.COLD,
    val routingVersion: Int = 1,
    val assertNoNetwork: Boolean = false,
    val injectSecondaryTitleOverwrite: Boolean = false,
    val continueWatching: Boolean = false,
    val staleRoutingVersion: Boolean = false,
    val productionCallerOwnership: Boolean = false
)

enum class AuditCacheMode {
    COLD,
    WARM_FRESH,
    WARM_STALE,
    FORCE_429,
    OFFLINE_STALE_ALLOWED
}
