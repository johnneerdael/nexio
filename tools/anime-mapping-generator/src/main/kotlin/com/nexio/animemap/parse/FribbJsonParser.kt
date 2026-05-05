package com.nexio.animemap.parse

import com.squareup.moshi.Moshi
import com.squareup.moshi.Types

data class IdentityFragment(
    val anidb: String,
    val kitsu: String? = null,
    val mal: String? = null,
    val anilist: String? = null,
    val tvdb: String? = null,
    val tmdb: String? = null,
    val imdb: String? = null,
    val sourceType: String? = null,
    val tvdbSeasonHint: String? = null,
    val tmdbSeasonHint: String? = null,
    val source: String = "fribb"
)

class FribbJsonParser {

    fun parse(json: String): List<IdentityFragment> {
        val moshi = Moshi.Builder().build()
        val listType = Types.newParameterizedType(List::class.java, Map::class.java)
        @Suppress("UNCHECKED_CAST")
        val raw = moshi.adapter<List<Map<String, Any?>>>(listType).fromJson(json) ?: return emptyList()
        return raw.mapNotNull { row -> toFragment(row) }
    }

    private fun toFragment(row: Map<String, Any?>): IdentityFragment? {
        val anidb = stringId(row["anidb_id"]) ?: return null
        val seasonMap = row["season"] as? Map<*, *>
        return IdentityFragment(
            anidb = anidb,
            kitsu = stringId(row["kitsu_id"]),
            mal = stringId(row["mal_id"]),
            anilist = stringId(row["anilist_id"]),
            tvdb = stringId(row["tvdb_id"]),
            tmdb = stringId(row["themoviedb_id"]),
            imdb = (row["imdb_id"] as? String)?.takeIf { it.isNotBlank() },
            sourceType = (row["type"] as? String)?.takeIf { it.isNotBlank() },
            tvdbSeasonHint = stringId(seasonMap?.get("tvdb")),
            tmdbSeasonHint = stringId(seasonMap?.get("tmdb"))
        )
    }

    private fun stringId(value: Any?): String? = when (value) {
        null -> null
        is String -> value.takeIf { it.isNotBlank() }
        is Number -> value.toLong().takeIf { it > 0 }?.toString()
        else -> null
    }
}
