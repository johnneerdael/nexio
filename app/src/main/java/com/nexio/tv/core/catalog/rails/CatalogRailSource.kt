package com.nexio.tv.core.catalog.rails

import com.nexio.tv.core.integration.IntegrationFetchResult
import com.nexio.tv.domain.model.RailItemPreview

/**
 * Contract every catalog-rail provider implements.
 *
 * Bound by Hilt multibinding (added in a follow-on plan) so the Home composer can iterate
 * the full set of sources without knowing each provider concretely.
 *
 * Implementers MUST:
 *  - Build their internal fetches via [CatalogRailFetchSpec] (mandates cache policy and scope).
 *  - Route every fetch through `IntegrationRuntime` so trace, cache, and HTTP audits cover them.
 *  - Map results to [RailItemPreview] so the shared first-paint boundary fires uniformly.
 *  - Apply per-profile rail visibility inside [availableRails] — never return rails this
 *    profile is not configured to see.
 *
 * Files awaiting migration carry [CatalogRailNotYetUniform] and are tracked by the
 * `generateCatalogRailUniformityAudit` Gradle task.
 */
interface CatalogRailSource {
    /** Stable provider identity; echoed into trace events and audit reports. */
    val providerId: CatalogRailProvider

    /**
     * Rail descriptors visible to [profileId]. Ordering is a hint; the Home composer has the
     * final say. Implementations MUST gate by per-profile rail-visibility config.
     */
    suspend fun availableRails(profileId: Int): List<CatalogRailDescriptor>

    /**
     * Fetch the items for one rail. Returns the integration runtime's standard
     * [IntegrationFetchResult] so callers can branch on Updated / Fresh / Stale / Missing.
     */
    suspend fun fetchRail(
        profileId: Int,
        rail: CatalogRailDescriptor
    ): IntegrationFetchResult<List<RailItemPreview>>
}
