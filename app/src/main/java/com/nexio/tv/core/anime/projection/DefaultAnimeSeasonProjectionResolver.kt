package com.nexio.tv.core.anime.projection

import com.nexio.tv.core.anime.AnimeIdMappingService
import com.nexio.tv.core.anime.ContentMediaKind
import com.nexio.tv.core.anime.KitsuMetadataService
import com.nexio.tv.domain.model.ProviderIds
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class DefaultAnimeSeasonProjectionResolver @Inject constructor(
    private val idMappingService: AnimeIdMappingService,
    private val kitsuMetadataService: KitsuMetadataService,
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
        return AnimeWorkIdentity(
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
    }

    override suspend fun resolveSeasonPresentation(
        work: AnimeWorkIdentity,
        sourceKitsuId: String,
        requestedSeason: Int?,
    ): AnimeSeasonPresentation {
        val cleanSourceId = sourceKitsuId.removePrefix("kitsu:")
        // Fetch episodes for every member kitsu resource to discover which seasons each carries.
        val perMember = work.memberKitsuIds.associateWith { memberId ->
            kitsuMetadataService.fetchEpisodeEnrichment(
                rawId = "kitsu:$memberId",
                mediaKind = ContentMediaKind.SERIES,
                seasonNumbers = emptyList(),
            )
        }
        // Build season → member mapping (first member that reports a season wins).
        val seasonToMember = mutableMapOf<Int, String>()
        val seasonToCount = mutableMapOf<Int, Int>()
        perMember.forEach { (memberId, eps) ->
            eps.keys.forEach { (season, _) ->
                seasonToMember.putIfAbsent(season, memberId)
                seasonToCount[season] = (seasonToCount[season] ?: 0) + 1
            }
        }

        // Flat-franchise detection: single season, single member, high episode count.
        val isFlatFranchise = seasonToMember.size == 1
            && (perMember[cleanSourceId]?.size ?: 0) >= FLAT_KITSU_MIN_EPISODES
        val source = if (isFlatFranchise) SeasonPresentationSource.KITSU_FLAT_FALLBACK
                     else SeasonPresentationSource.KITSU_SEASON_NUMBERS

        // Click-source-aware default: prefer the season the clicked kitsu resource actually carries.
        val sourceSeasons = perMember[cleanSourceId]?.keys?.map { it.first }?.toSet().orEmpty()
        val defaultSelected = requestedSeason
            ?: sourceSeasons.minOrNull()
            ?: seasonToMember.keys.minOrNull()
            ?: 1

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

        return AnimeSeasonPresentation(
            work = work,
            seasons = tabs,
            selectedSeason = defaultSelected,
            source = source,
            confidence = if (isFlatFranchise) CoordinateConfidence.LOW else CoordinateConfidence.HIGH,
        )
    }

    private companion object {
        private const val FLAT_KITSU_MIN_EPISODES = 50
    }

    override suspend fun resolveEpisodeProjection(
        work: AnimeWorkIdentity,
        sourceEpisode: SourceEpisodeCoordinate,
        target: EpisodeProjectionTarget,
    ): AnimeEpisodeProjection = TODO("Task 1.8")

    private fun unknownWork(source: AnimeSourceIdentity): AnimeWorkIdentity = AnimeWorkIdentity(
        groupKey = AnimeWorkGroupKey.preferred(null, null, null, source.sourceKitsuId),
        primaryKitsuId = source.sourceKitsuId,
        memberKitsuIds = setOfNotNull(source.sourceKitsuId),
        providerIds = ProviderIds(kitsu = source.sourceKitsuId),
        confidence = AnimeGroupingConfidence.LOW,
        evidence = listOf("no-mapping-record"),
    )
}
