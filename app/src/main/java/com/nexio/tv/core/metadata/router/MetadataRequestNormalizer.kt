package com.nexio.tv.core.metadata.router

import com.nexio.tv.core.trace.TraceMetadataEvents
import com.nexio.tv.domain.model.ContentType
import javax.inject.Inject

class MetadataRequestNormalizer @Inject constructor(
    private val traceEvents: TraceMetadataEvents
) {
    fun normalize(request: MetadataRequest): NormalizedMetadataRequest {
        val trimmedId = request.contentId.trim()
        return NormalizedMetadataRequest(
            originalContentId = trimmedId,
            parentId = parentIdOf(trimmedId),
            contentType = request.contentType,
            mediaKind = request.contentType.toMetadataMediaKind(trimmedId),
            sourceContext = request.sourceContext,
            language = request.language,
            seasonNumber = request.seasonNumber,
            depth = request.depth,
            pagination = request.pagination
        )
    }

    fun parentIdOf(contentId: String): String = MetadataParentIdNormalizer.parentIdOf(contentId)

    private fun ContentType.toMetadataMediaKind(contentId: String): MetadataMediaKind =
        when {
            contentId.startsWith("kitsu:", ignoreCase = true) ||
                contentId.startsWith("mal:", ignoreCase = true) ||
                contentId.startsWith("anilist:", ignoreCase = true) ||
                contentId.startsWith("anidb:", ignoreCase = true) -> MetadataMediaKind.ANIME
            this == ContentType.MOVIE -> MetadataMediaKind.MOVIE
            this == ContentType.SERIES -> MetadataMediaKind.SERIES
            this == ContentType.TV -> {
                traceEvents.emitNormalizerWarning(
                    contentId = contentId,
                    reason = "TV_TYPE_COERCED_TO_SERIES"
                )
                MetadataMediaKind.SERIES
            }
            else -> MetadataMediaKind.UNKNOWN
        }
}
