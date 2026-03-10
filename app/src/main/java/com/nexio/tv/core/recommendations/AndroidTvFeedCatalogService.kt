package com.nexio.tv.core.recommendations

import android.util.Log
import com.nexio.tv.core.network.NetworkResult
import com.nexio.tv.core.sync.addonCatalogDisableKey
import com.nexio.tv.data.local.LayoutPreferenceDataStore
import com.nexio.tv.data.local.MDBListCatalogPreferences
import com.nexio.tv.data.local.MDBListSettingsDataStore
import com.nexio.tv.data.local.TraktCatalogIds
import com.nexio.tv.data.local.TraktCatalogPreferences
import com.nexio.tv.data.local.TraktSettingsDataStore
import com.nexio.tv.data.repository.ContinueWatchingSnapshot
import com.nexio.tv.data.repository.ContinueWatchingSnapshotService
import com.nexio.tv.data.repository.MDBListCustomCatalog
import com.nexio.tv.data.repository.MDBListDiscoveryService
import com.nexio.tv.data.repository.MDBListDiscoverySnapshot
import com.nexio.tv.data.repository.TraktCustomListCatalog
import com.nexio.tv.data.repository.TraktDiscoveryService
import com.nexio.tv.data.repository.TraktDiscoverySnapshot
import com.nexio.tv.domain.model.Addon
import com.nexio.tv.domain.model.CatalogDescriptor
import com.nexio.tv.domain.model.CatalogRow
import com.nexio.tv.domain.model.ContentType
import com.nexio.tv.domain.model.MetaPreview
import com.nexio.tv.domain.model.PosterShape
import com.nexio.tv.domain.model.WatchProgress
import com.nexio.tv.domain.model.skipStep
import com.nexio.tv.domain.model.supportsExtra
import com.nexio.tv.domain.repository.AddonRepository
import com.nexio.tv.domain.repository.CatalogRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import javax.inject.Inject
import javax.inject.Singleton

private const val TAG = "AndroidTvFeedCatalog"
private const val TRAKT_RAIL_ADDON_ID = "trakt"
private const val TRAKT_RAIL_ADDON_NAME = "Trakt"
private const val TRAKT_RAIL_ADDON_BASE_URL = "https://api.trakt.tv"
private const val MDBLIST_RAIL_ADDON_ID = "mdblist"
private const val MDBLIST_RAIL_ADDON_NAME = "MDBList"
private const val MDBLIST_RAIL_ADDON_BASE_URL = "https://api.mdblist.com"

data class AndroidTvFeedOption(
    val key: String,
    val title: String,
    val subtitle: String,
    val sourceLabel: String,
    val typeLabel: String,
    val isDisabledOnHome: Boolean = false
)

data class AndroidTvFeedRow(
    val option: AndroidTvFeedOption,
    val items: List<MetaPreview>,
    val addonBaseUrl: String? = null
)

@Singleton
class AndroidTvFeedCatalogService @Inject constructor(
    private val addonRepository: AddonRepository,
    private val catalogRepository: CatalogRepository,
    private val layoutPreferenceDataStore: LayoutPreferenceDataStore,
    private val traktDiscoveryService: TraktDiscoveryService,
    private val traktSettingsDataStore: TraktSettingsDataStore,
    private val mdbListDiscoveryService: MDBListDiscoveryService,
    private val mdbListSettingsDataStore: MDBListSettingsDataStore,
    private val continueWatchingSnapshotService: ContinueWatchingSnapshotService
) {
    companion object {
        const val CONTINUE_WATCHING_FEED_KEY = "continue_watching"
    }

    fun observeFeedOptions(): Flow<List<AndroidTvFeedOption>> {
        return combine(
            addonRepository.getInstalledAddons(),
            layoutPreferenceDataStore.disabledHomeCatalogKeys,
            traktDiscoveryService.observeSnapshot(),
            traktSettingsDataStore.catalogPreferences,
            mdbListDiscoveryService.observeSnapshot(),
            mdbListSettingsDataStore.catalogPreferences
        ) { values ->
            @Suppress("UNCHECKED_CAST")
            val addons = values[0] as List<Addon>
            val disabledKeys = values[1] as List<String>
            val traktSnapshot = values[2] as TraktDiscoverySnapshot
            val traktPrefs = values[3] as TraktCatalogPreferences
            val mdbListSnapshot = values[4] as MDBListDiscoverySnapshot
            val mdbListPrefs = values[5] as MDBListCatalogPreferences
            buildFeedOptions(
                addons = addons,
                disabledKeys = disabledKeys.toSet(),
                traktSnapshot = traktSnapshot,
                traktPrefs = traktPrefs,
                mdbListSnapshot = mdbListSnapshot,
                mdbListPrefs = mdbListPrefs
            )
        }
    }

    suspend fun resolveSelectedRows(selectedFeedKeys: List<String>): List<AndroidTvFeedRow> {
        val normalizedKeys = selectedFeedKeys.asSequence()
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .distinct()
            .toList()
        if (normalizedKeys.isEmpty()) return emptyList()

        runCatching { continueWatchingSnapshotService.ensureFresh(force = false) }
        runCatching { traktDiscoveryService.ensureFresh(force = false) }
        runCatching { mdbListDiscoveryService.ensureFresh(force = false) }

        val addons = addonRepository.getInstalledAddons().first()
        val disabledKeys = layoutPreferenceDataStore.disabledHomeCatalogKeys.first().toSet()
        val traktSnapshot = traktDiscoveryService.observeSnapshot().first()
        val traktPrefs = traktSettingsDataStore.catalogPreferences.first()
        val mdbListSnapshot = mdbListDiscoveryService.observeSnapshot().first()
        val mdbListPrefs = mdbListSettingsDataStore.catalogPreferences.first()
        val continueWatchingSnapshot = continueWatchingSnapshotService.observeSnapshot().first()

        val optionByKey = buildFeedOptions(
            addons = addons,
            disabledKeys = disabledKeys,
            traktSnapshot = traktSnapshot,
            traktPrefs = traktPrefs,
            mdbListSnapshot = mdbListSnapshot,
            mdbListPrefs = mdbListPrefs
        ).associateBy { it.key }

        val syntheticRowsByKey = buildSyntheticRows(
            traktSnapshot = traktSnapshot,
            traktPrefs = traktPrefs,
            mdbListSnapshot = mdbListSnapshot,
            mdbListPrefs = mdbListPrefs,
            continueWatchingSnapshot = continueWatchingSnapshot
        ).associateBy { row -> catalogGlobalKey(row.addonId, row.apiType, row.catalogId) }

        return normalizedKeys.mapNotNull { key ->
            when {
                key == CONTINUE_WATCHING_FEED_KEY -> {
                    val option = optionByKey[key] ?: return@mapNotNull null
                    val items = buildContinueWatchingItems(continueWatchingSnapshot)
                    if (items.isEmpty()) null else AndroidTvFeedRow(
                        option = option,
                        items = items
                    )
                }

                key in syntheticRowsByKey -> {
                    val row = syntheticRowsByKey[key] ?: return@mapNotNull null
                    val option = optionByKey[key] ?: return@mapNotNull null
                    if (row.items.isEmpty()) null else AndroidTvFeedRow(
                        option = option,
                        items = row.items,
                        addonBaseUrl = row.addonBaseUrl
                    )
                }

                else -> resolveAddonFeedRow(addons, key, optionByKey[key])
            }
        }
    }

    private suspend fun resolveAddonFeedRow(
        addons: List<Addon>,
        selectedKey: String,
        option: AndroidTvFeedOption?
    ): AndroidTvFeedRow? {
        if (option == null) return null
        addons.forEach { addon ->
            addon.catalogs
                .filterNot { it.isSearchOnlyCatalog() }
                .forEach { catalog ->
                    val key = catalogGlobalKey(addon.id, catalog.apiType, catalog.id)
                    if (key != selectedKey) return@forEach
                    val result = catalogRepository.getCatalog(
                        addonBaseUrl = addon.baseUrl,
                        addonId = addon.id,
                        addonName = addon.displayName,
                        catalogId = catalog.id,
                        catalogName = catalog.name,
                        type = catalog.apiType,
                        skip = 0,
                        skipStep = catalog.skipStep(),
                        supportsSkip = catalog.supportsExtra("skip")
                    ).first { networkResult -> networkResult !is NetworkResult.Loading }
                    return when (result) {
                        is NetworkResult.Success -> AndroidTvFeedRow(
                            option = option,
                            items = result.data.items,
                            addonBaseUrl = addon.baseUrl
                        )

                        is NetworkResult.Error -> {
                            Log.w(
                                TAG,
                                "Failed to resolve Android TV feed key=$selectedKey code=${result.code} message=${result.message}"
                            )
                            null
                        }

                        NetworkResult.Loading -> null
                    }
                }
        }
        return null
    }

    private fun buildFeedOptions(
        addons: List<Addon>,
        disabledKeys: Set<String>,
        traktSnapshot: TraktDiscoverySnapshot,
        traktPrefs: TraktCatalogPreferences,
        mdbListSnapshot: MDBListDiscoverySnapshot,
        mdbListPrefs: MDBListCatalogPreferences
    ): List<AndroidTvFeedOption> {
        val options = mutableListOf<AndroidTvFeedOption>()

        options += AndroidTvFeedOption(
            key = CONTINUE_WATCHING_FEED_KEY,
            title = "Continue Watching",
            subtitle = "Resume items and next episodes from local progress and Trakt.",
            sourceLabel = "Nexio",
            typeLabel = "mixed"
        )

        addons.forEach { addon ->
            addon.catalogs
                .filterNot { it.isSearchOnlyCatalog() }
                .forEach { catalog ->
                    val key = catalogGlobalKey(addon.id, catalog.apiType, catalog.id)
                    val disableKey = addonCatalogDisableKey(addon.baseUrl, catalog.apiType, catalog.id, catalog.name)
                    val isDisabledOnHome = disableKey in disabledKeys || key in disabledKeys
                    options += AndroidTvFeedOption(
                        key = key,
                        title = catalog.name,
                        subtitle = buildString {
                            append(addon.displayName)
                            append(" • ")
                            append(catalog.apiType.displayLabel())
                            if (isDisabledOnHome) {
                                append(" • Hidden on Home")
                            }
                        },
                        sourceLabel = addon.displayName,
                        typeLabel = catalog.apiType.displayLabel(),
                        isDisabledOnHome = isDisabledOnHome
                    )
                }
        }

        buildSyntheticRows(
            traktSnapshot = traktSnapshot,
            traktPrefs = traktPrefs,
            mdbListSnapshot = mdbListSnapshot,
            mdbListPrefs = mdbListPrefs,
            continueWatchingSnapshot = null
        ).forEach { row ->
            options += AndroidTvFeedOption(
                key = catalogGlobalKey(row.addonId, row.apiType, row.catalogId),
                title = row.catalogName,
                subtitle = "${row.addonName} • ${row.apiType.displayLabel()}",
                sourceLabel = row.addonName,
                typeLabel = row.apiType.displayLabel()
            )
        }

        return options
            .distinctBy { it.key }
            .sortedWith(
                compareBy<AndroidTvFeedOption> { it.key != CONTINUE_WATCHING_FEED_KEY }
                    .thenBy { it.sourceLabel.lowercase() }
                    .thenBy { it.title.lowercase() }
            )
    }

    private fun buildSyntheticRows(
        traktSnapshot: TraktDiscoverySnapshot,
        traktPrefs: TraktCatalogPreferences,
        mdbListSnapshot: MDBListDiscoverySnapshot,
        mdbListPrefs: MDBListCatalogPreferences,
        continueWatchingSnapshot: ContinueWatchingSnapshot?
    ): List<CatalogRow> {
        val nextUpItems = continueWatchingSnapshot
            ?.nextUpItems
            ?.map { nextUp -> nextUp.toMetaPreview() }
            .orEmpty()

        return buildList {
            addAll(buildSyntheticTraktRows(traktSnapshot, traktPrefs, nextUpItems))
            addAll(buildSyntheticMdbListRows(mdbListSnapshot, mdbListPrefs))
        }
    }

    private fun buildSyntheticTraktRows(
        snapshot: TraktDiscoverySnapshot,
        prefs: TraktCatalogPreferences,
        upNextItems: List<MetaPreview>
    ): List<CatalogRow> {
        val rows = mutableListOf<CatalogRow>()

        if (TraktCatalogIds.UP_NEXT in prefs.enabledCatalogs && upNextItems.isNotEmpty()) {
            rows += buildSyntheticRow(
                addonId = TRAKT_RAIL_ADDON_ID,
                addonName = TRAKT_RAIL_ADDON_NAME,
                addonBaseUrl = TRAKT_RAIL_ADDON_BASE_URL,
                catalogId = TraktCatalogIds.UP_NEXT,
                catalogName = "Trakt Up Next",
                type = ContentType.SERIES,
                items = upNextItems
            )
        }
        if (TraktCatalogIds.TRENDING_MOVIES in prefs.enabledCatalogs && snapshot.trendingMovieItems.isNotEmpty()) {
            rows += buildSyntheticRow(TRAKT_RAIL_ADDON_ID, TRAKT_RAIL_ADDON_NAME, TRAKT_RAIL_ADDON_BASE_URL, TraktCatalogIds.TRENDING_MOVIES, "Trakt Trending Movies", ContentType.MOVIE, snapshot.trendingMovieItems)
        }
        if (TraktCatalogIds.TRENDING_SHOWS in prefs.enabledCatalogs && snapshot.trendingShowItems.isNotEmpty()) {
            rows += buildSyntheticRow(TRAKT_RAIL_ADDON_ID, TRAKT_RAIL_ADDON_NAME, TRAKT_RAIL_ADDON_BASE_URL, TraktCatalogIds.TRENDING_SHOWS, "Trakt Trending Shows", ContentType.SERIES, snapshot.trendingShowItems)
        }
        if (TraktCatalogIds.POPULAR_MOVIES in prefs.enabledCatalogs && snapshot.popularMovieItems.isNotEmpty()) {
            rows += buildSyntheticRow(TRAKT_RAIL_ADDON_ID, TRAKT_RAIL_ADDON_NAME, TRAKT_RAIL_ADDON_BASE_URL, TraktCatalogIds.POPULAR_MOVIES, "Trakt Popular Movies", ContentType.MOVIE, snapshot.popularMovieItems)
        }
        if (TraktCatalogIds.POPULAR_SHOWS in prefs.enabledCatalogs && snapshot.popularShowItems.isNotEmpty()) {
            rows += buildSyntheticRow(TRAKT_RAIL_ADDON_ID, TRAKT_RAIL_ADDON_NAME, TRAKT_RAIL_ADDON_BASE_URL, TraktCatalogIds.POPULAR_SHOWS, "Trakt Popular Shows", ContentType.SERIES, snapshot.popularShowItems)
        }
        if (TraktCatalogIds.RECOMMENDED_MOVIES in prefs.enabledCatalogs && snapshot.recommendationMovieItems.isNotEmpty()) {
            rows += buildSyntheticRow(TRAKT_RAIL_ADDON_ID, TRAKT_RAIL_ADDON_NAME, TRAKT_RAIL_ADDON_BASE_URL, TraktCatalogIds.RECOMMENDED_MOVIES, "Trakt Recommended Movies", ContentType.MOVIE, snapshot.recommendationMovieItems)
        }
        if (TraktCatalogIds.RECOMMENDED_SHOWS in prefs.enabledCatalogs && snapshot.recommendationShowItems.isNotEmpty()) {
            rows += buildSyntheticRow(TRAKT_RAIL_ADDON_ID, TRAKT_RAIL_ADDON_NAME, TRAKT_RAIL_ADDON_BASE_URL, TraktCatalogIds.RECOMMENDED_SHOWS, "Trakt Recommended Shows", ContentType.SERIES, snapshot.recommendationShowItems)
        }
        if (TraktCatalogIds.CALENDAR in prefs.enabledCatalogs && snapshot.calendarItems.isNotEmpty()) {
            rows += buildSyntheticRow(TRAKT_RAIL_ADDON_ID, TRAKT_RAIL_ADDON_NAME, TRAKT_RAIL_ADDON_BASE_URL, TraktCatalogIds.CALENDAR, "Trakt Calendar (Next 7 Days)", ContentType.SERIES, snapshot.calendarItems)
        }

        val selectedCustomKeys = prefs.selectedPopularListKeys.toSet()
        snapshot.customListCatalogs
            .filter { catalog -> catalog.key in selectedCustomKeys && catalog.items.isNotEmpty() }
            .forEach { custom ->
                rows += custom.toCatalogRow()
            }

        return rows
    }

    private fun buildSyntheticMdbListRows(
        snapshot: MDBListDiscoverySnapshot,
        prefs: MDBListCatalogPreferences
    ): List<CatalogRow> {
        if (snapshot.customListCatalogs.isEmpty()) return emptyList()
        val availableKeys = buildSet {
            addAll(snapshot.personalLists.filter { prefs.isPersonalListEnabled(it.key) }.map { it.key })
            addAll(snapshot.topLists.filter { prefs.isTopListSelected(it.key) }.map { it.key })
        }
        return snapshot.customListCatalogs
            .filter { catalog -> catalog.key in availableKeys && catalog.items.isNotEmpty() }
            .map { catalog -> catalog.toCatalogRow() }
    }

    private fun buildSyntheticRow(
        addonId: String,
        addonName: String,
        addonBaseUrl: String,
        catalogId: String,
        catalogName: String,
        type: ContentType,
        items: List<MetaPreview>
    ): CatalogRow {
        return CatalogRow(
            addonId = addonId,
            addonName = addonName,
            addonBaseUrl = addonBaseUrl,
            catalogId = catalogId,
            catalogName = catalogName,
            type = type,
            items = items,
            isLoading = false,
            hasMore = false,
            supportsSkip = false
        )
    }

    private fun buildContinueWatchingItems(snapshot: ContinueWatchingSnapshot): List<MetaPreview> {
        return buildList {
            snapshot.movieProgressItems.forEach { progress ->
                add(progress.toContinueWatchingMetaPreview())
            }
            snapshot.nextUpItems.forEach { nextUp ->
                add(nextUp.toMetaPreview())
            }
        }
    }

    private fun WatchProgress.toContinueWatchingMetaPreview(): MetaPreview {
        val isMovie = contentType.equals("movie", ignoreCase = true)
        val artwork = backdrop ?: poster
        return MetaPreview(
            id = contentId,
            type = if (isMovie) ContentType.MOVIE else ContentType.SERIES,
            rawType = contentType,
            name = name,
            poster = artwork,
            posterShape = if (!backdrop.isNullOrBlank()) PosterShape.LANDSCAPE else PosterShape.POSTER,
            background = backdrop ?: poster,
            logo = logo,
            description = episodeTitle,
            releaseInfo = buildString {
                append("Resume")
                val percent = ((progressPercent ?: progressPercentage * 100f).toInt()).coerceIn(0, 100)
                append(" • ")
                append(percent)
                append('%')
            },
            imdbRating = null,
            genres = emptyList()
        )
    }

    private fun com.nexio.tv.data.repository.TraktProgressService.CalendarShowEntry.toMetaPreview(): MetaPreview {
        val episodeSuffix = buildString {
            append("S")
            append(season)
            append("E")
            append(episode)
            if (!episodeTitle.isNullOrBlank()) {
                append(" ")
                append(episodeTitle)
            }
        }
        return MetaPreview(
            id = contentId,
            type = ContentType.SERIES,
            rawType = contentType,
            name = "$name • $episodeSuffix",
            poster = poster ?: backdrop,
            posterShape = PosterShape.LANDSCAPE,
            background = backdrop ?: poster,
            logo = logo,
            description = episodeTitle,
            releaseInfo = firstAired,
            imdbRating = null,
            genres = emptyList()
        )
    }

    private fun TraktCustomListCatalog.toCatalogRow(): CatalogRow {
        return buildSyntheticRow(
            addonId = TRAKT_RAIL_ADDON_ID,
            addonName = TRAKT_RAIL_ADDON_NAME,
            addonBaseUrl = TRAKT_RAIL_ADDON_BASE_URL,
            catalogId = catalogId,
            catalogName = catalogName,
            type = type,
            items = items
        )
    }

    private fun MDBListCustomCatalog.toCatalogRow(): CatalogRow {
        return buildSyntheticRow(
            addonId = MDBLIST_RAIL_ADDON_ID,
            addonName = MDBLIST_RAIL_ADDON_NAME,
            addonBaseUrl = MDBLIST_RAIL_ADDON_BASE_URL,
            catalogId = catalogId,
            catalogName = catalogName,
            type = type,
            items = items
        )
    }

    private fun CatalogDescriptor.isSearchOnlyCatalog(): Boolean {
        return extra.any { entry -> entry.name.equals("search", ignoreCase = true) && entry.isRequired }
    }

    private fun String.displayLabel(): String {
        return replaceFirstChar { ch ->
            if (ch.isLowerCase()) ch.titlecase() else ch.toString()
        }
    }

    private fun catalogGlobalKey(addonId: String, type: String, catalogId: String): String {
        return "${addonId}_${type}_${catalogId}"
    }
}
