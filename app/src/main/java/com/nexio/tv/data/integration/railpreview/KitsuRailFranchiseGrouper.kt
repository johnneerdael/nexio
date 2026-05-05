package com.nexio.tv.data.integration.railpreview

import com.nexio.tv.core.anime.AnimeIdMapRecord
import com.nexio.tv.core.anime.AnimeIdMappingService
import com.nexio.tv.core.anime.projection.AnimeWorkGroupKey
import com.nexio.tv.domain.model.ContentType
import com.nexio.tv.domain.model.RailItemPreview
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class KitsuRailFranchiseGrouper @Inject constructor(
    private val idMappingService: AnimeIdMappingService
) {

    fun group(items: List<RailItemPreview>): List<RailItemPreview> {
        val keys: List<AnimeWorkGroupKey?> = items.map { groupKeyFor(it) }
        val keyCount = keys.filterNotNull().groupingBy { it }.eachCount()

        val emitted = mutableSetOf<AnimeWorkGroupKey>()
        val result = mutableListOf<RailItemPreview>()

        for ((index, item) in items.withIndex()) {
            val key = keys[index]
            if (key == null || keyCount[key] == 1) {
                val record = item.stableIds.kitsu?.let { idMappingService.recordForKitsuId(it) }
                result += if (record != null) item.withEnrichedStableIds(record) else item
            } else {
                if (emitted.add(key)) {
                    val record = item.stableIds.kitsu?.let { idMappingService.recordForKitsuId(it) }
                    result += if (record != null) item.withEnrichedStableIds(record) else item
                }
                // Subsequent items from the same franchise group are dropped.
            }
        }
        return result
    }

    private fun groupKeyFor(item: RailItemPreview): AnimeWorkGroupKey? {
        if (item.itemType != ContentType.SERIES) return null
        val kitsuId = item.stableIds.kitsu?.takeIf { it.isNotBlank() } ?: return null
        val record = idMappingService.recordForKitsuId(kitsuId) ?: return null
        if (!isSeriesTvEntry(record)) return null
        if (record.tvdb.isNullOrBlank() && record.imdb.isNullOrBlank() && record.tmdb.isNullOrBlank()) return null
        return AnimeWorkGroupKey.preferred(record.tvdb, record.imdb, record.tmdb, kitsuId)
    }

    private fun isSeriesTvEntry(record: AnimeIdMapRecord): Boolean {
        val mediaType = record.mediaType?.lowercase() ?: return true
        val sourceType = record.sourceType?.lowercase() ?: ""
        return mediaType == "series" && sourceType in setOf("tv", "")
    }

    private fun RailItemPreview.withEnrichedStableIds(record: AnimeIdMapRecord): RailItemPreview =
        copy(
            stableIds = stableIds.copy(
                tvdb = record.tvdb ?: stableIds.tvdb,
                imdb = record.imdb ?: stableIds.imdb,
                tmdb = record.tmdb ?: stableIds.tmdb,
                mal = record.mal ?: stableIds.mal,
                anilist = record.anilist ?: stableIds.anilist,
                anidb = record.anidb ?: stableIds.anidb,
            )
        )
}
