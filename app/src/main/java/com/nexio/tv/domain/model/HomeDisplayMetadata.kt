package com.nexio.tv.domain.model

import androidx.compose.runtime.Immutable
import com.nexio.tv.core.artwork.ArtworkBundle
import com.nexio.tv.core.artwork.toLegacyArtworkString

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
    val backdrop: String? = null,
    val thumbnail: String? = null,
    @Transient
    val artwork: ArtworkBundle? = null
) {
    val displayPoster: String?
        get() = artwork?.poster.toLegacyArtworkString() ?: poster

    val displayBackdrop: String?
        get() = artwork?.backdrop.toLegacyArtworkString() ?: backdrop

    val displayLogo: String?
        get() = artwork?.logo.toLegacyArtworkString() ?: logo

    val displayThumbnail: String?
        get() = artwork?.thumbnail.toLegacyArtworkString() ?: thumbnail
}

fun MetaPreview.toHomeDisplayMetadata(): HomeDisplayMetadata {
    return HomeDisplayMetadata(
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
        backdrop = background,
        thumbnail = artwork?.thumbnail.toLegacyArtworkString(),
        artwork = artwork
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
        ratingSource = ratingSource.orDefault(),
        tomatoesRating = null,
        poster = poster,
        posterProviderTag = posterProviderTag,
        backdrop = background,
        thumbnail = artwork?.thumbnail.toLegacyArtworkString(),
        artwork = artwork
    )
}

fun HomeDisplayMetadata.applyTo(base: MetaPreview): MetaPreview {
    return base.copy(
        name = title ?: base.name,
        logo = displayLogo ?: base.logo,
        description = description ?: base.description,
        genres = if (genres.isNotEmpty()) genres else base.genres,
        releaseInfo = releaseInfo ?: base.releaseInfo,
        runtime = runtime ?: base.runtime,
        imdbRating = imdbRating ?: base.imdbRating,
        ratingSource = if (imdbRating != null) ratingSource.orDefault() else base.ratingSource.orDefault(),
        tomatoesRating = tomatoesRating ?: base.tomatoesRating,
        poster = displayPoster ?: base.poster,
        posterProviderTag = if (displayPoster != null) posterProviderTag else base.posterProviderTag,
        background = displayBackdrop ?: base.background,
        artwork = mergeAppliedArtwork(base)
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
        posterProviderTag = if (displayPoster != null) posterProviderTag else fallback.posterProviderTag,
        backdrop = backdrop ?: fallback.backdrop,
        thumbnail = thumbnail ?: fallback.thumbnail,
        artwork = mergeFallbackArtwork(fallback)
    )
}

private fun HomeDisplayMetadata.mergeFallbackArtwork(fallback: HomeDisplayMetadata): ArtworkBundle? {
    val fallbackArtwork = fallback.artwork ?: return artwork
    val merged = ArtworkBundle(
        poster = artwork?.poster ?: fallbackArtwork.poster.takeIf { displayPoster == null },
        backdrop = artwork?.backdrop ?: fallbackArtwork.backdrop.takeIf { displayBackdrop == null },
        logo = artwork?.logo ?: fallbackArtwork.logo.takeIf { displayLogo == null },
        thumbnail = artwork?.thumbnail ?: fallbackArtwork.thumbnail.takeIf { displayThumbnail == null }
    )
    return merged.takeUnless {
        it.poster == null &&
            it.backdrop == null &&
            it.logo == null &&
            it.thumbnail == null
    }
}

private fun HomeDisplayMetadata.mergeAppliedArtwork(base: MetaPreview): ArtworkBundle? {
    val baseArtwork = base.artwork ?: return artwork
    val merged = ArtworkBundle(
        poster = artwork?.poster ?: baseArtwork.poster.takeIf { displayPoster == null },
        backdrop = artwork?.backdrop ?: baseArtwork.backdrop.takeIf { displayBackdrop == null },
        logo = artwork?.logo ?: baseArtwork.logo.takeIf { displayLogo == null },
        thumbnail = artwork?.thumbnail ?: baseArtwork.thumbnail.takeIf { displayThumbnail == null }
    )
    return merged.takeUnless {
        it.poster == null &&
            it.backdrop == null &&
            it.logo == null &&
            it.thumbnail == null
    }
}

fun homeDisplayItemKey(contentType: String, contentId: String): String {
    return "${contentType.lowercase()}:${contentId.trim()}"
}
