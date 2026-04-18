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
    val tomatoesRating: Double? = null,
    val poster: String? = null,
    val posterProviderTag: String? = null,
    val backdrop: String? = null
)

fun MetaPreview.toHomeDisplayMetadata(): HomeDisplayMetadata {
    return HomeDisplayMetadata(
        title = name,
        logo = logo,
        description = description,
        genres = genres,
        releaseInfo = releaseInfo,
        runtime = runtime,
        imdbRating = imdbRating,
        tomatoesRating = tomatoesRating,
        poster = poster,
        posterProviderTag = posterProviderTag,
        backdrop = background
    )
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
        tomatoesRating = tomatoesRating ?: fallback.tomatoesRating,
        poster = poster ?: fallback.poster,
        posterProviderTag = posterProviderTag ?: fallback.posterProviderTag,
        backdrop = backdrop ?: fallback.backdrop
    )
}

fun homeDisplayItemKey(contentType: String, contentId: String): String {
    return "${contentType.lowercase()}:${contentId.trim()}"
}
