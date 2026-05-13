package com.nexio.tv.domain.model

import kotlinx.serialization.Serializable

const val HOME_CATALOG_RAILS_VERSION = 1

const val HOME_CATALOG_FAMILY_ADDON = "addon"
const val HOME_CATALOG_FAMILY_TRAKT = "trakt"
const val HOME_CATALOG_FAMILY_SIMKL = "simkl"
const val HOME_CATALOG_FAMILY_MDBLIST = "mdblist"
const val HOME_CATALOG_FAMILY_TMDB = "tmdb"
const val HOME_CATALOG_FAMILY_KITSU = "kitsu"

const val HOME_CATALOG_SOURCE_ADDON_CATALOG = "addon_catalog"
const val HOME_CATALOG_SOURCE_PROVIDER_CATALOG = "provider_catalog"
const val HOME_CATALOG_SOURCE_PROVIDER_LIST = "provider_list"

@Serializable
data class HomeCatalogRail(
    val key: String = "",
    val family: String = "",
    val source: String = "",
    val title: String = "",
    val enabled: Boolean = true,
    val addedAtMs: Long? = null
)

fun sanitizeHomeCatalogRails(rails: List<HomeCatalogRail>): List<HomeCatalogRail> {
    val seen = linkedSetOf<String>()
    val sanitized = ArrayList<HomeCatalogRail>(rails.size)
    for (rail in rails) {
        val key = rail.key.trim()
        if (key.isBlank() || !seen.add(key)) continue
        val family = rail.family.trim().ifBlank { homeCatalogRailFamilyForKey(key) }
        val source = rail.source.trim().ifBlank { homeCatalogRailSourceForFamily(family) }
        sanitized += rail.copy(
            key = key,
            family = family,
            source = source,
            title = rail.title.trim(),
            enabled = rail.enabled
        )
    }
    return sanitized
}

fun homeCatalogRailFamilyForKey(key: String): String = when {
    key.startsWith("trakt_") -> HOME_CATALOG_FAMILY_TRAKT
    key.startsWith("simkl_") -> HOME_CATALOG_FAMILY_SIMKL
    key.startsWith("tmdb_") -> HOME_CATALOG_FAMILY_TMDB
    key.startsWith("kitsu_") -> HOME_CATALOG_FAMILY_KITSU
    key.startsWith("mdblist_") || key.startsWith("top:") || key.startsWith("personal:") -> HOME_CATALOG_FAMILY_MDBLIST
    else -> HOME_CATALOG_FAMILY_ADDON
}

fun homeCatalogRailSourceForFamily(family: String): String = when (family) {
    HOME_CATALOG_FAMILY_ADDON -> HOME_CATALOG_SOURCE_ADDON_CATALOG
    HOME_CATALOG_FAMILY_MDBLIST -> HOME_CATALOG_SOURCE_PROVIDER_LIST
    else -> HOME_CATALOG_SOURCE_PROVIDER_CATALOG
}
