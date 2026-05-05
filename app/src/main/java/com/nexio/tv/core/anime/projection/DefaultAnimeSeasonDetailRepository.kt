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

        val selectedTab = presentation.seasons.firstOrNull { it.seasonNumber == presentation.selectedSeason }
        val memberId = selectedTab?.episodesKitsuMemberId ?: cleanId

        val episodeMap = kitsuMetadataService.fetchEpisodeEnrichment(
            rawId = "kitsu:$memberId",
            mediaKind = ContentMediaKind.SERIES,
            seasonNumbers = listOf(presentation.selectedSeason),
        )

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
