package com.nexio.tv.core.anime

import android.util.Log
import com.nexio.tv.core.tvdb.TvEpisodeMetadata
import com.nexio.tv.core.tvdb.TvMetadataEnrichment
import com.nexio.tv.core.tvdb.TvSeasonEpisode
import com.nexio.tv.data.remote.api.KitsuApi
import com.nexio.tv.data.remote.api.KitsuAnimeResource
import com.nexio.tv.data.remote.api.KitsuImage
import com.nexio.tv.data.repository.KitsuAuthService
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private const val TAG = "KitsuMetadata"
private const val KITSU_EPISODE_PAGE_LIMIT = 20
private const val KITSU_EPISODE_MAX_PAGES = 100

@Singleton
class KitsuMetadataService @Inject constructor(
    private val api: KitsuApi,
    private val idMappingService: AnimeIdMappingService,
    private val kitsuAuthService: KitsuAuthService
) {
    suspend fun fetchEnrichment(rawId: String, mediaKind: ContentMediaKind): TvMetadataEnrichment? =
        withContext(Dispatchers.IO) {
            if (!kitsuAuthService.providerEnabled()) return@withContext null
            val animeId = AnimeStremioId.parse(rawId) ?: return@withContext null
            val kitsuId = idMappingService.resolveKitsuId(animeId, mediaKind) ?: return@withContext null
            val authorization = kitsuAuthService.validAccessToken()?.let { "Bearer $it" }
            val response = runCatching {
                api.getAnime(authorization, kitsuId)
            }.onFailure {
                Log.w(TAG, "Kitsu anime fetch failed id=$rawId reason=${it.javaClass.simpleName}")
            }.getOrNull() ?: return@withContext null

            val resource = response.takeIf { it.isSuccessful }?.body()?.data ?: return@withContext null
            val attributes = resource.attributes ?: return@withContext null
            TvMetadataEnrichment(
                seriesTvdbId = null,
                localizedTitle = attributes.canonicalTitle,
                description = attributes.synopsis ?: attributes.description,
                genres = emptyList(),
                backdrop = attributes.coverImage?.bestUrl(),
                poster = attributes.posterImage?.bestUrl(),
                releaseInfo = attributes.startDate,
                rating = null,
                runtimeMinutes = attributes.episodeLength,
                ageRating = attributes.ageRating,
                language = "ja",
                status = attributes.status,
                remoteIds = mapOf("kitsu" to setOf(kitsuId))
            )
        }

    suspend fun fetchEpisodeEnrichment(
        rawId: String,
        mediaKind: ContentMediaKind,
        seasonNumbers: List<Int>
    ): Map<Pair<Int, Int>, TvEpisodeMetadata> = withContext(Dispatchers.IO) {
        if (!kitsuAuthService.providerEnabled()) return@withContext emptyMap()
        val animeId = AnimeStremioId.parse(rawId) ?: return@withContext emptyMap()
        val kitsuId = idMappingService.resolveKitsuId(animeId, mediaKind) ?: return@withContext emptyMap()
        val authorization = kitsuAuthService.validAccessToken()?.let { "Bearer $it" }
        val episodes = fetchEpisodePages(authorization, kitsuId, rawId)

        val acceptedSeasons = seasonNumbers.toSet()
        episodes
            .mapNotNull { episode ->
                val attributes = episode.attributes ?: return@mapNotNull null
                val season = attributes.seasonNumber ?: 1
                val number = attributes.number ?: return@mapNotNull null
                if (acceptedSeasons.isNotEmpty() && season !in acceptedSeasons) return@mapNotNull null
                (season to number) to TvEpisodeMetadata(
                    providerEpisodeId = episode.id?.let { "kitsu:$it" },
                    seasonNumber = season,
                    episodeNumber = number,
                    title = attributes.canonicalTitle,
                    overview = attributes.synopsis ?: attributes.description,
                    thumbnail = attributes.thumbnail?.bestUrl(),
                    airDate = attributes.airdate,
                    runtimeMinutes = attributes.length
                )
            }
            .toMap()
    }

    suspend fun fetchSeasonEpisodes(
        rawId: String,
        mediaKind: ContentMediaKind,
        seasonNumber: Int
    ): List<TvSeasonEpisode> {
        val episodes = fetchEpisodeEnrichment(rawId, mediaKind, listOf(seasonNumber))
        return episodes.values
            .filter { episode -> episode.episodeNumber != null }
            .sortedBy { episode -> episode.episodeNumber }
            .map { episode ->
                TvSeasonEpisode(
                    episodeNumber = episode.episodeNumber,
                    airDate = episode.airDate,
                    metadata = episode
                )
            }
    }

    private suspend fun fetchEpisodePages(
        authorization: String?,
        kitsuId: String,
        rawId: String
    ): List<KitsuAnimeResource> {
        val episodes = mutableListOf<KitsuAnimeResource>()
        var offset = 0
        var pageCount = 0
        while (pageCount < KITSU_EPISODE_MAX_PAGES) {
            val response = runCatching {
                api.getAnimeEpisodes(
                    authorization = authorization,
                    id = kitsuId,
                    limit = KITSU_EPISODE_PAGE_LIMIT,
                    offset = offset
                )
            }.onFailure {
                Log.w(TAG, "Kitsu episode fetch failed id=$rawId offset=$offset reason=${it.javaClass.simpleName}")
            }.getOrNull() ?: break

            if (!response.isSuccessful) break
            val body = response.body() ?: break
            val page = body.data.orEmpty()
            episodes += page
            if (body.links?.next.isNullOrBlank() || page.isEmpty()) break

            offset += KITSU_EPISODE_PAGE_LIMIT
            pageCount += 1
        }
        return episodes
    }
}

private fun KitsuImage.bestUrl(): String? =
    original ?: large ?: medium ?: small ?: tiny
