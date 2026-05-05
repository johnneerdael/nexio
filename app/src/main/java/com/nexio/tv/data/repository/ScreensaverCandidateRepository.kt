package com.nexio.tv.data.repository

import com.nexio.tv.core.artwork.ArtworkDisplayRef
import com.nexio.tv.domain.model.ResolvedDisplayItem
import com.nexio.tv.ui.screensaver.ScreensaverSlideCandidate
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

@Singleton
class ScreensaverCandidateRepository @Inject constructor(
    private val surfaceRepository: ResolvedDisplaySurfaceRepository
) {
    fun observeImageCandidates(profileId: Int): Flow<List<ScreensaverSlideCandidate>> =
        surfaceRepository.observeHomeSurface(profileId).map { items ->
            items.mapNotNull { item -> item.toImageCandidate() }
        }

    private fun ResolvedDisplayItem.toImageCandidate(): ScreensaverSlideCandidate? {
        val preferredImage = preferredScreensaverArtwork() ?: return null
        return ScreensaverSlideCandidate(
            itemKey = itemKey,
            contentId = contentId,
            itemType = itemType.toApiString(),
            title = display.title,
            subtitle = display.releaseDate,
            overview = display.overview,
            rating = rating,
            artwork = artwork,
            preferredImage = preferredImage,
            stableIds = stableIds,
            trace = sourceTrace
        )
    }

    private fun ResolvedDisplayItem.preferredScreensaverArtwork(): ArtworkDisplayRef? =
        artwork.backdrop ?: artwork.poster
}
