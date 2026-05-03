package com.nexio.tv.domain.model

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
