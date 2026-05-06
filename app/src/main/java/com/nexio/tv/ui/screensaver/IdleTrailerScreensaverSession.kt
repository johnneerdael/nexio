package com.nexio.tv.ui.screensaver

import android.view.KeyEvent
import com.nexio.tv.core.metadata.router.resolver.TrailerPlaybackRef
import com.nexio.tv.data.trailer.TrailerPlaybackSource
import kotlin.random.Random

internal enum class IdleScreensaverPresentationMode {
    IMAGE,
    TRAILER
}

internal data class IdleTrailerScreensaverPlayback(
    val candidate: IdleTrailerScreensaverCandidate,
    val playbackRef: TrailerPlaybackRef,
    val source: TrailerPlaybackSource,
    val index: Int
)

internal data class IdleTrailerScreensaverSessionStart(
    val candidates: List<IdleTrailerScreensaverCandidate>,
    val initialPlayback: IdleTrailerScreensaverPlayback
)

internal enum class IdleTrailerRemoteKeyAction {
    OPEN_DETAILS,
    DISMISS,
    UNMUTE_SESSION,
    CONSUME
}

private val IdleTrailerYearRegex = Regex("\\b(19|20)\\d{2}\\b")

internal fun collectIdleTrailerScreensaverCandidates(
    slides: List<IdleScreensaverSlide>
): List<IdleTrailerScreensaverCandidate> {
    return slides
        .mapNotNull { slide ->
            val trailerState = slide.modeData.trailer?.trailerState
            val playbackRefs = trailerState?.let { state ->
                IdleTrailerScreensaverCandidate(slide, state).playbackRefs
            }.orEmpty()
            if (trailerState == null || playbackRefs.isEmpty()) {
                null
            } else {
                IdleTrailerScreensaverCandidate(
                    itemId = slide.itemId,
                    itemType = slide.itemType,
                    addonBaseUrl = slide.addonBaseUrl,
                    title = slide.title,
                    logoArtwork = slide.logoArtwork,
                    backgroundArtwork = slide.backgroundArtwork,
                    fallbackArtwork = slide.modeData.image.fallbackArtwork.ifEmpty {
                        listOf(slide.backgroundArtwork)
                    },
                    genres = slide.genres,
                    description = slide.description,
                    releaseInfo = slide.releaseInfo,
                    runtime = slide.runtime,
                    imdbRating = slide.imdbRating,
                    tomatoesRating = slide.tomatoesRating,
                    trailerState = trailerState
                )
            }
        }
        .distinctBy { "${it.itemType}:${it.itemId}" }
}

internal fun chooseIdleTrailerCandidates(
    repositoryCandidates: List<IdleTrailerScreensaverCandidate>,
    slides: List<IdleScreensaverSlide>
): List<IdleTrailerScreensaverCandidate> {
    return if (repositoryCandidates.isNotEmpty()) {
        repositoryCandidates
    } else {
        collectIdleTrailerScreensaverCandidates(slides)
    }
}

internal suspend fun prepareIdleTrailerScreensaverSession(
    slides: List<IdleScreensaverSlide>,
    shuffleCandidates: (List<IdleTrailerScreensaverCandidate>) -> List<IdleTrailerScreensaverCandidate> = {
        it.shuffled(Random.Default)
    },
    resolvePlayback: suspend (
        candidate: IdleTrailerScreensaverCandidate,
        playbackRef: TrailerPlaybackRef
    ) -> TrailerPlaybackSource?
): IdleTrailerScreensaverSessionStart? {
    return prepareIdleTrailerScreensaverSessionFromCandidates(
        candidates = collectIdleTrailerScreensaverCandidates(slides),
        shuffleCandidates = shuffleCandidates,
        resolvePlayback = resolvePlayback
    )
}

internal suspend fun prepareIdleTrailerScreensaverSessionFromCandidates(
    candidates: List<IdleTrailerScreensaverCandidate>,
    shuffleCandidates: (List<IdleTrailerScreensaverCandidate>) -> List<IdleTrailerScreensaverCandidate> = {
        it.shuffled(Random.Default)
    },
    resolvePlayback: suspend (
        candidate: IdleTrailerScreensaverCandidate,
        playbackRef: TrailerPlaybackRef
    ) -> TrailerPlaybackSource?
): IdleTrailerScreensaverSessionStart? {
    val orderedCandidates = shuffleCandidates(
        candidates
            .distinctBy { "${it.itemType}:${it.itemId}" }
    )
    if (orderedCandidates.isEmpty()) return null

    val initialPlayback = resolveIdleTrailerPlaybackInOrder(
        candidates = orderedCandidates,
        orderedIndices = orderedCandidates.indices.toList(),
        resolvePlayback = resolvePlayback
    ) ?: return null

    return IdleTrailerScreensaverSessionStart(
        candidates = orderedCandidates,
        initialPlayback = initialPlayback
    )
}

internal suspend fun resolveNextIdleTrailerPlayback(
    candidates: List<IdleTrailerScreensaverCandidate>,
    currentIndex: Int,
    skippedPlaybackKeys: Set<String> = emptySet(),
    resolvePlayback: suspend (
        candidate: IdleTrailerScreensaverCandidate,
        playbackRef: TrailerPlaybackRef
    ) -> TrailerPlaybackSource?
): IdleTrailerScreensaverPlayback? {
    if (candidates.isEmpty()) return null
    val orderedIndices = buildList {
        for (offset in 1..candidates.size) {
            add((currentIndex + offset).floorMod(candidates.size))
        }
    }
    return resolveIdleTrailerPlaybackInOrder(
        candidates = candidates,
        orderedIndices = orderedIndices,
        skippedPlaybackKeys = skippedPlaybackKeys,
        resolvePlayback = resolvePlayback
    )
}

internal fun determineIdleTrailerRemoteKeyAction(
    keyCode: Int,
    action: Int,
    sessionMuted: Boolean
): IdleTrailerRemoteKeyAction {
    if (action != KeyEvent.ACTION_DOWN) return IdleTrailerRemoteKeyAction.CONSUME
    return when (keyCode) {
        KeyEvent.KEYCODE_DPAD_CENTER,
        KeyEvent.KEYCODE_ENTER,
        KeyEvent.KEYCODE_NUMPAD_ENTER -> IdleTrailerRemoteKeyAction.OPEN_DETAILS

        KeyEvent.KEYCODE_BACK -> IdleTrailerRemoteKeyAction.DISMISS

        else -> if (sessionMuted) {
            IdleTrailerRemoteKeyAction.UNMUTE_SESSION
        } else {
            IdleTrailerRemoteKeyAction.CONSUME
        }
    }
}

internal fun extractIdleTrailerReleaseYear(releaseInfo: String?): String? {
    if (releaseInfo.isNullOrBlank()) return null
    return IdleTrailerYearRegex.find(releaseInfo)?.value
}

internal fun idleTrailerPlaybackKey(
    candidate: IdleTrailerScreensaverCandidate,
    playbackRef: TrailerPlaybackRef
): String = "${candidate.itemType}:${candidate.itemId}:${playbackRef.stablePlaybackKey()}"

private suspend fun resolveIdleTrailerPlaybackInOrder(
    candidates: List<IdleTrailerScreensaverCandidate>,
    orderedIndices: List<Int>,
    skippedPlaybackKeys: Set<String> = emptySet(),
    resolvePlayback: suspend (
        candidate: IdleTrailerScreensaverCandidate,
        playbackRef: TrailerPlaybackRef
    ) -> TrailerPlaybackSource?
): IdleTrailerScreensaverPlayback? {
    orderedIndices.forEach { index ->
        val candidate = candidates.getOrNull(index) ?: return@forEach
        candidate.playbackRefsForSession().forEach { playbackRef ->
            if (idleTrailerPlaybackKey(candidate, playbackRef) in skippedPlaybackKeys) {
                return@forEach
            }
            val source = resolvePlayback(candidate, playbackRef) ?: return@forEach
            return IdleTrailerScreensaverPlayback(
                candidate = candidate,
                playbackRef = playbackRef,
                source = source,
                index = index
            )
        }
    }
    return null
}

private fun IdleTrailerScreensaverCandidate.playbackRefsForSession(): List<TrailerPlaybackRef> {
    return playbackRefs.ifEmpty {
        listOf(
            TrailerPlaybackRef.ItemLookup(
                title = title,
                year = extractIdleTrailerReleaseYear(releaseInfo),
                stableIds = stableIds,
                type = itemType,
                contentId = trailerResolverContentId()
            )
        )
    }
}

internal fun IdleTrailerScreensaverCandidate.trailerResolverContentId(): String {
    return stableIds.tvdb?.trim()?.takeIf(String::isNotEmpty)?.let { "tvdb:$it" }
        ?: stableIds.tmdb?.trim()?.takeIf(String::isNotEmpty)?.let { "tmdb:$it" }
        ?: stableIds.imdb?.trim()?.takeIf(String::isNotEmpty)?.let { "imdb:$it" }
        ?: stableIds.kitsu?.trim()?.takeIf(String::isNotEmpty)?.let { "kitsu:$it" }
        ?: itemId
}

private fun TrailerPlaybackRef.stablePlaybackKey(): String =
    when (this) {
        is TrailerPlaybackRef.YouTubeId -> "youtube:${videoId.trim()}"
        is TrailerPlaybackRef.ExternalUrl -> "external:${url.trim()}"
        is TrailerPlaybackRef.InAppSource -> "in-app:${videoUrl.trim()}:${audioUrl.orEmpty().trim()}"
        is TrailerPlaybackRef.ItemLookup -> listOf(
            "item",
            title.trim(),
            year.orEmpty().trim(),
            stableIds.tvdb.orEmpty().trim(),
            stableIds.tmdb.orEmpty().trim(),
            stableIds.imdb.orEmpty().trim(),
            stableIds.kitsu.orEmpty().trim(),
            type.orEmpty().trim(),
            seasonNumber?.toString().orEmpty(),
            contentId.orEmpty().trim()
        ).joinToString(":")
    }

private fun Int.floorMod(size: Int): Int {
    return ((this % size) + size) % size
}
