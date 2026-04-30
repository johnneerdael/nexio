package com.nexio.tv.core.catalog.rails

/**
 * Describes a single catalog rail without fetching its items.
 *
 * Returned in bulk from [CatalogRailSource.availableRails] so the Home screen can render
 * rail headers and decide ordering before paying any fetch cost.
 *
 * @property railId Stable identifier for this rail across app sessions. Format is
 *   provider-defined (e.g. `trakt:trending:movies`, `addon:com.example/movie/top`).
 * @property providerId Which [CatalogRailProvider] owns this rail.
 * @property displayTitle Human-readable rail title shown to the user (already localised
 *   if the provider supports localisation).
 * @property sortOrder Zero-based position hint within the provider's set; final ordering
 *   is decided by the Home composer using profile-level rail-config.
 */
data class CatalogRailDescriptor(
    val railId: String,
    val providerId: CatalogRailProvider,
    val displayTitle: String,
    val sortOrder: Int
) {
    init {
        require(railId.isNotBlank()) { "railId must not be blank" }
        require(displayTitle.isNotBlank()) { "displayTitle must not be blank" }
        require(sortOrder >= 0) { "sortOrder must be >= 0 (got $sortOrder)" }
    }
}
