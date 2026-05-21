package com.nexio.tv.ui.screens.player

import com.nexio.tv.core.metadata.router.MetadataDepth
import com.nexio.tv.core.metadata.router.MetadataRequest
import com.nexio.tv.core.metadata.router.MetadataSourceContext
import com.nexio.tv.core.tvdb.TvMetadataRequest
import com.nexio.tv.data.repository.TvEpisodeOrderProvider
import com.nexio.tv.data.repository.normalizeTmdbTvEpisodeOrderKey
import com.nexio.tv.domain.model.ContentType
import com.nexio.tv.domain.model.ProviderIds
import kotlinx.coroutines.CancellationException

internal fun PlayerRuntimeController.currentEpisodeMappingCacheKey(): String? {
    val resolvedContentId = contentId?.trim()?.takeIf { it.isNotBlank() } ?: return null
    val resolvedType = contentType?.trim()?.lowercase()?.takeIf { it.isNotBlank() } ?: return null
    val season = currentSeason ?: return null
    val episode = currentEpisode ?: return null
    val videoId = currentVideoId?.trim().orEmpty()
    return "$resolvedType|$resolvedContentId|$videoId|$season|$episode"
}

internal suspend fun PlayerRuntimeController.warmTraktEpisodeMappingForCurrentPlayback() {
    val normalizedType = contentType?.lowercase()
    if (normalizedType !in listOf("series", "tv")) {
        clearTraktEpisodeMapping()
        return
    }
    val resolvedContentId = contentId?.takeIf { it.isNotBlank() } ?: run {
        clearTraktEpisodeMapping()
        return
    }
    val season = currentSeason ?: run {
        clearTraktEpisodeMapping()
        return
    }
    val episode = currentEpisode ?: run {
        clearTraktEpisodeMapping()
        return
    }

    currentTraktEpisodeMapping = traktEpisodeMappingService.prefetchEpisodeMapping(
        contentId = resolvedContentId,
        contentType = contentType,
        videoId = currentVideoId,
        season = season,
        episode = episode
    )
    currentTraktEpisodeMappingKey = currentEpisodeMappingCacheKey()
}

internal suspend fun PlayerRuntimeController.warmTvdbScrobbleCoordinateForCurrentPlayback() {
    val rawContentId = contentId?.trim()?.takeIf { it.isNotBlank() } ?: run {
        clearTvdbScrobbleCoordinate()
        return
    }
    val normalizedType = contentType?.trim()?.lowercase()
    if (normalizedType !in listOf("series", "tv")) {
        clearTvdbScrobbleCoordinate()
        return
    }
    val displaySeason = currentSeason ?: run {
        clearTvdbScrobbleCoordinate()
        return
    }
    val displayEpisode = currentEpisode ?: run {
        clearTvdbScrobbleCoordinate()
        return
    }
    val key = currentEpisodeMappingCacheKey() ?: run {
        clearTvdbScrobbleCoordinate()
        return
    }

    try {
        val ids = hydratedIdsForScrobbleProjection(rawContentId)
        val tmdbId = ids.tmdb?.trim()?.takeIf { it.isNotEmpty() }
            ?: rawContentId.takeIf { it.startsWith("tmdb:", ignoreCase = true) }
                ?.removePrefix("tmdb:tv:")
                ?.removePrefix("tmdb:")
                ?.trim()
                ?.takeIf { it.isNotEmpty() }
            ?: run {
                clearTvdbScrobbleCoordinate()
                return
            }
        val tvdbContentId = if (rawContentId.startsWith("tvdb:", ignoreCase = true)) {
            rawContentId
        } else {
            val tvdbId = ids.tvdb?.trim()?.takeIf { it.isNotEmpty() } ?: run {
                clearTvdbScrobbleCoordinate()
                return
            }
            val orderResolution = tvEpisodeOrderResolver.resolve(
                tmdbTvId = normalizeTmdbTvEpisodeOrderKey(tmdbId),
                providerIds = ids
            )
            if (orderResolution.provider != TvEpisodeOrderProvider.TVDB_DEFAULT) {
                clearTvdbScrobbleCoordinate()
                return
            }
            "tvdb:$tvdbId"
        }
        val normalizedTmdbId = tmdbId
            .removePrefix("tmdb:tv:")
            .removePrefix("tmdb:")
            .trim()
            .takeIf { it.isNotEmpty() } ?: run {
            clearTvdbScrobbleCoordinate()
            return
        }
        val tvdbEpisodes = metadataRouterFacade.fetchTvEpisodeProjection(
            metadataRequest = MetadataRequest(
                contentId = tvdbContentId,
                contentType = ContentType.SERIES,
                sourceContext = MetadataSourceContext(itemType = normalizedType),
                depth = MetadataDepth.SEASON
            ),
            tvRequest = TvMetadataRequest(
                contentId = tvdbContentId,
                fallbackContentId = currentVideoId,
                contentType = ContentType.SERIES,
                seasonNumbers = emptyList()
            )
        ).value.orEmpty()
        val tmdbEpisodes = tmdbMetadataService.fetchEpisodeEnrichment(
            tmdbId = normalizedTmdbId,
            seasonNumbers = (1..displaySeason).toList()
        )
        val coordinate = TvdbScrobbleCoordinateProjector.projectDisplayToProviderNative(
            displaySeason = displaySeason,
            displayEpisode = displayEpisode,
            displayTitle = currentEpisodeTitle,
            tvdbEpisodes = tvdbEpisodes,
            tmdbEpisodes = tmdbEpisodes
        )?.takeUnless { it.first == displaySeason && it.second == displayEpisode }
        currentTvdbScrobbleCoordinate = coordinate
        currentTvdbScrobbleCoordinateKey = if (coordinate == null) null else key
    } catch (e: CancellationException) {
        throw e
    } catch (_: Exception) {
        clearTvdbScrobbleCoordinate()
    }
}

private fun PlayerRuntimeController.clearTraktEpisodeMapping() {
    currentTraktEpisodeMapping = null
    currentTraktEpisodeMappingKey = null
}

private fun PlayerRuntimeController.clearTvdbScrobbleCoordinate() {
    currentTvdbScrobbleCoordinate = null
    currentTvdbScrobbleCoordinateKey = null
}

private suspend fun PlayerRuntimeController.hydratedIdsForScrobbleProjection(
    rawContentId: String
): ProviderIds {
    val cached = currentHydratedIds
    if (hydratedIdsForContentId == rawContentId && cached != null) return cached
    return scrobbleIdBundleHydrator.hydrate(rawContentId, contentType).also { ids ->
        currentHydratedIds = ids
        hydratedIdsForContentId = rawContentId
    }
}
