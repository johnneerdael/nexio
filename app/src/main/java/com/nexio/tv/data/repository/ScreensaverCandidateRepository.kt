package com.nexio.tv.data.repository

import com.nexio.tv.core.artwork.ArtworkDisplayRef
import com.nexio.tv.core.metadata.router.resolver.TrailerPlaybackRef
import com.nexio.tv.core.metadata.router.resolver.TrailerSurface
import com.nexio.tv.core.profile.ProfileManager
import com.nexio.tv.core.trace.TraceHash
import com.nexio.tv.core.trace.TraceMetadataEvents
import com.nexio.tv.core.trace.TraceSessionManager
import com.nexio.tv.domain.model.RatingValueValidator
import com.nexio.tv.domain.model.ResolvedDisplayItem
import com.nexio.tv.domain.model.TitleRating
import com.nexio.tv.domain.model.TrailerDisplayState
import com.nexio.tv.ui.screensaver.ScreensaverSlideCandidate
import com.nexio.tv.ui.screensaver.ScreensaverTrailerCandidate
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

data class ScreensaverCandidatesSnapshot(
    val imageCandidates: List<ScreensaverSlideCandidate>,
    val trailerCandidates: List<ScreensaverTrailerCandidate>
)

@Singleton
class ScreensaverCandidateRepository(
    private val surfaceRepository: ResolvedDisplaySurfaceRepository,
    private val traceEvents: TraceMetadataEvents,
    private val profileHashForTrace: (Int) -> String?
) {
    @Inject
    constructor(
        surfaceRepository: ResolvedDisplaySurfaceRepository,
        traceEvents: TraceMetadataEvents,
        profileManager: ProfileManager,
        traceSessionManager: TraceSessionManager
    ) : this(
        surfaceRepository = surfaceRepository,
        traceEvents = traceEvents,
        profileHashForTrace = { profileId ->
            val activeProfileSession = profileManager.activeProfileSession.value
            val traceSessionId = traceSessionManager.activeSession()?.traceSessionId
            if (activeProfileSession.profileId == profileId && traceSessionId != null) {
                TraceHash.of(traceSessionId, profileId.toString())
            } else {
                null
            }
        }
    )

    fun observeImageCandidates(profileId: Int): Flow<List<ScreensaverSlideCandidate>> =
        observeCandidates(profileId).map { snapshot -> snapshot.imageCandidates }

    fun observeTrailerCandidates(profileId: Int): Flow<List<ScreensaverTrailerCandidate>> =
        observeCandidates(profileId).map { snapshot -> snapshot.trailerCandidates }

    fun observeCandidates(profileId: Int): Flow<ScreensaverCandidatesSnapshot> =
        surfaceRepository.observeScreensaverSurface(profileId).map { items ->
            items.toCandidatesSnapshot(profileId)
        }

    suspend fun getCandidatesSnapshot(profileId: Int): ScreensaverCandidatesSnapshot {
        val snapshot = surfaceRepository
            .getSnapshot(ResolvedDisplaySurfaceRepository.SCREENSAVER_SURFACE_KEY, profileId)
            .toCandidatesSnapshot(profileId)
        return snapshot
    }

    private fun List<ResolvedDisplayItem>.toCandidatesSnapshot(profileId: Int): ScreensaverCandidatesSnapshot {
        val imageCandidates = toImageCandidates()
        val trailerCandidates = toTrailerCandidates()
        traceEvents.emitScreensaverCandidatePoolBuilt(
            profileHash = profileHashForTrace(profileId),
            source = RESOLVED_DISPLAY_SURFACE_SOURCE,
            imageCandidateCount = imageCandidates.size,
            trailerCandidateCount = trailerCandidates.size
        )
        return ScreensaverCandidatesSnapshot(
            imageCandidates = imageCandidates,
            trailerCandidates = trailerCandidates
        )
    }

    private fun List<ResolvedDisplayItem>.toImageCandidates(): List<ScreensaverSlideCandidate> =
        mapNotNull { item -> item.toImageCandidate() }

    private fun List<ResolvedDisplayItem>.toTrailerCandidates(): List<ScreensaverTrailerCandidate> =
        mapNotNull { item -> item.toTrailerCandidate() }

    private fun ResolvedDisplayItem.toImageCandidate(): ScreensaverSlideCandidate? {
        val preferredImage = preferredScreensaverArtwork() ?: return null
        val title = display.title?.takeIf { it.isNotBlank() } ?: return null
        return ScreensaverSlideCandidate(
            itemKey = itemKey,
            contentId = contentId,
            itemType = itemType.toApiString(),
            title = title,
            subtitle = display.releaseDate,
            overview = display.overview,
            genres = display.genres,
            runtime = display.runtimeText,
            rating = rating.validTitleRatingOrNull(),
            artwork = artwork,
            preferredImage = preferredImage,
            stableIds = stableIds,
            trace = sourceTrace
        )
    }

    private fun ResolvedDisplayItem.toTrailerCandidate(): ScreensaverTrailerCandidate? {
        val title = display.title?.takeIf { it.isNotBlank() } ?: return null
        preferredScreensaverArtwork() ?: return null
        return ScreensaverTrailerCandidate(
            itemKey = itemKey,
            contentId = contentId,
            itemType = itemType.toApiString(),
            title = title,
            releaseInfo = display.releaseDate,
            overview = display.overview,
            genres = display.genres,
            runtime = display.runtimeText,
            rating = rating.validTitleRatingOrNull(),
            artwork = artwork,
            trailerState = screensaverTrailerState(),
            stableIds = stableIds
        )
    }

    private fun ResolvedDisplayItem.screensaverTrailerState(): TrailerDisplayState {
        val normalizedFallbacks = trailer.fallbackTrailerYtIds.normalizedTrailerIds()
        if (trailer.selectedPlaybackRef != null || normalizedFallbacks.isNotEmpty()) {
            return trailer.copy(fallbackTrailerYtIds = normalizedFallbacks)
        }
        val lookup = toScreensaverItemLookupRef() ?: return trailer.copy(fallbackTrailerYtIds = emptyList())
        return trailer.copy(
            fallbackTrailerYtIds = emptyList(),
            selectedPlaybackRef = lookup,
            availabilityReason = "screensaver_item_lookup",
            surface = TrailerSurface.SCREENSAVER.name.lowercase()
        )
    }

    private fun ResolvedDisplayItem.toScreensaverItemLookupRef(): TrailerPlaybackRef.ItemLookup? {
        val title = display.title?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        val contentLookupId = stableContentLookupId() ?: contentId.trim().takeIf { it.isNotEmpty() }
        if (contentLookupId == null) return null
        return TrailerPlaybackRef.ItemLookup(
            title = title,
            year = display.year?.toString() ?: display.releaseDate?.takeIf { it.length >= 4 }?.take(4),
            stableIds = stableIds,
            type = itemType.toApiString(),
            contentId = contentLookupId,
            fallbackYtIds = emptyList()
        )
    }

    private fun ResolvedDisplayItem.stableContentLookupId(): String? =
        stableIds.tvdb?.trim()?.takeIf { it.isNotEmpty() }?.let { "tvdb:$it" }
            ?: stableIds.tmdb?.trim()?.takeIf { it.isNotEmpty() }?.let { "tmdb:$it" }
            ?: stableIds.imdb?.trim()?.takeIf { it.isNotEmpty() }?.let { "imdb:$it" }
            ?: stableIds.kitsu?.trim()?.takeIf { it.isNotEmpty() }?.let { "kitsu:$it" }

    private fun List<String>.normalizedTrailerIds(): List<String> =
        mapNotNull { id -> id.trim().takeIf { it.isNotEmpty() } }.distinct()

    private fun ResolvedDisplayItem.preferredScreensaverArtwork(): ArtworkDisplayRef? =
        artwork.backdrop ?: artwork.poster

    private fun TitleRating?.validTitleRatingOrNull(): TitleRating? =
        this?.takeIf { rating -> RatingValueValidator.validTitleRating(rating.value) }

    private companion object {
        const val RESOLVED_DISPLAY_SURFACE_SOURCE = "RESOLVED_DISPLAY_SURFACE"
    }
}
