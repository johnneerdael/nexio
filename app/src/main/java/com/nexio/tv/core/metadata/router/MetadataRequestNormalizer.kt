package com.nexio.tv.core.metadata.router

import com.nexio.tv.domain.model.ContentType
import javax.inject.Inject

class MetadataRequestNormalizer @Inject constructor() {
    fun normalize(request: MetadataRequest): NormalizedMetadataRequest {
        val trimmedId = request.contentId.trim()
        return NormalizedMetadataRequest(
            originalContentId = trimmedId,
            parentId = parentIdOf(trimmedId),
            contentType = request.contentType,
            mediaKind = request.contentType.toMetadataMediaKind(),
            sourceContext = request.sourceContext,
            language = request.language,
            seasonNumber = request.seasonNumber,
            depth = request.depth
        )
    }

    fun parentIdOf(contentId: String): String {
        val id = contentId.trim()
        if (id.isBlank()) return ""

        val parts = id.split(":")
        return when {
            id.startsWith("imdb:", ignoreCase = true) && parts.size >= 2 -> "${parts[0]}:${parts[1]}"
            id.startsWith("kitsu:", ignoreCase = true) && parts.size >= 2 -> "${parts[0]}:${parts[1]}"
            id.startsWith("mal:", ignoreCase = true) && parts.size >= 2 -> "${parts[0]}:${parts[1]}"
            id.startsWith("anilist:", ignoreCase = true) && parts.size >= 2 -> "${parts[0]}:${parts[1]}"
            id.startsWith("anidb:", ignoreCase = true) && parts.size >= 2 -> "${parts[0]}:${parts[1]}"
            id.startsWith("tmdb:", ignoreCase = true) && parts.size >= 2 -> "${parts[0]}:${parts[1]}"
            id.startsWith("tvdb:", ignoreCase = true) && parts.size >= 2 -> "${parts[0]}:${parts[1]}"
            id.startsWith("tt", ignoreCase = true) && parts.size >= 3 -> parts[0]
            else -> id
        }
    }

    private fun ContentType.toMetadataMediaKind(): MetadataMediaKind =
        when (this) {
            ContentType.MOVIE -> MetadataMediaKind.MOVIE
            ContentType.SERIES -> MetadataMediaKind.SERIES
            ContentType.TV -> MetadataMediaKind.SERIES
            else -> MetadataMediaKind.UNKNOWN
        }
}
