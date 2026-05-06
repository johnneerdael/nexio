package com.nexio.tv.data.trailer

import com.nexio.tv.core.anime.AnimeStremioId
import com.nexio.tv.core.metadata.router.resolver.TrailerPlaybackRef
import com.nexio.tv.core.tmdb.TmdbMetadataService
import com.nexio.tv.data.integration.trailer.TrailerTmdbProvider
import com.nexio.tv.core.network.NetworkResult
import com.nexio.tv.domain.model.Stream
import com.nexio.tv.domain.repository.AddonRepository
import com.nexio.tv.domain.repository.StreamRepository
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext

data class SeasonTrailerRefRequest(
    val title: String,
    val year: String? = null,
    val tmdbId: String? = null,
    val type: String? = null,
    val seasonNumber: Int? = null,
    val contentId: String? = null
)

interface SeasonTrailerRefResolver {
    suspend fun resolveSeasonTrailerRefs(request: SeasonTrailerRefRequest): List<TrailerPlaybackRef>
    suspend fun resolveSeasonRecapRefs(request: SeasonTrailerRefRequest): List<TrailerPlaybackRef>
}

@Singleton
class ProviderSeasonTrailerRefResolver @Inject constructor(
    private val tmdbMetadataService: TmdbMetadataService,
    private val trailerTmdbProvider: TrailerTmdbProvider,
    private val addonRepository: AddonRepository,
    private val streamRepository: StreamRepository
) : SeasonTrailerRefResolver {
    override suspend fun resolveSeasonTrailerRefs(request: SeasonTrailerRefRequest): List<TrailerPlaybackRef> =
        withContext(Dispatchers.IO) {
            val normalizedSeason = request.seasonNumber?.takeIf { it >= 0 } ?: return@withContext emptyList()
            val tmdbRefs = fetchSeasonTmdbVideos(request, normalizedSeason, ::rankTmdbVideoCandidates)
                .mapNotNull { result ->
                    result.key
                        ?.trim()
                        ?.takeIf { it.isNotBlank() }
                        ?.let(TrailerPlaybackRef::YouTubeId)
                }
            val streailerRef = fetchStreailerStreams(request.contentId, request.type)
                ?.let { selectSeasonStreailerTrailerCandidate(it, normalizedSeason) }
                ?.toTrailerPlaybackRef()
            (tmdbRefs + listOfNotNull(streailerRef)).distinct()
        }

    override suspend fun resolveSeasonRecapRefs(request: SeasonTrailerRefRequest): List<TrailerPlaybackRef> =
        withContext(Dispatchers.IO) {
            val normalizedSeason = request.seasonNumber?.takeIf { it >= 0 } ?: return@withContext emptyList()
            val tmdbRefs = fetchSeasonTmdbVideos(request, normalizedSeason, ::rankTmdbRecapCandidates)
                .mapNotNull { result ->
                    result.key
                        ?.trim()
                        ?.takeIf { it.isNotBlank() }
                        ?.let(TrailerPlaybackRef::YouTubeId)
                }
            val streailerRef = fetchStreailerStreams(request.contentId, request.type)
                ?.let { selectSeasonStreailerRecapCandidate(it, normalizedSeason) }
                ?.toTrailerPlaybackRef()
            (tmdbRefs + listOfNotNull(streailerRef)).distinct()
        }

    private suspend fun fetchSeasonTmdbVideos(
        request: SeasonTrailerRefRequest,
        normalizedSeason: Int,
        rank: (List<com.nexio.tv.data.remote.api.TmdbVideoResult>) -> List<com.nexio.tv.data.remote.api.TmdbVideoResult>
    ): List<com.nexio.tv.data.remote.api.TmdbVideoResult> {
        val numericTmdbId = request.tmdbId?.toIntOrNull() ?: return emptyList()
        if (normalizeTmdbMediaType(request.type) != "tv") return emptyList()
        if (AnimeStremioId.isExplicitAnimeOnlyId(request.contentId)) return emptyList()
        val apiKey = trailerTmdbProvider.getTmdbApiKey() ?: return emptyList()
        val preferredLanguage = normalizeTmdbTrailerLanguage(
            runCatching { tmdbMetadataService.currentTmdbLanguageTag() }.getOrNull()
        )
        return rank(
            trailerTmdbProvider.fetchSeasonVideos(
                tmdbId = numericTmdbId,
                seasonNumber = normalizedSeason,
                preferredLanguage = preferredLanguage,
                apiKey = apiKey
            )
        )
    }

    private suspend fun fetchStreailerStreams(
        contentId: String?,
        type: String?
    ): List<Stream>? {
        val normalizedContentId = contentId?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        val normalizedType = normalizeStreailerType(type) ?: return null
        val addon = addonRepository.getInstalledAddons().first()
            .firstOrNull { it.id == STREAILER_ADDON_ID }
            ?: return null

        return when (
            val streamResult = streamRepository.getStreamsFromAddon(
                baseUrl = addon.baseUrl,
                type = normalizedType,
                videoId = normalizedContentId
            )
        ) {
            is NetworkResult.Success -> streamResult.data
            else -> null
        }
    }
}

private fun StreailerTrailerCandidate.toTrailerPlaybackRef(): TrailerPlaybackRef? =
    when {
        !youtubeId.isNullOrBlank() -> TrailerPlaybackRef.YouTubeId(youtubeId.trim())
        !externalUrl.isNullOrBlank() -> TrailerPlaybackRef.ExternalUrl(externalUrl.trim())
        else -> null
    }
