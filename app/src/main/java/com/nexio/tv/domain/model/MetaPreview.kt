package com.nexio.tv.domain.model

import androidx.compose.runtime.Immutable

enum class FirstPaintSource {
    ADDON_META_PREVIEW,
    RAIL_PREVIEW
}

@Immutable
data class MetaPreview(
    val id: String,
    val type: ContentType,
    val rawType: String = type.toApiString(),
    val name: String,
    val poster: String?,
    val posterShape: PosterShape,
    val background: String?,
    val logo: String?,
    val description: String?,
    val releaseInfo: String?,
    val runtime: String? = null,
    val imdbRating: Float?,
    val ratingSource: TitleRatingSource? = TitleRatingSource.IMDB,
    val tomatoesRating: Double? = null,
    val genres: List<String>,
    val trailerYtIds: List<String> = emptyList(),
    val language: String? = null,
    val posterProviderTag: String? = null,
    val firstPaintSource: FirstPaintSource = FirstPaintSource.ADDON_META_PREVIEW,
    val firstPaintSourceProvider: ProviderId? = null,
    val firstPaintStableIds: ProviderIds = ProviderIds(),
    val firstPaintRailSource: RailSource? = null,
    val firstPaintSourceItemId: String? = null
) {
    val apiType: String
        get() = type.toApiString(rawType)
}
