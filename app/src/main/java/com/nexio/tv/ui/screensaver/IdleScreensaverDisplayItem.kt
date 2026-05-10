package com.nexio.tv.ui.screensaver

import androidx.compose.runtime.Immutable
import com.nexio.tv.core.artwork.ArtworkDisplayRef
import com.nexio.tv.domain.model.ResolvedDisplayItem
import com.nexio.tv.domain.model.TitleRating
import com.nexio.tv.domain.model.TrailerDisplayState

/**
 * Single source of truth for whether a [ResolvedDisplayItem] is renderable on
 * the screensaver. Shared by `IdleScreensaverRepository` (gates the upstream
 * publish path) and `ScreensaverCandidateRepository` (gates legacy slide /
 * trailer adapter outputs).
 *
 * Definition: a non-blank title AND at least one displayable artwork
 * (backdrop or poster — the screensaver will fall back to poster when no
 * backdrop is available, see [IdleScreensaverDisplayItem.from]).
 *
 * Without a shared predicate the gate would drift between the three call
 * sites (publishResolvedItems, toIdleScreensaverSlide,
 * toIdleTrailerScreensaverCandidate) — code-quality review on commit
 * c26f80b66 flagged this as Important.
 */
internal fun ResolvedDisplayItem.isScreensaverDisplayable(): Boolean =
    !display.title.isNullOrBlank() && (artwork.backdrop != null || artwork.poster != null)

/**
 * Projection-side displayability check. Equivalent to the
 * [ResolvedDisplayItem]-side gate: `backgroundRef` is `backdrop ?: poster`,
 * so a non-null `backgroundRef` proves at least one of those was non-null at
 * projection time.
 */
internal fun IdleScreensaverDisplayItem.isDisplayable(): Boolean =
    !title.isNullOrBlank() && backgroundRef != null

/**
 * Screensaver-specific projection of [ResolvedDisplayItem]. Used by both
 * `IdleScreensaverOverlay` (variant 1: backdrop + logo + metadata) and
 * `IdleTrailerScreensaverOverlay` (variant 2: trailer + metadata).
 *
 * The minimal field set in the original Plan B sketch was scoped to
 * MetaPreview parity; the actual overlay rendering needs `itemType`
 * (for navigation), `releaseInfo` and `runtime` (for the metadata strip).
 * `addonBaseUrl` is intentionally absent — the previous
 * IdleScreensaverSlide hardcoded it to "" already.
 */
@Immutable
data class IdleScreensaverDisplayItem(
    val itemKey: String,
    val contentId: String,
    val itemType: String,
    val title: String?,
    val overview: String?,
    val genres: List<String>,
    val releaseInfo: String?,
    val runtime: String?,
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
                itemType = resolved.itemType.toApiString(),
                title = resolved.display.title,
                overview = resolved.display.overview,
                genres = resolved.display.genres,
                releaseInfo = resolved.display.releaseDate,
                runtime = resolved.display.runtimeText,
                year = resolved.display.year,
                backgroundRef = resolved.artwork.backdrop ?: resolved.artwork.poster,
                logoRef = resolved.artwork.logo,
                rating = resolved.rating,
                trailer = resolved.trailer
            )
    }
}
