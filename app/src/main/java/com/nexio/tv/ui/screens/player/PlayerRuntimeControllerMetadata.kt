package com.nexio.tv.ui.screens.player

import com.nexio.tv.R
import com.nexio.tv.core.network.NetworkResult
import com.nexio.tv.domain.model.Meta
import com.nexio.tv.domain.model.MetaCastMember
import com.nexio.tv.domain.model.Stream
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

internal fun PlayerRuntimeController.fetchMetaDetails(id: String?, type: String?) {
    if (id.isNullOrBlank() || type.isNullOrBlank()) return

    scope.launch {
        when (
            val result = metaRepository.getMetaFromAllAddons(type = type, id = id)
                .first { it !is NetworkResult.Loading }
        ) {
            is NetworkResult.Success -> {
                applyMetaDetails(result.data)
            }
            is NetworkResult.Error -> {
                
            }
            NetworkResult.Loading -> {
                
            }
        }
    }
}

internal fun PlayerRuntimeController.applyMetaDetails(meta: Meta) {
    metaVideos = meta.videos
    val description = resolveDescription(meta)
    val safeCastMembers = sanitizeCastMembers(meta)

    _uiState.update { state ->
        state.copy(
            description = description ?: state.description,
            castMembers = if (safeCastMembers.isNotEmpty()) safeCastMembers else state.castMembers
        )
    }
    recomputeNextEpisode(resetVisibility = false)
}

private fun sanitizeCastMembers(meta: Meta): List<MetaCastMember> {
    val safeFromMembers = (meta.castMembers as List<*>).mapNotNull { raw ->
        when (raw) {
            is MetaCastMember -> raw
            is Map<*, *> -> {
                val name = (raw["name"] as? String)?.trim().orEmpty()
                if (name.isBlank()) null else MetaCastMember(
                    name = name,
                    character = (raw["character"] as? String)?.takeIf { it.isNotBlank() },
                    photo = (raw["photo"] as? String)?.takeIf { it.isNotBlank() },
                    tmdbId = when (val tmdbRaw = raw["tmdbId"]) {
                        is Number -> tmdbRaw.toInt()
                        is String -> tmdbRaw.toIntOrNull()
                        else -> null
                    }
                )
            }
            else -> null
        }
    }
    if (safeFromMembers.isNotEmpty()) return safeFromMembers
    return (meta.cast as List<*>).mapNotNull { raw ->
        val name = (raw as? String)?.trim().orEmpty()
        if (name.isBlank()) null else MetaCastMember(name = name)
    }
}

internal fun PlayerRuntimeController.resolveDescription(meta: Meta): String? {
    val type = contentType
    if (type in listOf("series", "tv") && currentSeason != null && currentEpisode != null) {
        val episodeOverview = meta.videos.firstOrNull { video ->
            video.season == currentSeason && video.episode == currentEpisode
        }?.overview
        if (!episodeOverview.isNullOrBlank()) return episodeOverview
    }

    return meta.description
}

internal fun PlayerRuntimeController.updateEpisodeDescription() {
    val overview = metaVideos.firstOrNull { video ->
        video.season == currentSeason && video.episode == currentEpisode
    }?.overview

    if (!overview.isNullOrBlank()) {
        _uiState.update { it.copy(description = overview) }
    }
}

internal fun PlayerRuntimeController.recomputeNextEpisode(resetVisibility: Boolean) {
    val normalizedType = contentType?.lowercase()
    if (normalizedType !in listOf("series", "tv")) {
        nextEpisodeVideo = null
        _uiState.update {
            it.copy(
                nextEpisode = null,
                showNextEpisodeCard = false,
                nextEpisodeCardDismissed = false,
                nextEpisodeAutoPlaySearching = false,
                nextEpisodeAutoPlaySourceName = null,
                nextEpisodeAutoPlayCountdownSec = null
            )
        }
        return
    }

    val season = currentSeason
    val episode = currentEpisode
    if (season == null || episode == null) {
        nextEpisodeVideo = null
        _uiState.update {
            it.copy(
                nextEpisode = null,
                showNextEpisodeCard = false,
                nextEpisodeCardDismissed = false,
                nextEpisodeAutoPlaySearching = false,
                nextEpisodeAutoPlaySourceName = null,
                nextEpisodeAutoPlayCountdownSec = null
            )
        }
        return
    }

    val resolvedNext = PlayerNextEpisodeRules.resolveNextEpisode(
        videos = metaVideos,
        currentSeason = season,
        currentEpisode = episode
    )

    nextEpisodeVideo = resolvedNext
    if (resolvedNext == null) {
        _uiState.update {
            it.copy(
                nextEpisode = null,
                showNextEpisodeCard = false,
                nextEpisodeCardDismissed = false,
                nextEpisodeAutoPlaySearching = false,
                nextEpisodeAutoPlaySourceName = null,
                nextEpisodeAutoPlayCountdownSec = null
            )
        }
        return
    }

    val hasAired = PlayerNextEpisodeRules.hasEpisodeAired(resolvedNext.released)
    val nextInfo = NextEpisodeInfo(
        videoId = resolvedNext.id,
        season = resolvedNext.season ?: return,
        episode = resolvedNext.episode ?: return,
        title = resolvedNext.title,
        thumbnail = resolvedNext.thumbnail,
        overview = resolvedNext.overview,
        released = resolvedNext.released,
        hasAired = hasAired,
        unairedMessage = if (hasAired) {
            null
        } else {
            context.getString(com.nexio.tv.R.string.next_episode_not_aired_yet)
        }
    )

    _uiState.update { state ->
        val sameEpisode = state.nextEpisode?.videoId == nextInfo.videoId
        val shouldResetVisibility = resetVisibility || !sameEpisode
        state.copy(
            nextEpisode = nextInfo,
            showNextEpisodeCard = if (shouldResetVisibility) false else state.showNextEpisodeCard,
            nextEpisodeCardDismissed = if (shouldResetVisibility) false else state.nextEpisodeCardDismissed
        )
    }
}

internal fun PlayerRuntimeController.resetNextEpisodeCardState(clearEpisode: Boolean = false) {
    nextEpisodeAutoPlayJob?.cancel()
    nextEpisodeAutoPlayJob = null
    _uiState.update { state ->
        state.copy(
            nextEpisode = if (clearEpisode) null else state.nextEpisode,
            showNextEpisodeCard = false,
            nextEpisodeCardDismissed = false,
            nextEpisodeAutoPlaySearching = false,
            nextEpisodeAutoPlaySourceName = null,
            nextEpisodeAutoPlayCountdownSec = null
        )
    }
    if (clearEpisode) {
        nextEpisodeVideo = null
    }
}

internal fun PlayerRuntimeController.evaluateNextEpisodeCardVisibility(positionMs: Long, durationMs: Long) {
    if (!hasRenderedFirstFrame) return

    val state = _uiState.value
    if (state.nextEpisode == null || nextEpisodeVideo == null) {
        if (state.showNextEpisodeCard) {
            _uiState.update { it.copy(showNextEpisodeCard = false) }
        }
        return
    }
    if (state.showNextEpisodeCard || state.nextEpisodeCardDismissed) return

    val effectiveDuration = durationMs.takeIf { it > 0L } ?: lastKnownDuration
    val shouldShow = PlayerNextEpisodeRules.shouldShowNextEpisodeCard(
        positionMs = positionMs,
        durationMs = effectiveDuration,
        skipIntervals = skipIntervals,
        thresholdMode = nextEpisodeThresholdModeSetting,
        thresholdPercent = nextEpisodeThresholdPercentSetting,
        thresholdMinutesBeforeEnd = nextEpisodeThresholdMinutesBeforeEndSetting
    )

    if (shouldShow) {
        _uiState.update { it.copy(showNextEpisodeCard = true) }
        if (
            state.nextEpisode.hasAired &&
            streamAutoPlayNextEpisodeEnabledSetting
        ) {
            playNextEpisode()
        }
    }
}

internal fun PlayerRuntimeController.showStreamSourceIndicator(stream: Stream) {
    val chosenSource = (stream.name?.takeIf { it.isNotBlank() } ?: stream.addonName).trim()
    if (chosenSource.isBlank()) return

    hideStreamSourceIndicatorJob?.cancel()
    _uiState.update {
        it.copy(
            showStreamSourceIndicator = true,
            streamSourceIndicatorText = "Source: $chosenSource"
        )
    }
    hideStreamSourceIndicatorJob = scope.launch {
        delay(2200)
        _uiState.update { it.copy(showStreamSourceIndicator = false) }
    }
}

internal fun PlayerRuntimeController.updateActiveSkipInterval(positionMs: Long) {
    if (skipIntervals.isEmpty()) {
        if (_uiState.value.activeSkipInterval != null) {
            _uiState.update { it.copy(activeSkipInterval = null) }
        }
        return
    }

    val positionSec = positionMs / 1000.0
    val active = skipIntervals.find { interval ->
        positionSec >= interval.startTime && positionSec < (interval.endTime - 0.5)
    }

    val currentActive = _uiState.value.activeSkipInterval

    if (active != null) {
        
        if (currentActive == null || active.type != currentActive.type || active.startTime != currentActive.startTime) {
            lastActiveSkipType = active.type
            _uiState.update { it.copy(activeSkipInterval = active, skipIntervalDismissed = false) }
        }
    } else if (currentActive != null) {
        
        _uiState.update { it.copy(activeSkipInterval = null, skipIntervalDismissed = false) }
    }
}
