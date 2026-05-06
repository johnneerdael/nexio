package com.nexio.tv.ui.screensaver

import android.view.KeyEvent
import com.nexio.tv.data.trailer.TrailerPlaybackSource
import kotlin.random.Random

internal enum class IdleScreensaverPresentationMode {
    IMAGE,
    TRAILER
}

internal data class IdleTrailerScreensaverPlayback(
    val candidate: IdleTrailerScreensaverCandidate,
    val trailerId: String,
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

internal const val RESOLVE_TRAILER_BY_ITEM_SENTINEL = "__resolve_by_item__"

internal fun collectIdleTrailerScreensaverCandidates(
    slides: List<IdleScreensaverSlide>
): List<IdleTrailerScreensaverCandidate> {
    return slides
        .mapNotNull { slide ->
            val trailerIds = slide.modeData.trailer
                ?.trailerYtIds
                .orEmpty()
                .mapNotNull { it.trim().takeIf(String::isNotEmpty) }
                .distinct()
            if (trailerIds.isEmpty()) {
                null
            } else {
                IdleTrailerScreensaverCandidate(
                    itemId = slide.itemId,
                    itemType = slide.itemType,
                    addonBaseUrl = slide.addonBaseUrl,
                    title = slide.title,
                    logoUrl = slide.logoUrl,
                    backgroundUrl = slide.backgroundUrl,
                    fallbackArtworkUrls = slide.modeData.image.fallbackArtworkUrls.ifEmpty {
                        listOf(slide.backgroundUrl)
                    },
                    genres = slide.genres,
                    description = slide.description,
                    releaseInfo = slide.releaseInfo,
                    runtime = slide.runtime,
                    imdbRating = slide.imdbRating,
                    tomatoesRating = slide.tomatoesRating,
                    trailerYtIds = trailerIds
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
        trailerId: String
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
        trailerId: String
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
        trailerId: String
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
    trailerId: String
): String = "${candidate.itemType}:${candidate.itemId}:${trailerId.trim()}"

private suspend fun resolveIdleTrailerPlaybackInOrder(
    candidates: List<IdleTrailerScreensaverCandidate>,
    orderedIndices: List<Int>,
    skippedPlaybackKeys: Set<String> = emptySet(),
    resolvePlayback: suspend (
        candidate: IdleTrailerScreensaverCandidate,
        trailerId: String
    ) -> TrailerPlaybackSource?
): IdleTrailerScreensaverPlayback? {
    orderedIndices.forEach { index ->
        val candidate = candidates.getOrNull(index) ?: return@forEach
        val trailerIds = candidate.trailerYtIds.takeIf { it.isNotEmpty() }
            ?: listOf(RESOLVE_TRAILER_BY_ITEM_SENTINEL)
        trailerIds.forEach { trailerId ->
            if (idleTrailerPlaybackKey(candidate, trailerId) in skippedPlaybackKeys) {
                return@forEach
            }
            val source = resolvePlayback(candidate, trailerId) ?: return@forEach
            return IdleTrailerScreensaverPlayback(
                candidate = candidate,
                trailerId = trailerId,
                source = source,
                index = index
            )
        }
    }
    return null
}

private fun Int.floorMod(size: Int): Int {
    return ((this % size) + size) % size
}
