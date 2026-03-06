package com.nexio.tv.ui.screens.home

import com.nexio.tv.core.sync.addonCatalogDisableKey
import com.nexio.tv.core.sync.normalizePublicAddonBaseUrl
import com.nexio.tv.domain.model.Addon
import com.nexio.tv.domain.model.CatalogDescriptor
import com.nexio.tv.domain.model.CatalogRow
import com.nexio.tv.domain.model.MetaPreview
import kotlinx.coroutines.Job

internal const val TRAKT_HOME_ADDON_ID = "trakt"
internal const val MDBLIST_HOME_ADDON_ID = "mdblist"
private const val TRAKT_HOME_KEY_PREFIX = "trakt_"
private const val MDBLIST_HOME_KEY_PREFIX = "mdblist_"

internal fun HomeViewModel.catalogKey(addonId: String, type: String, catalogId: String): String {
    return "${addonId}_${type}_${catalogId}"
}

internal fun homeCatalogGlobalKey(row: CatalogRow): String {
    return when (row.addonId) {
        TRAKT_HOME_ADDON_ID -> if (row.catalogId.startsWith(TRAKT_HOME_KEY_PREFIX)) row.catalogId else "$TRAKT_HOME_KEY_PREFIX${row.catalogId}"
        MDBLIST_HOME_ADDON_ID -> if (row.catalogId.startsWith(MDBLIST_HOME_KEY_PREFIX)) row.catalogId else "$MDBLIST_HOME_KEY_PREFIX${row.catalogId}"
        else -> "${row.addonId}_${row.apiType}_${row.catalogId}"
    }
}

internal fun HomeViewModel.buildHomeCatalogLoadSignature(addons: List<Addon>): String {
    val addonCatalogSignature = addons
        .flatMap { addon ->
            addon.catalogs.map { catalog ->
                "${addon.id}|${normalizePublicAddonBaseUrl(addon.baseUrl)}|${catalog.apiType}|${catalog.id}|${catalog.name}"
            }
        }
        .sorted()
        .joinToString(separator = ",")
    val disabledSignature = disabledHomeCatalogKeys
        .asSequence()
        .sorted()
        .joinToString(separator = ",")
    return "$addonCatalogSignature::$disabledSignature"
}

internal fun HomeViewModel.registerCatalogLoadJob(job: Job) {
    synchronized(activeCatalogLoadJobs) {
        activeCatalogLoadJobs.add(job)
    }
    job.invokeOnCompletion {
        synchronized(activeCatalogLoadJobs) {
            activeCatalogLoadJobs.remove(job)
        }
    }
}

internal fun HomeViewModel.cancelInFlightCatalogLoads() {
    val jobsToCancel = synchronized(activeCatalogLoadJobs) {
        activeCatalogLoadJobs.toList().also { activeCatalogLoadJobs.clear() }
    }
    jobsToCancel.forEach { it.cancel() }
}

internal fun HomeViewModel.rebuildCatalogOrder(addons: List<Addon>) {
    val defaultOrder = buildDefaultCatalogOrder(addons)
    val availableSet = defaultOrder.toSet()

    val savedValid = homeCatalogOrderKeys
        .asSequence()
        .mapNotNull { rawKey -> resolveHomeOrderedKey(rawKey, availableSet) }
        .distinct()
        .toList()

    val savedSet = savedValid.toSet()
    val mergedOrder = savedValid + defaultOrder.filterNot { it in savedSet }

    catalogOrder.clear()
    catalogOrder.addAll(mergedOrder)
}

private fun resolveHomeOrderedKey(rawKey: String, availableKeys: Set<String>): String? {
    if (rawKey in availableKeys) {
        return rawKey
    }

    val canonical = canonicalSyntheticCatalogOrderKey(rawKey)
    if (canonical.isBlank()) {
        return null
    }

    return availableKeys.firstOrNull { canonicalSyntheticCatalogOrderKey(it) == canonical }
}

private fun canonicalSyntheticCatalogOrderKey(value: String): String {
    val trimmed = value.trim()
    if (trimmed.isBlank()) return ""
    return when {
        trimmed.startsWith("personal:", ignoreCase = true) ||
            trimmed.startsWith("top:", ignoreCase = true) -> {
            val prefix = trimmed.substringBefore(':').lowercase()
            val payload = trimmed.substringAfter(':', "")
            val listId = payload.substringAfterLast('/').trim().lowercase()
            if (listId.isBlank()) trimmed.lowercase() else "$prefix:$listId"
        }

        else -> trimmed
    }
}

private fun HomeViewModel.buildDefaultCatalogOrder(addons: List<Addon>): List<String> {
    val orderedKeys = mutableListOf<String>()
    addons.forEach { addon ->
        addon.catalogs
            .filterNot {
                it.isSearchOnlyCatalog() || isCatalogDisabled(
                    addonBaseUrl = addon.baseUrl,
                    addonId = addon.id,
                    type = it.apiType,
                    catalogId = it.id,
                    catalogName = it.name
                )
            }
            .forEach { catalog ->
                val key = catalogKey(
                    addonId = addon.id,
                    type = catalog.apiType,
                    catalogId = catalog.id
                )
                if (key !in orderedKeys) {
                    orderedKeys.add(key)
                }
            }
    }
    return orderedKeys
}

internal fun HomeViewModel.isCatalogDisabled(
    addonBaseUrl: String,
    addonId: String,
    type: String,
    catalogId: String,
    catalogName: String
): Boolean {
    if (disableCatalogKey(addonBaseUrl, type, catalogId, catalogName) in disabledHomeCatalogKeys) {
        return true
    }
    // Backward compatibility with previously stored keys.
    return catalogKey(addonId, type, catalogId) in disabledHomeCatalogKeys
}

internal fun HomeViewModel.disableCatalogKey(
    addonBaseUrl: String,
    type: String,
    catalogId: String,
    catalogName: String
): String {
    return addonCatalogDisableKey(addonBaseUrl, type, catalogId, catalogName)
}

internal fun CatalogDescriptor.isSearchOnlyCatalog(): Boolean {
    return extra.any { extra -> extra.name.equals("search", ignoreCase = true) && extra.isRequired }
}

internal fun MetaPreview.hasHeroArtwork(): Boolean {
    return !background.isNullOrBlank()
}

internal fun HomeViewModel.extractYear(releaseInfo: String?): String? {
    if (releaseInfo.isNullOrBlank()) return null
    return Regex("\\b(19|20)\\d{2}\\b").find(releaseInfo)?.value
}
