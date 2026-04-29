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
        imdbId?.takeIf { it.startsWith("tt", ignoreCase = true) },
        defaultVideoId?.takeIf { it.startsWith("tt", ignoreCase = true) },
        trimmedId.takeIf { it.startsWith("tt", ignoreCase = true) }
    )

    return ProviderIds(
        imdb = imdb,
        tmdb = tmdb
    )
}

private fun firstNonBlank(vararg values: String?): String? =
    values.firstOrNull { !it.isNullOrBlank() }?.trim()
