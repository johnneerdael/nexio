package com.nexio.tv.data.repository

import android.util.Log
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

internal data class ScreensaverCatalogRequest(
    val addonId: String,
    val addonName: String,
    val addonBaseUrl: String,
    val catalogId: String,
    val catalogName: String,
    val type: String
)

@Singleton
class IdleScreensaverRepository @Inject constructor(
    private val addonRepository: AddonRepository,
    private val catalogRepository: CatalogRepository
) {
    private val refreshMutex = Mutex()
    private val _slides = MutableStateFlow<List<IdleScreensaverSlide>>(emptyList())
    val slides = _slides.asStateFlow()

    suspend fun refreshOnColdBoot() {
        refreshMutex.withLock {
            val addons = addonRepository.getInstalledAddons().first()
            val movieRequest = findStockCinemetaPopularCatalogRequest(addons, ContentType.MOVIE)
            val seriesRequest = findStockCinemetaPopularCatalogRequest(addons, ContentType.SERIES)

            val rows = coroutineScope {
                listOfNotNull(movieRequest, seriesRequest)
                    .map { request ->
                        async { fetchScreensaverCatalog(request) }
                    }
                    .mapNotNull { deferred -> deferred.await() }
            }

            val preparedSlides = buildIdleScreensaverSlides(rows)
            _slides.value = preparedSlides
            Log.d(TAG, "Prepared ${preparedSlides.size} idle screensaver slides")
        }
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

internal fun buildIdleScreensaverSlides(rows: List<CatalogRow>): List<IdleScreensaverSlide> {
    return rows
        .flatMap { row ->
            row.items
                .take(SCREENSAVER_CATALOG_LIMIT)
                .mapNotNull { item -> item.toIdleScreensaverSlide(row.addonBaseUrl) }
        }
        .distinctBy { "${it.itemType}:${it.itemId}" }
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
        imdbRating = imdbRating
    )
}
