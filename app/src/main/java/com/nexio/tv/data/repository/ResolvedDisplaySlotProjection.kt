package com.nexio.tv.data.repository

import com.nexio.tv.core.artwork.ArtworkBundle
import com.nexio.tv.core.artwork.enforceArtworkTypeBoundaries
import com.nexio.tv.domain.model.ResolvedDisplayFields
import com.nexio.tv.domain.model.ResolvedDisplayFieldSlots
import com.nexio.tv.domain.model.TitleRating

/**
 * Project [ResolvedDisplayFieldSlots] → flat-field shapes that legacy renderers consume.
 *
 * Shared between [com.nexio.tv.ui.screens.home.HomeResolvedDisplayMapper] (which produces
 * fresh items from a CatalogRow + overlay) and [ResolvedDisplaySurfaceRepository] (which
 * re-projects after the boundary's rank-aware merge). Both paths must produce byte-
 * identical flat fields from the same slots, otherwise a downgrade-protected merge at the
 * boundary could leak through if either projection diverged.
 */
internal fun ResolvedDisplayFieldSlots.toArtworkBundle(): ArtworkBundle =
    ArtworkBundle(
        poster = poster.value,
        backdrop = backdrop.value,
        logo = logo.value,
        thumbnail = thumbnail.value
    ).enforceArtworkTypeBoundaries()

internal fun ResolvedDisplayFieldSlots.toResolvedDisplayFields(
    fallbackTitle: String,
    fallbackTomatoesRating: Double?
): ResolvedDisplayFields {
    val effectiveTitle = title.value ?: fallbackTitle
    val yearText = releaseInfo.value?.take(4)?.takeIf { it.length == 4 }
    return ResolvedDisplayFields(
        title = effectiveTitle,
        originalTitle = originalTitle.value,
        year = yearText?.toIntOrNull(),
        releaseDate = releaseInfo.value,
        overview = overview.value,
        genres = genres.value.orEmpty(),
        runtimeText = runtime.value,
        tomatoesRating = fallbackTomatoesRating
    )
}

internal fun ResolvedDisplayFieldSlots.toRating(): TitleRating? = rating.value
