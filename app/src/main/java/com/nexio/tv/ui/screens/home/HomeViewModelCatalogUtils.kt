package com.nexio.tv.ui.screens.home

import com.nexio.tv.core.sync.addonCatalogDisableKey
import com.nexio.tv.core.sync.addonCatalogKey
import com.nexio.tv.core.sync.isAddonCatalogDisabled
import com.nexio.tv.core.sync.normalizePublicAddonBaseUrl
import com.nexio.tv.data.local.MDBListCatalogPreferences
import com.nexio.tv.data.local.KitsuCatalogIds
import com.nexio.tv.data.local.KitsuCatalogPreferences
import com.nexio.tv.data.local.SimklCatalogIds
import com.nexio.tv.data.local.SimklCatalogPreferences
import com.nexio.tv.data.local.TmdbCatalogIds
import com.nexio.tv.data.local.TmdbCatalogPreferences
import com.nexio.tv.data.local.TraktCatalogPreferences
import com.nexio.tv.data.local.TraktCatalogIds
import com.nexio.tv.data.repository.MDBListDiscoverySnapshot
import com.nexio.tv.data.repository.KitsuDiscoverySnapshot
import com.nexio.tv.data.repository.SimklDiscoverySnapshot
import com.nexio.tv.data.repository.TmdbDiscoverySnapshot
import com.nexio.tv.data.repository.TraktDiscoverySnapshot
import com.nexio.tv.data.repository.kitsuCatalogTitle
import com.nexio.tv.data.repository.tmdbCatalogTitle
import com.nexio.tv.domain.model.Addon
import com.nexio.tv.domain.model.CatalogDescriptor
import com.nexio.tv.domain.model.CatalogRow
import com.nexio.tv.domain.model.ContentType
import com.nexio.tv.domain.model.MetaPreview
import com.nexio.tv.domain.model.RailItemPreview
import com.nexio.tv.domain.model.TmdbSettings
import com.nexio.tv.domain.model.toMetaPreview
import kotlinx.coroutines.Job

internal const val TRAKT_HOME_ADDON_ID = "trakt"
internal const val SIMKL_HOME_ADDON_ID = "simkl"
internal const val MDBLIST_HOME_ADDON_ID = "mdblist"
internal const val TMDB_HOME_ADDON_ID = "tmdb"
internal const val KITSU_HOME_ADDON_ID = "kitsu"
private const val TRAKT_HOME_KEY_PREFIX = "trakt_"
private const val SIMKL_HOME_KEY_PREFIX = "simkl_"
private const val MDBLIST_HOME_KEY_PREFIX = "mdblist_"
private const val TMDB_HOME_KEY_PREFIX = "tmdb_"
private const val KITSU_HOME_KEY_PREFIX = "kitsu_"

internal data class ConfiguredHomeCatalogDescriptor(
    val orderKey: String,
    val addonId: String,
    val addonName: String,
    val addonBaseUrl: String,
    val catalogId: String,
    val catalogName: String,
    val type: ContentType,
    val rawType: String = type.toApiString()
) {
    fun toLoadingCatalogRow(): CatalogRow {
        return CatalogRow(
            addonId = addonId,
            addonName = addonName,
            addonBaseUrl = addonBaseUrl,
            catalogId = catalogId,
            catalogName = catalogName,
            type = type,
            rawType = rawType,
            items = emptyList(),
            isLoading = true,
            hasMore = false,
            supportsSkip = false
        )
    }
}

internal fun railPreviewsToCatalogRow(
    addonId: String,
    addonName: String,
    addonBaseUrl: String,
    catalogId: String,
    catalogName: String,
    type: ContentType,
    previews: List<RailItemPreview>
): CatalogRow = CatalogRow(
    addonId = addonId,
    addonName = addonName,
    addonBaseUrl = addonBaseUrl,
    catalogId = catalogId,
    catalogName = catalogName,
    type = type,
    rawType = type.toApiString("catalog"),
    items = previews.map { it.toMetaPreview() },
    isLoading = false,
    hasMore = false,
    supportsSkip = false
)

internal fun HomeViewModel.catalogKey(addonId: String, type: String, catalogId: String): String {
    return addonCatalogKey(addonId, type, catalogId)
}

internal fun homeCatalogGlobalKey(row: CatalogRow): String {
    return when (row.addonId) {
        TRAKT_HOME_ADDON_ID -> if (row.catalogId.startsWith(TRAKT_HOME_KEY_PREFIX)) row.catalogId else "$TRAKT_HOME_KEY_PREFIX${row.catalogId}"
        SIMKL_HOME_ADDON_ID -> if (row.catalogId.startsWith(SIMKL_HOME_KEY_PREFIX)) row.catalogId else "$SIMKL_HOME_KEY_PREFIX${row.catalogId}"
        MDBLIST_HOME_ADDON_ID -> if (row.catalogId.startsWith(MDBLIST_HOME_KEY_PREFIX)) row.catalogId else "$MDBLIST_HOME_KEY_PREFIX${row.catalogId}"
        TMDB_HOME_ADDON_ID -> if (row.catalogId.startsWith(TMDB_HOME_KEY_PREFIX)) row.catalogId else "$TMDB_HOME_KEY_PREFIX${row.catalogId}"
        KITSU_HOME_ADDON_ID -> if (row.catalogId.startsWith(KITSU_HOME_KEY_PREFIX)) row.catalogId else "$KITSU_HOME_KEY_PREFIX${row.catalogId}"
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

internal fun buildConfiguredHomeCatalogDescriptors(
    addons: List<Addon>,
    disabledHomeCatalogKeys: Set<String>,
    traktPrefs: TraktCatalogPreferences,
    traktSnapshot: TraktDiscoverySnapshot,
    hasTraktUpNextItems: Boolean = false,
    simklPrefs: SimklCatalogPreferences,
    mdbPrefs: MDBListCatalogPreferences,
    mdbSnapshot: MDBListDiscoverySnapshot,
    tmdbPrefs: TmdbCatalogPreferences = TmdbCatalogPreferences(enabledCatalogs = emptySet(), catalogOrder = emptyList()),
    tmdbSnapshot: TmdbDiscoverySnapshot = TmdbDiscoverySnapshot(),
    kitsuPrefs: KitsuCatalogPreferences = KitsuCatalogPreferences(enabledCatalogs = emptySet(), catalogOrder = emptyList()),
    kitsuSnapshot: KitsuDiscoverySnapshot = KitsuDiscoverySnapshot(),
    existingRowsByOrderKey: Map<String, CatalogRow> = emptyMap()
): List<ConfiguredHomeCatalogDescriptor> {
    val descriptorsByKey = linkedMapOf<String, ConfiguredHomeCatalogDescriptor>()
    val currentTmdbRows = tmdbSnapshot.currentRowsFor(tmdbPrefs)
    val currentKitsuRows = kitsuSnapshot.currentRowsFor(kitsuPrefs)

    fun existingTitle(key: String): String? {
        return existingRowsByOrderKey[key]?.catalogName?.takeIf { it.isNotBlank() }
    }

    buildExpectedConfiguredTraktOrderKeys(traktPrefs)
        .filterNot { isSyntheticHomeCatalogDisabled(it, disabledHomeCatalogKeys) }
        .filter { key -> key != TraktCatalogIds.UP_NEXT || hasTraktUpNextItems }
        .forEach { key ->
            val type = traktCatalogContentType(key)
            descriptorsByKey[key] = ConfiguredHomeCatalogDescriptor(
                orderKey = key,
                addonId = TRAKT_HOME_ADDON_ID,
                addonName = "Trakt",
                addonBaseUrl = "https://api.trakt.tv",
                catalogId = key,
                catalogName = existingTitle(key)
                    ?: traktCustomListTitle(key, traktSnapshot)
                    ?: traktCatalogTitle(key)
                    ?: humanizeCatalogKey(key),
                type = type,
                rawType = type.toApiString("catalog")
            )
        }

    buildExpectedConfiguredSimklOrderKeys(simklPrefs)
        .filterNot { isSyntheticHomeCatalogDisabled(it, disabledHomeCatalogKeys) }
        .forEach { key ->
            val type = simklCatalogContentTypeForDescriptor(key)
            descriptorsByKey[key] = ConfiguredHomeCatalogDescriptor(
                orderKey = key,
                addonId = SIMKL_HOME_ADDON_ID,
                addonName = "SIMKL",
                addonBaseUrl = "https://data.simkl.in",
                catalogId = key,
                catalogName = existingTitle(key) ?: simklCatalogTitle(key) ?: humanizeCatalogKey(key),
                type = type,
                rawType = type.toApiString("catalog")
            )
        }

    buildExpectedConfiguredMDBListOrderKeys(mdbPrefs, mdbSnapshot)
        .filterNot { isSyntheticHomeCatalogDisabled(it, disabledHomeCatalogKeys) }
        .forEach { key ->
            descriptorsByKey[key] = ConfiguredHomeCatalogDescriptor(
                orderKey = key,
                addonId = MDBLIST_HOME_ADDON_ID,
                addonName = "MDBList",
                addonBaseUrl = "https://api.mdblist.com",
                catalogId = "mdblist_pending_${slugifyCatalogKey(key)}",
                catalogName = existingTitle(key)
                    ?: mdbSnapshot.listTitle(key)
                    ?: humanizeCatalogKey(key),
                type = ContentType.UNKNOWN,
                rawType = "catalog"
            )
        }

    buildExpectedConfiguredTmdbOrderKeys(tmdbPrefs)
        .filterNot { isSyntheticHomeCatalogDisabled(it, disabledHomeCatalogKeys) }
        .forEach { key ->
            val row = currentTmdbRows[key]
            val type = row?.type ?: tmdbCatalogContentType(key)
            descriptorsByKey[key] = ConfiguredHomeCatalogDescriptor(
                orderKey = key,
                addonId = TMDB_HOME_ADDON_ID,
                addonName = "TMDB",
                addonBaseUrl = "https://api.themoviedb.org/3",
                catalogId = key,
                catalogName = existingTitle(key)
                    ?: row?.catalogName?.takeIf { it.isNotBlank() }
                    ?: tmdbCatalogTitle(key)
                    ?: humanizeCatalogKey(key),
                type = type,
                rawType = type.toApiString("catalog")
            )
        }

    buildExpectedConfiguredKitsuOrderKeys(kitsuPrefs)
        .filterNot { isSyntheticHomeCatalogDisabled(it, disabledHomeCatalogKeys) }
        .forEach { key ->
            val row = currentKitsuRows[key]
            val type = row?.type ?: ContentType.SERIES
            descriptorsByKey[key] = ConfiguredHomeCatalogDescriptor(
                orderKey = key,
                addonId = KITSU_HOME_ADDON_ID,
                addonName = "Kitsu",
                addonBaseUrl = "https://kitsu.io/api/edge",
                catalogId = key,
                catalogName = existingTitle(key)
                    ?: row?.catalogName?.takeIf { it.isNotBlank() }
                    ?: kitsuCatalogTitle(key)
                    ?: humanizeCatalogKey(key),
                type = type,
                rawType = type.toApiString("catalog")
            )
        }

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
                val key = addonCatalogKey(addon.id, catalog.apiType, catalog.id)
                descriptorsByKey[key] = ConfiguredHomeCatalogDescriptor(
                    orderKey = key,
                    addonId = addon.id,
                    addonName = addon.displayName,
                    addonBaseUrl = addon.baseUrl,
                    catalogId = catalog.id,
                    catalogName = existingTitle(key) ?: catalog.name.ifBlank { humanizeCatalogKey(key) },
                    type = catalog.type,
                    rawType = catalog.apiType
                )
            }
    }

    return descriptorsByKey.values.toList()
}

internal fun persistableHomeCatalogRows(rows: List<CatalogRow>): List<CatalogRow> {
    return rows.filterNot { row -> row.isLoading && row.items.isEmpty() }
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
    simklPrefs: SimklCatalogPreferences,
    mdbPrefs: MDBListCatalogPreferences,
    mdbSnapshot: MDBListDiscoverySnapshot,
    tmdbPrefs: TmdbCatalogPreferences = TmdbCatalogPreferences(enabledCatalogs = emptySet(), catalogOrder = emptyList()),
    kitsuPrefs: KitsuCatalogPreferences = KitsuCatalogPreferences(enabledCatalogs = emptySet(), catalogOrder = emptyList())
): List<String> {
    val traktKeys = buildExpectedConfiguredTraktOrderKeys(traktPrefs)
        .filterNot { isSyntheticHomeCatalogDisabled(it, disabledHomeCatalogKeys) }
    val simklKeys = buildExpectedConfiguredSimklOrderKeys(simklPrefs)
        .filterNot { isSyntheticHomeCatalogDisabled(it, disabledHomeCatalogKeys) }
    val mdbKeys = buildExpectedConfiguredMDBListOrderKeys(mdbPrefs, mdbSnapshot)
        .filterNot { isSyntheticHomeCatalogDisabled(it, disabledHomeCatalogKeys) }
    val tmdbKeys = buildExpectedConfiguredTmdbOrderKeys(tmdbPrefs)
        .filterNot { isSyntheticHomeCatalogDisabled(it, disabledHomeCatalogKeys) }
    val kitsuKeys = buildExpectedConfiguredKitsuOrderKeys(kitsuPrefs)
        .filterNot { isSyntheticHomeCatalogDisabled(it, disabledHomeCatalogKeys) }
    val addonKeys = buildExpectedConfiguredAddonOrderKeys(addons, disabledHomeCatalogKeys)
    return (traktKeys + simklKeys + mdbKeys + tmdbKeys + kitsuKeys + addonKeys).distinct()
}

internal fun buildPublishableConfiguredHomeOrderKeys(
    addons: List<Addon>,
    disabledHomeCatalogKeys: Set<String>,
    availableAddonOrderKeys: Set<String>,
    traktPrefs: TraktCatalogPreferences,
    traktSnapshot: TraktDiscoverySnapshot,
    hasTraktUpNextItems: Boolean,
    simklPrefs: SimklCatalogPreferences,
    simklSnapshot: SimklDiscoverySnapshot,
    mdbPrefs: MDBListCatalogPreferences,
    mdbSnapshot: MDBListDiscoverySnapshot,
    tmdbPrefs: TmdbCatalogPreferences = TmdbCatalogPreferences(enabledCatalogs = emptySet(), catalogOrder = emptyList()),
    tmdbSnapshot: TmdbDiscoverySnapshot = TmdbDiscoverySnapshot(),
    kitsuPrefs: KitsuCatalogPreferences = KitsuCatalogPreferences(enabledCatalogs = emptySet(), catalogOrder = emptyList()),
    kitsuSnapshot: KitsuDiscoverySnapshot = KitsuDiscoverySnapshot()
): List<String> {
    val traktKeys = buildPublishableConfiguredTraktOrderKeys(
        prefs = traktPrefs,
        snapshot = traktSnapshot,
        hasTraktUpNextItems = hasTraktUpNextItems
    ).filterNot { isSyntheticHomeCatalogDisabled(it, disabledHomeCatalogKeys) }
    val simklKeys = buildPublishableConfiguredSimklOrderKeys(
        prefs = simklPrefs,
        snapshot = simklSnapshot
    ).filterNot { isSyntheticHomeCatalogDisabled(it, disabledHomeCatalogKeys) }
    val mdbKeys = buildPublishableConfiguredMDBListOrderKeys(
        prefs = mdbPrefs,
        snapshot = mdbSnapshot
    ).filterNot { isSyntheticHomeCatalogDisabled(it, disabledHomeCatalogKeys) }
    val tmdbKeys = buildPublishableConfiguredTmdbOrderKeys(
        prefs = tmdbPrefs,
        snapshot = tmdbSnapshot
    ).filterNot { isSyntheticHomeCatalogDisabled(it, disabledHomeCatalogKeys) }
    val kitsuKeys = buildPublishableConfiguredKitsuOrderKeys(
        prefs = kitsuPrefs,
        snapshot = kitsuSnapshot
    ).filterNot { isSyntheticHomeCatalogDisabled(it, disabledHomeCatalogKeys) }
    val addonKeys = buildExpectedConfiguredAddonOrderKeys(addons, disabledHomeCatalogKeys)
        .filter { it in availableAddonOrderKeys }
    return (traktKeys + simklKeys + mdbKeys + tmdbKeys + kitsuKeys + addonKeys).distinct()
}

internal fun buildExpectedConfiguredTraktOrderKeys(
    prefs: TraktCatalogPreferences
): List<String> {
    val orderedEnabledBuiltIns = prefs.catalogOrder.filter { it in prefs.enabledCatalogs }
    val remainingEnabledBuiltIns = prefs.enabledCatalogs.filterNot { it in orderedEnabledBuiltIns }
    val orderedCustomKeys = prefs.selectedPopularListKeys.sorted()
    return (orderedEnabledBuiltIns + remainingEnabledBuiltIns + orderedCustomKeys).distinct()
}

internal fun TraktCatalogPreferences.onlyWhenAuthenticated(
    authenticated: Boolean
): TraktCatalogPreferences {
    return if (authenticated) {
        this
    } else {
        TraktCatalogPreferences(
            enabledCatalogs = emptySet(),
            catalogOrder = emptyList(),
            selectedPopularListKeys = emptySet()
        )
    }
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

internal fun buildExpectedConfiguredTmdbOrderKeys(prefs: TmdbCatalogPreferences): List<String> {
    return prefs.enabledCatalogIds().toList()
}

internal fun buildExpectedConfiguredKitsuOrderKeys(prefs: KitsuCatalogPreferences): List<String> {
    return prefs.enabledCatalogIds().toList()
}

internal fun buildPublishableConfiguredTmdbOrderKeys(
    prefs: TmdbCatalogPreferences,
    snapshot: TmdbDiscoverySnapshot
): List<String> {
    val available = snapshot.currentRowsFor(prefs).filterValues { it.items.isNotEmpty() }.keys
    return buildExpectedConfiguredTmdbOrderKeys(prefs).filter { it in available }
}

internal fun buildPublishableConfiguredKitsuOrderKeys(
    prefs: KitsuCatalogPreferences,
    snapshot: KitsuDiscoverySnapshot
): List<String> {
    val available = snapshot.currentRowsFor(prefs).filterValues { it.items.isNotEmpty() }.keys
    return buildExpectedConfiguredKitsuOrderKeys(prefs).filter { it in available }
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
    simklExpectedOrderKeys: List<String>,
    simklPrefs: SimklCatalogPreferences,
    simklSnapshot: SimklDiscoverySnapshot,
    mdbExpectedOrderKeys: List<String>,
    mdbPrefs: MDBListCatalogPreferences,
    mdbSnapshot: MDBListDiscoverySnapshot,
    kitsuExpectedOrderKeys: List<String> = emptyList(),
    kitsuPrefs: KitsuCatalogPreferences = KitsuCatalogPreferences(enabledCatalogs = emptySet(), catalogOrder = emptyList()),
    kitsuSnapshot: KitsuDiscoverySnapshot = KitsuDiscoverySnapshot(),
    tmdbExpectedOrderKeys: List<String> = emptyList(),
    tmdbPrefs: TmdbCatalogPreferences = TmdbCatalogPreferences(enabledCatalogs = emptySet(), catalogOrder = emptyList()),
    tmdbSnapshot: TmdbDiscoverySnapshot = TmdbDiscoverySnapshot()
): Boolean {
    val addonsReady = addonExpectedOrderKeys.all { it in availableAddonOrderKeys }
    val traktReady = if (traktExpectedOrderKeys.isEmpty()) {
        true
    } else {
        !shouldRefreshTraktDiscoveryForState(traktPrefs, traktSnapshot)
    }
    val simklReady = if (simklExpectedOrderKeys.isEmpty()) {
        true
    } else {
        !shouldRefreshSimklDiscoveryForState(simklPrefs, simklSnapshot)
    }
    val mdbReady = if (mdbExpectedOrderKeys.isEmpty()) {
        true
    } else {
        !shouldRefreshMDBListDiscoveryForState(mdbPrefs, mdbSnapshot)
    }
    val kitsuReady = if (kitsuExpectedOrderKeys.isEmpty()) {
        true
    } else {
        !shouldRefreshKitsuDiscoveryForState(kitsuPrefs, kitsuSnapshot)
    }
    val tmdbReady = if (tmdbExpectedOrderKeys.isEmpty()) {
        true
    } else {
        !shouldRefreshTmdbDiscoveryForState(tmdbPrefs, tmdbSnapshot)
    }
    return addonsReady && traktReady && simklReady && mdbReady && kitsuReady && tmdbReady
}

internal fun areConfiguredHomePublishSourcesReady(
    addonExpectedOrderKeys: List<String>,
    availableAddonOrderKeys: Set<String>,
    traktExpectedOrderKeys: List<String>,
    traktObserved: Boolean,
    simklExpectedOrderKeys: List<String>,
    simklObserved: Boolean,
    mdbExpectedOrderKeys: List<String>,
    mdbObserved: Boolean,
    kitsuExpectedOrderKeys: List<String> = emptyList(),
    kitsuObserved: Boolean = true,
    tmdbExpectedOrderKeys: List<String> = emptyList(),
    tmdbObserved: Boolean = true
): Boolean {
    val addonsReady = addonExpectedOrderKeys.all { it in availableAddonOrderKeys }
    val traktReady = traktExpectedOrderKeys.isEmpty() || traktObserved
    val simklReady = simklExpectedOrderKeys.isEmpty() || simklObserved
    val mdbReady = mdbExpectedOrderKeys.isEmpty() || mdbObserved
    val kitsuReady = kitsuExpectedOrderKeys.isEmpty() || kitsuObserved
    val tmdbReady = tmdbExpectedOrderKeys.isEmpty() || tmdbObserved
    return addonsReady && traktReady && simklReady && mdbReady && kitsuReady && tmdbReady
}

internal fun isConfiguredHomeRefreshInProgress(
    catalogsLoadInProgress: Boolean,
    traktDiscoveryRefreshInProgress: Boolean,
    simklDiscoveryRefreshInProgress: Boolean,
    mdbListDiscoveryRefreshInProgress: Boolean,
    kitsuDiscoveryRefreshInProgress: Boolean,
    tmdbDiscoveryRefreshInProgress: Boolean
): Boolean {
    return catalogsLoadInProgress ||
        traktDiscoveryRefreshInProgress ||
        simklDiscoveryRefreshInProgress ||
        mdbListDiscoveryRefreshInProgress ||
        kitsuDiscoveryRefreshInProgress ||
        tmdbDiscoveryRefreshInProgress
}

internal fun shouldRefreshTmdbDiscoveryForState(
    prefs: TmdbCatalogPreferences,
    snapshot: TmdbDiscoverySnapshot
): Boolean {
    val expectedKeys = buildExpectedConfiguredTmdbOrderKeys(prefs)
    if (expectedKeys.isEmpty()) return false
    if (snapshot.updatedAtMs <= 0L) return true
    if (!snapshot.matchesPreferences(prefs)) return true
    return expectedKeys.any { key ->
        key !in snapshot.catalogIdsWithCurrentPreferences
    }
}

internal fun shouldRefreshKitsuDiscoveryForState(
    prefs: KitsuCatalogPreferences,
    snapshot: KitsuDiscoverySnapshot
): Boolean {
    val expectedKeys = buildExpectedConfiguredKitsuOrderKeys(prefs)
    if (expectedKeys.isEmpty()) return false
    if (snapshot.updatedAtMs <= 0L) return true
    return expectedKeys.any { key ->
        key !in snapshot.catalogIdsWithCurrentPreferences
    }
}

internal fun shouldForceTmdbDiscoveryRefreshForCredentialChange(
    previous: TmdbSettings,
    current: TmdbSettings,
    prefs: TmdbCatalogPreferences
): Boolean {
    if (prefs.enabledCatalogIds().isEmpty()) return false
    return previous.apiKey.trim() != current.apiKey.trim()
}

internal fun shouldRefreshSimklDiscoveryForState(
    prefs: SimklCatalogPreferences,
    snapshot: SimklDiscoverySnapshot
): Boolean {
    if (snapshot.updatedAtMs <= 0L) return true
    return buildExpectedConfiguredSimklOrderKeys(prefs).any { key ->
        snapshot.itemsByCatalog[key].isNullOrEmpty()
    }
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

internal fun isSyntheticHomeCatalogDisabled(
    key: String,
    disabledHomeCatalogKeys: Set<String>
): Boolean {
    if (key in disabledHomeCatalogKeys) return true
    val canonicalKey = canonicalSyntheticCatalogOrderKey(key)
    if (canonicalKey.isBlank()) return false
    return disabledHomeCatalogKeys.any { disabledKey ->
        canonicalSyntheticCatalogOrderKey(disabledKey) == canonicalKey
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


internal fun buildExpectedConfiguredSimklOrderKeys(
    prefs: SimklCatalogPreferences
): List<String> {
    val orderedEnabledBuiltIns = prefs.catalogOrder.filter { it in prefs.enabledCatalogs }
    val remainingEnabledBuiltIns = prefs.enabledCatalogs.filterNot { it in orderedEnabledBuiltIns }
    return (orderedEnabledBuiltIns + remainingEnabledBuiltIns).distinct()
}

internal fun buildPublishableConfiguredSimklOrderKeys(
    prefs: SimklCatalogPreferences,
    snapshot: SimklDiscoverySnapshot
): List<String> {
    val availableKeys = snapshot.itemsByCatalog
        .filterValues { it.isNotEmpty() }
        .keys
        .toSet()
    return buildExpectedConfiguredSimklOrderKeys(prefs).filter { it in availableKeys }
}

private fun traktCustomListTitle(
    key: String,
    snapshot: TraktDiscoverySnapshot
): String? {
    return snapshot.popularLists.firstOrNull {
        canonicalSyntheticCatalogOrderKey(it.key) == canonicalSyntheticCatalogOrderKey(key)
    }?.title?.takeIf { it.isNotBlank() }
}

private fun MDBListDiscoverySnapshot.listTitle(key: String): String? {
    val canonical = canonicalSyntheticCatalogOrderKey(key)
    return (personalLists + topLists).firstOrNull {
        canonicalSyntheticCatalogOrderKey(it.key) == canonical
    }?.title?.takeIf { it.isNotBlank() }
        ?: customListCatalogs.firstOrNull {
            canonicalSyntheticCatalogOrderKey(it.key) == canonical
        }?.catalogName
            ?.removeSuffix(" (Movies)")
            ?.removeSuffix(" (Shows)")
            ?.takeIf { it.isNotBlank() }
}

private fun traktCatalogTitle(key: String): String? {
    return when (key) {
        TraktCatalogIds.UP_NEXT -> "Trakt Up Next"
        TraktCatalogIds.TRENDING_MOVIES -> "Trakt Trending Movies"
        TraktCatalogIds.TRENDING_SHOWS -> "Trakt Trending Shows"
        TraktCatalogIds.POPULAR_MOVIES -> "Trakt Popular Movies"
        TraktCatalogIds.POPULAR_SHOWS -> "Trakt Popular Shows"
        TraktCatalogIds.RECOMMENDED_MOVIES -> "Trakt Recommended Movies"
        TraktCatalogIds.RECOMMENDED_SHOWS -> "Trakt Recommended Shows"
        TraktCatalogIds.CALENDAR -> "Trakt Calendar (Next 7 Days)"
        else -> null
    }
}

private fun traktCatalogContentType(key: String): ContentType {
    return when (key) {
        TraktCatalogIds.TRENDING_MOVIES,
        TraktCatalogIds.POPULAR_MOVIES,
        TraktCatalogIds.RECOMMENDED_MOVIES -> ContentType.MOVIE
        TraktCatalogIds.UP_NEXT,
        TraktCatalogIds.TRENDING_SHOWS,
        TraktCatalogIds.POPULAR_SHOWS,
        TraktCatalogIds.RECOMMENDED_SHOWS,
        TraktCatalogIds.CALENDAR -> ContentType.SERIES
        else -> ContentType.UNKNOWN
    }
}

private fun simklCatalogTitle(key: String): String? {
    return when (key) {
        SimklCatalogIds.TV_TRENDING_TODAY -> "SIMKL Trending TV (Today)"
        SimklCatalogIds.TV_TRENDING_WEEK -> "SIMKL Trending TV (Week)"
        SimklCatalogIds.TV_TRENDING_MONTH -> "SIMKL Trending TV (Month)"
        SimklCatalogIds.ANIME_TRENDING_TODAY -> "SIMKL Trending Anime (Today)"
        SimklCatalogIds.ANIME_TRENDING_WEEK -> "SIMKL Trending Anime (Week)"
        SimklCatalogIds.ANIME_TRENDING_MONTH -> "SIMKL Trending Anime (Month)"
        SimklCatalogIds.MOVIE_TRENDING_TODAY -> "SIMKL Trending Movies (Today)"
        SimklCatalogIds.MOVIE_TRENDING_WEEK -> "SIMKL Trending Movies (Week)"
        SimklCatalogIds.MOVIE_TRENDING_MONTH -> "SIMKL Trending Movies (Month)"
        SimklCatalogIds.DVD_RELEASES -> "SIMKL Popular DVD Releases"
        else -> null
    }
}

private fun simklCatalogContentTypeForDescriptor(key: String): ContentType {
    return when (key) {
        SimklCatalogIds.MOVIE_TRENDING_TODAY,
        SimklCatalogIds.MOVIE_TRENDING_WEEK,
        SimklCatalogIds.MOVIE_TRENDING_MONTH,
        SimklCatalogIds.DVD_RELEASES -> ContentType.MOVIE
        else -> ContentType.SERIES
    }
}

private fun tmdbCatalogContentType(key: String): ContentType {
    return when (key) {
        TmdbCatalogIds.TRENDING_MOVIES,
        TmdbCatalogIds.LATEST_RELEASES_MOVIES,
        TmdbCatalogIds.POPULAR_MOVIES,
        TmdbCatalogIds.YEAR_MOVIES,
        TmdbCatalogIds.LANGUAGE_MOVIES -> ContentType.MOVIE
        TmdbCatalogIds.TRENDING_SERIES,
        TmdbCatalogIds.LATEST_RELEASES_SERIES,
        TmdbCatalogIds.POPULAR_SERIES,
        TmdbCatalogIds.YEAR_SERIES,
        TmdbCatalogIds.LANGUAGE_SERIES -> ContentType.SERIES
        else -> ContentType.UNKNOWN
    }
}

private fun humanizeCatalogKey(key: String): String {
    return canonicalSyntheticCatalogOrderKey(key)
        .substringAfter(':')
        .replace('_', ' ')
        .replace('-', ' ')
        .split(' ')
        .filter { it.isNotBlank() }
        .joinToString(" ") { word -> word.replaceFirstChar { it.uppercase() } }
        .ifBlank { key }
}

private fun slugifyCatalogKey(key: String): String {
    return canonicalSyntheticCatalogOrderKey(key)
        .lowercase()
        .replace(Regex("[^a-z0-9]+"), "_")
        .trim('_')
        .ifBlank { "catalog" }
}
