package com.nexio.tv.ui.screens.home

import androidx.compose.runtime.Immutable
import com.nexio.tv.core.artwork.ArtworkDisplayRef
import com.nexio.tv.domain.model.ResolvedDisplayItem
import com.nexio.tv.domain.model.TitleRating
import com.nexio.tv.domain.model.TrailerDisplayState

/**
 * Hero-panel-specific projection of [ResolvedDisplayItem].
 *
 * Hero is the only surface where `backdrop ?: poster` fallback is allowed for the
 * background image (per architecture: "Hero panel background = resolved.artwork.backdrop
 * ?: resolved.artwork.poster"). All other surfaces must keep slot types strict — see
 * [ModernHomeRowItem] which exposes typed `posterRef` / `backdropRef` / `logoRef`
 * separately and never falls back across types.
 */
@Immutable
data class HeroDisplayItem(
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
        fun from(resolved: ResolvedDisplayItem): HeroDisplayItem =
            HeroDisplayItem(
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
