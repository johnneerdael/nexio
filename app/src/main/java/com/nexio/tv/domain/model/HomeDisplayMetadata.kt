package com.nexio.tv.domain.model

import androidx.compose.runtime.Immutable
import com.nexio.tv.core.artwork.ArtworkBundle
import com.nexio.tv.core.artwork.ArtworkDisplayRef
import com.nexio.tv.core.artwork.ArtworkTrace
import com.nexio.tv.core.artwork.ArtworkType
import com.nexio.tv.core.artwork.emptyOrNull
import com.nexio.tv.core.artwork.enforceArtworkTypeBoundaries
import com.nexio.tv.core.artwork.toLegacyArtworkString

@Immutable
data class HomeDisplayMetadata(
    val title: String? = null,
    val logo: String? = null,
    val description: String? = null,
    val genres: List<String> = emptyList(),
    val releaseInfo: String? = null,
    val runtime: String? = null,
    val imdbRating: Float? = null,
    val ratingSource: TitleRatingSource? = TitleRatingSource.IMDB,
    val tomatoesRating: Double? = null,
    /**
     * Production language of the title (e.g. `"eng"` for Citadel). Plumbed
     * through to the player's nav arg via `buildContinueWatchingStreamRoute`,
     * so continue-watching playback can target the show's original audio.
     * See `docs/superpowers/notes/2026-05-10-original-language-audio-track-bug.md`.
     */
    val originalLanguage: String? = null,
    /**
     * IMDB id sidecar for the title (e.g. `"tt0903747"`). Carried through
     * `buildContinueWatchingStreamRoute` so subtitle providers (OpenSubtitles,
     * Wyzie) that key on IMDB can still serve content whose canonical id uses
     * a non-IMDB namespace (e.g. `tvdb:N` for series). Null when the metadata
     * pipeline has no IMDB sidecar for the title.
     */
    val imdbId: String? = null,
    val poster: String? = null,
    val posterProviderTag: String? = null,
    val backdrop: String? = null,
    val thumbnail: String? = null,
    @Transient
    val artwork: ArtworkBundle? = null
) {
    val displayPoster: String?
        get() = artwork?.poster.toLegacyArtworkString() ?: poster

    val displayBackdrop: String?
        get() = artwork?.backdrop.toLegacyArtworkString() ?: backdrop

    val displayLogo: String?
        get() = artwork?.logo.toLegacyArtworkString() ?: logo

    val displayThumbnail: String?
        get() = artwork?.thumbnail.toLegacyArtworkString() ?: thumbnail
}

fun MetaPreview.toHomeDisplayMetadata(): HomeDisplayMetadata {
    return HomeDisplayMetadata(
        title = name,
        logo = logo,
        description = description,
        genres = genres,
        releaseInfo = releaseInfo,
        runtime = runtime,
        imdbRating = imdbRating,
        ratingSource = ratingSource.orDefault(),
        tomatoesRating = tomatoesRating,
        originalLanguage = originalLanguage,
        imdbId = firstPaintStableIds.imdb?.takeIf { it.isNotBlank() }
            ?: id.takeIf { it.startsWith("tt", ignoreCase = true) },
        poster = poster,
        posterProviderTag = posterProviderTag,
        backdrop = background,
        thumbnail = artwork?.thumbnail.toLegacyArtworkString(),
        artwork = artwork
    )
}

fun Meta.toHomeDisplayMetadata(): HomeDisplayMetadata {
    return HomeDisplayMetadata(
        title = name,
        logo = logo,
        description = description,
        genres = genres,
        releaseInfo = releaseInfo,
        runtime = runtime,
        imdbRating = imdbRating,
        ratingSource = ratingSource.orDefault(),
        tomatoesRating = null,
        originalLanguage = originalLanguage,
        imdbId = id.takeIf { it.startsWith("tt", ignoreCase = true) },
        poster = poster,
        posterProviderTag = posterProviderTag,
        backdrop = background,
        thumbnail = artwork?.thumbnail.toLegacyArtworkString(),
        artwork = artwork
    )
}

@Deprecated(
    "Use HomeRailProjectionReducer for rail projection. This applyTo path is preserved only for hero-enrichment callers that have not yet migrated. Will be removed once Plan B (UI consumption migration) lands.",
    level = DeprecationLevel.WARNING
)
fun HomeDisplayMetadata.applyTo(base: MetaPreview): MetaPreview {
    val cleanOverlayRating = sanitizedTitleRating()
    val cleanBaseRating = base.imdbRating.sanitizedTitleRating()
    val appliedRating = cleanOverlayRating ?: cleanBaseRating
    val appliedRatingSource = when {
        cleanOverlayRating != null -> ratingSource.orDefault()
        cleanBaseRating != null -> base.ratingSource.orDefault()
        else -> null
    }
    val appliedPoster = preferDurableArtworkRef(displayPoster, base.poster)
    val appliedBackground = preferDurableArtworkRef(displayBackdrop, base.background)
    val appliedLogo = preferDurableArtworkRef(displayLogo, base.logo)
    return base.copy(
        name = title ?: base.name,
        logo = appliedLogo,
        description = description ?: base.description,
        genres = if (genres.isNotEmpty()) genres else base.genres,
        releaseInfo = releaseInfo ?: base.releaseInfo,
        runtime = runtime ?: base.runtime,
        imdbRating = appliedRating,
        ratingSource = appliedRatingSource,
        tomatoesRating = tomatoesRating ?: base.tomatoesRating,
        // Carry production language overlay from the metadata router onto the
        // MetaPreview. Without this the value reaches HomeDisplayMetadata
        // (after E4) but dies at this base.copy boundary, leaving CW items'
        // displayMetadata.originalLanguage null at click time.
        originalLanguage = originalLanguage ?: base.originalLanguage,
        poster = appliedPoster,
        // Pin posterProviderTag to whichever side actually owns the chosen poster ref so
        // a base-preserved durable RPDB ref doesn't get tagged with the overlay's
        // bare-URL provider.
        posterProviderTag = when {
            appliedPoster === base.poster && appliedPoster != null -> base.posterProviderTag
            displayPoster != null -> posterProviderTag
            else -> base.posterProviderTag
        },
        background = appliedBackground,
        artwork = mergeAppliedArtwork(base)
    )
}

/**
 * Prefers a durable `nexio-artwork://...` artwork reference on the base item over a
 * bare-URL overlay value. The metadata router's resolved-doc fields can carry a bare
 * TMDB/TVDB URL (e.g., `https://image.tmdb.org/...`), while the rail's first-paint
 * pipeline (PosterRatingsUrlResolver.applyArtworkRef) stamps `nexio-artwork://decision/...`
 * onto MetaPreview.poster. Without this guard, the overlay merge silently demotes the
 * durable premium ref back to a raw URL, causing the user-visible "premium poster
 * pops then disappears" cold-start flicker.
 *
 * Both sides durable: prefer overlay (it's the freshest selection).
 * Only base durable: keep base.
 * Neither durable: fall back to overlay if present, else base.
 */
private fun preferDurableArtworkRef(overlayValue: String?, baseValue: String?): String? {
    val overlayDurable = overlayValue?.startsWith("nexio-artwork://") == true
    val baseDurable = baseValue?.startsWith("nexio-artwork://") == true
    return when {
        overlayDurable -> overlayValue
        baseDurable -> baseValue
        else -> overlayValue ?: baseValue
    }
}

/**
 * Per-field overlay onto a base [MetaPreview]. Same body as the deprecated
 * [applyTo] but non-deprecated and intentionally scoped to legitimate
 * MetaPreview-shape internal pipeline use cases (provider-localized
 * metadata overlay, etc.) that don't fit the rank-aware
 * [com.nexio.tv.ui.screens.home.HomeRailProjectionReducer.reduce]
 * pattern.
 *
 * Plan B Phase 2C replacement. Will retire alongside the deprecated
 * [applyTo] in Phase 4 once Phase 3 (catalog pipeline restructure)
 * eliminates the remaining HomeHydrationOverlayApplier caller.
 */
fun HomeDisplayMetadata.applyToPreview(base: MetaPreview): MetaPreview {
    val cleanOverlayRating = sanitizedTitleRating()
    val cleanBaseRating = base.imdbRating.sanitizedTitleRating()
    val appliedRating = cleanOverlayRating ?: cleanBaseRating
    val appliedRatingSource = when {
        cleanOverlayRating != null -> ratingSource.orDefault()
        cleanBaseRating != null -> base.ratingSource.orDefault()
        else -> null
    }
    val appliedPoster = preferDurableArtworkRef(displayPoster, base.poster)
    val appliedBackground = preferDurableArtworkRef(displayBackdrop, base.background)
    val appliedLogo = preferDurableArtworkRef(displayLogo, base.logo)
    return base.copy(
        name = title ?: base.name,
        logo = appliedLogo,
        description = description ?: base.description,
        genres = if (genres.isNotEmpty()) genres else base.genres,
        releaseInfo = releaseInfo ?: base.releaseInfo,
        runtime = runtime ?: base.runtime,
        imdbRating = appliedRating,
        ratingSource = appliedRatingSource,
        tomatoesRating = tomatoesRating ?: base.tomatoesRating,
        // Carry production language overlay from the metadata router onto the
        // MetaPreview. Without this the value reaches HomeDisplayMetadata
        // (after E4) but dies at this base.copy boundary, leaving CW items'
        // displayMetadata.originalLanguage null at click time.
        originalLanguage = originalLanguage ?: base.originalLanguage,
        poster = appliedPoster,
        // Pin posterProviderTag to whichever side actually owns the chosen poster ref so
        // a base-preserved durable RPDB ref doesn't get tagged with the overlay's
        // bare-URL provider.
        posterProviderTag = when {
            appliedPoster === base.poster && appliedPoster != null -> base.posterProviderTag
            displayPoster != null -> posterProviderTag
            else -> base.posterProviderTag
        },
        background = appliedBackground,
        artwork = mergeAppliedArtwork(base)
    )
}

@Deprecated(
    "Use HomeRailProjectionReducer.reduce(...) instead. mergeFallback predates the rank-aware reducer and treats inputs as primary/fallback rather than rank-ordered. Will be removed once all callers migrate.",
    level = DeprecationLevel.WARNING
)
fun HomeDisplayMetadata.mergeFallback(fallback: HomeDisplayMetadata?): HomeDisplayMetadata {
    if (fallback == null) return this
    val cleanPrimaryRating = sanitizedTitleRating()
    val cleanFallbackRating = fallback.imdbRating.sanitizedTitleRating()
    val mergedRating = cleanPrimaryRating ?: cleanFallbackRating
    val mergedRatingSource = when {
        cleanPrimaryRating != null -> ratingSource.orDefault()
        cleanFallbackRating != null -> fallback.ratingSource.orDefault()
        else -> null
    }
    return copy(
        title = title ?: fallback.title,
        logo = logo ?: fallback.logo,
        description = description ?: fallback.description,
        genres = if (genres.isNotEmpty()) genres else fallback.genres,
        releaseInfo = releaseInfo ?: fallback.releaseInfo,
        runtime = runtime ?: fallback.runtime,
        imdbRating = mergedRating,
        ratingSource = mergedRatingSource,
        tomatoesRating = tomatoesRating ?: fallback.tomatoesRating,
        originalLanguage = originalLanguage ?: fallback.originalLanguage,
        imdbId = imdbId ?: fallback.imdbId,
        poster = poster ?: fallback.poster,
        posterProviderTag = if (displayPoster != null) posterProviderTag else fallback.posterProviderTag,
        backdrop = backdrop ?: fallback.backdrop,
        thumbnail = thumbnail ?: fallback.thumbnail,
        artwork = mergeFallbackArtwork(fallback)
    )
}

/**
 * Per-field coalesce: each non-null field of `this` wins; null fields fall back to
 * [fallback]'s same field. Same body as the deprecated [mergeFallback] but
 * non-deprecated and intentionally scoped to legitimate per-field-coalesce use cases
 * (CW persistence boundary, localized-metadata enrichment) that don't fit the
 * rank-aware [HomeRailProjectionReducer.reduce] pattern.
 *
 * Plan B Phase 2B replacement. When [fallback] is null, returns `this` unchanged.
 *
 * Special handling matches [mergeFallback]:
 * - `imdbRating`/`ratingSource` are paired — both come from whichever input has a
 *   sanitized non-null rating.
 * - `genres` keeps `this.genres` only when non-empty; otherwise falls back.
 * - `posterProviderTag` is gated on `displayPoster` (only kept if `this` has a
 *   non-null displayPoster, otherwise falls back).
 * - `artwork` uses the same durable-ref preference as [mergeFallback].
 */
fun HomeDisplayMetadata.coalesceWith(fallback: HomeDisplayMetadata?): HomeDisplayMetadata {
    if (fallback == null) return this
    val cleanPrimaryRating = sanitizedTitleRating()
    val cleanFallbackRating = fallback.imdbRating.sanitizedTitleRating()
    val mergedRating = cleanPrimaryRating ?: cleanFallbackRating
    val mergedRatingSource = when {
        cleanPrimaryRating != null -> ratingSource.orDefault()
        cleanFallbackRating != null -> fallback.ratingSource.orDefault()
        else -> null
    }
    return copy(
        title = title ?: fallback.title,
        logo = logo ?: fallback.logo,
        description = description ?: fallback.description,
        genres = if (genres.isNotEmpty()) genres else fallback.genres,
        releaseInfo = releaseInfo ?: fallback.releaseInfo,
        runtime = runtime ?: fallback.runtime,
        imdbRating = mergedRating,
        ratingSource = mergedRatingSource,
        tomatoesRating = tomatoesRating ?: fallback.tomatoesRating,
        originalLanguage = originalLanguage ?: fallback.originalLanguage,
        imdbId = imdbId ?: fallback.imdbId,
        poster = poster ?: fallback.poster,
        posterProviderTag = if (displayPoster != null) posterProviderTag else fallback.posterProviderTag,
        backdrop = backdrop ?: fallback.backdrop,
        thumbnail = thumbnail ?: fallback.thumbnail,
        artwork = mergeFallbackArtwork(fallback)
    )
}

private fun HomeDisplayMetadata.sanitizedTitleRating(): Float? =
    RatingValueValidator.sanitizeTitleRating(imdbRating)

private fun Float?.sanitizedTitleRating(): Float? =
    RatingValueValidator.sanitizeTitleRating(this)

private fun HomeDisplayMetadata.mergeFallbackArtwork(fallback: HomeDisplayMetadata): ArtworkBundle? {
    val primaryArtwork = artwork?.enforceArtworkTypeBoundaries()
    val fallbackArtwork = fallback.artwork?.enforceArtworkTypeBoundaries()
    val merged = ArtworkBundle(
        poster = primaryArtwork?.poster ?: fallbackArtwork?.poster,
        backdrop = primaryArtwork?.backdrop ?: fallbackArtwork?.backdrop,
        logo = primaryArtwork?.logo ?: fallbackArtwork?.logo,
        thumbnail = primaryArtwork?.thumbnail ?: fallbackArtwork?.thumbnail
    )
    return merged.enforceArtworkTypeBoundaries().emptyOrNull()
}

private fun HomeDisplayMetadata.mergeAppliedArtwork(base: MetaPreview): ArtworkBundle? {
    val primaryArtwork = artwork?.enforceArtworkTypeBoundaries()
    val baseArtwork = base.artwork?.enforceArtworkTypeBoundaries()
    val merged = ArtworkBundle(
        poster = primaryArtwork?.poster ?: baseArtwork?.poster,
        backdrop = primaryArtwork?.backdrop ?: baseArtwork?.backdrop,
        logo = primaryArtwork?.logo ?: baseArtwork?.logo,
        thumbnail = primaryArtwork?.thumbnail ?: baseArtwork?.thumbnail
    )
    return merged.enforceArtworkTypeBoundaries().emptyOrNull()
}

fun homeDisplayItemKey(contentType: String, contentId: String): String {
    return "${contentType.lowercase()}:${contentId.trim()}"
}

/**
 * Reconstructs a structured [ArtworkBundle] from this metadata.
 *
 * Continue Watching snapshots persist [HomeDisplayMetadata] without the structured `artwork`
 * bundle (it is `@Transient`), but the per-field strings retain the durable
 * `nexio-artwork://...` references emitted by the artwork pipeline. This helper rebuilds the
 * bundle from those strings so downstream projections (e.g., `MetaPreview.artwork`) can keep
 * the resolved provider artwork through restore.
 *
 * Raw premium provider URLs (RPDB / top-posters) are rejected because they are non-durable —
 * they shouldn't appear in display fields, but we defend against bad inputs.
 */
fun HomeDisplayMetadata.toArtworkBundleFromDisplayFields(): ArtworkBundle? {
    val structured = artwork?.enforceArtworkTypeBoundaries()
    val merged = ArtworkBundle(
        poster = structured?.poster ?: displayPoster.toLegacyArtworkRef(ArtworkType.POSTER),
        backdrop = structured?.backdrop ?: displayBackdrop.toLegacyArtworkRef(ArtworkType.BACKDROP),
        logo = structured?.logo ?: displayLogo.toLegacyArtworkRef(ArtworkType.LOGO),
        thumbnail = structured?.thumbnail ?: displayThumbnail.toLegacyArtworkRef(ArtworkType.THUMBNAIL)
    )
    return merged.enforceArtworkTypeBoundaries().emptyOrNull()
}

private fun String?.toLegacyArtworkRef(imageType: ArtworkType): ArtworkDisplayRef.LegacyString? {
    val value = this?.trim()?.takeIf { it.isNotEmpty() } ?: return null
    return when {
        value.startsWith("nexio-artwork://asset/") ||
            value.startsWith("nexio-artwork://decision/") ||
            value.startsWith("nexio-placeholder://") ->
            ArtworkDisplayRef.LegacyString(
                value = value,
                imageType = imageType,
                trace = ArtworkTrace.empty()
            )

        value.startsWith("https://api.top-posters", ignoreCase = true) ||
            value.startsWith("https://api.ratingposterdb", ignoreCase = true) ->
            null

        value.startsWith("http://", ignoreCase = true) ||
            value.startsWith("https://", ignoreCase = true) ->
            ArtworkDisplayRef.LegacyString(
                value = value,
                imageType = imageType,
                trace = ArtworkTrace(
                    selectedProvider = "COMPATIBILITY_REMOTE",
                    sourceRole = "RAIL_PREVIEW"
                )
            )

        else -> null
    }
}
