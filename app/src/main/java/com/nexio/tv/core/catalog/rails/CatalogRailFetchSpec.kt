package com.nexio.tv.core.catalog.rails

import com.nexio.tv.core.integration.IntegrationCachePolicy
import com.nexio.tv.core.integration.IntegrationScope

/**
 * Uniform fetch specification for a single catalog rail.
 *
 * Every [CatalogRailSource.fetchRail] returns one of these. The non-nullable
 * [cachePolicy] and [scope] are deliberate: no rail can be loaded without an explicit
 * caching decision and an explicit profile-isolation scope. This is the type-level
 * complement to the runtime audit, which scans `IntegrationCallSpec` construction sites
 * and currently cannot enforce a non-null cachePolicy globally.
 *
 * @property providerId Which provider built this spec (echoed in trace events).
 * @property railId Stable rail identifier matching [CatalogRailDescriptor.railId].
 * @property operationKey Operation key used by the integration runtime; should be
 *   stable per (provider, rail) pair across app sessions so cache lookups hit.
 * @property scope Integration runtime scope (Account, Profile, GlobalContent, etc.).
 * @property cachePolicy Integration runtime cache policy. Use [IntegrationCachePolicy.Disabled]
 *   for explicitly uncached rails; never construct ad-hoc `null`.
 */
data class CatalogRailFetchSpec(
    val providerId: CatalogRailProvider,
    val railId: String,
    val operationKey: String,
    val scope: IntegrationScope,
    val cachePolicy: IntegrationCachePolicy
) {
    init {
        require(railId.isNotBlank()) { "railId must not be blank" }
        require(operationKey.isNotBlank()) { "operationKey must not be blank" }
    }
}
