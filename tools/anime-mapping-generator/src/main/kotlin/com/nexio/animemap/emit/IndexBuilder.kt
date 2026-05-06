package com.nexio.animemap.emit

import com.nexio.animemap.model.IdentityRecord
import com.nexio.animemap.model.MapIndexes

class IndexBuilder {

    fun build(identity: Map<String, IdentityRecord>): MapIndexes {
        val byKitsu = identity.keys.associateWith { it }
        val byMal = mutableMapOf<String, String>()
        val byAnilist = mutableMapOf<String, String>()
        val byAnidb = mutableMapOf<String, String>()
        val byTvdb = mutableMapOf<String, MutableList<String>>()
        val byTmdbTv = mutableMapOf<String, MutableList<String>>()
        val byTmdbMovie = mutableMapOf<String, String>()
        val byImdb = mutableMapOf<String, MutableList<String>>()

        for ((kitsu, rec) in identity) {
            rec.mal?.let { byMal.putIfAbsent(it, kitsu) }
            rec.anilist?.let { byAnilist.putIfAbsent(it, kitsu) }
            rec.anidb?.let { byAnidb.putIfAbsent(it, kitsu) }
            rec.tvdb?.let { byTvdb.getOrPut(it) { mutableListOf() }.add(kitsu) }
            rec.imdb?.let { byImdb.getOrPut(it) { mutableListOf() }.add(kitsu) }
            rec.tmdb?.let { tmdb ->
                if (rec.mediaType == "movie") byTmdbMovie.putIfAbsent(tmdb, kitsu)
                else byTmdbTv.getOrPut(tmdb) { mutableListOf() }.add(kitsu)
            }
        }

        return MapIndexes(
            byKitsu = byKitsu,
            byMal = byMal.toMap(),
            byAnilist = byAnilist.toMap(),
            byAnidb = byAnidb.toMap(),
            byTvdb = byTvdb.mapValues { it.value.toList() },
            byTmdbTv = byTmdbTv.mapValues { it.value.toList() },
            byTmdbMovie = byTmdbMovie.toMap(),
            byImdb = byImdb.mapValues { it.value.toList() }
        )
    }
}
