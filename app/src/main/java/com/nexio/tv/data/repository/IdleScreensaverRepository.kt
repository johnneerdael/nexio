package com.nexio.tv.data.repository

import android.util.Log
import com.nexio.tv.data.local.TraktAuthDataStore
import com.nexio.tv.data.local.TraktCatalogIds
import com.nexio.tv.data.local.TraktCatalogPreferences
import com.nexio.tv.data.local.TraktDiscoverySnapshotStore
import com.nexio.tv.data.local.TraktSettingsDataStore
import com.nexio.tv.domain.model.Addon
import com.nexio.tv.domain.model.CatalogDescriptor
import com.nexio.tv.domain.model.CatalogRow
import com.nexio.tv.domain.model.ContentType
import com.nexio.tv.domain.model.MetaPreview
import com.nexio.tv.domain.repository.AddonRepository
import com.nexio.tv.domain.repository.CatalogRepository
import com.nexio.tv.ui.screensaver.IdleScreensaverSlide
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

private const val TAG = "IdleScreensaverRepo"
private const val CINEMETA_BASE_URL = "https://v3-cinemeta.strem.io"
private const val POPULAR_CATALOG_NAME = "Popular"
private const val POPULAR_MOVIE_CATALOG_NAME = "Popular - Movie"
private const val POPULAR_SERIES_CATALOG_NAME = "Popular - Series"
private const val SCREENSAVER_CATALOG_LIMIT = 5
private const val TRAKT_SCREENSAVER_CATALOG_LIMIT = 10
private const val TRAKT_RAIL_ADDON_ID = "trakt"
private const val TRAKT_RAIL_ADDON_NAME = "Trakt"
private const val TRAKT_RAIL_ADDON_BASE_URL = "https://api.trakt.tv"
private const val TRAKT_ROW_NAME_TRENDING_MOVIES = "Trakt Trending Movies"
private const val TRAKT_ROW_NAME_TRENDING_SHOWS = "Trakt Trending Shows"

internal data class ScreensaverCatalogRequest(
    val addonId: String,
    val addonName: String,
    val addonBaseUrl: String,
    val catalogId: String,
    val catalogName: String,
    val type: String
)

private data class ScreensaverRowSelection(
    val rows: List<CatalogRow>,
    val itemsPerRowLimit: Int
)

@Singleton
class IdleScreensaverRepository @Inject constructor(
    private val addonRepository: AddonRepository,
    private val catalogRepository: CatalogRepository,
    private val mdbListRepository: MDBListRepository,
    private val traktAuthDataStore: TraktAuthDataStore,
    private val traktSettingsDataStore: TraktSettingsDataStore,
    private val traktDiscoverySnapshotStore: TraktDiscoverySnapshotStore
) {
    private val refreshMutex = Mutex()
    private val _slides = MutableStateFlow<List<IdleScreensaverSlide>>(emptyList())
    val slides = _slides.asStateFlow()

    suspend fun refreshOnColdBoot() {
        refreshMutex.withLock {
            val selection = selectScreensaverRows()

            val preparedSlides = buildIdleScreensaverSlides(
                rows = selection.rows,
                itemsPerRowLimit = selection.itemsPerRowLimit
            ) { preview ->
                mdbListRepository.enrichPreview(preview)
            }
            _slides.value = preparedSlides
            Log.d(TAG, "Prepared ${preparedSlides.size} idle screensaver slides")
        }
    }

    private suspend fun selectScreensaverRows(): ScreensaverRowSelection {
        val traktPrefs = traktSettingsDataStore.catalogPreferences.first()
        val traktSnapshot = traktDiscoverySnapshotStore.read()
        val traktAuthenticated = traktAuthDataStore.isAuthenticated.first()
        if (shouldUseTraktScreensaverSource(traktAuthenticated, traktPrefs, traktSnapshot)) {
            return ScreensaverRowSelection(
                rows = buildTraktScreensaverRows(requireNotNull(traktSnapshot)),
                itemsPerRowLimit = TRAKT_SCREENSAVER_CATALOG_LIMIT
            )
        }

        val addons = addonRepository.getInstalledAddons().first()
        val movieRequest = findStockCinemetaPopularCatalogRequest(addons, ContentType.MOVIE)
        val seriesRequest = findStockCinemetaPopularCatalogRequest(addons, ContentType.SERIES)
        val rows = coroutineScope {
            listOfNotNull(movieRequest, seriesRequest)
                .map { request -> async { fetchScreensaverCatalog(request) } }
                .mapNotNull { deferred -> deferred.await() }
        }
        return ScreensaverRowSelection(rows = rows, itemsPerRowLimit = SCREENSAVER_CATALOG_LIMIT)
    }

    private suspend fun fetchScreensaverCatalog(request: ScreensaverCatalogRequest): CatalogRow? {
        return runCatching {
            catalogRepository.refreshCatalogToDisk(
                addonBaseUrl = request.addonBaseUrl,
                addonId = request.addonId,
                addonName = request.addonName,
                catalogId = request.catalogId,
                catalogName = request.catalogName,
                type = request.type
            ).getOrNull()
        }.onFailure { error ->
            Log.w(
                TAG,
                "Failed to refresh screensaver catalog " +
                    "addonId=${request.addonId} catalogId=${request.catalogId} type=${request.type}",
                error
            )
        }.getOrNull()
    }
}

internal fun findStockCinemetaPopularCatalogRequest(
    addons: List<Addon>,
    type: ContentType
): ScreensaverCatalogRequest? {
    val addon = addons.firstOrNull { addon ->
        addon.baseUrl.trimEnd('/').equals(CINEMETA_BASE_URL, ignoreCase = true)
    } ?: return null
    val expectedType = type.toApiString()
    val catalog = addon.catalogs.firstOrNull { descriptor ->
        descriptor.apiType.equals(expectedType, ignoreCase = true) &&
            descriptor.isScreensaverPopularCatalog()
    } ?: return null

    return ScreensaverCatalogRequest(
        addonId = addon.id,
        addonName = addon.displayName,
        addonBaseUrl = addon.baseUrl,
        catalogId = catalog.id,
        catalogName = catalog.name,
        type = catalog.apiType
    )
}

internal suspend fun buildIdleScreensaverSlides(
    rows: List<CatalogRow>,
    itemsPerRowLimit: Int = SCREENSAVER_CATALOG_LIMIT,
    enrichPreview: suspend (MetaPreview) -> MetaPreview = { it }
): List<IdleScreensaverSlide> {
    return rows
        .flatMap { row ->
            row.items
                .take(itemsPerRowLimit)
                .map { item -> item to row.addonBaseUrl }
        }
        .map { (item, addonBaseUrl) ->
            enrichPreview(item).toIdleScreensaverSlide(addonBaseUrl)
        }
        .filterNotNull()
        .distinctBy { "${it.itemType}:${it.itemId}" }
}

internal fun shouldUseTraktScreensaverSource(
    traktAuthenticated: Boolean,
    prefs: TraktCatalogPreferences,
    snapshot: TraktDiscoverySnapshot?
): Boolean {
    if (!traktAuthenticated) return false
    val trendingEnabled = TraktCatalogIds.TRENDING_MOVIES in prefs.enabledCatalogs &&
        TraktCatalogIds.TRENDING_SHOWS in prefs.enabledCatalogs
    if (!trendingEnabled) return false
    val currentSnapshot = snapshot ?: return false
    return currentSnapshot.trendingMovieItems.isNotEmpty() && currentSnapshot.trendingShowItems.isNotEmpty()
}

internal fun buildTraktScreensaverRows(snapshot: TraktDiscoverySnapshot): List<CatalogRow> {
    val rows = mutableListOf<CatalogRow>()
    if (snapshot.trendingMovieItems.isNotEmpty()) {
        rows += CatalogRow(
            addonId = TRAKT_RAIL_ADDON_ID,
            addonName = TRAKT_RAIL_ADDON_NAME,
            addonBaseUrl = TRAKT_RAIL_ADDON_BASE_URL,
            catalogId = TraktCatalogIds.TRENDING_MOVIES,
            catalogName = TRAKT_ROW_NAME_TRENDING_MOVIES,
            type = ContentType.MOVIE,
            items = snapshot.trendingMovieItems.take(TRAKT_SCREENSAVER_CATALOG_LIMIT)
        )
    }
    if (snapshot.trendingShowItems.isNotEmpty()) {
        rows += CatalogRow(
            addonId = TRAKT_RAIL_ADDON_ID,
            addonName = TRAKT_RAIL_ADDON_NAME,
            addonBaseUrl = TRAKT_RAIL_ADDON_BASE_URL,
            catalogId = TraktCatalogIds.TRENDING_SHOWS,
            catalogName = TRAKT_ROW_NAME_TRENDING_SHOWS,
            type = ContentType.SERIES,
            items = snapshot.trendingShowItems.take(TRAKT_SCREENSAVER_CATALOG_LIMIT)
        )
    }
    return rows
}

private fun CatalogDescriptor.isScreensaverPopularCatalog(): Boolean {
    return name.equals(POPULAR_CATALOG_NAME, ignoreCase = true) ||
        name.equals(POPULAR_MOVIE_CATALOG_NAME, ignoreCase = true) ||
        name.equals(POPULAR_SERIES_CATALOG_NAME, ignoreCase = true)
}

private fun MetaPreview.toIdleScreensaverSlide(addonBaseUrl: String): IdleScreensaverSlide? {
    val backgroundUrl = background?.takeIf { it.isNotBlank() }
        ?: poster?.takeIf { it.isNotBlank() }
        ?: return null
    return IdleScreensaverSlide(
        itemId = id,
        itemType = apiType,
        addonBaseUrl = addonBaseUrl,
        title = name,
        backgroundUrl = backgroundUrl,
        logoUrl = logo?.takeIf { it.isNotBlank() },
        genres = genres,
        description = description?.takeIf { it.isNotBlank() },
        releaseInfo = releaseInfo?.takeIf { it.isNotBlank() },
        runtime = runtime?.takeIf { it.isNotBlank() },
        imdbRating = imdbRating,
        tomatoesRating = tomatoesRating
    )
}
