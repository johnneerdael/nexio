package com.nexio.tv.core.anime.projection

import com.nexio.tv.core.anime.AnimeEpisodeMappingRecord
import com.nexio.tv.core.anime.AnimeIdMapRecord
import com.nexio.tv.core.anime.AnimeIdMappingService
import com.nexio.tv.core.trace.AnimeProjectionTraceEvents
import com.nexio.tv.domain.model.ProviderId
import com.nexio.tv.domain.model.ProviderIds
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class DefaultAnimeSeasonProjectionResolver @Inject constructor(
    private val mappingService: AnimeIdMappingService,
    private val store: AnimeEpisodeCoordinateStore,
    private val traceEvents: AnimeProjectionTraceEvents,
) : AnimeSeasonProjectionResolver {

    override suspend fun resolveWork(source: AnimeSourceIdentity): AnimeWorkIdentity {
        val kitsuId = source.sourceKitsuId?.removePrefix("kitsu:")?.takeIf { it.isNotBlank() }
            ?: return unknownWork(source)
        val record = mappingService.recordForKitsuId(kitsuId) ?: return unknownWork(source)

        val memberRecords = mappingService.allSeriesRecordsSharingTvdb(record)
        val memberIds = memberRecords.map { it.kitsu }.toSet()
        val primary = memberRecords.minByOrNull { it.kitsu.toIntOrNull() ?: Int.MAX_VALUE }?.kitsu

        val groupKey = AnimeWorkGroupKey.preferred(record.tvdb, record.imdb, record.tmdb, kitsuId)
        val confidence = when {
            !record.tvdb.isNullOrBlank() -> AnimeGroupingConfidence.HIGH
            !record.imdb.isNullOrBlank() -> AnimeGroupingConfidence.MEDIUM
            else -> AnimeGroupingConfidence.LOW
        }
        val result = AnimeWorkIdentity(
            groupKey = groupKey,
            primaryKitsuId = primary,
            memberKitsuIds = memberIds,
            providerIds = ProviderIds(
                tvdb = record.tvdb, imdb = record.imdb, tmdb = record.tmdb,
                kitsu = kitsuId, mal = record.mal, anilist = record.anilist, anidb = record.anidb
            ),
            confidence = confidence,
            evidence = listOfNotNull(
                record.tvdb?.let { "kitsu.tvdb=$it" },
                record.imdb?.let { "kitsu.imdb=$it" },
                record.tmdb?.let { "kitsu.tmdb=$it" }
            )
        )
        traceEvents.emitWorkResolved(result)
        return result
    }

    override suspend fun resolveSeasonPresentation(
        work: AnimeWorkIdentity,
        sourceKitsuId: String,
        requestedSeason: Int?,
    ): AnimeSeasonPresentation {
        val cleanSourceId = sourceKitsuId.removePrefix("kitsu:")
        val record = mappingService.recordForKitsuId(cleanSourceId)
            ?: return unresolvedPresentation(work, FallbackReason.KITSU_NOT_IN_PACK)

        val mapping = record.anidb?.let { mappingService.episodeMappingForAnidb(it) }
        if (mapping != null && mapping.ranges.isNotEmpty()) {
            return rangeRulePresentation(work, mapping, requestedSeason)
        }

        val seasonals = mappingService.allSeriesRecordsSharingTvdb(record)
            .mapNotNull { rec ->
                val marker = SeasonMarker.fromWire(rec.tvdbSeason)
                if (marker is SeasonMarker.Number) rec to marker else null
            }
        if (seasonals.isNotEmpty()) {
            val sourceOwnSeason = (SeasonMarker.fromWire(record.tvdbSeason) as? SeasonMarker.Number)?.season
            return perResourcePresentation(work, seasonals, requestedSeason, sourceOwnSeason)
        }

        val fallback = when (SeasonMarker.fromWire(record.tvdbSeason)) {
            SeasonMarker.Hentai -> FallbackReason.SEASON_MARKER_HENTAI
            SeasonMarker.Unknown -> FallbackReason.SEASON_MARKER_UNKNOWN
            else -> FallbackReason.NO_CURATED_SEASON
        }
        return unresolvedPresentation(work, fallback)
    }

    override suspend fun resolveEpisodeProjection(
        work: AnimeWorkIdentity,
        sourceEpisode: SourceEpisodeCoordinate,
        target: EpisodeProjectionTarget,
    ): AnimeEpisodeProjection {
        store.get(work.groupKey, sourceEpisode, target)?.let { return it }

        val record = mappingService.recordForKitsuId(sourceEpisode.sourceKitsuId)
        val computed = if (record == null) {
            unresolvedProjection(sourceEpisode, FallbackReason.KITSU_NOT_IN_PACK)
        } else {
            project(record, sourceEpisode, target)
        }
        store.put(work.groupKey, sourceEpisode, target, computed)
        if (computed.scrobbleCoordinate != null) {
            traceEvents.emitEpisodeCoordinateResolved(computed, target)
        } else {
            traceEvents.emitEpisodeCoordinateUnresolved(
                sourceKitsuId = sourceEpisode.sourceKitsuId,
                season = sourceEpisode.season,
                episode = sourceEpisode.episode,
                target = target,
                fallbackReason = computed.fallbackReason,
            )
        }
        return computed
    }

    private fun project(
        record: AnimeIdMapRecord,
        sourceEpisode: SourceEpisodeCoordinate,
        target: EpisodeProjectionTarget,
    ): AnimeEpisodeProjection {
        val mapping = record.anidb?.let { mappingService.episodeMappingForAnidb(it) }
        val targetProvider = providerFor(target)
        val (targetCoord, fallback) = projectVia(mapping, record, sourceEpisode, targetProvider)
        val sourceCoord = EpisodeCoordinate(
            ProviderId.KITSU, sourceEpisode.sourceKitsuId, sourceEpisode.season, sourceEpisode.episode
        )
        val tvdbCoord = if (targetProvider == ProviderId.TVDB) targetCoord
            else projectVia(mapping, record, sourceEpisode, ProviderId.TVDB).first
        val tmdbCoord = if (targetProvider == ProviderId.TMDB) targetCoord
            else projectVia(mapping, record, sourceEpisode, ProviderId.TMDB).first

        val confidence = when {
            fallback != null -> CoordinateConfidence.LOW
            targetCoord?.provider == ProviderId.TVDB -> CoordinateConfidence.HIGH
            targetCoord != null -> CoordinateConfidence.MEDIUM
            else -> CoordinateConfidence.UNKNOWN
        }
        val isScrobble = target == EpisodeProjectionTarget.TRAKT_SCROBBLE ||
            target == EpisodeProjectionTarget.SIMKL_SCROBBLE
        val scrobbleCoord = when {
            isScrobble && confidence == CoordinateConfidence.HIGH -> tvdbCoord
            isScrobble -> null
            else -> tvdbCoord
        }
        return AnimeEpisodeProjection(
            sourceKitsuId = sourceEpisode.sourceKitsuId,
            sourceKitsuCoordinate = sourceCoord,
            displayCoordinate = tvdbCoord ?: sourceCoord,
            targetCoordinate = targetCoord,
            scrobbleCoordinate = scrobbleCoord,
            premiumArtworkCoordinate = if (confidence != CoordinateConfidence.LOW) (tvdbCoord ?: tmdbCoord) else null,
            tvdbCoordinate = tvdbCoord,
            tmdbCoordinate = tmdbCoord,
            confidence = confidence,
            fallbackReason = fallback,
            evidence = listOfNotNull(
                record.tvdb?.let { "kitsu.tvdb=$it" },
                record.tmdb?.let { "kitsu.tmdb=$it" },
                if (mapping != null) "curated.range-or-explicit" else null
            )
        )
    }

    private fun projectVia(
        mapping: AnimeEpisodeMappingRecord?,
        record: AnimeIdMapRecord,
        sourceEpisode: SourceEpisodeCoordinate,
        targetProvider: ProviderId,
    ): Pair<EpisodeCoordinate?, FallbackReason?> {
        val seriesId = when (targetProvider) {
            ProviderId.TVDB -> mapping?.tvdbSeriesId ?: record.tvdb
            ProviderId.TMDB -> mapping?.tmdbTvId ?: record.tmdb
            else -> null
        }
        if (seriesId == null) return null to FallbackReason.NO_CURATED_SEASON

        if (mapping != null) {
            val explicit = mapping.explicitMaps.firstOrNull {
                it.sourceSeason == sourceEpisode.season &&
                    it.sourceEpisode == sourceEpisode.episode &&
                    it.targetProvider == targetProvider.name
            }
            if (explicit != null) {
                return EpisodeCoordinate(
                    targetProvider, seriesId, explicit.targetSeason, explicit.targetEpisode
                ) to null
            }
            val range = mapping.ranges.firstOrNull {
                it.targetProvider == targetProvider.name &&
                    it.sourceSeason == sourceEpisode.season &&
                    sourceEpisode.episode >= it.startEpisode &&
                    (it.endEpisode == null || sourceEpisode.episode <= it.endEpisode)
            }
            if (range != null) {
                return EpisodeCoordinate(
                    targetProvider, seriesId, range.targetSeason, sourceEpisode.episode + range.offset
                ) to null
            }
            // mapping exists but no rule matched — out of range
            return null to FallbackReason.EPISODE_OUT_OF_RANGE
        }

        val seasonField = if (targetProvider == ProviderId.TVDB) record.tvdbSeason else record.tmdbSeason
        val offsetField =
            if (targetProvider == ProviderId.TVDB) record.tvdbEpisodeOffset else record.tmdbEpisodeOffset
        return when (val marker = SeasonMarker.fromWire(seasonField)) {
            null -> null to FallbackReason.NO_CURATED_SEASON
            SeasonMarker.Hentai -> null to FallbackReason.SEASON_MARKER_HENTAI
            SeasonMarker.Unknown -> null to FallbackReason.SEASON_MARKER_UNKNOWN
            SeasonMarker.Absolute -> null to FallbackReason.EPISODE_OUT_OF_RANGE
            is SeasonMarker.Number -> EpisodeCoordinate(
                targetProvider, seriesId, marker.season, sourceEpisode.episode + (offsetField ?: 0)
            ) to null
        }
    }

    private fun providerFor(target: EpisodeProjectionTarget): ProviderId = when (target) {
        EpisodeProjectionTarget.TRAKT_SCROBBLE,
        EpisodeProjectionTarget.SIMKL_SCROBBLE,
        EpisodeProjectionTarget.UI_DISPLAY,
        EpisodeProjectionTarget.PREMIUM_THUMBNAIL,
        EpisodeProjectionTarget.CONTINUE_WATCHING,
        EpisodeProjectionTarget.EPISODE_RATING -> ProviderId.TVDB
    }

    private fun perResourcePresentation(
        work: AnimeWorkIdentity,
        seasonals: List<Pair<AnimeIdMapRecord, SeasonMarker.Number>>,
        requestedSeason: Int?,
        sourceOwnSeason: Int?,
    ): AnimeSeasonPresentation {
        val tabs = seasonals.distinctBy { it.second.season }
            .sortedBy { it.second.season }
            .map { (rec, marker) ->
                AnimeSeasonTab(
                    seasonNumber = marker.season,
                    title = null,
                    episodeCount = null,
                    episodesKitsuMemberId = rec.kitsu,
                    isFlatFallback = false,
                )
            }
        val auto = sourceOwnSeason?.takeIf { own -> tabs.any { it.seasonNumber == own } }
            ?: tabs.firstOrNull()?.seasonNumber
            ?: 1
        val selected = requestedSeason?.takeIf { req -> tabs.any { it.seasonNumber == req } } ?: auto
        return AnimeSeasonPresentation(
            work = work,
            seasons = tabs,
            selectedSeason = selected,
            source = SeasonPresentationSource.CURATED_PER_RESOURCE,
            confidence = CoordinateConfidence.HIGH,
        )
    }

    private fun rangeRulePresentation(
        work: AnimeWorkIdentity,
        mapping: AnimeEpisodeMappingRecord,
        requestedSeason: Int?,
    ): AnimeSeasonPresentation {
        val tabs = mapping.ranges.filter { it.targetProvider == "TVDB" }
            .map { it.targetSeason }.distinct().sorted().map { season ->
                AnimeSeasonTab(
                    seasonNumber = season,
                    title = null,
                    episodeCount = null,
                    episodesKitsuMemberId = null,
                    isFlatFallback = false,
                )
            }
        val auto = tabs.firstOrNull()?.seasonNumber ?: 1
        val selected = requestedSeason?.takeIf { req -> tabs.any { it.seasonNumber == req } } ?: auto
        return AnimeSeasonPresentation(
            work = work,
            seasons = tabs,
            selectedSeason = selected,
            source = SeasonPresentationSource.CURATED_RANGE_RULES,
            confidence = CoordinateConfidence.HIGH,
        )
    }

    private fun unresolvedPresentation(
        work: AnimeWorkIdentity,
        reason: FallbackReason,
    ): AnimeSeasonPresentation = AnimeSeasonPresentation(
        work = work,
        seasons = emptyList(),
        selectedSeason = 1,
        source = SeasonPresentationSource.CURATED_PER_RESOURCE,
        confidence = CoordinateConfidence.LOW,
        fallbackReason = reason,
    )

    private fun unresolvedProjection(
        sourceEpisode: SourceEpisodeCoordinate,
        reason: FallbackReason,
    ): AnimeEpisodeProjection {
        val src = EpisodeCoordinate(
            ProviderId.KITSU, sourceEpisode.sourceKitsuId, sourceEpisode.season, sourceEpisode.episode
        )
        return AnimeEpisodeProjection(
            sourceKitsuId = sourceEpisode.sourceKitsuId,
            sourceKitsuCoordinate = src,
            displayCoordinate = src,
            targetCoordinate = null,
            scrobbleCoordinate = null,
            premiumArtworkCoordinate = null,
            tvdbCoordinate = null,
            tmdbCoordinate = null,
            confidence = CoordinateConfidence.LOW,
            fallbackReason = reason,
            evidence = emptyList(),
        )
    }

    private fun unknownWork(source: AnimeSourceIdentity): AnimeWorkIdentity = AnimeWorkIdentity(
        groupKey = AnimeWorkGroupKey.preferred(null, null, null, source.sourceKitsuId),
        primaryKitsuId = source.sourceKitsuId,
        memberKitsuIds = setOfNotNull(source.sourceKitsuId),
        providerIds = ProviderIds(kitsu = source.sourceKitsuId),
        confidence = AnimeGroupingConfidence.LOW,
        evidence = listOf("no-mapping-record"),
    )
}
