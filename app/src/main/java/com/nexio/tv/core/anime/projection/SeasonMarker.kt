package com.nexio.tv.core.anime.projection

sealed interface SeasonMarker {
    data class Number(val season: Int) : SeasonMarker
    data object Absolute : SeasonMarker
    data object Hentai : SeasonMarker
    data object Unknown : SeasonMarker

    companion object {
        fun fromWire(value: String?): SeasonMarker? {
            val trimmed = value?.trim()?.lowercase() ?: return null
            if (trimmed.isEmpty()) return null
            return when (trimmed) {
                "a" -> Absolute
                "hentai" -> Hentai
                "unknown" -> Unknown
                else -> trimmed.toIntOrNull()?.let(::Number)
            }
        }
    }
}
