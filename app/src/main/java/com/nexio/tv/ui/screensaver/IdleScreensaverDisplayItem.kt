package com.nexio.tv.ui.screensaver

import androidx.compose.runtime.Immutable
import com.nexio.tv.core.artwork.ArtworkDisplayRef
import com.nexio.tv.domain.model.ResolvedDisplayItem
import com.nexio.tv.domain.model.TitleRating
import com.nexio.tv.domain.model.TrailerDisplayState

/**
 * Screensaver-specific projection of [ResolvedDisplayItem]. Used by both
 * `IdleScreensaverOverlay` (variant 1: backdrop + logo + metadata) and
 * `IdleTrailerScreensaverOverlay` (variant 2: trailer + metadata).
 */
@Immutable
data class IdleScreensaverDisplayItem(
    val itemKey: String,
    val contentId: String,
    val title: String?,
    val overview: String?,
    val genres: List<String>,
    val year: Int?,
    val backgroundRef: ArtworkDisplayRef?,
    val logoRef: ArtworkDisplayRef?,
    val rating: TitleRating?,
    val trailer: TrailerDisplayState
) {
    companion object {
        fun from(resolved: ResolvedDisplayItem): IdleScreensaverDisplayItem =
            IdleScreensaverDisplayItem(
                itemKey = resolved.itemKey,
                contentId = resolved.contentId,
                title = resolved.display.title,
                overview = resolved.display.overview,
                genres = resolved.display.genres,
                year = resolved.display.year,
                backgroundRef = resolved.artwork.backdrop ?: resolved.artwork.poster,
                logoRef = resolved.artwork.logo,
                rating = resolved.rating,
                trailer = resolved.trailer
            )
    }
}
