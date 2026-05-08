package com.nexio.tv.data.local

import com.nexio.tv.core.tmdb.TmdbEnrichment
import com.nexio.tv.core.tvdb.TvMetadataEnrichment
import com.nexio.tv.domain.model.CatalogRow
import com.nexio.tv.domain.model.HomeDisplayMetadata
import com.nexio.tv.domain.model.Meta
import com.nexio.tv.domain.model.MetaCastMember
import com.nexio.tv.domain.model.MetaCompany
import com.nexio.tv.domain.model.FirstPaintSource
import com.nexio.tv.domain.model.MetaPreview
import com.nexio.tv.domain.model.ProviderIds
import com.nexio.tv.domain.model.RatingValueValidator
import com.nexio.tv.domain.model.TitleRatingSource
import com.nexio.tv.domain.model.orDefault
import java.util.Locale

private val recognizedArtworkProviderTags = setOf(
    "RPDB",
    "TOP_POSTERS",
    "TMDB",
    "TVDB",
    "RAIL_PREVIEW",
    "ADDON_PREVIEW",
    "PLACEHOLDER"
)

private val recognizedArtworkImageTypes = setOf(
    "poster",
    "backdrop",
    "logo",
    "thumbnail"
)

internal fun MetaPreview.sanitizedForCache(): MetaPreview {
    val cleanRating = imdbRating.sanitizedTitleRating()
    val cleanPoster = poster.sanitizedPremiumArtworkRef()
    return copy(
        poster = cleanPoster,
        posterProviderTag = cleanPoster.derivePosterProviderTagFromArtworkRef(),
        imdbRating = cleanRating,
        ratingSource = ratingSource.sanitizedForTitleRating(cleanRating),
        genres = genres.orEmpty(),
        trailerYtIds = trailerYtIds.orEmpty(),
        // Cast to nullable before Elvis so the compiler does not flag it as redundant.
        // Gson can inject null into these non-null fields on legacy cached JSON missing those fields.
        firstPaintSource = (firstPaintSource as FirstPaintSource?) ?: FirstPaintSource.ADDON_META_PREVIEW,
        firstPaintStableIds = (firstPaintStableIds as ProviderIds?) ?: ProviderIds()
    )
}

internal fun HomeDisplayMetadata.sanitizedForCache(): HomeDisplayMetadata {
    val cleanRating = imdbRating.sanitizedTitleRating()
    val cleanPoster = poster.sanitizedPremiumArtworkRef()
    return copy(
        poster = cleanPoster,
        posterProviderTag = cleanPoster.derivePosterProviderTagFromArtworkRef(),
        imdbRating = cleanRating,
        ratingSource = ratingSource.sanitizedForTitleRating(cleanRating),
        genres = genres.orEmpty()
    )
}

internal fun CatalogRow.sanitizedForCache(): CatalogRow = copy(
    items = items.orEmpty().map { it.sanitizedForCache() }
)

internal fun Meta.sanitizedForCache(): Meta {
    val cleanRating = imdbRating.sanitizedTitleRating()
    val cleanPoster = poster.sanitizedPremiumArtworkRef()
    return copy(
        poster = cleanPoster,
        posterProviderTag = cleanPoster.derivePosterProviderTagFromArtworkRef(),
        imdbRating = cleanRating,
        ratingSource = ratingSource.sanitizedForTitleRating(cleanRating),
        genres = genres.orEmpty(),
        director = director.orEmpty(),
        writer = writer.orEmpty(),
        cast = cast.orEmpty(),
        castMembers = castMembers.orEmpty().mapNotNull { it.sanitizedOrNull() },
        videos = videos.orEmpty(),
        productionCompanies = productionCompanies.orEmpty().mapNotNull { it.sanitizedOrNull() },
        networks = networks.orEmpty().mapNotNull { it.sanitizedOrNull() },
        links = links.orEmpty(),
        trailerYtIds = trailerYtIds.orEmpty()
    )
}

private fun String?.sanitizedPremiumArtworkRef(): String? {
    val value = this?.trim()?.takeIf { it.isNotBlank() } ?: return null
    if (value.startsWith("integration-poster://", ignoreCase = true)) return null
    if (value.startsWith("https://api.ratingposterdb.com/", ignoreCase = true)) return null
    if (value.startsWith("https://api.top-posters.com/", ignoreCase = true)) return null
    return value
}

private fun String?.derivePosterProviderTagFromArtworkRef(): String? {
    val value = this?.trim()?.takeIf { it.isNotBlank() } ?: return null
    return when {
        value.startsWith("nexio-artwork://decision/", ignoreCase = true) ->
            value.removePrefixIgnoringCase("nexio-artwork://decision/").parseProviderFromDecisionKey()
        value.startsWith("nexio-artwork://asset/", ignoreCase = true) ->
            value.removePrefixIgnoringCase("nexio-artwork://asset/").parseProviderFromAssetKey()
        else -> null
    }?.lowercase(Locale.US)
}

private fun String.removePrefixIgnoringCase(prefix: String): String =
    if (startsWith(prefix, ignoreCase = true)) substring(prefix.length) else this

private fun String.parseProviderFromDecisionKey(): String? {
    val parts = split(':')
    if (!parts.firstOrNull().equals("artwork-decision", ignoreCase = true)) return null

    val providerIndex = parts.indexOfLast { it.equals("provider", ignoreCase = true) }
    if (providerIndex < 0) return null
    return parts.getOrNull(providerIndex + 1)?.recognizedArtworkProviderOrNull()
}

private fun String.parseProviderFromAssetKey(): String? {
    val parts = split(':')
    if (!parts.firstOrNull().equals("artwork-asset", ignoreCase = true)) return null

    val provider = parts.getOrNull(1)?.recognizedArtworkProviderOrNull() ?: return null
    val imageType = parts.getOrNull(2) ?: return null
    if (imageType !in recognizedArtworkImageTypes) return null
    if (parts.getOrNull(3).isNullOrBlank()) return null

    return provider
}

private fun String.recognizedArtworkProviderOrNull(): String? {
    if (isBlank() || this != trim()) return null

    val provider = uppercase(Locale.US)
    return provider.takeIf { it in recognizedArtworkProviderTags }
}

private fun Float?.sanitizedTitleRating(): Float? =
    RatingValueValidator.sanitizeTitleRating(this)

private fun Double?.sanitizedTitleRating(): Double? =
    RatingValueValidator.sanitizeTitleRating(this)

private fun TitleRatingSource?.sanitizedForTitleRating(sanitized: Float?): TitleRatingSource? =
    if (sanitized != null) this ?: TitleRatingSource.IMDB else null

private fun TitleRatingSource?.sanitizedForTitleRating(
    sanitized: Double?,
    defaultSource: TitleRatingSource = TitleRatingSource.IMDB
): TitleRatingSource? =
    if (sanitized != null) this ?: defaultSource else null

internal fun TmdbEnrichment.sanitizedForCache(): TmdbEnrichment {
    val cleanRating = rating.sanitizedTitleRating()
    return copy(
        rating = cleanRating,
        ratingSource = ratingSource.sanitizedForTitleRating(cleanRating, TitleRatingSource.TMDB),
        genres = genres.orEmpty(),
        directorMembers = directorMembers.orEmpty().mapNotNull { it.sanitizedOrNull() },
        writerMembers = writerMembers.orEmpty().mapNotNull { it.sanitizedOrNull() },
        castMembers = castMembers.orEmpty().mapNotNull { it.sanitizedOrNull() },
        director = director.orEmpty(),
        writer = writer.orEmpty(),
        productionCompanies = productionCompanies.orEmpty().mapNotNull { it.sanitizedOrNull() },
        networks = networks.orEmpty().mapNotNull { it.sanitizedOrNull() }
    )
}

internal fun TvMetadataEnrichment.sanitizedForCache(): TvMetadataEnrichment {
    val cleanRating = rating.sanitizedTitleRating()
    return copy(
        rating = cleanRating,
        ratingSource = ratingSource.sanitizedForTitleRating(cleanRating),
        genres = genres.orEmpty(),
        airsDays = airsDays.orEmpty(),
        aliases = aliases.orEmpty(),
        contentRatings = contentRatings.orEmpty(),
        remoteIds = remoteIds.orEmpty(),
        castMembers = castMembers.orEmpty().mapNotNull { it.sanitizedOrNull() },
        productionCompanies = productionCompanies.orEmpty().mapNotNull { it.sanitizedOrNull() },
        networks = networks.orEmpty().mapNotNull { it.sanitizedOrNull() }
    )
}

private fun MetaCastMember.sanitizedOrNull(): MetaCastMember? {
    val cleanName = name.trim().takeIf { it.isNotBlank() } ?: return null
    return copy(
        name = cleanName,
        provider = provider?.trim()?.takeIf { it.isNotBlank() },
        providerId = providerId?.trim()?.takeIf { it.isNotBlank() }
    )
}

private fun MetaCompany.sanitizedOrNull(): MetaCompany? {
    val cleanName = name.trim().takeIf { it.isNotBlank() } ?: return null
    return copy(
        name = cleanName,
        provider = provider?.trim()?.takeIf { it.isNotBlank() },
        providerId = providerId?.trim()?.takeIf { it.isNotBlank() }
    )
}
