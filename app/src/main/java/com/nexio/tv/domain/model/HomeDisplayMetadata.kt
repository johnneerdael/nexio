package com.nexio.tv.domain.model

import androidx.compose.runtime.Immutable
import com.nexio.tv.core.artwork.ArtworkBundle
import com.nexio.tv.core.artwork.emptyOrNull
import com.nexio.tv.core.artwork.enforceArtworkTypeBoundaries
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
    val cleanOverlayRating = sanitizedTitleRating()
    val cleanBaseRating = base.imdbRating.sanitizedTitleRating()
    val appliedRating = cleanOverlayRating ?: cleanBaseRating
    val appliedRatingSource = when {
        cleanOverlayRating != null -> ratingSource.orDefault()
        cleanBaseRating != null -> base.ratingSource.orDefault()
        else -> null
    }
    return base.copy(
        name = title ?: base.name,
        logo = displayLogo ?: base.logo,
        description = description ?: base.description,
        genres = if (genres.isNotEmpty()) genres else base.genres,
        releaseInfo = releaseInfo ?: base.releaseInfo,
        runtime = runtime ?: base.runtime,
        imdbRating = appliedRating,
        ratingSource = appliedRatingSource,
        tomatoesRating = tomatoesRating ?: base.tomatoesRating,
        poster = displayPoster ?: base.poster,
        posterProviderTag = if (displayPoster != null) posterProviderTag else base.posterProviderTag,
        background = displayBackdrop ?: base.background,
        artwork = mergeAppliedArtwork(base)
    )
}

fun HomeDisplayMetadata.mergeFallback(fallback: HomeDisplayMetadata?): HomeDisplayMetadata {
    if (fallback == null) return this
    val cleanPrimaryRating = sanitizedTitleRating()
    val cleanFallbackRating = fallback.imdbRating.sanitizedTitleRating()
    val mergedRating = cleanPrimaryRating ?: cleanFallbackRating
    val mergedRatingSource = when {
        cleanPrimaryRating != null -> ratingSource.orDefault()
        cleanFallbackRating != null -> fallback.ratingSource.orDefault()
        else -> null
    }
    return copy(
        title = title ?: fallback.title,
        logo = logo ?: fallback.logo,
        description = description ?: fallback.description,
        genres = if (genres.isNotEmpty()) genres else fallback.genres,
        releaseInfo = releaseInfo ?: fallback.releaseInfo,
        runtime = runtime ?: fallback.runtime,
        imdbRating = mergedRating,
        ratingSource = mergedRatingSource,
        tomatoesRating = tomatoesRating ?: fallback.tomatoesRating,
        poster = poster ?: fallback.poster,
        posterProviderTag = if (displayPoster != null) posterProviderTag else fallback.posterProviderTag,
        backdrop = backdrop ?: fallback.backdrop,
        thumbnail = thumbnail ?: fallback.thumbnail,
        artwork = mergeFallbackArtwork(fallback)
    )
}

private fun HomeDisplayMetadata.sanitizedTitleRating(): Float? =
    RatingValueValidator.sanitizeTitleRating(imdbRating)

private fun Float?.sanitizedTitleRating(): Float? =
    RatingValueValidator.sanitizeTitleRating(this)

private fun HomeDisplayMetadata.mergeFallbackArtwork(fallback: HomeDisplayMetadata): ArtworkBundle? {
    val primaryArtwork = artwork?.enforceArtworkTypeBoundaries()
    val fallbackArtwork = fallback.artwork?.enforceArtworkTypeBoundaries()
    val merged = ArtworkBundle(
        poster = primaryArtwork?.poster ?: fallbackArtwork?.poster,
        backdrop = primaryArtwork?.backdrop ?: fallbackArtwork?.backdrop,
        logo = primaryArtwork?.logo ?: fallbackArtwork?.logo,
        thumbnail = primaryArtwork?.thumbnail ?: fallbackArtwork?.thumbnail
    )
    return merged.enforceArtworkTypeBoundaries().emptyOrNull()
}

private fun HomeDisplayMetadata.mergeAppliedArtwork(base: MetaPreview): ArtworkBundle? {
    val primaryArtwork = artwork?.enforceArtworkTypeBoundaries()
    val baseArtwork = base.artwork?.enforceArtworkTypeBoundaries()
    val merged = ArtworkBundle(
        poster = primaryArtwork?.poster ?: baseArtwork?.poster,
        backdrop = primaryArtwork?.backdrop ?: baseArtwork?.backdrop,
        logo = primaryArtwork?.logo ?: baseArtwork?.logo,
        thumbnail = primaryArtwork?.thumbnail ?: baseArtwork?.thumbnail
    )
    return merged.enforceArtworkTypeBoundaries().emptyOrNull()
}

fun homeDisplayItemKey(contentType: String, contentId: String): String {
    return "${contentType.lowercase()}:${contentId.trim()}"
}
