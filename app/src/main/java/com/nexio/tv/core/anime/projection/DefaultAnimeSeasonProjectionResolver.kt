package com.nexio.tv.core.anime.projection

import com.nexio.tv.core.anime.AnimeIdMappingService
import com.nexio.tv.core.anime.ContentMediaKind
import com.nexio.tv.core.anime.KitsuMetadataService
import com.nexio.tv.core.trace.AnimeProjectionTraceEvents
import com.nexio.tv.domain.model.ProviderId
import com.nexio.tv.domain.model.ProviderIds
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class DefaultAnimeSeasonProjectionResolver @Inject constructor(
    private val idMappingService: AnimeIdMappingService,
    private val kitsuMetadataService: KitsuMetadataService,
    private val store: AnimeEpisodeCoordinateStore,
    private val traceEvents: AnimeProjectionTraceEvents,
    private val presentationCache: AnimeSeasonPresentationCache,
) : AnimeSeasonProjectionResolver {

    override suspend fun resolveWork(source: AnimeSourceIdentity): AnimeWorkIdentity {
        val kitsuId = source.sourceKitsuId?.removePrefix("kitsu:")?.takeIf { it.isNotBlank() }
            ?: return unknownWork(source)
        val record = idMappingService.recordForKitsuId(kitsuId)
            ?: return unknownWork(source)

        val memberRecords = idMappingService.allSeriesRecordsSharingTvdb(record)
        val memberIds = memberRecords.map { it.kitsu }.toSet()
        val primary = memberRecords.minByOrNull { it.kitsu.toIntOrNull() ?: Int.MAX_VALUE }?.kitsu

        val groupKey = AnimeWorkGroupKey.preferred(
            tvdbId = record.tvdb,
            imdbId = record.imdb,
            tmdbId = record.tmdb,
            sourceKitsuId = kitsuId,
        )
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
                tvdb = record.tvdb,
                imdb = record.imdb,
                tmdb = record.tmdb,
                kitsu = kitsuId,
                mal = record.mal,
                anilist = record.anilist,
                anidb = record.anidb,
            ),
            confidence = confidence,
            evidence = listOfNotNull(
                record.tvdb?.let { "kitsu.tvdb=$it" },
                record.imdb?.let { "kitsu.imdb=$it" },
                record.tmdb?.let { "kitsu.tmdb=$it" },
            ),
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

        presentationCache.get(work.groupKey, cleanSourceId)?.let { cached ->
            return if (requestedSeason != null && cached.seasons.any { it.seasonNumber == requestedSeason })
                cached.copy(selectedSeason = requestedSeason)
            else
                cached
        }

        val perMember = work.memberKitsuIds.associateWith { memberId ->
            kitsuMetadataService.fetchEpisodeEnrichment(
                rawId = "kitsu:$memberId",
                mediaKind = ContentMediaKind.SERIES,
                seasonNumbers = emptyList(),
            )
        }
        val seasonToMember = mutableMapOf<Int, String>()
        val seasonToCount = mutableMapOf<Int, Int>()
        perMember.forEach { (memberId, eps) ->
            eps.keys.forEach { (season, _) ->
                seasonToMember.putIfAbsent(season, memberId)
                seasonToCount[season] = (seasonToCount[season] ?: 0) + 1
            }
        }

        val isFlatFranchise = seasonToMember.size == 1
            && (perMember[cleanSourceId]?.size ?: 0) >= FLAT_KITSU_MIN_EPISODES
        val source = if (isFlatFranchise) SeasonPresentationSource.KITSU_FLAT_FALLBACK
                     else SeasonPresentationSource.KITSU_SEASON_NUMBERS

        val sourceSeasons = perMember[cleanSourceId]?.keys?.map { it.first }?.toSet().orEmpty()
        val autoSelected = sourceSeasons.minOrNull()
            ?: seasonToMember.keys.minOrNull()
            ?: 1
        val defaultSelected = requestedSeason?.takeIf { seasonToMember.containsKey(it) } ?: autoSelected

        val tabs = seasonToMember.entries
            .sortedBy { it.key }
            .map { (season, memberId) ->
                AnimeSeasonTab(
                    seasonNumber = season,
                    title = null,
                    episodeCount = seasonToCount[season],
                    episodesKitsuMemberId = memberId,
                    isFlatFallback = isFlatFranchise,
                )
            }

        val presentation = AnimeSeasonPresentation(
            work = work,
            seasons = tabs,
            selectedSeason = defaultSelected,
            source = source,
            confidence = if (isFlatFranchise) CoordinateConfidence.LOW else CoordinateConfidence.HIGH,
        )
        traceEvents.emitSeasonProjectionBuilt(presentation)
        presentationCache.put(work.groupKey, cleanSourceId, presentation.copy(selectedSeason = autoSelected))
        return presentation
    }

    private companion object {
        private const val FLAT_KITSU_MIN_EPISODES = 50
    }

    override suspend fun resolveEpisodeProjection(
        work: AnimeWorkIdentity,
        sourceEpisode: SourceEpisodeCoordinate,
        target: EpisodeProjectionTarget,
    ): AnimeEpisodeProjection {
        store.get(work.groupKey, sourceEpisode, target)?.let { return it }
        val computed = computeEpisodeProjection(work, sourceEpisode, target)
        store.put(work.groupKey, sourceEpisode, target, computed)
        val isScrobbleTarget = target == EpisodeProjectionTarget.TRAKT_SCROBBLE || target == EpisodeProjectionTarget.SIMKL_SCROBBLE
        if (computed.scrobbleCoordinate != null || !isScrobbleTarget) {
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

    private suspend fun computeEpisodeProjection(
        work: AnimeWorkIdentity,
        sourceEpisode: SourceEpisodeCoordinate,
        target: EpisodeProjectionTarget,
    ): AnimeEpisodeProjection {
        val sourceKitsuCoord = EpisodeCoordinate(
            provider = ProviderId.KITSU,
            seriesId = sourceEpisode.sourceKitsuId,
            season = sourceEpisode.season,
            episode = sourceEpisode.episode,
        )
        val record = idMappingService.recordForKitsuId(sourceEpisode.sourceKitsuId)
        val tvdbId = record?.tvdb?.takeIf { it.isNotBlank() }
        val tmdbId = record?.tmdb?.takeIf { it.isNotBlank() }

        val presentation = resolveSeasonPresentation(work, sourceEpisode.sourceKitsuId, requestedSeason = null)
        val isFlatFranchise = presentation.source == SeasonPresentationSource.KITSU_FLAT_FALLBACK

        val tvdbCoord = tvdbId?.let { id ->
            if (isFlatFranchise) null
            else EpisodeCoordinate(ProviderId.TVDB, id, sourceEpisode.season, sourceEpisode.episode)
        }
        val tmdbCoord = tmdbId?.let { id ->
            if (isFlatFranchise) null
            else EpisodeCoordinate(ProviderId.TMDB, id, sourceEpisode.season, sourceEpisode.episode)
        }

        val confidence = when {
            isFlatFranchise -> CoordinateConfidence.LOW
            tvdbCoord != null -> CoordinateConfidence.HIGH
            tmdbCoord != null -> CoordinateConfidence.MEDIUM
            else -> CoordinateConfidence.UNKNOWN
        }
        val fallbackReason = when {
            isFlatFranchise -> FallbackReason.LOW_CONFIDENCE_FLAT_KITSU
            tvdbCoord == null && tmdbCoord == null -> FallbackReason.NO_TVDB_MAPPING
            else -> null
        }

        val scrobbleCoord = when (target) {
            EpisodeProjectionTarget.TRAKT_SCROBBLE,
            EpisodeProjectionTarget.SIMKL_SCROBBLE ->
                if (confidence == CoordinateConfidence.HIGH) tvdbCoord else null
            else -> tvdbCoord
        }
        val artworkCoord = if (confidence != CoordinateConfidence.LOW) (tvdbCoord ?: tmdbCoord) else null
        val displayCoord = tvdbCoord ?: sourceKitsuCoord

        val targetCoordinate = when (target) {
            EpisodeProjectionTarget.UI_DISPLAY -> tvdbCoord ?: sourceKitsuCoord
            EpisodeProjectionTarget.TRAKT_SCROBBLE,
            EpisodeProjectionTarget.SIMKL_SCROBBLE -> scrobbleCoord
            EpisodeProjectionTarget.PREMIUM_THUMBNAIL -> artworkCoord
            EpisodeProjectionTarget.CONTINUE_WATCHING -> tvdbCoord ?: tmdbCoord ?: sourceKitsuCoord
            EpisodeProjectionTarget.EPISODE_RATING -> tvdbCoord ?: tmdbCoord
        }

        return AnimeEpisodeProjection(
            sourceKitsuId = sourceEpisode.sourceKitsuId,
            sourceKitsuCoordinate = sourceKitsuCoord,
            displayCoordinate = displayCoord,
            targetCoordinate = targetCoordinate,
            scrobbleCoordinate = scrobbleCoord,
            premiumArtworkCoordinate = artworkCoord,
            tvdbCoordinate = tvdbCoord,
            tmdbCoordinate = tmdbCoord,
            confidence = confidence,
            fallbackReason = fallbackReason,
            evidence = listOfNotNull(
                tvdbId?.let { "kitsu.tvdb=$it" },
                tmdbId?.let { "kitsu.tmdb=$it" },
                "source.member-count=${work.memberKitsuIds.size}",
                if (isFlatFranchise) "flat-franchise=true" else null,
            ),
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
