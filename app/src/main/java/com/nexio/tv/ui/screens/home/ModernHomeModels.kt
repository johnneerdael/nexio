package com.nexio.tv.ui.screens.home

import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.Stable
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.nexio.tv.domain.model.CatalogRow
import com.nexio.tv.domain.model.HomeDisplayMetadata
import com.nexio.tv.domain.model.MetaPreview
import com.nexio.tv.domain.model.toHomeDisplayMetadata

internal val YEAR_REGEX = Regex("""\b(19|20)\d{2}\b""")
internal const val MODERN_HERO_TEXT_WIDTH_FRACTION = 0.42f
internal const val MODERN_HERO_BACKDROP_HEIGHT_FRACTION = 0.62f
internal const val MODERN_TRAILER_OVERSCAN_ZOOM = 1.35f
internal const val MODERN_HERO_FOCUS_DEBOUNCE_MS = 90L
internal val MODERN_ROW_HEADER_FOCUS_INSET = 40.dp
internal val MODERN_LANDSCAPE_LOGO_GRADIENT = Brush.verticalGradient(
    colorStops = arrayOf(
        0.0f to Color.Transparent,
        0.58f to Color.Transparent,
        1.0f to Color.Black.copy(alpha = 0.75f)
    )
)

@Immutable
internal data class HeroPreview(
    val title: String,
    val logo: String?,
    val description: String?,
    val contentTypeText: String?,
    val yearText: String?,
    val imdbText: String?,
    val genres: List<String>,
    val poster: String?,
    val backdrop: String?,
    val imageUrl: String?
)

@Immutable
internal sealed class ModernPayload {
    data class ContinueWatching(val item: ContinueWatchingItem) : ModernPayload()
    data class Catalog(
        val focusKey: String,
        val itemId: String,
        val itemType: String,
        val addonBaseUrl: String,
        val trailerTitle: String,
        val trailerReleaseInfo: String?,
        val trailerApiType: String
    ) : ModernPayload()
}

@Immutable
internal data class FocusedCatalogSelection(
    val rowKey: String,
    val focusKey: String,
    val payload: ModernPayload.Catalog
)

@Immutable
internal data class ModernCarouselItem(
    val key: String,
    val title: String,
    val subtitle: String?,
    val imageUrl: String?,
    val heroPreview: HeroPreview,
    val payload: ModernPayload,
    val metaPreview: MetaPreview? = null
)

@Immutable
internal data class HeroCarouselRow(
    val key: String,
    val title: String,
    val globalRowIndex: Int,
    val items: List<ModernCarouselItem>,
    val catalogId: String? = null,
    val addonId: String? = null,
    val apiType: String? = null,
    val supportsSkip: Boolean = false,
    val hasMore: Boolean = false,
    val isLoading: Boolean = false
)

@Immutable
internal data class CarouselRowLookups(
    val rowIndexByKey: Map<String, Int>,
    val rowByKey: Map<String, HeroCarouselRow>,
    val activeRowKeys: Set<String>,
    val activeItemKeysByRow: Map<String, Set<String>>,
    val activeCatalogItemIds: Set<String>
)

@Immutable
internal data class ModernHomeContentState(
    val catalogRows: List<CatalogRow> = emptyList(),
    val continueWatchingItems: List<ContinueWatchingItem> = emptyList(),
    val modernLandscapePostersEnabled: Boolean = false,
    val catalogTypeSuffixEnabled: Boolean = true,
    val focusedPosterBackdropExpandEnabled: Boolean = false,
    val focusedPosterBackdropExpandDelaySeconds: Int = 3,
    val posterCardWidthDp: Int = 126,
    val posterCardHeightDp: Int = 189,
    val posterCardCornerRadiusDp: Int = 12,
    val posterLabelsEnabled: Boolean = true
)

internal data class ModernCatalogRowBuildCacheEntry(
    val source: CatalogRow,
    val useLandscapePosters: Boolean,
    val showCatalogTypeSuffix: Boolean,
    val mappedRow: HeroCarouselRow
)

@Stable
internal class ModernHomeUiCaches {
    val focusedItemByRow = mutableMapOf<String, Int>()
    val itemFocusRequesters = mutableMapOf<String, MutableMap<String, FocusRequester>>()
    val rowListStates = mutableMapOf<String, LazyListState>()
    val loadMoreRequestedTotals = mutableMapOf<String, Int>()

    fun requesterFor(rowKey: String, itemKey: String): FocusRequester {
        val byIndex = itemFocusRequesters.getOrPut(rowKey) { mutableMapOf() }
        return byIndex.getOrPut(itemKey) { FocusRequester() }
    }
}

@Stable
internal class ModernCarouselRowBuildCache {
    var continueWatchingItems: List<ContinueWatchingItem> = emptyList()
    var continueWatchingTitle: String = ""
    var continueWatchingAirsDateTemplate: String = ""
    var continueWatchingUpcomingLabel: String = ""
    var continueWatchingUseLandscapePosters: Boolean = false
    var continueWatchingRow: HeroCarouselRow? = null
    val catalogRows = mutableMapOf<String, ModernCatalogRowBuildCacheEntry>()
    val catalogItemCache = mutableMapOf<String, MutableMap<String, CachedCarouselItem>>()
}

internal data class CachedCarouselItem(
    val source: MetaPreview,
    val useLandscapePosters: Boolean,
    val carouselItem: ModernCarouselItem
)


internal fun buildContinueWatchingItem(
    item: ContinueWatchingItem,
    useLandscapePosters: Boolean,
    airsDateTemplate: String,
    upcomingLabel: String
): ModernCarouselItem {
    val displayMetadata = item.displayMetadata()
    val heroPreview = when (item) {
        is ContinueWatchingItem.InProgress -> {
            val isSeries = isSeriesType(item.progress.contentType)
            val episodeCode = item.progress.episodeDisplayString
            val episodeTitle = item.progress.episodeTitle?.takeIf { it.isNotBlank() }
            val episodeLabel = when {
                isSeries && episodeCode != null && episodeTitle != null -> "$episodeCode · $episodeTitle"
                isSeries && episodeCode != null -> episodeCode
                isSeries && episodeTitle != null -> episodeTitle
                else -> item.progress.contentType.replaceFirstChar { ch -> ch.uppercase() }
            }
            HeroPreview(
                title = displayMetadata.title ?: item.progress.name,
                logo = displayMetadata.logo ?: item.progress.logo,
                description = item.episodeDescription
                    ?: displayMetadata.description
                    ?: item.progress.episodeTitle,
                contentTypeText = episodeLabel,
                yearText = extractYear(displayMetadata.releaseInfo ?: item.releaseInfo),
                imdbText = (item.episodeImdbRating ?: displayMetadata.imdbRating)
                    ?.let { String.format("%.1f", it) },
                genres = item.genres.ifEmpty { displayMetadata.genres },
                poster = displayMetadata.poster ?: item.progress.poster,
                backdrop = displayMetadata.backdrop ?: item.progress.backdrop,
                imageUrl = if (useLandscapePosters) {
                    firstNonBlank(
                        displayMetadata.backdrop,
                        displayMetadata.poster,
                        item.progress.backdrop,
                        item.progress.poster
                    )
                } else {
                    firstNonBlank(
                        displayMetadata.poster,
                        displayMetadata.backdrop,
                        item.progress.poster,
                        item.progress.backdrop
                    )
                }
            )
        }
        is ContinueWatchingItem.NextUp -> {
            val episodeCode = "S${item.info.season}E${item.info.episode}"
            val episodeTitle = item.info.episodeTitle?.takeIf { it.isNotBlank() }
            val episodeLabel = if (episodeTitle != null) "$episodeCode · $episodeTitle" else episodeCode
            HeroPreview(
                title = displayMetadata.title ?: item.info.name,
                logo = displayMetadata.logo ?: item.info.logo,
                description = item.info.episodeDescription
                    ?: displayMetadata.description
                    ?: item.info.episodeTitle
                    ?: item.info.airDateLabel?.let { airsDateTemplate.format(it) },
                contentTypeText = episodeLabel,
                yearText = extractYear(displayMetadata.releaseInfo ?: item.info.releaseInfo),
                imdbText = (item.info.imdbRating ?: displayMetadata.imdbRating)
                    ?.let { String.format("%.1f", it) },
                genres = item.info.genres.ifEmpty { displayMetadata.genres },
                poster = displayMetadata.poster ?: item.info.poster,
                backdrop = displayMetadata.backdrop ?: item.info.backdrop,
                imageUrl = if (useLandscapePosters) {
                    firstNonBlank(
                        displayMetadata.backdrop,
                        displayMetadata.poster,
                        item.info.backdrop,
                        item.info.poster,
                        item.info.thumbnail
                    )
                } else {
                    firstNonBlank(
                        displayMetadata.poster,
                        displayMetadata.backdrop,
                        item.info.poster,
                        item.info.backdrop,
                        item.info.thumbnail
                    )
                }
            )
        }
    }

    val imageUrl = when (item) {
        is ContinueWatchingItem.InProgress -> if (useLandscapePosters) {
            if (isSeriesType(item.progress.contentType)) {
                firstNonBlank(
                    item.episodeThumbnail,
                    displayMetadata.poster,
                    displayMetadata.backdrop,
                    item.progress.poster,
                    item.progress.backdrop
                )
            } else {
                firstNonBlank(
                    displayMetadata.backdrop,
                    displayMetadata.poster,
                    item.progress.backdrop,
                    item.progress.poster
                )
            }
        } else {
            if (isSeriesType(item.progress.contentType)) {
                firstNonBlank(
                    heroPreview.poster,
                    displayMetadata.poster,
                    displayMetadata.backdrop,
                    item.progress.poster,
                    item.progress.backdrop
                )
            } else {
                firstNonBlank(
                    displayMetadata.poster,
                    displayMetadata.backdrop,
                    item.progress.poster,
                    item.progress.backdrop
                )
            }
        }
        is ContinueWatchingItem.NextUp -> if (useLandscapePosters) {
            if (item.info.hasAired) {
                firstNonBlank(
                    item.info.thumbnail,
                    displayMetadata.poster,
                    displayMetadata.backdrop,
                    item.info.poster,
                    item.info.backdrop
                )
            } else {
                firstNonBlank(
                    displayMetadata.backdrop,
                    displayMetadata.poster,
                    item.info.backdrop,
                    item.info.poster,
                    item.info.thumbnail
                )
            }
        } else {
            firstNonBlank(
                displayMetadata.poster,
                displayMetadata.backdrop,
                item.info.poster,
                item.info.backdrop,
                item.info.thumbnail
            )
        }
    }

    return ModernCarouselItem(
        key = continueWatchingItemKey(item),
        title = when (item) {
            is ContinueWatchingItem.InProgress -> displayMetadata.title ?: item.progress.name
            is ContinueWatchingItem.NextUp -> displayMetadata.title ?: item.info.name
        },
        subtitle = when (item) {
            is ContinueWatchingItem.InProgress -> item.progress.episodeDisplayString ?: item.progress.episodeTitle
            is ContinueWatchingItem.NextUp -> {
                val code = "S${item.info.season}E${item.info.episode}"
                if (item.info.hasAired) {
                    code
                } else {
                    item.info.airDateLabel?.let { "$code • ${airsDateTemplate.format(it)}" } ?: "$code • $upcomingLabel"
                }
            }
        },
        imageUrl = imageUrl,
        heroPreview = heroPreview.copy(imageUrl = imageUrl ?: heroPreview.imageUrl),
        payload = ModernPayload.ContinueWatching(item)
    )
}

internal fun buildCatalogItem(
    item: MetaPreview,
    row: CatalogRow,
    useLandscapePosters: Boolean,
    occurrence: Int
): ModernCarouselItem {
    val displayMetadata = item.toHomeDisplayMetadata()
    val heroPreview = HeroPreview(
        title = displayMetadata.title ?: item.name,
        logo = displayMetadata.logo ?: item.logo,
        description = displayMetadata.description ?: item.description,
        contentTypeText = item.apiType.replaceFirstChar { ch -> ch.uppercase() },
        yearText = extractYear(displayMetadata.releaseInfo ?: item.releaseInfo),
        imdbText = (displayMetadata.imdbRating ?: item.imdbRating)?.let { String.format("%.1f", it) },
        genres = displayMetadata.genres.ifEmpty { item.genres }.take(3),
        poster = displayMetadata.poster ?: item.poster,
        backdrop = displayMetadata.backdrop ?: item.background,
        imageUrl = if (useLandscapePosters) {
            firstNonBlank(displayMetadata.backdrop, displayMetadata.poster, item.background, item.poster)
        } else {
            firstNonBlank(displayMetadata.poster, displayMetadata.backdrop, item.poster, item.background)
        }
    )

    return ModernCarouselItem(
        key = "catalog_${row.key()}_${item.id}_${occurrence}",
        title = displayMetadata.title ?: item.name,
        subtitle = displayMetadata.releaseInfo ?: item.releaseInfo,
        imageUrl = if (useLandscapePosters) {
            firstNonBlank(displayMetadata.backdrop, displayMetadata.poster, item.background, item.poster)
        } else {
            firstNonBlank(displayMetadata.poster, displayMetadata.backdrop, item.poster, item.background)
        },
        heroPreview = heroPreview,
        payload = ModernPayload.Catalog(
            focusKey = "${row.key()}::${item.id}",
            itemId = item.id,
            itemType = item.apiType,
            addonBaseUrl = row.addonBaseUrl,
            trailerTitle = displayMetadata.title ?: item.name,
            trailerReleaseInfo = displayMetadata.releaseInfo ?: item.releaseInfo,
            trailerApiType = item.apiType
        ),
        metaPreview = item
    )
}

internal fun ContinueWatchingItem.displayMetadata(): HomeDisplayMetadata {
    return when (this) {
        is ContinueWatchingItem.InProgress -> displayMetadata ?: HomeDisplayMetadata(
            title = progress.name,
            logo = progress.logo,
            description = episodeDescription ?: progress.episodeTitle,
            genres = genres,
            releaseInfo = releaseInfo,
            imdbRating = episodeImdbRating,
            poster = progress.poster,
            backdrop = progress.backdrop
        )

        is ContinueWatchingItem.NextUp -> info.displayMetadata ?: HomeDisplayMetadata(
            title = info.name,
            logo = info.logo,
            description = info.episodeDescription ?: info.episodeTitle,
            genres = info.genres,
            releaseInfo = info.releaseInfo ?: info.released,
            imdbRating = info.imdbRating,
            poster = info.poster ?: info.thumbnail,
            backdrop = info.backdrop ?: info.thumbnail
        )
    }
}

internal fun continueWatchingItemKey(item: ContinueWatchingItem): String {
    return when (item) {
        is ContinueWatchingItem.InProgress ->
            "cw_inprogress_${item.progress.contentId}_${item.progress.videoId}_${item.progress.season ?: -1}_${item.progress.episode ?: -1}"
        is ContinueWatchingItem.NextUp ->
            "cw_nextup_${item.info.contentId}_${item.info.videoId}_${item.info.season}_${item.info.episode}"
    }
}

internal fun catalogRowKey(row: CatalogRow): String {
    return "${row.addonId}_${row.apiType}_${row.catalogId}"
}

internal fun catalogRowTitle(
    row: CatalogRow,
    showCatalogTypeSuffix: Boolean,
    strTypeMovie: String = "",
    strTypeSeries: String = ""
): String {
    val catalogName = row.catalogName.replaceFirstChar { it.uppercase() }
    if (!showCatalogTypeSuffix) return catalogName
    val typeLabel = when (row.apiType.lowercase()) {
        "movie" -> strTypeMovie.ifBlank { row.apiType.replaceFirstChar { it.uppercase() } }
        "series" -> strTypeSeries.ifBlank { row.apiType.replaceFirstChar { it.uppercase() } }
        else -> row.apiType.replaceFirstChar { it.uppercase() }
    }
    return "$catalogName - $typeLabel"
}

internal fun CatalogRow.key(): String {
    return "${addonId}_${apiType}_${catalogId}"
}

internal fun isSeriesType(type: String?): Boolean {
    return type.equals("series", ignoreCase = true) || type.equals("tv", ignoreCase = true)
}

internal fun firstNonBlank(vararg candidates: String?): String? {
    return candidates.firstOrNull { !it.isNullOrBlank() }?.trim()
}

internal fun extractYear(releaseInfo: String?): String? {
    if (releaseInfo.isNullOrBlank()) return null
    return YEAR_REGEX.find(releaseInfo)?.value
}

internal fun ContinueWatchingItem.contentId(): String {
    return when (this) {
        is ContinueWatchingItem.InProgress -> progress.contentId
        is ContinueWatchingItem.NextUp -> info.contentId
    }
}

internal fun ContinueWatchingItem.contentType(): String {
    return when (this) {
        is ContinueWatchingItem.InProgress -> progress.contentType
        is ContinueWatchingItem.NextUp -> info.contentType
    }
}

internal fun ContinueWatchingItem.season(): Int? {
    return when (this) {
        is ContinueWatchingItem.InProgress -> progress.season
        is ContinueWatchingItem.NextUp -> info.season
    }
}

internal fun ContinueWatchingItem.episode(): Int? {
    return when (this) {
        is ContinueWatchingItem.InProgress -> progress.episode
        is ContinueWatchingItem.NextUp -> info.episode
    }
}
