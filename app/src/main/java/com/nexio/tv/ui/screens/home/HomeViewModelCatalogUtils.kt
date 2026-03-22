package com.nexio.tv.ui.screens.home

import com.nexio.tv.core.sync.addonCatalogDisableKey
import com.nexio.tv.core.sync.addonCatalogKey
import com.nexio.tv.core.sync.isAddonCatalogDisabled
import com.nexio.tv.core.sync.normalizePublicAddonBaseUrl
import com.nexio.tv.data.local.MDBListCatalogPreferences
import com.nexio.tv.data.local.TraktCatalogPreferences
import com.nexio.tv.data.local.TraktCatalogIds
import com.nexio.tv.data.repository.MDBListDiscoverySnapshot
import com.nexio.tv.data.repository.TraktDiscoverySnapshot
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
    return addonCatalogKey(addonId, type, catalogId)
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

internal fun buildExpectedConfiguredHomeOrderKeys(
    addons: List<Addon>,
    disabledHomeCatalogKeys: Set<String>,
    traktPrefs: TraktCatalogPreferences,
    mdbPrefs: MDBListCatalogPreferences,
    mdbSnapshot: MDBListDiscoverySnapshot
): List<String> {
    val traktKeys = buildExpectedConfiguredTraktOrderKeys(traktPrefs)
    val mdbKeys = buildExpectedConfiguredMDBListOrderKeys(mdbPrefs, mdbSnapshot)
    val addonKeys = buildExpectedConfiguredAddonOrderKeys(addons, disabledHomeCatalogKeys)
    return (traktKeys + mdbKeys + addonKeys).distinct()
}

internal fun buildPublishableConfiguredHomeOrderKeys(
    addons: List<Addon>,
    disabledHomeCatalogKeys: Set<String>,
    traktPrefs: TraktCatalogPreferences,
    traktSnapshot: TraktDiscoverySnapshot,
    hasTraktUpNextItems: Boolean,
    mdbPrefs: MDBListCatalogPreferences,
    mdbSnapshot: MDBListDiscoverySnapshot
): List<String> {
    val traktKeys = buildPublishableConfiguredTraktOrderKeys(
        prefs = traktPrefs,
        snapshot = traktSnapshot,
        hasTraktUpNextItems = hasTraktUpNextItems
    )
    val mdbKeys = buildPublishableConfiguredMDBListOrderKeys(
        prefs = mdbPrefs,
        snapshot = mdbSnapshot
    )
    val addonKeys = buildExpectedConfiguredAddonOrderKeys(addons, disabledHomeCatalogKeys)
    return (traktKeys + mdbKeys + addonKeys).distinct()
}

internal fun buildExpectedConfiguredTraktOrderKeys(
    prefs: TraktCatalogPreferences
): List<String> {
    val orderedEnabledBuiltIns = prefs.catalogOrder.filter { it in prefs.enabledCatalogs }
    val remainingEnabledBuiltIns = prefs.enabledCatalogs.filterNot { it in orderedEnabledBuiltIns }
    val orderedCustomKeys = prefs.selectedPopularListKeys.sorted()
    return (orderedEnabledBuiltIns + remainingEnabledBuiltIns + orderedCustomKeys).distinct()
}

internal fun buildExpectedConfiguredMDBListOrderKeys(
    prefs: MDBListCatalogPreferences,
    snapshot: MDBListDiscoverySnapshot
): List<String> {
    val enabledPersonalKeys = snapshot.personalLists
        .map { it.key }
        .filter { prefs.isPersonalListEnabled(it) }
    val selectedTopKeys = prefs.selectedTopListKeys.toList()
    val availableKeys = (enabledPersonalKeys + selectedTopKeys).distinct()
    if (availableKeys.isEmpty()) return emptyList()
    val orderedKnown = prefs.catalogOrder.filter { key ->
        availableKeys.any { canonicalSyntheticCatalogOrderKey(it) == canonicalSyntheticCatalogOrderKey(key) }
    }
    val remaining = availableKeys.filterNot { available ->
        orderedKnown.any { canonicalSyntheticCatalogOrderKey(it) == canonicalSyntheticCatalogOrderKey(available) }
    }
    return (orderedKnown + remaining).distinct()
}

internal fun buildPublishableConfiguredTraktOrderKeys(
    prefs: TraktCatalogPreferences,
    snapshot: TraktDiscoverySnapshot,
    hasTraktUpNextItems: Boolean
): List<String> {
    val availableKeys = linkedSetOf<String>().apply {
        if (hasTraktUpNextItems) {
            add(TraktCatalogIds.UP_NEXT)
        }
        if (snapshot.trendingMovieItems.isNotEmpty()) {
            add(TraktCatalogIds.TRENDING_MOVIES)
        }
        if (snapshot.trendingShowItems.isNotEmpty()) {
            add(TraktCatalogIds.TRENDING_SHOWS)
        }
        if (snapshot.popularMovieItems.isNotEmpty()) {
            add(TraktCatalogIds.POPULAR_MOVIES)
        }
        if (snapshot.popularShowItems.isNotEmpty()) {
            add(TraktCatalogIds.POPULAR_SHOWS)
        }
        if (snapshot.recommendationMovieItems.isNotEmpty()) {
            add(TraktCatalogIds.RECOMMENDED_MOVIES)
        }
        if (snapshot.recommendationShowItems.isNotEmpty()) {
            add(TraktCatalogIds.RECOMMENDED_SHOWS)
        }
        if (snapshot.calendarItems.isNotEmpty()) {
            add(TraktCatalogIds.CALENDAR)
        }
        addAll(snapshot.customListCatalogs.map { it.key })
    }
    return buildExpectedConfiguredTraktOrderKeys(prefs).filter { it in availableKeys }
}

internal fun buildPublishableConfiguredMDBListOrderKeys(
    prefs: MDBListCatalogPreferences,
    snapshot: MDBListDiscoverySnapshot
): List<String> {
    val availableCanonicalKeys = snapshot.customListCatalogs
        .map { canonicalSyntheticCatalogOrderKey(it.key) }
        .toSet()
    return buildExpectedConfiguredMDBListOrderKeys(prefs, snapshot).filter { key ->
        canonicalSyntheticCatalogOrderKey(key) in availableCanonicalKeys
    }
}

internal fun buildExpectedConfiguredAddonOrderKeys(
    addons: List<Addon>,
    disabledHomeCatalogKeys: Set<String>
): List<String> {
    return buildList {
        addons.forEach { addon ->
            addon.catalogs
                .filterNot { catalog ->
                    catalog.isSearchOnlyCatalog() ||
                        isAddonCatalogDisabled(
                            disabledKeys = disabledHomeCatalogKeys,
                            addonBaseUrl = addon.baseUrl,
                            addonId = addon.id,
                            type = catalog.apiType,
                            catalogId = catalog.id,
                            catalogName = catalog.name
                        )
                }
                .forEach { catalog ->
                    add(addonCatalogKey(addon.id, catalog.apiType, catalog.id))
                }
        }
    }.distinct()
}

internal fun isConfiguredHomeSnapshotComplete(
    snapshotOrderedGroupKeys: List<String>,
    expectedConfiguredOrderKeys: List<String>
): Boolean {
    if (expectedConfiguredOrderKeys.isEmpty()) return snapshotOrderedGroupKeys.isEmpty()
    val available = snapshotOrderedGroupKeys.toSet()
    return expectedConfiguredOrderKeys.all { it in available }
}

internal fun areConfiguredHomeSourceCachesReady(
    addonExpectedOrderKeys: List<String>,
    availableAddonOrderKeys: Set<String>,
    traktExpectedOrderKeys: List<String>,
    traktPrefs: TraktCatalogPreferences,
    traktSnapshot: TraktDiscoverySnapshot,
    mdbExpectedOrderKeys: List<String>,
    mdbPrefs: MDBListCatalogPreferences,
    mdbSnapshot: MDBListDiscoverySnapshot
): Boolean {
    val addonsReady = addonExpectedOrderKeys.all { it in availableAddonOrderKeys }
    val traktReady = if (traktExpectedOrderKeys.isEmpty()) {
        true
    } else {
        !shouldRefreshTraktDiscoveryForState(traktPrefs, traktSnapshot)
    }
    val mdbReady = if (mdbExpectedOrderKeys.isEmpty()) {
        true
    } else {
        !shouldRefreshMDBListDiscoveryForState(mdbPrefs, mdbSnapshot)
    }
    return addonsReady && traktReady && mdbReady
}

internal fun areConfiguredHomePublishSourcesReady(
    addonExpectedOrderKeys: List<String>,
    availableAddonOrderKeys: Set<String>,
    traktExpectedOrderKeys: List<String>,
    traktObserved: Boolean,
    mdbExpectedOrderKeys: List<String>,
    mdbObserved: Boolean
): Boolean {
    val addonsReady = addonExpectedOrderKeys.all { it in availableAddonOrderKeys }
    val traktReady = traktExpectedOrderKeys.isEmpty() || traktObserved
    val mdbReady = mdbExpectedOrderKeys.isEmpty() || mdbObserved
    return addonsReady && traktReady && mdbReady
}

internal fun resolveHomeOrderedKey(rawKey: String, availableKeys: Set<String>): String? {
    if (rawKey in availableKeys) {
        return rawKey
    }

    val canonical = canonicalSyntheticCatalogOrderKey(rawKey)
    if (canonical.isBlank()) {
        return null
    }

    return availableKeys.firstOrNull { canonicalSyntheticCatalogOrderKey(it) == canonical }
}

internal fun canonicalSyntheticCatalogOrderKey(value: String): String {
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
    return isAddonCatalogDisabled(
        disabledKeys = disabledHomeCatalogKeys,
        addonBaseUrl = addonBaseUrl,
        addonId = addonId,
        type = type,
        catalogId = catalogId,
        catalogName = catalogName
    )
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
