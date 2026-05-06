package com.nexio.tv.core.anime.projection

import com.nexio.tv.core.anime.ContentMediaKind
import com.nexio.tv.core.anime.KitsuMetadataService
import com.nexio.tv.core.tvdb.TvEpisodeMetadata
import com.nexio.tv.domain.model.Meta
import com.nexio.tv.domain.model.Video
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class DefaultAnimeSeasonDetailRepository @Inject constructor(
    private val animeSeasonProjectionResolver: AnimeSeasonProjectionResolver,
    private val kitsuMetadataService: KitsuMetadataService,
) : AnimeSeasonDetailRepository {

    override suspend fun resolveAndHydrateAnimeDetail(
        baseMeta: Meta,
        sourceKitsuId: String,
        requestedSeason: Int?,
    ): AnimeDetailResult {
        val cleanId = sourceKitsuId.removePrefix("kitsu:")
        val work = animeSeasonProjectionResolver.resolveWork(
            AnimeSourceIdentity(sourceKitsuId = cleanId, animeStremioId = null)
        )
        val presentation = animeSeasonProjectionResolver.resolveSeasonPresentation(
            work = work,
            sourceKitsuId = cleanId,
            requestedSeason = requestedSeason,
        )

        val episodeMap: Map<Pair<Int, Int>, TvEpisodeMetadata> = if (presentation.seasons.isEmpty()) {
            // Unresolved / Kitsu-only fallback: trust Kitsu's reported (season, episode).
            // Detail screen still works for anime that isn't in the curated pack — just no
            // cross-provider scrobble or thumbnail routing.
            fetchKitsuNativeEpisodes(cleanId)
        } else when (presentation.source) {
            SeasonPresentationSource.CURATED_PER_RESOURCE ->
                fetchPerResourceEpisodes(presentation)
            SeasonPresentationSource.CURATED_RANGE_RULES ->
                fetchRangeRuleEpisodes(work, cleanId)
        }

        if (episodeMap.isEmpty()) {
            return AnimeDetailResult.Error("Episode metadata is unavailable")
        }

        val hydratedVideos = buildAnimeEpisodeVideos(
            seriesId = baseMeta.id,
            episodeMap = episodeMap,
        )

        val hydratedMeta = baseMeta.copy(videos = hydratedVideos)
        return AnimeDetailResult.Success(meta = hydratedMeta, presentation = presentation)
    }

    /**
     * MHA-style: each curated tab points at its own Kitsu seasonal resource
     * via [AnimeSeasonTab.episodesKitsuMemberId]. Fetch each member and re-key
     * its episodes under the curated season number, then merge.
     */
    private suspend fun fetchPerResourceEpisodes(
        presentation: AnimeSeasonPresentation,
    ): Map<Pair<Int, Int>, TvEpisodeMetadata> {
        val merged = mutableMapOf<Pair<Int, Int>, TvEpisodeMetadata>()
        for (tab in presentation.seasons) {
            val memberId = tab.episodesKitsuMemberId ?: continue
            val memberEpisodes = kitsuMetadataService.fetchEpisodeEnrichment(
                rawId = "kitsu:$memberId",
                mediaKind = ContentMediaKind.SERIES,
                seasonNumbers = emptyList(),
            )
            for ((kitsuKey, episode) in memberEpisodes) {
                val episodeNumber = episode.episodeNumber ?: kitsuKey.second
                merged[tab.seasonNumber to episodeNumber] = episode.copy(
                    seasonNumber = tab.seasonNumber,
                    episodeNumber = episodeNumber,
                )
            }
        }
        return merged
    }

    /**
     * One-Piece-style: a single flat Kitsu resource holds every episode. Run
     * each Kitsu episode through [AnimeSeasonProjectionResolver.resolveEpisodeProjection]
     * to obtain its TVDB (season, episode) coordinate, then re-key under that.
     * Episodes that fail to project (no curated rule match) are dropped.
     */
    private suspend fun fetchRangeRuleEpisodes(
        work: AnimeWorkIdentity,
        sourceKitsuId: String,
    ): Map<Pair<Int, Int>, TvEpisodeMetadata> {
        val flatEpisodes = kitsuMetadataService.fetchEpisodeEnrichment(
            rawId = "kitsu:$sourceKitsuId",
            mediaKind = ContentMediaKind.SERIES,
            seasonNumbers = emptyList(),
        )
        val merged = mutableMapOf<Pair<Int, Int>, TvEpisodeMetadata>()
        for ((kitsuKey, episode) in flatEpisodes) {
            val source = SourceEpisodeCoordinate(
                sourceKitsuId = sourceKitsuId,
                season = kitsuKey.first,
                episode = kitsuKey.second,
            )
            val projection = animeSeasonProjectionResolver.resolveEpisodeProjection(
                work = work,
                sourceEpisode = source,
                target = EpisodeProjectionTarget.UI_DISPLAY,
            )
            val tvdbCoord = projection.tvdbCoordinate ?: continue
            merged[tvdbCoord.season to tvdbCoord.episode] = episode.copy(
                seasonNumber = tvdbCoord.season,
                episodeNumber = tvdbCoord.episode,
            )
        }
        return merged
    }

    private suspend fun fetchKitsuNativeEpisodes(
        sourceKitsuId: String,
    ): Map<Pair<Int, Int>, TvEpisodeMetadata> = kitsuMetadataService.fetchEpisodeEnrichment(
        rawId = "kitsu:$sourceKitsuId",
        mediaKind = ContentMediaKind.SERIES,
        seasonNumbers = emptyList(),
    )

    private fun buildAnimeEpisodeVideos(
        seriesId: String,
        episodeMap: Map<Pair<Int, Int>, TvEpisodeMetadata>,
    ): List<Video> = episodeMap.entries
        .sortedWith(
            compareBy<Map.Entry<Pair<Int, Int>, TvEpisodeMetadata>> { it.key.first }
                .thenBy { it.key.second }
        )
        .map { (key, episode) ->
            val seasonNumber = episode.seasonNumber ?: key.first
            val episodeNumber = episode.episodeNumber ?: key.second
            Video(
                id = "$seriesId:$seasonNumber:$episodeNumber",
                title = episode.title ?: "Episode $episodeNumber",
                released = episode.airDate,
                thumbnail = episode.thumbnail,
                streams = emptyList(),
                season = seasonNumber,
                episode = episodeNumber,
                overview = episode.overview,
                runtime = episode.runtimeMinutes,
            )
        }
}
