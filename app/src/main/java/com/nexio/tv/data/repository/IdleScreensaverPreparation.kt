package com.nexio.tv.data.repository

import android.util.Log
import com.nexio.tv.core.metadata.router.MetadataDepth
import com.nexio.tv.core.metadata.router.MetadataRequest
import com.nexio.tv.core.metadata.router.MetadataResolutionResult
import com.nexio.tv.core.metadata.router.MetadataRouterFacade
import com.nexio.tv.core.metadata.router.MetadataSourceContext
import com.nexio.tv.domain.model.Meta
import com.nexio.tv.domain.model.MetaPreview
import com.nexio.tv.domain.model.PosterShape
import com.nexio.tv.domain.model.applyTo
import com.nexio.tv.domain.model.homeDisplayItemKey
import com.nexio.tv.domain.model.mergeFallback
import com.nexio.tv.domain.model.toHomeDisplayMetadata
import com.nexio.tv.ui.screensaver.IdleScreensaverImageModeData
import com.nexio.tv.ui.screensaver.IdleScreensaverModeData
import com.nexio.tv.ui.screensaver.IdleScreensaverSlide
import com.nexio.tv.ui.screensaver.IdleScreensaverTrailerModeData
import com.nexio.tv.ui.screensaver.IdleTrailerScreensaverCandidate
import kotlinx.coroutines.CancellationException

private const val TAG_SCREENSAVER_PREP = "IdleScreensaverPrep"

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
    metadataRouterFacade: MetadataRouterFacade
): Meta? {
    val request = MetadataRequest(
        contentId = preview.id,
        contentType = preview.type,
        sourceContext = MetadataSourceContext(
            itemType = preview.apiType,
            addonMetadata = preview.toHomeDisplayMetadata()
        ),
        depth = MetadataDepth.DETAIL_CORE
    )
    val canonical = try {
        metadataRouterFacade.resolveRequest(request)
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        Log.w(TAG_SCREENSAVER_PREP, "fetchIdleScreensaverMeta resolveRequest failed for ${preview.id}: ${e.message}", e)
        return null
    }
    if (canonical.route == null) return null
    return canonical.toScreensaverMeta(preview)
}

/**
 * Converts a [MetadataResolutionResult] to the [Meta] shape consumed by
 * [mergeHydratedScreensaverMetadata]. Only the fields that shape needs are populated
 * (title/poster/backdrop/logo/description/genres/releaseInfo/runtime/imdbRating/ratingSource/
 * tomatoesRating/trailerYtIds); enrichment fields (cast, videos, etc.) are left empty.
 */
private fun MetadataResolutionResult.toScreensaverMeta(preview: MetaPreview): Meta {
    val doc = resolvedDocument
    val display = displayMetadata
    return Meta(
        id = doc.canonicalId ?: preview.id,
        type = preview.type,
        rawType = preview.apiType,
        name = doc.title ?: display.title ?: preview.name,
        poster = doc.poster ?: display.poster,
        posterShape = PosterShape.POSTER,
        background = doc.backdrop ?: display.backdrop,
        logo = doc.logo ?: display.logo,
        description = doc.overview ?: display.description,
        releaseInfo = display.releaseInfo,
        imdbRating = (doc.rating as? Number)?.toFloat() ?: display.imdbRating,
        ratingSource = display.ratingSource,
        genres = display.genres,
        runtime = doc.runtimeMinutes?.let { "${it}m" } ?: display.runtime,
        director = emptyList(),
        cast = emptyList(),
        videos = emptyList(),
        country = null,
        awards = null,
        language = null,
        links = emptyList(),
        trailerYtIds = emptyList(),
        posterProviderTag = display.posterProviderTag
    )
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
