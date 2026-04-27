package com.nexio.tv.domain.model

import androidx.compose.runtime.Immutable

@Immutable
data class HomeDisplayMetadata(
    val title: String? = null,
    val logo: String? = null,
    val description: String? = null,
    val genres: List<String> = emptyList(),
    val releaseInfo: String? = null,
    val runtime: String? = null,
    val imdbRating: Float? = null,
    val ratingSource: TitleRatingSource? = TitleRatingSource.IMDB,
    val tomatoesRating: Double? = null,
    val poster: String? = null,
    val posterProviderTag: String? = null,
    val backdrop: String? = null
)

fun MetaPreview.toHomeDisplayMetadata(): HomeDisplayMetadata {
    val display = HomeDisplayMetadata(
        title = name,
        logo = logo,
        description = description,
        genres = genres,
        releaseInfo = releaseInfo,
        runtime = runtime,
        imdbRating = imdbRating,
        ratingSource = ratingSource.orDefault(),
        tomatoesRating = tomatoesRating,
        poster = poster,
        posterProviderTag = posterProviderTag,
        backdrop = background
    )
    com.nexio.tv.core.trace.FirstPaintTracer.recordHomePreview(
        contentId = id,
        itemType = apiType,
        fieldsUsed = collectFirstPaintFieldsUsed(display)
    )
    return display
}

private fun collectFirstPaintFieldsUsed(d: HomeDisplayMetadata): Set<String> {
    val fields = mutableSetOf<String>()
    if (!d.title.isNullOrBlank()) fields += "title"
    if (!d.poster.isNullOrBlank()) fields += "poster"
    if (!d.backdrop.isNullOrBlank()) fields += "background"
    if (!d.description.isNullOrBlank()) fields += "description"
    if (!d.releaseInfo.isNullOrBlank()) fields += "releaseInfo"
    if (!d.logo.isNullOrBlank()) fields += "logo"
    return fields
}

fun Meta.toHomeDisplayMetadata(): HomeDisplayMetadata {
    return HomeDisplayMetadata(
        title = name,
        logo = logo,
        description = description,
        genres = genres,
        releaseInfo = releaseInfo,
        runtime = runtime,
        imdbRating = imdbRating,
        ratingSource = ratingSource.orDefault(),
        tomatoesRating = null,
        poster = poster,
        posterProviderTag = posterProviderTag,
        backdrop = background
    )
}

fun HomeDisplayMetadata.applyTo(base: MetaPreview): MetaPreview {
    return base.copy(
        name = title ?: base.name,
        logo = logo ?: base.logo,
        description = description ?: base.description,
        genres = if (genres.isNotEmpty()) genres else base.genres,
        releaseInfo = releaseInfo ?: base.releaseInfo,
        runtime = runtime ?: base.runtime,
        imdbRating = imdbRating ?: base.imdbRating,
        ratingSource = if (imdbRating != null) ratingSource.orDefault() else base.ratingSource.orDefault(),
        tomatoesRating = tomatoesRating ?: base.tomatoesRating,
        poster = poster ?: base.poster,
        posterProviderTag = posterProviderTag ?: base.posterProviderTag,
        background = backdrop ?: base.background
    )
}

fun HomeDisplayMetadata.mergeFallback(fallback: HomeDisplayMetadata?): HomeDisplayMetadata {
    if (fallback == null) return this
    return copy(
        title = title ?: fallback.title,
        logo = logo ?: fallback.logo,
        description = description ?: fallback.description,
        genres = if (genres.isNotEmpty()) genres else fallback.genres,
        releaseInfo = releaseInfo ?: fallback.releaseInfo,
        runtime = runtime ?: fallback.runtime,
        imdbRating = imdbRating ?: fallback.imdbRating,
        ratingSource = if (imdbRating != null) ratingSource.orDefault() else fallback.ratingSource.orDefault(),
        tomatoesRating = tomatoesRating ?: fallback.tomatoesRating,
        poster = poster ?: fallback.poster,
        posterProviderTag = posterProviderTag ?: fallback.posterProviderTag,
        backdrop = backdrop ?: fallback.backdrop
    )
}

fun homeDisplayItemKey(contentType: String, contentId: String): String {
    return "${contentType.lowercase()}:${contentId.trim()}"
}
