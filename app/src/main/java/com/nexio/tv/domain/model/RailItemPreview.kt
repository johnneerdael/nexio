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
    fun bestStableItemKey(itemType: ContentType, sourceItemId: String): String {
        val type = itemType.toApiString()
        return when {
            imdb != null -> "$type:imdb:$imdb"
            tmdb != null -> "$type:tmdb:$tmdb"
            tvdb != null -> "$type:tvdb:$tvdb"
            kitsu != null -> "$type:kitsu:$kitsu"
            trakt != null -> "$type:trakt:$trakt"
            simkl != null -> "$type:simkl:$simkl"
            else -> "$type:raw:${sourceItemId.trim()}"
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
    val backdropUrl: String? = null,
    val logoUrl: String? = null,
    val rating: RatingSeed? = null,
    val trailerHint: TrailerHint? = null
)

data class RatingSeed(
    val provider: ProviderId,
    val value: Double,
    val votes: Int? = null
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
        ?: stableIds.tmdb?.let { "TMDB " }
        ?: sourceItemId
    val ratingSource = display.rating?.provider.toTitleRatingSource()

    return MetaPreview(
        id = sourceItemId,
        type = itemType,
        rawType = itemType.toApiString(),
        name = title,
        poster = display.posterUrl,
        posterShape = PosterShape.POSTER,
        background = display.backdropUrl,
        logo = display.logoUrl,
        description = display.overview,
        releaseInfo = display.year?.toString() ?: display.releaseDate,
        runtime = display.runtimeText,
        imdbRating = display.rating?.value?.toFloat()?.takeIf { ratingSource != null },
        ratingSource = ratingSource,
        genres = display.genres,
        trailerYtIds = when (val hint = display.trailerHint) {
            is TrailerHint.YouTube -> listOf(hint.videoId)
            null -> emptyList()
        },
        posterProviderTag = sourceProvider?.name?.lowercase()
    )
}

private fun ProviderId?.toTitleRatingSource(): TitleRatingSource? = when (this) {
    ProviderId.IMDB -> TitleRatingSource.IMDB
    ProviderId.TMDB -> TitleRatingSource.TMDB
    else -> null
}
