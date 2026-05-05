package com.nexio.tv.data.repository

import com.nexio.tv.core.artwork.ArtworkDisplayRef
import com.nexio.tv.domain.model.ResolvedDisplayItem
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
class ScreensaverCandidateRepository @Inject constructor(
    private val surfaceRepository: ResolvedDisplaySurfaceRepository
) {
    fun observeImageCandidates(profileId: Int): Flow<List<ScreensaverSlideCandidate>> =
        surfaceRepository.observeHomeSurface(profileId).map { items ->
            items.toImageCandidates()
        }

    fun observeTrailerCandidates(profileId: Int): Flow<List<ScreensaverTrailerCandidate>> =
        surfaceRepository.observeHomeSurface(profileId).map { items ->
            items.toTrailerCandidates()
        }

    suspend fun getCandidatesSnapshot(profileId: Int): ScreensaverCandidatesSnapshot {
        val items = surfaceRepository.getSnapshot(profileId)
        return ScreensaverCandidatesSnapshot(
            imageCandidates = items.toImageCandidates(),
            trailerCandidates = items.toTrailerCandidates()
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
            rating = rating,
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
            rating = rating,
            artwork = artwork,
            fallbackTrailerYtIds = trailer.fallbackTrailerYtIds.normalizedTrailerIds(),
            stableIds = stableIds
        )
    }

    private fun List<String>.normalizedTrailerIds(): List<String> =
        mapNotNull { id -> id.trim().takeIf { it.isNotEmpty() } }.distinct()

    private fun ResolvedDisplayItem.preferredScreensaverArtwork(): ArtworkDisplayRef? =
        artwork.backdrop ?: artwork.poster
}
