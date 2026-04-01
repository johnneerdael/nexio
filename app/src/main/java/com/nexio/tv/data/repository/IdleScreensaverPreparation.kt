package com.nexio.tv.data.repository

import com.nexio.tv.core.network.NetworkResult
import com.nexio.tv.domain.model.Meta
import com.nexio.tv.domain.model.MetaPreview
import com.nexio.tv.domain.model.applyTo
import com.nexio.tv.domain.model.homeDisplayItemKey
import com.nexio.tv.domain.model.mergeFallback
import com.nexio.tv.domain.model.toHomeDisplayMetadata
import com.nexio.tv.ui.screensaver.IdleScreensaverImageModeData
import com.nexio.tv.ui.screensaver.IdleScreensaverModeData
import com.nexio.tv.ui.screensaver.IdleScreensaverSlide
import com.nexio.tv.ui.screensaver.IdleScreensaverTrailerModeData
import com.nexio.tv.ui.screensaver.IdleTrailerScreensaverCandidate
import kotlinx.coroutines.flow.first

internal data class PreparedIdleScreensaverItem(
    val preview: MetaPreview,
    val fallbackArtworkUrls: List<String>,
    val trailerYtIds: List<String>
)

internal suspend fun prepareIdleScreensaverItems(
    rows: List<com.nexio.tv.domain.model.CatalogRow>,
    itemsPerRowLimit: Int,
    hydrateMeta: suspend (MetaPreview) -> Meta?,
    enrichPreview: suspend (MetaPreview) -> MetaPreview
): Map<String, PreparedIdleScreensaverItem> {
    return rows
        .flatMap { row -> row.items.take(itemsPerRowLimit) }
        .distinctBy { preview -> homeDisplayItemKey(preview.apiType, preview.id) }
        .associate { preview ->
            val hydratedMeta = hydrateMeta(preview)
            val mergedPreview = mergeHydratedScreensaverMetadata(
                currentItem = preview,
                externalMeta = hydratedMeta
            )
            val enrichedPreview = enrichPreview(mergedPreview)
            val fallbackArtworkUrls = buildIdleScreensaverFallbackArtworkUrls(enrichedPreview)
            val trailerYtIds = hydratedMeta
                ?.trailerYtIds
                .orEmpty()
                .mapNotNull { it.trim().takeIf(String::isNotEmpty) }
                .distinct()
                .takeIf {
                    it.isNotEmpty() &&
                        hasUsableIdleScreensaverDisplayData(
                            title = enrichedPreview.name,
                            fallbackArtworkUrls = fallbackArtworkUrls
                        )
                }
                .orEmpty()

            homeDisplayItemKey(preview.apiType, preview.id) to PreparedIdleScreensaverItem(
                preview = enrichedPreview,
                fallbackArtworkUrls = fallbackArtworkUrls,
                trailerYtIds = trailerYtIds
            )
        }
}

internal fun buildIdleScreensaverFallbackArtworkUrls(preview: MetaPreview): List<String> {
    return listOf(preview.background, preview.poster)
        .mapNotNull { it?.trim()?.takeIf(String::isNotEmpty) }
        .distinct()
}

internal fun mergeHydratedScreensaverMetadata(
    currentItem: MetaPreview,
    externalMeta: Meta?
): MetaPreview {
    val mergedMetadata = (externalMeta?.toHomeDisplayMetadata() ?: currentItem.toHomeDisplayMetadata())
        .mergeFallback(currentItem.toHomeDisplayMetadata())
    return mergedMetadata.applyTo(currentItem)
}

internal fun hasUsableIdleScreensaverDisplayData(
    title: String,
    fallbackArtworkUrls: List<String>
): Boolean {
    return title.isNotBlank() && fallbackArtworkUrls.isNotEmpty()
}

internal suspend fun fetchIdleScreensaverMeta(
    preview: MetaPreview,
    metaRepository: com.nexio.tv.domain.repository.MetaRepository
): Meta? {
    val result = runCatching {
        metaRepository.getMetaFromAllAddons(
            type = preview.apiType,
            id = preview.id,
            cacheOnDisk = true,
            origin = "idle_screensaver"
        ).first { networkResult -> networkResult !is NetworkResult.Loading }
    }.getOrNull()
    return (result as? NetworkResult.Success<*>)?.data as? Meta
}

internal suspend fun buildIdleScreensaverSlides(
    rows: List<com.nexio.tv.domain.model.CatalogRow>,
    itemsPerRowLimit: Int = IMAGE_SCREENSAVER_CATALOG_LIMIT,
    enrichPreview: suspend (MetaPreview) -> MetaPreview = { it }
): List<IdleScreensaverSlide> {
    return rows
        .flatMap { row ->
            row.items
                .take(itemsPerRowLimit)
                .map { item -> item to row.addonBaseUrl }
        }
        .map { (item, addonBaseUrl) ->
            buildPreparedIdleScreensaverItem(enrichPreview(item)).toIdleScreensaverSlide(addonBaseUrl)
        }
        .filterNotNull()
        .distinctBy { "${it.itemType}:${it.itemId}" }
}

internal fun buildIdleTrailerScreensaverCandidates(
    rows: List<com.nexio.tv.domain.model.CatalogRow>,
    itemsPerRowLimit: Int,
    preparedItemsByKey: Map<String, PreparedIdleScreensaverItem>
): List<IdleTrailerScreensaverCandidate> {
    return rows
        .flatMap { row ->
            row.items
                .take(itemsPerRowLimit)
                .mapNotNull { item ->
                    preparedItemsByKey[homeDisplayItemKey(item.apiType, item.id)]?.toIdleTrailerScreensaverCandidate(
                        addonBaseUrl = row.addonBaseUrl
                    )
                }
        }
        .distinctBy { "${it.itemType}:${it.itemId}" }
}

internal fun buildIdleScreensaverSlides(
    rows: List<com.nexio.tv.domain.model.CatalogRow>,
    itemsPerRowLimit: Int,
    preparedItemsByKey: Map<String, PreparedIdleScreensaverItem>
): List<IdleScreensaverSlide> {
    return rows
        .flatMap { row ->
            row.items
                .take(itemsPerRowLimit)
                .mapNotNull { item ->
                    preparedItemsByKey[homeDisplayItemKey(item.apiType, item.id)]?.toIdleScreensaverSlide(row.addonBaseUrl)
                }
        }
        .distinctBy { "${it.itemType}:${it.itemId}" }
}

internal fun buildPreparedIdleScreensaverItem(preview: MetaPreview): PreparedIdleScreensaverItem {
    return PreparedIdleScreensaverItem(
        preview = preview,
        fallbackArtworkUrls = buildIdleScreensaverFallbackArtworkUrls(preview),
        trailerYtIds = preview.trailerYtIds
            .mapNotNull { it.trim().takeIf(String::isNotEmpty) }
            .distinct()
            .takeIf {
                it.isNotEmpty() &&
                    hasUsableIdleScreensaverDisplayData(
                        title = preview.name,
                        fallbackArtworkUrls = buildIdleScreensaverFallbackArtworkUrls(preview)
                    )
            }
            .orEmpty()
    )
}

private fun PreparedIdleScreensaverItem.toIdleScreensaverSlide(addonBaseUrl: String): IdleScreensaverSlide? {
    val backgroundUrl = fallbackArtworkUrls.firstOrNull() ?: return null
    return IdleScreensaverSlide(
        itemId = preview.id,
        itemType = preview.apiType,
        addonBaseUrl = addonBaseUrl,
        title = preview.name,
        backgroundUrl = backgroundUrl,
        logoUrl = preview.logo?.takeIf { it.isNotBlank() },
        genres = preview.genres,
        description = preview.description?.takeIf { it.isNotBlank() },
        releaseInfo = preview.releaseInfo?.takeIf { it.isNotBlank() },
        runtime = preview.runtime?.takeIf { it.isNotBlank() },
        imdbRating = preview.imdbRating,
        tomatoesRating = preview.tomatoesRating,
        modeData = IdleScreensaverModeData(
            image = IdleScreensaverImageModeData(fallbackArtworkUrls = fallbackArtworkUrls),
            trailer = trailerYtIds
                .takeIf { it.isNotEmpty() }
                ?.let { IdleScreensaverTrailerModeData(trailerYtIds = it) }
            )
    )
}

private fun PreparedIdleScreensaverItem.toIdleTrailerScreensaverCandidate(
    addonBaseUrl: String
): IdleTrailerScreensaverCandidate? {
    val backgroundUrl = fallbackArtworkUrls.firstOrNull() ?: return null
    if (trailerYtIds.isEmpty()) return null
    return IdleTrailerScreensaverCandidate(
        itemId = preview.id,
        itemType = preview.apiType,
        addonBaseUrl = addonBaseUrl,
        title = preview.name,
        logoUrl = preview.logo?.takeIf { it.isNotBlank() },
        backgroundUrl = backgroundUrl,
        fallbackArtworkUrls = fallbackArtworkUrls,
        genres = preview.genres,
        description = preview.description?.takeIf { it.isNotBlank() },
        releaseInfo = preview.releaseInfo?.takeIf { it.isNotBlank() },
        runtime = preview.runtime?.takeIf { it.isNotBlank() },
        imdbRating = preview.imdbRating,
        tomatoesRating = preview.tomatoesRating,
        trailerYtIds = trailerYtIds
    )
}
