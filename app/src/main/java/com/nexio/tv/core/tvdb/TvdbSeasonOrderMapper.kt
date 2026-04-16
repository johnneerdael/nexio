package com.nexio.tv.core.tvdb

import com.nexio.tv.data.remote.api.TvdbSeriesExtendedRecord
import com.nexio.tv.domain.model.TvdbEpisodeOrder
import com.nexio.tv.domain.model.TvdbSeasonOrderContext
import com.nexio.tv.domain.model.TvdbSeasonTypeSummary
import com.nexio.tv.domain.model.Video
import javax.inject.Inject

/**
 * Maps TVDB default season-type and episode order metadata into provider-neutral
 * domain types. Preserves canonical [Video.season] and [Video.episode] for Trakt
 * progress matching while storing TVDB order context alongside.
 */
class TvdbSeasonOrderMapper @Inject constructor() {

    /**
     * Builds series-level season-order context from a [TvdbSeriesExtendedRecord].
     *
     * Returns null only when [TvdbSeriesExtendedRecord.defaultSeasonType] is null
     * AND [TvdbSeriesExtendedRecord.seasonTypes] is empty.
     */
    fun buildSeriesOrderContext(series: TvdbSeriesExtendedRecord): TvdbSeasonOrderContext? {
        val seasonTypes = series.seasonTypes.orEmpty()
        if (series.defaultSeasonType == null && seasonTypes.isEmpty()) {
            return null
        }

        val matchingType = seasonTypes.firstOrNull { it.id == series.defaultSeasonType }

        val defaultSeasonTypeName = matchingType?.name?.trim()?.takeIf { it.isNotBlank() }
        val defaultSeasonTypeSlug = deriveSlug(matchingType?.type ?: matchingType?.name)

        val availableSeasonTypes = seasonTypes.mapNotNull { record ->
            if (record.id == null && record.name == null && record.type == null) return@mapNotNull null
            TvdbSeasonTypeSummary(
                id = record.id,
                name = record.name?.trim()?.takeIf { it.isNotBlank() },
                type = record.type?.trim()?.takeIf { it.isNotBlank() }
            )
        }

        return TvdbSeasonOrderContext(
            defaultSeasonTypeId = series.defaultSeasonType,
            defaultSeasonTypeName = defaultSeasonTypeName,
            defaultSeasonTypeSlug = defaultSeasonTypeSlug,
            availableSeasonTypes = availableSeasonTypes,
            alternateOrderPreservedButNotApplied = false
        )
    }

    /**
     * Maps per-episode TVDB default-order metadata from a [TvEpisodeMetadata].
     *
     * Returns null only when all order-relevant fields are null.
     */
    fun mapEpisodeOrder(episode: TvEpisodeMetadata): TvdbEpisodeOrder? {
        if (episode.seasonNumber == null && episode.episodeNumber == null &&
            episode.absoluteNumber == null && episode.airsAfterSeason == null &&
            episode.airsBeforeSeason == null && episode.airsBeforeEpisode == null
        ) {
            return null
        }

        val hasSpecialsPlacement = episode.airsAfterSeason != null ||
            episode.airsBeforeSeason != null ||
            episode.airsBeforeEpisode != null

        return TvdbEpisodeOrder(
            defaultSeason = episode.seasonNumber,
            defaultEpisode = episode.episodeNumber,
            absoluteNumber = episode.absoluteNumber,
            airsAfterSeason = episode.airsAfterSeason,
            airsBeforeSeason = episode.airsBeforeSeason,
            airsBeforeEpisode = episode.airsBeforeEpisode,
            alternateOrderPreservedButNotApplied = hasSpecialsPlacement
        )
    }

    /**
     * Applies TVDB episode-order metadata to a [Video] without changing canonical
     * [Video.season] or [Video.episode] keys. Only writes [Video.tvdbEpisodeOrder].
     */
    fun applyEpisodeOrder(video: Video, metadata: TvEpisodeMetadata): Video {
        return video.copy(tvdbEpisodeOrder = metadata.tvdbEpisodeOrder)
    }

    private fun deriveSlug(value: String?): String? {
        val trimmed = value?.trim()?.takeIf { it.isNotBlank() } ?: return null
        return trimmed.lowercase().replace(Regex("\\s+"), "-")
    }
}
