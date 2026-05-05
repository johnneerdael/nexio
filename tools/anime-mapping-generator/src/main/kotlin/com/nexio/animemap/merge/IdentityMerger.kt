package com.nexio.animemap.merge

import com.nexio.animemap.model.EpisodeMappingRecord
import com.nexio.animemap.model.IdentityRecord
import com.nexio.animemap.parse.ExpandedMappingList
import com.nexio.animemap.parse.IdentityFragment
import com.nexio.animemap.parse.ScudleeAnimeEntry

data class MergedScudlee(
    val entry: ScudleeAnimeEntry,
    val expanded: ExpandedMappingList?
)

data class MergedAssetData(
    val identity: Map<String, IdentityRecord>,
    val episodeMapping: Map<String, EpisodeMappingRecord>
)

class IdentityMerger {

    fun merge(
        fribb: List<IdentityFragment>,
        scudlee: Map<String, MergedScudlee>
    ): MergedAssetData {
        val identity = mutableMapOf<String, IdentityRecord>()
        val episodeMapping = mutableMapOf<String, EpisodeMappingRecord>()

        for (frag in fribb) {
            val kitsu = frag.kitsu ?: continue
            val s = scudlee[frag.anidb]
            val mappingPresent = s?.expanded?.let { it.ranges.isNotEmpty() || it.explicitMaps.isNotEmpty() } == true

            val tvdbSeason = s?.entry?.tvdbSeason ?: frag.tvdbSeasonHint
            val tmdbSeason = s?.entry?.tmdbSeason ?: frag.tmdbSeasonHint

            val evidence = buildList {
                add("fribb.kitsu=$kitsu")
                if (frag.tvdb != null) add("fribb.tvdb=${frag.tvdb}")
                if (s != null) {
                    if (s.entry.tvdb != null) add("scudlee.tvdb=${s.entry.tvdb}")
                    if (s.entry.tvdbSeason != null) add("scudlee.defaulttvdbseason=${s.entry.tvdbSeason}")
                    if (s.entry.tmdbSeason != null) add("scudlee.tmdbseason=${s.entry.tmdbSeason}")
                    if (mappingPresent) add("scudlee.mapping-list")
                }
            }

            identity[kitsu] = IdentityRecord(
                kitsu = kitsu,
                mal = frag.mal,
                anilist = frag.anilist,
                anidb = frag.anidb,
                tmdb = frag.tmdb ?: s?.entry?.tmdbTv,
                tvdb = frag.tvdb ?: s?.entry?.tvdb,
                imdb = frag.imdb ?: s?.entry?.imdb,
                mediaType = mediaTypeOf(frag.sourceType),
                sourceType = frag.sourceType,
                tvdbSeason = tvdbSeason,
                tmdbSeason = tmdbSeason,
                tvdbEpisodeOffset = s?.entry?.tvdbEpisodeOffset,
                tmdbEpisodeOffset = s?.entry?.tmdbEpisodeOffset,
                hasMappingRules = mappingPresent,
                evidence = evidence
            )
        }

        for ((anidb, s) in scudlee) {
            val expanded = s.expanded ?: continue
            if (expanded.ranges.isEmpty() && expanded.explicitMaps.isEmpty()) continue
            episodeMapping[anidb] = EpisodeMappingRecord(
                anidb = anidb,
                name = s.entry.name,
                tvdbSeriesId = s.entry.tvdb,
                tmdbTvId = s.entry.tmdbTv,
                ranges = expanded.ranges,
                explicitMaps = expanded.explicitMaps,
                evidence = listOf("scudlee.mapping-list")
            )
        }

        return MergedAssetData(identity, episodeMapping)
    }

    private fun mediaTypeOf(sourceType: String?): String? = when (sourceType?.uppercase()) {
        null -> null
        "MOVIE" -> "movie"
        else -> "series"
    }
}
