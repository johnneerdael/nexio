package com.nexio.tv.ui.screens.home

import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.Stable
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.nexio.tv.core.artwork.toLegacyArtworkString
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
import com.nexio.tv.ui.components.ContinueWatchingResolvedDisplayItem

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
    data class ContinueWatching(val item: ContinueWatchingResolvedDisplayItem) : ModernPayload()
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
    /**
     * Authority lookup key for surface-level `Map<String, MetaPreview>` plumbing.
     *
     * Catalog items use [homeDisplayItemKey] of (apiType, id). Continue Watching
     * items use the CW resolved item's own itemKey (a different keyspace —
     * `metaByItemKey` lookups for CW will return null, which is fine because the
     * CW render path doesn't consult MetaPreview anyway).
     */
    val itemKey: String,
    val title: String,
    val subtitle: String?,
    val imageUrl: String?,
    val heroPreview: HeroPreview,
    val payload: ModernPayload
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
    val resolvedRailRows: List<ResolvedRailRow>,
    val continueWatchingItems: List<ContinueWatchingResolvedDisplayItem>,
    val useLandscapePosters: Boolean,
    val showCatalogTypeSuffix: Boolean,
    val continueWatchingTitle: String,
    val airsDateTemplate: String,
    val upcomingLabel: String
)

@Immutable
data class ModernHomePresentationState(
    val rows: List<HeroCarouselRow> = emptyList(),
    val lookups: CarouselRowLookups = buildCarouselRowLookups(emptyList()),
    /**
     * Catalog-row MetaPreview lookup keyed by [ModernCarouselItem.itemKey]
     * (`homeDisplayItemKey(apiType, id)`). Built once per presentation rebuild
     * in [buildModernHomePresentation]; the carousel render path consults this
     * map at the call site instead of storing a per-item MetaPreview field on
     * ModernCarouselItem — eliminating ~430 retained MetaPreview references
     * through the carousel chain.
     *
     * Continue Watching items use a different keyspace (the CW resolved
     * itemKey); their renderer doesn't consult MetaPreview, so misses against
     * this map are expected and harmless.
     */
    val metaByItemKey: Map<String, MetaPreview> = emptyMap()
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
    val continueWatchingItems: List<ContinueWatchingResolvedDisplayItem> = emptyList(),
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
    val trailerPreviewSigningClientKeys: Map<String, String> = emptyMap(),
    val trailerPreviewCaptions: Map<String, List<com.nexio.tv.data.trailer.YouTubeCaptionTrack>> = emptyMap(),
    val trailerPreviewExternalUrls: Map<String, String> = emptyMap(),
    val trailerPreviewLoadingIds: Set<String> = emptySet(),
    val trailerPreviewNegativeCacheIds: Set<String> = emptySet(),
    val trailerMetadataAvailableKeys: Set<String> = emptySet()
)

internal data class ModernCatalogRowBuildCacheEntry(
    val source: CatalogRow,
    val resolvedRail: ResolvedRailRow,
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
    var continueWatchingItems: List<ContinueWatchingResolvedDisplayItem> = emptyList()
    var continueWatchingTitle: String = ""
    var continueWatchingAirsDateTemplate: String = ""
    var continueWatchingUpcomingLabel: String = ""
    var continueWatchingUseLandscapePosters: Boolean = false
    var continueWatchingRow: HeroCarouselRow? = null
    val catalogRows = mutableMapOf<String, ModernCatalogRowBuildCacheEntry>()
    val catalogItemCache = mutableMapOf<String, MutableMap<String, CachedCarouselItem>>()
}

internal data class CachedCarouselItem(
    val resolvedSource: ModernHomeRowItem,
    val metaSource: MetaPreview?,
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

        is ModernPayload.ContinueWatching -> {
            val legacy = payload.item.toContinueWatchingItem()
            FocusedTrailerSelection(
                rowKey = resolvedRowKey,
                focusKey = resolvedItem.key,
                itemId = legacy.contentId(),
                itemType = legacy.contentType(),
                trailerTitle = resolvedItem.heroPreview.title.ifBlank { resolvedItem.title },
                trailerReleaseInfo = resolvedItem.subtitle,
                trailerApiType = legacy.contentType(),
                fallbackTrailerYtId = null
            )
        }
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
    resolved: ContinueWatchingResolvedDisplayItem,
    useLandscapePosters: Boolean,
    airsDateTemplate: String,
    upcomingLabel: String
): ModernCarouselItem {
    // The resolved projection drives both the rendered CW row card AND the
    // Modern carousel's HERO PREVIEW (this function). poster/backdrop/logo/title
    // read from the typed `resolved.posterRef` / `backdropRef` / `logoRef` /
    // `title` slots — rule #1 compliant. Other display fields (description,
    // releaseInfo, imdbRating, tomatoesRating, genres) still come from the
    // legacy `displayMetadata` chain on the embedded ContinueWatchingItem;
    // migrating those is Phase 3 (catalog pipeline restructure) of
    // docs/superpowers/specs/2026-05-10-home-metapreview-elimination-design.md.
    val item = resolved.toContinueWatchingItem()
    // Phase 2A migration: poster/backdrop/logo/title now read from the typed
    // projection (rule #1) instead of the legacy displayMetadata chain. Other
    // fields (description, releaseInfo, imdbRating, tomatoesRating, genres)
    // stay on displayMetadata for now — those typed equivalents land in Phase 3
    // (catalog pipeline restructure).
    val resolvedPoster: String? = resolved.posterRef.toLegacyArtworkString()
    val resolvedBackdrop: String? = resolved.backdropRef.toLegacyArtworkString()
    val resolvedLogo: String? = resolved.logoRef.toLegacyArtworkString()
    val resolvedTitle: String? = resolved.title
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
                title = resolvedTitle ?: item.progress.name,
                logo = resolvedLogo,
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
                poster = resolvedPoster,
                backdrop = resolvedBackdrop,
                imageUrl = if (useLandscapePosters) {
                    firstNonBlank(
                        resolvedBackdrop,
                        resolvedPoster
                    )
                } else {
                    // Portrait poster cards must take their image only from poster sources
                    // (Task 3 step 5). Backdrop/logo are NOT valid fallbacks here.
                    resolvedPoster
                }
            )
        }
        is ContinueWatchingItem.NextUp -> {
            val episodeCode = "S${item.info.season}E${item.info.episode}"
            val episodeTitle = item.info.episodeTitle?.takeIf { it.isNotBlank() }
            val episodeLabel = if (episodeTitle != null) "$episodeCode · $episodeTitle" else episodeCode
            HeroPreview(
                title = resolvedTitle ?: item.info.name,
                logo = resolvedLogo,
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
                poster = resolvedPoster,
                backdrop = resolvedBackdrop,
                imageUrl = if (useLandscapePosters) {
                    firstNonBlank(
                        resolvedBackdrop,
                        resolvedPoster,
                        item.info.thumbnail
                    )
                } else {
                    // Portrait poster cards: poster sources only. Episode thumbnails are also
                    // permitted because they are episode-specific portrait/landscape stills,
                    // but backdrop/logo are NOT valid fallbacks here.
                    firstNonBlank(
                        resolvedPoster,
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
                    resolvedPoster,
                    resolvedBackdrop
                )
            } else {
                firstNonBlank(
                    resolvedBackdrop,
                    resolvedPoster
                )
            }
        } else {
            // Portrait poster card: poster sources only. Episode thumbnails are series-specific
            // portrait stills which are acceptable; backdrop/logo are NOT valid fallbacks.
            if (isSeriesType(item.progress.contentType)) {
                firstNonBlank(
                    heroPreview.poster,
                    resolvedPoster,
                    item.episodeThumbnail
                )
            } else {
                resolvedPoster
            }
        }
        is ContinueWatchingItem.NextUp -> if (useLandscapePosters) {
            if (item.info.hasAired) {
                firstNonBlank(
                    item.info.thumbnail,
                    resolvedPoster,
                    resolvedBackdrop
                )
            } else {
                firstNonBlank(
                    resolvedBackdrop,
                    resolvedPoster,
                    item.info.thumbnail
                )
            }
        } else {
            // Portrait poster card: poster sources only. Episode thumbnails are acceptable;
            // backdrop/logo are NOT valid fallbacks here.
            firstNonBlank(
                resolvedPoster,
                item.info.thumbnail
            )
        }
    }

    return ModernCarouselItem(
        key = continueWatchingItemKey(item),
        itemKey = resolved.itemKey,
        title = when (item) {
            is ContinueWatchingItem.InProgress -> resolvedTitle ?: item.progress.name
            is ContinueWatchingItem.NextUp -> resolvedTitle ?: item.info.name
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
        payload = ModernPayload.ContinueWatching(resolved)
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
    // Normalize to lowercase so the `substringAfter` extractions below are unambiguous
    // regardless of whether upstream content IDs arrive uppercased (e.g., "TMDB:TV:76479").
    // Without normalization, a case-insensitive `startsWith` match combined with a
    // case-sensitive `substringAfter` literal would silently return the entire string.
    val value = contentId.trim().lowercase()
    if (value.isBlank()) return ProviderIds()
    return when {
        value.startsWith("tt") -> ProviderIds(imdb = value)
        value.startsWith("imdb:") ->
            ProviderIds(imdb = value.substringAfter(':').takeIf { it.isNotBlank() })
        value.startsWith("tvdb:") ->
            ProviderIds(tvdb = value.substringAfter(':').takeIf { it.isNotBlank() })
        value.startsWith("tmdb:tv:") ->
            ProviderIds(tmdb = value.substringAfter("tmdb:tv:").takeIf { it.isNotBlank() })
        value.startsWith("tmdb:") ->
            ProviderIds(tmdb = value.substringAfter(':').takeIf { it.isNotBlank() })
        value.startsWith("trakt:") ->
            ProviderIds(trakt = value.substringAfter(':').takeIf { it.isNotBlank() })
        else -> ProviderIds()
    }
}

internal fun buildCatalogItem(
    resolved: ModernHomeRowItem,
    metaPreview: MetaPreview?,
    row: CatalogRow,
    useLandscapePosters: Boolean,
    occurrence: Int,
    previousCachedItem: ModernCarouselItem? = null,
    selectedTrailerFallbackYtId: String? = null
): ModernCarouselItem {
    // Resolved authority drives artwork + title + year + rating. MetaPreview supplies
    // description/genres/release info/etc. that the resolved projection does not yet
    // expose, plus the legacy `metaPreview` callback payload on ModernCarouselItem.
    val item: MetaPreview? = metaPreview
    // Architecture pin (RailPreviewLifecycleArchitectureTest): the canonical Home tile
    // boundary must emit first-paint tracing via item.toFirstPaintHomeDisplayMetadata().
    // The if/else below keeps that exact substring in real executable code (not just a
    // comment) so the architecture test is enforced by the call site, not documentation.
    // When the parallel MetaPreview is missing (transient race during resolved/catalog
    // skew), we fall back to an empty HomeDisplayMetadata so callbacks/legacy fields keep
    // working while resolved data drives the visible rendering.
    val displayMetadata: HomeDisplayMetadata = if (item != null) {
        item.toFirstPaintHomeDisplayMetadata()
    } else {
        HomeDisplayMetadata()
    }

    val resolvedPosterUrl = resolved.posterRef?.toLegacyArtworkString()
    val resolvedBackdropUrl = resolved.backdropRef?.toLegacyArtworkString()
    val resolvedLogoUrl = resolved.logoRef?.toLegacyArtworkString()

    val frozenBackdrop = previousCachedItem?.heroPreview?.frozenBackdropUrl?.takeIf { it.isNotBlank() }
        ?: resolvedBackdropUrl
    val frozenLogo = previousCachedItem?.heroPreview?.frozenLogoUrl?.takeIf { it.isNotBlank() }
        ?: resolvedLogoUrl

    val resolvedTitle = resolved.title ?: item?.name ?: ""
    val resolvedYearText = resolved.year?.toString() ?: extractYear(displayMetadata.releaseInfo ?: item?.releaseInfo)
    val resolvedRatingValue = resolved.rating?.value
    // Only surface a ratingSource when there is an actual rating value to show; otherwise
    // the UI would render an IMDB chip without a number.
    val resolvedRatingSource: TitleRatingSource? = if (resolvedRatingValue != null) {
        resolved.rating?.source ?: TitleRatingSource.IMDB
    } else {
        null
    }
    val imdbText = resolvedRatingValue?.let { RatingDisplayFormatter.formatTitleRating(it) }

    // Description / genres / tomatoes / contentTypeText still come from MetaPreview
    // (Plan B Task 4 minimum scope: ResolvedDisplayItem does not yet expose these via
    // ModernHomeRowItem). When MetaPreview is unavailable, fall back to the empty values.
    val description = resolved.description ?: displayMetadata.description ?: item?.description
    val contentTypeText = (item?.apiType ?: row.apiType).replaceFirstChar { ch -> ch.uppercase() }
    val tomatoesText = (resolved.tomatoesRating ?: displayMetadata.tomatoesRating ?: item?.tomatoesRating)
        ?.let(::formatPreviewTomatoesRating)
    val genres = resolved.genres.ifEmpty { displayMetadata.genres.ifEmpty { item?.genres ?: emptyList() } }.take(3)

    val heroImageUrl = if (useLandscapePosters) {
        firstNonBlank(resolvedBackdropUrl, resolvedPosterUrl)
    } else {
        // Portrait poster cards must NEVER fall back to backdrop/logo. The resolved
        // authority guarantees the poster slot is the correct portrait artwork; if it's
        // absent there's no valid replacement.
        resolvedPosterUrl
    }

    val heroPreview = HeroPreview(
        title = resolvedTitle,
        logo = resolvedLogoUrl,
        description = description,
        contentTypeText = contentTypeText,
        yearText = resolvedYearText,
        imdbText = imdbText,
        ratingSource = resolvedRatingSource,
        tomatoesText = tomatoesText,
        genres = genres,
        poster = resolvedPosterUrl,
        backdrop = resolvedBackdropUrl,
        imageUrl = heroImageUrl,
        frozenBackdropUrl = frozenBackdrop,
        frozenLogoUrl = frozenLogo
    )

    val itemId = item?.id ?: resolved.contentId
    val itemType = item?.apiType ?: row.apiType
    val trailerTitle = resolvedTitle
    val trailerReleaseInfo = resolved.releaseInfo ?: displayMetadata.releaseInfo ?: item?.releaseInfo

    return ModernCarouselItem(
        key = "catalog_${row.key()}_${itemId}_${occurrence}",
        itemKey = com.nexio.tv.domain.model.homeDisplayItemKey(itemType, itemId),
        title = resolvedTitle,
        subtitle = trailerReleaseInfo,
        imageUrl = heroImageUrl,
        heroPreview = heroPreview,
        payload = ModernPayload.Catalog(
            focusKey = "${row.key()}::${itemId}",
            itemId = itemId,
            itemType = itemType,
            addonBaseUrl = row.addonBaseUrl,
            trailerTitle = trailerTitle,
            trailerReleaseInfo = trailerReleaseInfo,
            trailerApiType = itemType,
            fallbackTrailerYtId = selectedTrailerFallbackYtId
        )
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
