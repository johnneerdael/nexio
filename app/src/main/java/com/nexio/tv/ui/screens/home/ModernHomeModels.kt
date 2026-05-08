package com.nexio.tv.ui.screens.home

import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.Stable
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.nexio.tv.domain.model.CatalogRow
import com.nexio.tv.domain.model.ContentType
import com.nexio.tv.domain.model.FocusedPosterTrailerPlaybackTarget
import com.nexio.tv.domain.model.HomeDisplayMetadata
import com.nexio.tv.domain.model.MetaPreview
import com.nexio.tv.domain.model.PosterShape
import com.nexio.tv.domain.model.ProviderIds
import com.nexio.tv.domain.model.RatingDisplayFormatter
import com.nexio.tv.domain.model.TitleRatingSource
import com.nexio.tv.domain.model.orDefault
import com.nexio.tv.domain.model.toArtworkBundleFromDisplayFields

internal val YEAR_REGEX = Regex("""\b(19|20)\d{2}\b""")
internal const val MODERN_HERO_TEXT_WIDTH_FRACTION = 0.42f
internal const val MODERN_HERO_BACKDROP_HEIGHT_FRACTION = 0.62f
internal const val MODERN_TRAILER_OVERSCAN_ZOOM = 1.35f
internal const val MODERN_HERO_FOCUS_DEBOUNCE_MS = 90L
internal const val MODERN_LOADING_PLACEHOLDER_COUNT = 8
internal const val MODERN_LOADING_PLACEHOLDER_TEST_TAG = "modern_loading_placeholder"
internal val MODERN_ROW_HEADER_FOCUS_INSET = 40.dp
internal val MODERN_LANDSCAPE_LOGO_GRADIENT = Brush.verticalGradient(
    colorStops = arrayOf(
        0.0f to Color.Transparent,
        0.58f to Color.Transparent,
        1.0f to Color.Black.copy(alpha = 0.75f)
    )
)

@Immutable
data class HeroPreview(
    val title: String,
    val logo: String?,
    val description: String?,
    val contentTypeText: String?,
    val yearText: String?,
    val imdbText: String?,
    val ratingSource: TitleRatingSource? = TitleRatingSource.IMDB,
    val tomatoesText: String?,
    val genres: List<String>,
    val poster: String?,
    val backdrop: String?,
    val imageUrl: String?,
    val frozenBackdropUrl: String? = null,
    val frozenLogoUrl: String? = null
)

@Immutable
sealed class ModernPayload {
    data class ContinueWatching(val item: ContinueWatchingItem) : ModernPayload()
    data class Catalog(
        val focusKey: String,
        val itemId: String,
        val itemType: String,
        val addonBaseUrl: String,
        val trailerTitle: String,
        val trailerReleaseInfo: String?,
        val trailerApiType: String,
        val fallbackTrailerYtId: String? = null
    ) : ModernPayload()
}

@Immutable
internal data class FocusedCatalogSelection(
    val rowKey: String,
    val focusKey: String,
    val payload: ModernPayload.Catalog
)

@Immutable
internal data class FocusedTrailerSelection(
    val rowKey: String,
    val focusKey: String,
    val itemId: String,
    val itemType: String,
    val trailerTitle: String,
    val trailerReleaseInfo: String?,
    val trailerApiType: String,
    val fallbackTrailerYtId: String? = null
)

@Immutable
data class ModernCarouselItem(
    val key: String,
    val title: String,
    val subtitle: String?,
    val imageUrl: String?,
    val heroPreview: HeroPreview,
    val payload: ModernPayload,
    val metaPreview: MetaPreview? = null
)

@Immutable
data class HeroCarouselRow(
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
data class CarouselRowLookups(
    val rowIndexByKey: Map<String, Int>,
    val rowByKey: Map<String, HeroCarouselRow>,
    val activeRowKeys: Set<String>,
    val activeItemKeysByRow: Map<String, Set<String>>,
    val activeCatalogItemIds: Set<String>
)

@Immutable
internal data class ModernHomePresentationInput(
    val catalogRows: List<CatalogRow>,
    val continueWatchingItems: List<ContinueWatchingItem>,
    val useLandscapePosters: Boolean,
    val showCatalogTypeSuffix: Boolean,
    val continueWatchingTitle: String,
    val airsDateTemplate: String,
    val upcomingLabel: String
)

@Immutable
data class ModernHomePresentationState(
    val rows: List<HeroCarouselRow> = emptyList(),
    val lookups: CarouselRowLookups = buildCarouselRowLookups(emptyList())
)

internal fun buildCarouselRowLookups(carouselRows: List<HeroCarouselRow>): CarouselRowLookups {
    val rowIndexByKey = LinkedHashMap<String, Int>(carouselRows.size)
    val rowByKey = LinkedHashMap<String, HeroCarouselRow>(carouselRows.size)
    val activeRowKeys = LinkedHashSet<String>(carouselRows.size)
    val activeItemKeysByRow = LinkedHashMap<String, Set<String>>(carouselRows.size)
    val activeCatalogItemIds = LinkedHashSet<String>()

    carouselRows.forEachIndexed { index, row ->
        rowIndexByKey[row.key] = index
        rowByKey[row.key] = row
        activeRowKeys += row.key

        val itemKeys = LinkedHashSet<String>(row.items.size)
        row.items.forEach { item ->
            itemKeys += item.key
            val payload = item.payload
            if (payload is ModernPayload.Catalog) {
                activeCatalogItemIds += payload.itemId
            }
        }
        activeItemKeysByRow[row.key] = itemKeys
    }

    return CarouselRowLookups(
        rowIndexByKey = rowIndexByKey,
        rowByKey = rowByKey,
        activeRowKeys = activeRowKeys,
        activeItemKeysByRow = activeItemKeysByRow,
        activeCatalogItemIds = activeCatalogItemIds
    )
}

@Immutable
internal data class ModernHomeContentState(
    val catalogRows: List<CatalogRow> = emptyList(),
    val continueWatchingItems: List<ContinueWatchingItem> = emptyList(),
    val modernHomePresentation: ModernHomePresentationState = ModernHomePresentationState(),
    val deterministicAutoplayEnabled: Boolean = false,
    val modernLandscapePostersEnabled: Boolean = false,
    val catalogTypeSuffixEnabled: Boolean = true,
    val focusedPosterBackdropExpandEnabled: Boolean = false,
    val focusedPosterBackdropExpandDelaySeconds: Int = 3,
    val focusedPosterBackdropTrailerEnabled: Boolean = false,
    val focusedPosterBackdropTrailerMuted: Boolean = true,
    val focusedPosterBackdropTrailerPlaybackTarget: FocusedPosterTrailerPlaybackTarget =
        FocusedPosterTrailerPlaybackTarget.HERO_MEDIA,
    val homeTrailerAutoplayEnabled: Boolean = true,
    val homeTrailerAutoplayDelaySeconds: Int = 7,
    val posterCardWidthDp: Int = 126,
    val posterCardHeightDp: Int = 189,
    val posterCardCornerRadiusDp: Int = 12,
    val posterLabelsEnabled: Boolean = true,
    val trailerPreviewUrls: Map<String, String> = emptyMap(),
    val trailerPreviewAudioUrls: Map<String, String> = emptyMap(),
    val trailerPreviewUserAgents: Map<String, String> = emptyMap(),
    val trailerPreviewExternalUrls: Map<String, String> = emptyMap(),
    val trailerPreviewLoadingIds: Set<String> = emptySet(),
    val trailerPreviewNegativeCacheIds: Set<String> = emptySet(),
    val trailerMetadataAvailableKeys: Set<String> = emptySet()
)

internal data class ModernCatalogRowBuildCacheEntry(
    val source: CatalogRow,
    val useLandscapePosters: Boolean,
    val showCatalogTypeSuffix: Boolean,
    val mappedRow: HeroCarouselRow
)

internal fun modernHomeRowContentType(row: HeroCarouselRow): String = row.apiType ?: "modern_home_row"

internal fun modernRowItemContentType(item: ModernCarouselItem): String {
    return when (val payload = item.payload) {
        is ModernPayload.ContinueWatching -> "modern_cw_card"
        is ModernPayload.Catalog -> payload.itemType
    }
}

internal fun shouldPrefetchModernRow(
    isActiveRow: Boolean,
    isVerticalRowsScrolling: Boolean
): Boolean = isActiveRow && !isVerticalRowsScrolling

internal fun modernVisibleCatalogRows(rows: List<CatalogRow>): List<CatalogRow> {
    val visibleRowsByKey = LinkedHashMap<String, CatalogRow>()
    rows.forEach { row ->
        if (row.items.isEmpty() && !row.isLoading) return@forEach
        val key = catalogRowKey(row)
        val existing = visibleRowsByKey[key]
        if (existing == null || (existing.isLoading && row.items.isNotEmpty())) {
            visibleRowsByKey[key] = row
        }
    }
    return visibleRowsByKey.values.toList()
}

internal fun modernLoadingPlaceholderCount(): Int = MODERN_LOADING_PLACEHOLDER_COUNT

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

internal fun heroPreviewContentKey(item: ModernCarouselItem?): String? {
    val currentItem = item ?: return null
    val preview = currentItem.heroPreview
    return buildString {
        append(currentItem.key)
        append('|')
        append(preview.title)
        append('|')
        append(preview.logo)
        append('|')
        append(preview.description)
        append('|')
        append(preview.contentTypeText)
        append('|')
        append(preview.yearText)
        append('|')
        append(preview.imdbText)
        append('|')
        append(preview.tomatoesText)
        append('|')
        append(preview.genres.joinToString(","))
        append('|')
        append(preview.poster)
        append('|')
        append(preview.backdrop)
        append('|')
        append(preview.imageUrl)
    }
}

internal fun resolveActiveHeroPreview(
    row: HeroCarouselRow?,
    itemIndex: Int
): HeroPreview? {
    val currentRow = row ?: return null
    return currentRow.items.getOrNull(itemIndex)?.heroPreview ?: currentRow.items.firstOrNull()?.heroPreview
}

internal fun resolveDisplayedHeroPreview(
    displayedHeroItemKey: String?,
    activeHeroItemKey: String?,
    displayedHeroPreview: HeroPreview?,
    liveActiveHeroPreview: HeroPreview?
): HeroPreview? {
    return if (displayedHeroItemKey != null && displayedHeroItemKey == activeHeroItemKey) {
        liveActiveHeroPreview ?: displayedHeroPreview
    } else {
        displayedHeroPreview ?: liveActiveHeroPreview
    }
}

internal fun resolveFocusedTrailerSelection(
    rowKey: String?,
    item: ModernCarouselItem?
): FocusedTrailerSelection? {
    val resolvedRowKey = rowKey ?: return null
    val resolvedItem = item ?: return null
    return when (val payload = resolvedItem.payload) {
        is ModernPayload.Catalog -> FocusedTrailerSelection(
            rowKey = resolvedRowKey,
            focusKey = payload.focusKey,
            itemId = payload.itemId,
            itemType = payload.itemType,
            trailerTitle = payload.trailerTitle,
            trailerReleaseInfo = payload.trailerReleaseInfo,
            trailerApiType = payload.trailerApiType,
            fallbackTrailerYtId = payload.fallbackTrailerYtId
        )

        is ModernPayload.ContinueWatching -> FocusedTrailerSelection(
            rowKey = resolvedRowKey,
            focusKey = resolvedItem.key,
            itemId = payload.item.contentId(),
            itemType = payload.item.contentType(),
            trailerTitle = resolvedItem.heroPreview.title.ifBlank { resolvedItem.title },
            trailerReleaseInfo = resolvedItem.subtitle,
            trailerApiType = payload.item.contentType(),
            fallbackTrailerYtId = null
        )
    }
}

internal fun applyTomatoesToContinueWatchingItem(
    item: ContinueWatchingItem,
    tomatoesRating: Double
): ContinueWatchingItem {
    return when (item) {
        is ContinueWatchingItem.InProgress -> {
            item.copy(
                displayMetadata = item.displayMetadata().copy(tomatoesRating = tomatoesRating)
            )
        }
        is ContinueWatchingItem.NextUp -> {
            item.copy(
                info = item.info.copy(
                    displayMetadata = item.displayMetadata().copy(tomatoesRating = tomatoesRating)
                )
            )
        }
    }
}


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
                logo = displayMetadata.displayLogo,
                description = item.episodeDescription
                    ?: displayMetadata.description
                    ?: item.progress.episodeTitle,
                contentTypeText = episodeLabel,
                yearText = extractYear(displayMetadata.releaseInfo ?: item.releaseInfo),
                imdbText = (item.episodeImdbRating ?: displayMetadata.imdbRating)
                    ?.let { RatingDisplayFormatter.formatTitleRating(it) },
                ratingSource = if (item.episodeImdbRating != null) TitleRatingSource.IMDB else displayMetadata.ratingSource.orDefault(),
                tomatoesText = displayMetadata.tomatoesRating?.let(::formatPreviewTomatoesRating),
                genres = item.genres.ifEmpty { displayMetadata.genres },
                poster = displayMetadata.displayPoster,
                backdrop = displayMetadata.displayBackdrop,
                imageUrl = if (useLandscapePosters) {
                    firstNonBlank(
                        displayMetadata.displayBackdrop,
                        displayMetadata.displayPoster
                    )
                } else {
                    // Portrait poster cards must take their image only from poster sources
                    // (Task 3 step 5). Backdrop/logo are NOT valid fallbacks here.
                    displayMetadata.displayPoster
                }
            )
        }
        is ContinueWatchingItem.NextUp -> {
            val episodeCode = "S${item.info.season}E${item.info.episode}"
            val episodeTitle = item.info.episodeTitle?.takeIf { it.isNotBlank() }
            val episodeLabel = if (episodeTitle != null) "$episodeCode · $episodeTitle" else episodeCode
            HeroPreview(
                title = displayMetadata.title ?: item.info.name,
                logo = displayMetadata.displayLogo,
                description = item.info.episodeDescription
                    ?: displayMetadata.description
                    ?: item.info.episodeTitle
                    ?: item.info.airDateLabel?.let { airsDateTemplate.format(it) },
                contentTypeText = episodeLabel,
                yearText = extractYear(displayMetadata.releaseInfo ?: item.info.releaseInfo),
                imdbText = (item.info.imdbRating ?: displayMetadata.imdbRating)
                    ?.let { RatingDisplayFormatter.formatTitleRating(it) },
                ratingSource = if (item.info.imdbRating != null) TitleRatingSource.IMDB else displayMetadata.ratingSource.orDefault(),
                tomatoesText = displayMetadata.tomatoesRating?.let(::formatPreviewTomatoesRating),
                genres = item.info.genres.ifEmpty { displayMetadata.genres },
                poster = displayMetadata.displayPoster,
                backdrop = displayMetadata.displayBackdrop,
                imageUrl = if (useLandscapePosters) {
                    firstNonBlank(
                        displayMetadata.displayBackdrop,
                        displayMetadata.displayPoster,
                        item.info.thumbnail
                    )
                } else {
                    // Portrait poster cards: poster sources only. Episode thumbnails are also
                    // permitted because they are episode-specific portrait/landscape stills,
                    // but backdrop/logo are NOT valid fallbacks here.
                    firstNonBlank(
                        displayMetadata.displayPoster,
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
                    displayMetadata.displayPoster,
                    displayMetadata.displayBackdrop
                )
            } else {
                firstNonBlank(
                    displayMetadata.displayBackdrop,
                    displayMetadata.displayPoster
                )
            }
        } else {
            // Portrait poster card: poster sources only. Episode thumbnails are series-specific
            // portrait stills which are acceptable; backdrop/logo are NOT valid fallbacks.
            if (isSeriesType(item.progress.contentType)) {
                firstNonBlank(
                    heroPreview.poster,
                    displayMetadata.displayPoster,
                    item.episodeThumbnail
                )
            } else {
                displayMetadata.displayPoster
            }
        }
        is ContinueWatchingItem.NextUp -> if (useLandscapePosters) {
            if (item.info.hasAired) {
                firstNonBlank(
                    item.info.thumbnail,
                    displayMetadata.displayPoster,
                    displayMetadata.displayBackdrop
                )
            } else {
                firstNonBlank(
                    displayMetadata.displayBackdrop,
                    displayMetadata.displayPoster,
                    item.info.thumbnail
                )
            }
        } else {
            // Portrait poster card: poster sources only. Episode thumbnails are acceptable;
            // backdrop/logo are NOT valid fallbacks here.
            firstNonBlank(
                displayMetadata.displayPoster,
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
        payload = ModernPayload.ContinueWatching(item),
        metaPreview = when (item) {
            is ContinueWatchingItem.InProgress -> continueWatchingInProgressToMetaPreview(item)
            is ContinueWatchingItem.NextUp -> nextUpToMetaPreview(item)
        }
    )
}

internal fun continueWatchingInProgressToMetaPreview(item: ContinueWatchingItem.InProgress): MetaPreview {
    val displayMetadata = item.displayMetadata()
    val contentType = if (isSeriesType(item.progress.contentType)) ContentType.SERIES else ContentType.MOVIE
    return MetaPreview(
        id = item.progress.contentId,
        type = contentType,
        rawType = item.progress.contentType,
        name = displayMetadata.title ?: item.progress.name,
        poster = displayMetadata.displayPoster,
        posterShape = PosterShape.LANDSCAPE,
        background = displayMetadata.displayBackdrop,
        logo = displayMetadata.displayLogo,
        description = displayMetadata.description ?: item.progress.episodeTitle,
        releaseInfo = displayMetadata.releaseInfo ?: item.releaseInfo,
        imdbRating = item.episodeImdbRating ?: displayMetadata.imdbRating,
        tomatoesRating = displayMetadata.tomatoesRating,
        genres = item.genres.ifEmpty { displayMetadata.genres },
        posterProviderTag = displayMetadata.posterProviderTag,
        artwork = displayMetadata.toArtworkBundleFromDisplayFields(),
        firstPaintStableIds = providerIdsFromContinueWatchingContentId(item.progress.contentId)
    )
}

/**
 * Maps a Continue Watching content ID to a [ProviderIds] using only durable identifiers.
 *
 * Used by all three CW MetaPreview projection sites so first-paint cache lookups have stable
 * IDs available without going back through display metadata. Only honest mappings are emitted —
 * we never derive provider IDs from titles or release years.
 */
internal fun providerIdsFromContinueWatchingContentId(contentId: String): ProviderIds {
    val value = contentId.trim()
    if (value.isBlank()) return ProviderIds()
    return when {
        value.startsWith("tt", ignoreCase = true) -> ProviderIds(imdb = value)
        value.startsWith("imdb:", ignoreCase = true) ->
            ProviderIds(imdb = value.substringAfter(':').takeIf { it.isNotBlank() })
        value.startsWith("tvdb:", ignoreCase = true) ->
            ProviderIds(tvdb = value.substringAfter(':').takeIf { it.isNotBlank() })
        value.startsWith("tmdb:tv:", ignoreCase = true) ->
            ProviderIds(tmdb = value.substringAfter("tmdb:tv:").takeIf { it.isNotBlank() })
        value.startsWith("tmdb:", ignoreCase = true) ->
            ProviderIds(tmdb = value.substringAfter(':').takeIf { it.isNotBlank() })
        value.startsWith("trakt:", ignoreCase = true) ->
            ProviderIds(trakt = value.substringAfter(':').takeIf { it.isNotBlank() })
        else -> ProviderIds()
    }
}

internal fun buildCatalogItem(
    item: MetaPreview,
    row: CatalogRow,
    useLandscapePosters: Boolean,
    occurrence: Int,
    previousCachedItem: ModernCarouselItem? = null,
    selectedTrailerFallbackYtId: String? = null
): ModernCarouselItem {
    val displayMetadata = item.toFirstPaintHomeDisplayMetadata()
    val frozenBackdrop = previousCachedItem?.heroPreview?.frozenBackdropUrl?.takeIf { it.isNotBlank() }
        ?: displayMetadata.displayBackdrop
    val frozenLogo = previousCachedItem?.heroPreview?.frozenLogoUrl?.takeIf { it.isNotBlank() }
        ?: displayMetadata.displayLogo
    val heroPreview = HeroPreview(
        title = displayMetadata.title ?: item.name,
        logo = displayMetadata.displayLogo,
        description = displayMetadata.description ?: item.description,
        contentTypeText = item.apiType.replaceFirstChar { ch -> ch.uppercase() },
        yearText = extractYear(displayMetadata.releaseInfo ?: item.releaseInfo),
        imdbText = (displayMetadata.imdbRating ?: item.imdbRating)?.let { RatingDisplayFormatter.formatTitleRating(it) },
        ratingSource = if (displayMetadata.imdbRating != null) displayMetadata.ratingSource.orDefault() else item.ratingSource.orDefault(),
        tomatoesText = (displayMetadata.tomatoesRating ?: item.tomatoesRating)?.let(::formatPreviewTomatoesRating),
        genres = displayMetadata.genres.ifEmpty { item.genres }.take(3),
        poster = displayMetadata.displayPoster,
        backdrop = displayMetadata.displayBackdrop,
        imageUrl = if (useLandscapePosters) {
            firstNonBlank(displayMetadata.displayBackdrop, displayMetadata.displayPoster)
        } else {
            firstNonBlank(displayMetadata.displayPoster, displayMetadata.displayBackdrop)
        },
        frozenBackdropUrl = frozenBackdrop,
        frozenLogoUrl = frozenLogo
    )

    return ModernCarouselItem(
        key = "catalog_${row.key()}_${item.id}_${occurrence}",
        title = displayMetadata.title ?: item.name,
        subtitle = displayMetadata.releaseInfo ?: item.releaseInfo,
        imageUrl = if (useLandscapePosters) {
            firstNonBlank(displayMetadata.displayBackdrop, displayMetadata.displayPoster)
        } else {
            firstNonBlank(displayMetadata.displayPoster, displayMetadata.displayBackdrop)
        },
        heroPreview = heroPreview,
        payload = ModernPayload.Catalog(
            focusKey = "${row.key()}::${item.id}",
            itemId = item.id,
            itemType = item.apiType,
            addonBaseUrl = row.addonBaseUrl,
            trailerTitle = displayMetadata.title ?: item.name,
            trailerReleaseInfo = displayMetadata.releaseInfo ?: item.releaseInfo,
            trailerApiType = item.apiType,
            fallbackTrailerYtId = selectedTrailerFallbackYtId
        ),
        metaPreview = item
    )
}

internal fun ContinueWatchingItem.displayMetadata(): HomeDisplayMetadata {
    return when (this) {
        is ContinueWatchingItem.InProgress -> displayMetadata ?: HomeDisplayMetadata(
            title = progress.name,
            logo = progress.displayLogo,
            description = episodeDescription ?: progress.episodeTitle,
            genres = genres,
            releaseInfo = releaseInfo,
            imdbRating = episodeImdbRating,
            tomatoesRating = displayMetadata?.tomatoesRating,
            poster = progress.displayPoster,
            backdrop = progress.displayBackdrop
        )

        is ContinueWatchingItem.NextUp -> info.displayMetadata ?: HomeDisplayMetadata(
            title = info.name,
            logo = info.displayLogo,
            description = info.episodeDescription ?: info.episodeTitle,
            genres = info.genres,
            releaseInfo = info.releaseInfo ?: info.released,
            imdbRating = info.imdbRating,
            tomatoesRating = info.displayMetadata?.tomatoesRating,
            poster = info.displayPoster ?: info.displayThumbnail,
            backdrop = info.displayBackdrop ?: info.displayThumbnail,
            thumbnail = info.displayThumbnail
        )
    }
}

internal fun continueWatchingItemKey(item: ContinueWatchingItem): String {
    return when (item) {
        is ContinueWatchingItem.InProgress ->
            item.canonicalKey
                ?: "cw_inprogress_${item.progress.contentId}_${item.progress.videoId}_${item.progress.season ?: -1}_${item.progress.episode ?: -1}"
        is ContinueWatchingItem.NextUp ->
            item.info.canonicalKey
                ?: "cw_nextup_${item.info.contentId}_${item.info.videoId}_${item.info.season}_${item.info.episode}"
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

private fun formatPreviewTomatoesRating(rating: Double): String =
    RatingDisplayFormatter.formatPercentRating(rating)

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

internal fun ContinueWatchingItem.canonicalOrContentKey(): String {
    return when (this) {
        is ContinueWatchingItem.InProgress -> canonicalKey ?: progress.contentId
        is ContinueWatchingItem.NextUp -> info.canonicalKey ?: info.contentId
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
