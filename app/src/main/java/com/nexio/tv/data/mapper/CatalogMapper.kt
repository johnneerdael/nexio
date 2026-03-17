package com.nexio.tv.data.mapper

import com.nexio.tv.data.remote.dto.MetaPreviewDto
import com.nexio.tv.domain.model.ContentType
import com.nexio.tv.domain.model.MetaPreview
import com.nexio.tv.domain.model.PosterShape

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
        releaseInfo = releaseInfo,
        runtime = runtime,
        imdbRating = imdbRating?.toFloatOrNull(),
        genres = genres ?: emptyList(),
        trailerYtIds = trailerStreams?.mapNotNull { it.ytId?.takeIf { id -> id.isNotBlank() } } ?: emptyList()
    )
}
