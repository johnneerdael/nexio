package com.nexio.tv.domain.model

data class RailItemPreview(
    val railId: String,
    val railSource: RailSource,
    val sourceProvider: ProviderId?,
    val sourceItemId: String,
    val itemType: ContentType,
    val stableIds: ProviderIds,
    val display: RailDisplaySeed,
    val ranking: RailRankingMetadata? = null,
    val sourcePayloadQuality: SourcePayloadQuality,
    val sourcePayloadHash: String,
    val generatedAtMs: Long,
    val hydrationState: RailHydrationState = RailHydrationState.PREVIEW_ONLY
)

enum class RailSource {
    ADDON_CATALOG,
    BUILT_IN_TRAKT,
    BUILT_IN_MDBLIST,
    BUILT_IN_TMDB,
    BUILT_IN_KITSU,
    BUILT_IN_SIMKL_DISCOVERY
}

enum class ProviderId {
    ADDON,
    TRAKT,
    MDBLIST,
    TMDB,
    TVDB,
    KITSU,
    SIMKL,
    IMDB
}

data class ProviderIds(
    val imdb: String? = null,
    val tmdb: String? = null,
    val tvdb: String? = null,
    val trakt: String? = null,
    val simkl: String? = null,
    val kitsu: String? = null,
    val slug: String? = null,
    val mal: String? = null,
    val anilist: String? = null,
    val anidb: String? = null
) {
    fun bestStableItemKey(
        itemType: ContentType,
        sourceItemId: String,
        rawNamespace: String? = null
    ): String {
        val type = itemType.toApiString()
        return when {
            imdb != null -> "$type:imdb:$imdb"
            tmdb != null -> "$type:tmdb:$tmdb"
            tvdb != null -> "$type:tvdb:$tvdb"
            kitsu != null -> "$type:kitsu:$kitsu"
            trakt != null -> "$type:trakt:$trakt"
            simkl != null -> "$type:simkl:$simkl"
            else -> {
                val namespace = rawNamespace
                    ?.trim()
                    ?.takeIf { it.isNotEmpty() }
                    ?: "source-item"
                "$type:raw:$namespace:${sourceItemId.trim()}"
            }
        }
    }
}

data class RailDisplaySeed(
    val title: String? = null,
    val originalTitle: String? = null,
    val year: Int? = null,
    val releaseDate: String? = null,
    val overview: String? = null,
    val runtimeText: String? = null,
    val genres: List<String> = emptyList(),
    val posterUrl: String? = null,
    val posterShape: PosterShape? = null,
    val backdropUrl: String? = null,
    val logoUrl: String? = null,
    val rating: RatingSeed? = null,
    val ratingText: String? = null,
    val trailerHint: TrailerHint? = null,
    val tomatoesRating: Double? = null
)

data class RatingSeed(
    val provider: ProviderId,
    val value: Double,
    val votes: Int? = null
)

data class RejectedPreviewRating(
    val rawValue: Double,
    val rawField: String,
    val reason: String
)

data class PreviewRatingResolution(
    val rating: Float?,
    val source: ProviderId?,
    val rejected: RejectedPreviewRating?
)

sealed interface TrailerHint {
    data class YouTube(val videoId: String) : TrailerHint
}

data class RailRankingMetadata(
    val watchers: Int? = null,
    val rank: Int? = null,
    val collectedCount: Int? = null,
    val listLikes: Int? = null,
    val rawLabel: String? = null
)

enum class SourcePayloadQuality {
    ID_ONLY,
    SPARSE_IDENTITY,
    DISPLAY_BASIC,
    RICH_PREVIEW
}

enum class RailHydrationState {
    PREVIEW_ONLY,
    HYDRATING,
    CANONICAL_READY,
    HYDRATION_FAILED_USING_PREVIEW,
    STALE_CANONICAL_USING_PREVIEW_OVERLAY
}

fun RailItemPreview.toMetaPreview(): MetaPreview {
    val title = display.title
        ?: display.originalTitle
        ?: stableIds.imdb
        ?: stableIds.tmdb?.let { "TMDB $it" }
        ?: sourceItemId
    val previewRating = display.toPreviewRating(fallbackProvider = sourceProvider)
    val ratingSource = previewRating.source.toTitleRatingSource()

    return MetaPreview(
        id = bestSupportedRoutingId(),
        type = itemType,
        rawType = itemType.toApiString(),
        name = title,
        poster = display.posterUrl,
        posterShape = display.posterShape ?: PosterShape.POSTER,
        background = display.backdropUrl,
        logo = display.logoUrl,
        description = display.overview,
        releaseInfo = display.year?.toString() ?: display.releaseDate?.take(4),
        runtime = display.runtimeText,
        imdbRating = previewRating.rating,
        ratingSource = ratingSource,
        tomatoesRating = display.tomatoesRating,
        genres = display.genres,
        trailerYtIds = when (val hint = display.trailerHint) {
            is TrailerHint.YouTube -> listOf(hint.videoId)
            null -> emptyList()
        },
        posterProviderTag = sourceProvider?.name?.lowercase(),
        firstPaintSource = FirstPaintSource.RAIL_PREVIEW,
        firstPaintSourceProvider = sourceProvider,
        firstPaintStableIds = stableIds,
        firstPaintRailSource = railSource,
        firstPaintSourceItemId = sourceItemId
    )
}

fun RailDisplaySeed.toPreviewRating(fallbackProvider: ProviderId? = null): PreviewRatingResolution {
    val rawFromText = ratingText?.trim()?.takeIf { it.isNotEmpty() }?.toDoubleOrNull()
    val raw = rawFromText ?: rating?.value
    val rawField = when {
        rawFromText != null -> "ratingText"
        rating != null -> "rating.value"
        else -> null
    }

    if (raw == null || rawField == null) {
        return PreviewRatingResolution(rating = null, source = null, rejected = null)
    }

    val source = (rating?.provider ?: fallbackProvider).takeIf { it.isTrustedTitleRatingProvider() }

    if (source == null) {
        return PreviewRatingResolution(
            rating = null,
            source = null,
            rejected = RejectedPreviewRating(
                rawValue = raw,
                rawField = rawField,
                reason = "MISSING_RATING_SOURCE"
            )
        )
    }

    return if (RatingValueValidator.validTitleRating(raw)) {
        PreviewRatingResolution(
            rating = raw.toFloat(),
            source = source,
            rejected = null
        )
    } else {
        PreviewRatingResolution(
            rating = null,
            source = null,
            rejected = RejectedPreviewRating(
                rawValue = raw,
                rawField = rawField,
                reason = "OUT_OF_RANGE_TITLE_RATING"
            )
        )
    }
}

fun RailItemPreview.bestSupportedRoutingId(): String = when {
    stableIds.kitsu != null && prefersKitsuRouting() -> "kitsu:${stableIds.kitsu}"
    itemType == ContentType.MOVIE && stableIds.tmdb != null -> "tmdb:${stableIds.tmdb}"
    itemType == ContentType.SERIES && stableIds.tvdb != null -> "tvdb:${stableIds.tvdb}"
    itemType == ContentType.TV && stableIds.tvdb != null -> "tvdb:${stableIds.tvdb}"
    else -> sourceItemId
}

private fun RailItemPreview.prefersKitsuRouting(): Boolean =
    sourceProvider == ProviderId.KITSU || railSource == RailSource.BUILT_IN_KITSU

private fun ProviderId?.isTrustedTitleRatingProvider(): Boolean =
    this == ProviderId.IMDB || this == ProviderId.TMDB

private fun ProviderId?.toTitleRatingSource(): TitleRatingSource? = when (this) {
    ProviderId.IMDB -> TitleRatingSource.IMDB
    ProviderId.TMDB -> TitleRatingSource.TMDB
    else -> null
}
