package com.nexio.tv.data.catalog.rails

/**
 * Encodes and decodes addon-rail identifiers used by [AddonCatalogRailSource].
 *
 * Format: `addon::<addonId>::<type>::<catalogId>`.
 *
 * The double-colon delimiter is chosen because addon IDs commonly contain single colons
 * (reverse-DNS style like `community.foo:catalog:bar`), but double colons are exceedingly
 * rare. Inputs containing `::` are rejected at encode time.
 */
class AddonCatalogRailIdCodec {

    data class Parts(val addonId: String, val type: String, val catalogId: String)

    fun encode(addonId: String, type: String, catalogId: String): String {
        require(addonId.isNotBlank()) { "addonId must not be blank" }
        require(type.isNotBlank()) { "type must not be blank" }
        require(catalogId.isNotBlank()) { "catalogId must not be blank" }
        require(!addonId.contains(DELIM)) { "addonId must not contain '$DELIM' (got: $addonId)" }
        require(!type.contains(DELIM)) { "type must not contain '$DELIM' (got: $type)" }
        require(!catalogId.contains(DELIM)) { "catalogId must not contain '$DELIM' (got: $catalogId)" }
        return "$PREFIX$DELIM$addonId$DELIM$type$DELIM$catalogId"
    }

    fun decode(railId: String): Parts? {
        if (railId.isBlank()) return null
        val parts = railId.split(DELIM)
        if (parts.size != 4) return null
        if (parts[0] != PREFIX) return null
        if (parts.any { it.isBlank() }) return null
        return Parts(addonId = parts[1], type = parts[2], catalogId = parts[3])
    }

    companion object {
        const val PREFIX: String = "addon"
        const val DELIM: String = "::"
    }
}
