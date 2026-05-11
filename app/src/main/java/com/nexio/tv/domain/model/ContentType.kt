package com.nexio.tv.domain.model

import com.nexio.tv.core.metadata.router.MetadataMediaKind

enum class ContentType {
    MOVIE,
    SERIES,
    CHANNEL,
    TV,
    PERSON,
    UNKNOWN;

    companion object {
        fun fromString(value: String): ContentType = when (value.trim().lowercase()) {
            "movie" -> MOVIE
            "series" -> SERIES
            "channel" -> CHANNEL
            "tv" -> TV
            "person" -> PERSON
            else -> UNKNOWN
        }
    }

    fun toApiString(fallbackType: String? = null): String = when (this) {
        MOVIE -> "movie"
        SERIES -> "series"
        CHANNEL -> "channel"
        TV -> "tv"
        PERSON -> "person"
        UNKNOWN -> fallbackType
            ?.trim()
            ?.takeIf { it.isNotEmpty() }
            ?: "movie"
    }
}

internal fun ContentType.toMetadataMediaKind(): MetadataMediaKind =
    when (this) {
        ContentType.MOVIE -> MetadataMediaKind.MOVIE
        ContentType.SERIES,
        ContentType.TV -> MetadataMediaKind.SERIES
        else -> MetadataMediaKind.UNKNOWN
    }
