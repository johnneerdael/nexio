package com.nexio.tv.data.catalog.rails

/**
 * Encodes and decodes the railId scheme used by [TraktCatalogRailSource].
 *
 * Format: `trakt::<kind>` where `<kind>` is the lowercase token of a [Kind] value.
 *
 * The supported set is closed: the Plan 3 migration covers only the four **global**
 * Trakt rails (no auth required). Profile-bound rails (recommendations, calendar,
 * user lists) are deferred to a follow-up plan and will extend the [Kind] enum.
 */
class TraktRailIdCodec {

    enum class Kind(val token: String) {
        TRENDING_MOVIES("trending_movies"),
        TRENDING_SHOWS("trending_shows"),
        POPULAR_MOVIES("popular_movies"),
        POPULAR_SHOWS("popular_shows");

        companion object {
            fun fromToken(token: String): Kind? = values().firstOrNull { it.token == token }
        }
    }

    fun encode(kind: Kind): String = "$PREFIX$DELIM${kind.token}"

    fun decode(railId: String): Kind? {
        if (railId.isBlank()) return null
        val parts = railId.split(DELIM)
        if (parts.size != 2) return null
        if (parts[0] != PREFIX) return null
        if (parts[1].isBlank()) return null
        return Kind.fromToken(parts[1])
    }

    companion object {
        const val PREFIX: String = "trakt"
        const val DELIM: String = "::"
    }
}
