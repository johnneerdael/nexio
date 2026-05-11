package com.nexio.tv.ui.screens.detail

import androidx.compose.runtime.Immutable
import com.nexio.tv.core.artwork.ArtworkDisplayRef
import com.nexio.tv.domain.model.ResolvedDisplayItem
import com.nexio.tv.domain.model.TitleRating

/**
 * Typed projection of [ResolvedDisplayItem] for the detail screen surface.
 *
 * Detail-screen-specific subset — carries the hero-section / overview / rating
 * fields, plus poster/backdrop/logo URLs. Does NOT mirror every field that the
 * rail-level [com.nexio.tv.ui.screens.home.ModernHomeRowItem] needs because
 * detail renders the hero, not a poster card.
 *
 * Plan B Surface 5 — produced by [MetaDetailsViewModel] from
 * [com.nexio.tv.data.repository.ResolvedDisplaySurfaceRepository], consumed by
 * `HeroSection` / detail composables via `MetaDetailsUiState.resolvedDetailFields`.
 * Replaces legacy `Meta` / `MetaPreview` field reads on the detail screen.
 */
@Immutable
data class MetaDetailsResolvedFields(
    val itemKey: String,
    val title: String?,
    val originalTitle: String?,
    val year: Int?,
    val releaseDate: String?,
    val overview: String?,
    val genres: List<String>,
    val runtimeText: String?,
    val tomatoesRating: Double?,
    val rating: TitleRating?,
    val posterUrl: String?,
    val backdropUrl: String?,
    val logoUrl: String?
) {
    companion object {
        fun from(item: ResolvedDisplayItem): MetaDetailsResolvedFields {
            val display = item.display
            return MetaDetailsResolvedFields(
                itemKey = item.itemKey,
                title = display.title,
                originalTitle = display.originalTitle,
                year = display.year,
                releaseDate = display.releaseDate,
                overview = display.overview,
                genres = display.genres,
                runtimeText = display.runtimeText,
                tomatoesRating = display.tomatoesRating,
                rating = item.rating,
                posterUrl = item.artwork.poster.toLegacyUrlOrNull(),
                backdropUrl = item.artwork.backdrop.toLegacyUrlOrNull(),
                logoUrl = item.artwork.logo.toLegacyUrlOrNull()
            )
        }

        private fun ArtworkDisplayRef?.toLegacyUrlOrNull(): String? = when (this) {
            is ArtworkDisplayRef.LegacyString -> value.takeIf { it.isNotBlank() }
            else -> null
        }
    }
}
