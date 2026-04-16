package com.nexio.tv.ui.screens.home

import android.util.Log
import com.nexio.tv.core.network.NetworkResult
import com.nexio.tv.core.tvdb.TvMetadataRequest
import com.nexio.tv.core.tvdb.TvdbLanguageMapper
import com.nexio.tv.domain.model.ContentType
import com.nexio.tv.domain.model.HomeDisplayMetadata
import com.nexio.tv.domain.model.Meta
import com.nexio.tv.ui.navigation.continueWatchingRuntimeMinutes
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update

internal suspend fun HomeViewModel.resolveContinueWatchingRuntimeMinutes(
    item: ContinueWatchingItem
): Int? {
    continueWatchingRuntimeMinutes(item)?.let { return it }

    val contentId = item.contentId().takeIf { it.isNotBlank() } ?: return null
    val contentType = item.contentType().takeIf { it.isNotBlank() } ?: return null
    val season = item.season()
    val episode = item.episode()

    val meta = (metaRepository.getMetaFromAllAddons(
        type = contentType,
        id = contentId,
        cacheOnDisk = true,
        writeToDisk = true,
        origin = "continue_watching_runtime"
    ).first { it !is NetworkResult.Loading } as? NetworkResult.Success)?.data

    resolveRuntimeMinutesFromMeta(item, meta)?.let { return it }

    if (isSeriesType(contentType)) {
        val tvdbLanguage = TvdbLanguageMapper.normalize(profileBoundary.currentLanguageTag())
        if (season != null && episode != null) {
            val episodeRuntime = tvMetadataRouter.fetchEpisodeEnrichment(
                TvMetadataRequest(
                    contentId = contentId,
                    fallbackContentId = item.videoId(),
                    contentType = parseContinueWatchingContentType(contentType),
                    language = tvdbLanguage,
                    seasonNumbers = listOf(season)
                )
            ).value?.get(season to episode)?.runtimeMinutes
            if (episodeRuntime != null && episodeRuntime > 0) return episodeRuntime
        } else {
            val enrichment = tvMetadataRouter.fetchEnrichment(
                TvMetadataRequest(
                    contentId = contentId,
                    fallbackContentId = item.videoId(),
                    contentType = parseContinueWatchingContentType(contentType),
                    language = tvdbLanguage
                )
            ).value
            val runtime = enrichment?.runtimeMinutes ?: enrichment?.averageRuntimeMinutes
            if (runtime != null && runtime > 0) return runtime
        }

        return null
    }

    val tmdbId = tmdbService.ensureTmdbId(contentId, contentType)
        ?: tmdbService.ensureTmdbId(
            item.videoId(),
            contentType
        )

    if (!tmdbId.isNullOrBlank()) {
        if (season != null && episode != null) {
            val episodeRuntime = tmdbMetadataService.fetchEpisodeEnrichment(
                tmdbId = tmdbId,
                seasonNumbers = listOf(season)
            )[season to episode]?.runtimeMinutes
            if (episodeRuntime != null && episodeRuntime > 0) return episodeRuntime
        } else {
            val runtime = tmdbMetadataService.fetchEnrichment(
                tmdbId = tmdbId,
                contentType = parseContinueWatchingContentType(contentType)
            )?.runtimeMinutes
            if (runtime != null && runtime > 0) return runtime
        }
    }

    return null
}

internal fun ContinueWatchingItem.withHydratedRuntimeMinutes(runtimeMinutes: Int?): ContinueWatchingItem {
    val runtime = runtimeMinutes?.takeIf { it > 0 } ?: return this
    return when (this) {
        is ContinueWatchingItem.InProgress -> copy(
            progress = progress.copy(duration = runtime * 60_000L),
            displayMetadata = (displayMetadata ?: HomeDisplayMetadata()).copy(runtime = runtime.toString())
        )
        is ContinueWatchingItem.NextUp -> copy(
            info = info.copy(
                displayMetadata = (info.displayMetadata ?: HomeDisplayMetadata()).copy(runtime = runtime.toString())
            )
        )
    }
}

internal suspend fun HomeViewModel.warmContinueWatchingRuntimeIfNeededPipeline() {
    val candidates = continueWatchingItemsMissingRuntime(_uiState.value.continueWatchingItems)
    if (candidates.isEmpty()) return

    candidates.forEach { item ->
        val runtime = runCatching { resolveContinueWatchingRuntimeMinutes(item) }
            .onFailure { error ->
                Log.w(HomeViewModel.TAG, "Failed to warm continue watching runtime for ${item.contentId()}: ${error.message}")
            }
            .getOrNull()
        if (runtime != null) {
            updateContinueWatchingRuntime(item, runtime)
        }
    }
}

internal fun continueWatchingItemsMissingRuntime(
    items: List<ContinueWatchingItem>
): List<ContinueWatchingItem> {
    return items.filter {
        continueWatchingRuntimeMinutes(it) == null &&
            it.season() != null &&
            it.episode() != null
    }
}

private fun HomeViewModel.updateContinueWatchingRuntime(item: ContinueWatchingItem, runtimeMinutes: Int) {
    _uiState.update { state ->
        val updatedContinueWatching = state.continueWatchingItems.map { current ->
            if (current.contentId() == item.contentId() &&
                current.season() == item.season() &&
                current.episode() == item.episode()
            ) {
                current.withHydratedRuntimeMinutes(runtimeMinutes)
            } else {
                current
            }
        }
        val updatedTraktUpNext = state.traktUpNextItems.map { current ->
            if (current.contentId() == item.contentId() &&
                current.season() == item.season() &&
                current.episode() == item.episode()
            ) {
                current.withHydratedRuntimeMinutes(runtimeMinutes) as ContinueWatchingItem.NextUp
            } else {
                current
            }
        }
        if (updatedContinueWatching == state.continueWatchingItems &&
            updatedTraktUpNext == state.traktUpNextItems
        ) {
            state
        } else {
            state.copy(
                continueWatchingItems = updatedContinueWatching,
                traktUpNextItems = updatedTraktUpNext
            )
        }
    }
}

private fun resolveRuntimeMinutesFromMeta(item: ContinueWatchingItem, meta: Meta?): Int? {
    val resolved = if (item.season() != null && item.episode() != null) {
        meta?.videos?.firstOrNull {
            it.season == item.season() && it.episode == item.episode()
        }?.runtime
    } else {
        parseRuntimeMinutesFromMeta(meta?.runtime)
    }
    return resolved?.takeIf { it > 0 }
}

private fun ContinueWatchingItem.videoId(): String {
    return when (this) {
        is ContinueWatchingItem.InProgress -> progress.videoId
        is ContinueWatchingItem.NextUp -> info.videoId
    }
}

private fun parseContinueWatchingContentType(raw: String): ContentType {
    return when (raw.lowercase()) {
        "series", "tv" -> ContentType.SERIES
        else -> ContentType.MOVIE
    }
}

private fun parseRuntimeMinutesFromMeta(raw: String?): Int? {
    return raw
        ?.let { Regex("(\\d+)").find(it)?.groupValues?.getOrNull(1) }
        ?.toIntOrNull()
        ?.takeIf { it > 0 }
}
