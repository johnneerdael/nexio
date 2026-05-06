package com.nexio.tv.core.trace

import com.nexio.tv.core.anime.projection.AnimeEpisodeProjection
import com.nexio.tv.core.anime.projection.AnimeSeasonPresentation
import com.nexio.tv.core.anime.projection.AnimeWorkIdentity
import com.nexio.tv.core.anime.projection.EpisodeProjectionTarget
import com.nexio.tv.core.anime.projection.FallbackReason
import com.nexio.tv.data.repository.ScrobbleRejectionReason
import com.nexio.tv.domain.model.TrackingProvider
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class AnimeProjectionTraceEvents @Inject constructor(
    private val sink: TraceMetadataEvents,
) {
    fun emitWorkResolved(identity: AnimeWorkIdentity) {
        sink.emitAnimeWorkResolved(
            sourceKitsuId = identity.primaryKitsuId.orEmpty(),
            groupKey = identity.groupKey.value,
            memberCount = identity.memberKitsuIds.size,
            confidence = identity.confidence.name,
            evidence = identity.evidence,
        )
    }

    fun emitSeasonProjectionBuilt(presentation: AnimeSeasonPresentation) {
        sink.emitAnimeSeasonProjectionBuilt(
            groupKey = presentation.work.groupKey.value,
            selectedSeason = presentation.selectedSeason,
            seasonCount = presentation.seasons.size,
            source = presentation.source.name,
            confidence = presentation.confidence.name,
        )
    }

    fun emitEpisodeCoordinateResolved(projection: AnimeEpisodeProjection, target: EpisodeProjectionTarget) {
        val coord = projection.targetCoordinate ?: projection.displayCoordinate
        sink.emitAnimeEpisodeCoordinateResolved(
            sourceKitsuId = projection.sourceKitsuId,
            sourceSeason = projection.sourceKitsuCoordinate.season,
            sourceEpisode = projection.sourceKitsuCoordinate.episode,
            targetProvider = coord.provider.name,
            targetSeriesId = coord.seriesId,
            targetSeason = coord.season,
            targetEpisode = coord.episode,
            confidence = projection.confidence.name,
            uses = listOf(target.name),
            evidence = projection.evidence,
        )
    }

    fun emitEpisodeCoordinateUnresolved(
        sourceKitsuId: String,
        season: Int,
        episode: Int,
        target: EpisodeProjectionTarget,
        fallbackReason: FallbackReason?,
    ) {
        sink.emitAnimeEpisodeCoordinateUnresolved(
            sourceKitsuId = sourceKitsuId,
            sourceSeason = season,
            sourceEpisode = episode,
            target = target.name,
            fallbackReason = fallbackReason?.name ?: "NONE",
            confidence = "LOW",
        )
    }

    fun emitCuratedHit(source: String, kitsuId: String, target: String) {
        sink.emitAnimeCuratedHit(
            source = source,
            kitsuId = kitsuId,
            target = target,
        )
    }

    fun emitUnresolvedTopKitsuId(kitsuId: String, reason: String) {
        sink.emitAnimeUnresolvedTopKitsuId(
            kitsuId = kitsuId,
            reason = reason,
        )
    }

    fun emitScrobbleRejected(
        contentId: String,
        reason: ScrobbleRejectionReason,
        provider: TrackingProvider,
    ) {
        sink.emitAnimeScrobbleRejected(
            contentId = contentId,
            reason = reason.name,
            provider = provider.name,
        )
    }

    fun emitPremiumThumbnailCoordinateSelected(
        sourceKitsuId: String,
        projection: AnimeEpisodeProjection,
    ) {
        val coord = projection.premiumArtworkCoordinate
        sink.emitAnimePremiumThumbnailCoordinateSelected(
            sourceKitsuId = sourceKitsuId,
            selectedProvider = coord?.provider?.name,
            selectedSeriesId = coord?.seriesId,
            selectedSeason = coord?.season,
            selectedEpisode = coord?.episode,
            confidence = projection.confidence.name,
            fallbackToPrimary = coord == null,
        )
    }
}
