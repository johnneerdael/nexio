package com.nexio.tv.data.mapper

import com.nexio.tv.data.remote.dto.MetaPreviewDto
import com.nexio.tv.domain.model.ContentType
import com.nexio.tv.domain.model.MetaPreview
import com.nexio.tv.domain.model.PosterShape
import com.nexio.tv.domain.model.ProviderIds

fun MetaPreviewDto.toDomain(): MetaPreview {
    return MetaPreview(
        id = id,
        type = ContentType.fromString(type),
        rawType = type,
        name = name,
        poster = poster,
        posterShape = PosterShape.fromString(posterShape),
        background = background,
        logo = logo,
        description = description,
        releaseInfo = releaseInfo ?: year,
        runtime = runtime,
        imdbRating = imdbRating?.toFloatOrNull(),
        genres = genres ?: genre ?: emptyList(),
        trailerYtIds = trailerStreams?.mapNotNull { it.ytId?.takeIf { id -> id.isNotBlank() } } ?: emptyList(),
        language = language,
        firstPaintStableIds = deriveAddonStableIds(
            id = id,
            imdbId = imdbId,
            defaultVideoId = behaviorHints?.defaultVideoId
        )
    )
}

private fun deriveAddonStableIds(
    id: String,
    imdbId: String?,
    defaultVideoId: String?
): ProviderIds {
    val trimmedId = id.trim()
    val tmdb = trimmedId
        .takeIf { it.startsWith("tmdb:", ignoreCase = true) }
        ?.substringAfter(':')
        ?.takeIf { it.isNotBlank() }
    val imdb = firstNonBlank(
        canonicalImdbTitleId(imdbId),
        canonicalImdbTitleId(defaultVideoId),
        canonicalImdbTitleId(trimmedId)
    )

    return ProviderIds(
        imdb = imdb,
        tmdb = tmdb
    )
}

private val imdbTitleIdPattern = Regex("^tt\\d+$", RegexOption.IGNORE_CASE)

private fun canonicalImdbTitleId(value: String?): String? {
    val trimmed = value?.trim()?.takeIf { it.isNotBlank() } ?: return null
    if (!imdbTitleIdPattern.matches(trimmed)) return null
    return "tt" + trimmed.drop(2)
}

private fun firstNonBlank(vararg values: String?): String? =
    values.firstOrNull { !it.isNullOrBlank() }?.trim()
