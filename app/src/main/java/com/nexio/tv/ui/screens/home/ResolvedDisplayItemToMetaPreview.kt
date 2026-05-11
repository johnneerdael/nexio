package com.nexio.tv.ui.screens.home

import com.nexio.tv.core.artwork.toLegacyArtworkString
import com.nexio.tv.domain.model.MetaPreview
import com.nexio.tv.domain.model.PosterShape
import com.nexio.tv.domain.model.ResolvedDisplayItem
import com.nexio.tv.domain.model.TitleRatingSource

/**
 * Plan B Task 6f.5 — project the typed authority's [ResolvedDisplayItem] back to a
 * [MetaPreview] for legacy consumers ([composeHydratedHomeOverlaySnapshot]) that
 * still operate on [com.nexio.tv.domain.model.CatalogRow]. Used during snapshot
 * apply to reconstruct `CatalogRow.items` from `Rail.items` (a list of
 * `RailItemKey`) + the typed surface lookup.
 *
 * Field choices match the existing artwork/display fallback chain in
 * [com.nexio.tv.domain.model.HomeDisplayMetadata.displayPoster] etc. — prefer the
 * structured [com.nexio.tv.core.artwork.ArtworkBundle] field, fall back to nothing
 * (legacy reads on `MetaPreview.poster` etc. already tolerate null).
 *
 * The mapping is lossy on purpose: ratings beyond IMDB value, genres, trailers,
 * language metadata etc. come from downstream enrichment, not the typed surface.
 * Consumers of reconstructed rows must not assume those fields are populated.
 */
internal fun ResolvedDisplayItem.toMetaPreview(): MetaPreview {
    return MetaPreview(
        id = contentId,
        type = itemType,
        name = display.title.orEmpty(),
        poster = artwork.poster?.toLegacyArtworkString(),
        posterShape = PosterShape.POSTER,
        background = artwork.backdrop?.toLegacyArtworkString(),
        logo = artwork.logo?.toLegacyArtworkString(),
        description = display.overview,
        releaseInfo = display.releaseDate ?: display.year?.toString(),
        runtime = display.runtimeText,
        imdbRating = rating?.value?.toFloat(),
        ratingSource = rating?.source ?: TitleRatingSource.IMDB,
        tomatoesRating = display.tomatoesRating,
        genres = display.genres,
        artwork = artwork,
        firstPaintStableIds = stableIds
    )
}
