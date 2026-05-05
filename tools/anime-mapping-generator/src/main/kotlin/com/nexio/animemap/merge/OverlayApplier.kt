package com.nexio.animemap.merge

import com.nexio.animemap.model.EpisodeMappingRecord
import com.nexio.animemap.model.ExplicitMap
import com.nexio.animemap.model.ExplicitMapKey
import com.nexio.animemap.model.IdentityRecord
import com.nexio.animemap.model.OverlayEntry
import com.nexio.animemap.model.OverlayMode
import com.nexio.animemap.model.OverlayTarget
import com.nexio.animemap.model.RangeRule
import com.nexio.animemap.model.RangeRuleKey

class OverlayApplier {

    fun apply(
        identity: Map<String, IdentityRecord>,
        episodeMapping: Map<String, EpisodeMappingRecord>,
        entries: List<OverlayEntry>
    ): MergedAssetData {
        validate(entries)

        val identityById = identity.toMutableMap()
        val mappingByAnidb = episodeMapping.toMutableMap()

        for (entry in entries) {
            when (entry.mode) {
                OverlayMode.DROP -> applyDrop(entry, identityById, mappingByAnidb)
                OverlayMode.REPLACE -> applyReplace(entry, identityById, mappingByAnidb)
                OverlayMode.PATCH_IDENTITY -> applyPatchIdentity(entry, identityById)
                OverlayMode.PATCH_MAPPING -> applyPatchMapping(entry, mappingByAnidb)
                else -> error("unknown overlay mode: ${entry.mode}")
            }
        }

        return MergedAssetData(identityById, mappingByAnidb)
    }

    private fun validate(entries: List<OverlayEntry>) {
        val seen = mutableSetOf<Pair<String, String>>()
        for (e in entries) {
            require(e.anidb.isNotBlank()) { "overlay entry missing anidb" }
            require(e.reason.isNotBlank()) { "overlay entry for anidb=${e.anidb} missing reason" }
            check(e.mode in OverlayMode.ALL) { "unknown overlay mode '${e.mode}' for anidb=${e.anidb}" }
            val key = e.anidb to e.mode
            check(seen.add(key)) { "duplicate overlay entry for (anidb=${e.anidb}, mode=${e.mode})" }
        }
    }

    private fun applyDrop(
        entry: OverlayEntry,
        identity: MutableMap<String, IdentityRecord>,
        mapping: MutableMap<String, EpisodeMappingRecord>
    ) {
        val toRemoveKitsuIds = identity.values.filter { it.anidb == entry.anidb }.map { it.kitsu }
        toRemoveKitsuIds.forEach { identity.remove(it) }
        mapping.remove(entry.anidb)
    }

    private fun applyReplace(
        entry: OverlayEntry,
        identity: MutableMap<String, IdentityRecord>,
        mapping: MutableMap<String, EpisodeMappingRecord>
    ) {
        val record = entry.record ?: error("replace entry for anidb=${entry.anidb} missing record")
        when (entry.target) {
            OverlayTarget.IDENTITY -> {
                val rec = mapToIdentityRecord(record)
                identity[rec.kitsu] = rec
            }
            OverlayTarget.MAPPING -> {
                val rec = mapToMappingRecord(entry.anidb, record)
                mapping[entry.anidb] = rec
            }
            else -> error("replace entry for anidb=${entry.anidb} missing valid target")
        }
    }

    private fun applyPatchIdentity(entry: OverlayEntry, identity: MutableMap<String, IdentityRecord>) {
        val patch = entry.patch ?: error("patch-identity for anidb=${entry.anidb} missing patch")
        val key = identity.entries.firstOrNull { it.value.anidb == entry.anidb }?.key
            ?: error("patch-identity target identity record missing for anidb=${entry.anidb}")
        val current = identity.getValue(key)
        identity[key] = current.copy(
            tvdbSeason = patch.tvdbSeason ?: current.tvdbSeason,
            tmdbSeason = patch.tmdbSeason ?: current.tmdbSeason,
            tvdbEpisodeOffset = patch.tvdbEpisodeOffset ?: current.tvdbEpisodeOffset,
            tmdbEpisodeOffset = patch.tmdbEpisodeOffset ?: current.tmdbEpisodeOffset,
            tvdb = patch.tvdb ?: current.tvdb,
            tmdb = patch.tmdb ?: current.tmdb,
            imdb = patch.imdb ?: current.imdb,
            evidence = current.evidence + "overlay.patch-identity"
        )
    }

    private fun applyPatchMapping(entry: OverlayEntry, mapping: MutableMap<String, EpisodeMappingRecord>) {
        val patch = entry.patch ?: error("patch-mapping for anidb=${entry.anidb} missing patch")
        val current = mapping[entry.anidb]
            ?: error("patch-mapping target mapping record missing for anidb=${entry.anidb}")
        val newRanges = current.ranges
            .filterNot { r ->
                patch.removeRanges.any { k ->
                    k.sourceSeason == r.sourceSeason && k.startEpisode == r.startEpisode &&
                        k.targetProvider == r.targetProvider
                }
            }
            .plus(patch.addRanges)
        val newExplicit = current.explicitMaps
            .filterNot { e ->
                patch.removeExplicitMaps.any { k ->
                    k.sourceSeason == e.sourceSeason && k.sourceEpisode == e.sourceEpisode &&
                        k.targetProvider == e.targetProvider
                }
            }
            .plus(patch.addExplicitMaps)
        mapping[entry.anidb] = current.copy(
            ranges = newRanges,
            explicitMaps = newExplicit,
            evidence = current.evidence + "overlay.patch-mapping"
        )
    }

    private fun mapToIdentityRecord(map: Map<String, Any?>): IdentityRecord {
        val kitsu = (map["kitsu"] as? String) ?: error("replace identity record missing kitsu")
        return IdentityRecord(
            kitsu = kitsu,
            mal = map["mal"] as? String,
            anilist = map["anilist"] as? String,
            anidb = map["anidb"] as? String,
            tmdb = map["tmdb"] as? String,
            tvdb = map["tvdb"] as? String,
            imdb = map["imdb"] as? String,
            mediaType = map["mediaType"] as? String,
            sourceType = map["sourceType"] as? String,
            tvdbSeason = map["tvdbSeason"] as? String,
            tmdbSeason = map["tmdbSeason"] as? String,
            tvdbEpisodeOffset = (map["tvdbEpisodeOffset"] as? Number)?.toInt(),
            tmdbEpisodeOffset = (map["tmdbEpisodeOffset"] as? Number)?.toInt(),
            hasMappingRules = (map["hasMappingRules"] as? Boolean) ?: false,
            evidence = listOf("overlay.replace")
        )
    }

    private fun mapToMappingRecord(anidb: String, map: Map<String, Any?>): EpisodeMappingRecord {
        @Suppress("UNCHECKED_CAST")
        val rangesRaw = (map["ranges"] as? List<Map<String, Any?>>) ?: emptyList()
        @Suppress("UNCHECKED_CAST")
        val explicitRaw = (map["explicitMaps"] as? List<Map<String, Any?>>) ?: emptyList()
        return EpisodeMappingRecord(
            anidb = anidb,
            name = map["name"] as? String,
            tvdbSeriesId = map["tvdbSeriesId"] as? String,
            tmdbTvId = map["tmdbTvId"] as? String,
            ranges = rangesRaw.map { r ->
                RangeRule(
                    sourceSeason = (r["sourceSeason"] as Number).toInt(),
                    startEpisode = (r["startEpisode"] as Number).toInt(),
                    endEpisode = (r["endEpisode"] as? Number)?.toInt(),
                    targetProvider = r["targetProvider"] as String,
                    targetSeason = (r["targetSeason"] as Number).toInt(),
                    offset = (r["offset"] as Number).toInt()
                )
            },
            explicitMaps = explicitRaw.map { e ->
                ExplicitMap(
                    sourceSeason = (e["sourceSeason"] as Number).toInt(),
                    sourceEpisode = (e["sourceEpisode"] as Number).toInt(),
                    targetProvider = e["targetProvider"] as String,
                    targetSeason = (e["targetSeason"] as Number).toInt(),
                    targetEpisode = (e["targetEpisode"] as Number).toInt()
                )
            },
            evidence = listOf("overlay.replace")
        )
    }
}
