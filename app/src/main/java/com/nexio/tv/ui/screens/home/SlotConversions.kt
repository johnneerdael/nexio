package com.nexio.tv.ui.screens.home

import com.nexio.tv.core.artwork.ArtworkBundle
import com.nexio.tv.core.artwork.ArtworkDisplayRef
import com.nexio.tv.core.artwork.ArtworkTrace
import com.nexio.tv.core.artwork.ArtworkType
import com.nexio.tv.core.artwork.emptyOrNull
import com.nexio.tv.core.artwork.enforceArtworkTypeBoundaries
import com.nexio.tv.core.artwork.takeIfImageType
import com.nexio.tv.core.artwork.toLegacyArtworkString
import com.nexio.tv.domain.model.DisplaySourceRank
import com.nexio.tv.domain.model.HomeDisplayMetadata
import com.nexio.tv.domain.model.HydratedHomeOverlay
import com.nexio.tv.domain.model.MetaPreview
import com.nexio.tv.domain.model.RatingValueValidator
import com.nexio.tv.domain.model.ResolvedDisplayFieldSlots
import com.nexio.tv.domain.model.ResolvedSlot
import com.nexio.tv.domain.model.TitleRating
import com.nexio.tv.domain.model.TitleRatingSource

private const val ROLE_RAIL_PREVIEW = "RAIL_PREVIEW"
private const val ROLE_HYDRATION_RESOLVED = "HYDRATION_RESOLVED"

/**
 * Projects a rail-emitted [MetaPreview] into rank-tagged slots. The rail row is
 * always FIRST_PAINT — it is INPUT to the reducer, never authority. Any field
 * that is null/blank/empty becomes an EMPTY slot so a higher-rank source can
 * fill it; conversely a non-null FIRST_PAINT slot still loses to RESOLVED.
 */
fun MetaPreview.toFirstPaintSlots(nowMs: Long): ResolvedDisplayFieldSlots {
    val railProvider = firstPaintRailSource?.name ?: firstPaintSourceProvider?.name
    return ResolvedDisplayFieldSlots(
        title = stringSlot(name, railProvider, ROLE_RAIL_PREVIEW, nowMs),
        originalTitle = ResolvedSlot.empty(nowMs),
        overview = stringSlot(description, railProvider, ROLE_RAIL_PREVIEW, nowMs),
        genres = listSlot(genres, railProvider, ROLE_RAIL_PREVIEW, nowMs),
        releaseInfo = stringSlot(releaseInfo, railProvider, ROLE_RAIL_PREVIEW, nowMs),
        runtime = stringSlot(runtime, railProvider, ROLE_RAIL_PREVIEW, nowMs),
        rating = ratingSlot(imdbRating, ratingSource, railProvider, ROLE_RAIL_PREVIEW, nowMs),
        poster = artworkSlotFromBundle(
            artwork?.poster.takeIfImageType(ArtworkType.POSTER), poster,
            ArtworkType.POSTER, railProvider, ROLE_RAIL_PREVIEW, nowMs, DisplaySourceRank.FIRST_PAINT
        ),
        backdrop = artworkSlotFromBundle(
            artwork?.backdrop.takeIfImageType(ArtworkType.BACKDROP), background,
            ArtworkType.BACKDROP, railProvider, ROLE_RAIL_PREVIEW, nowMs, DisplaySourceRank.FIRST_PAINT
        ),
        logo = artworkSlotFromBundle(
            artwork?.logo.takeIfImageType(ArtworkType.LOGO), logo,
            ArtworkType.LOGO, railProvider, ROLE_RAIL_PREVIEW, nowMs, DisplaySourceRank.FIRST_PAINT
        ),
        thumbnail = artworkSlotFromBundle(
            artwork?.thumbnail.takeIfImageType(ArtworkType.THUMBNAIL), artwork?.thumbnail?.toLegacyArtworkString(),
            ArtworkType.THUMBNAIL, railProvider, ROLE_RAIL_PREVIEW, nowMs, DisplaySourceRank.FIRST_PAINT
        ),
        posterProviderTag = stringSlot(posterProviderTag, railProvider, ROLE_RAIL_PREVIEW, nowMs)
    )
}

/**
 * Projects a [HydratedHomeOverlay] into rank-tagged slots at RESOLVED or
 * STALE_RESOLVED rank depending on freshness. Field provenance comes from the
 * overlay's per-field trace where available, falling back to the canonical
 * provider name.
 */
fun HydratedHomeOverlay.toResolvedSlots(nowMs: Long, isStale: Boolean): ResolvedDisplayFieldSlots {
    val rank = if (isStale) DisplaySourceRank.STALE_RESOLVED else DisplaySourceRank.RESOLVED
    val provider = canonicalProvider.name
    fun providerFor(field: String): String =
        fieldTrace.firstOrNull { it.field.equals(field, ignoreCase = true) }?.selectedProvider
            ?: provider
    fun roleFor(field: String): String =
        fieldTrace.firstOrNull { it.field.equals(field, ignoreCase = true) }?.sourceRole
            ?: ROLE_HYDRATION_RESOLVED
    return ResolvedDisplayFieldSlots(
        title = stringSlot(fields.title, providerFor("title"), roleFor("title"), updatedAtMs, rank),
        originalTitle = ResolvedSlot.empty(nowMs),
        overview = stringSlot(fields.description, providerFor("description"), roleFor("description"), updatedAtMs, rank),
        genres = listSlot(fields.genres, providerFor("genres"), roleFor("genres"), updatedAtMs, rank),
        releaseInfo = stringSlot(fields.releaseInfo, providerFor("releaseInfo"), roleFor("releaseInfo"), updatedAtMs, rank),
        runtime = stringSlot(fields.runtime, providerFor("runtime"), roleFor("runtime"), updatedAtMs, rank),
        rating = ratingSlot(fields.imdbRating, fields.ratingSource, providerFor("rating"), roleFor("rating"), updatedAtMs, rank),
        poster = artworkSlotFromBundle(fields.artwork?.poster.takeIfImageType(ArtworkType.POSTER), fields.poster, ArtworkType.POSTER, providerFor("poster"), roleFor("poster"), updatedAtMs, rank),
        backdrop = artworkSlotFromBundle(fields.artwork?.backdrop.takeIfImageType(ArtworkType.BACKDROP), fields.backdrop, ArtworkType.BACKDROP, providerFor("backdrop"), roleFor("backdrop"), updatedAtMs, rank),
        logo = artworkSlotFromBundle(fields.artwork?.logo.takeIfImageType(ArtworkType.LOGO), fields.logo, ArtworkType.LOGO, providerFor("logo"), roleFor("logo"), updatedAtMs, rank),
        thumbnail = artworkSlotFromBundle(fields.artwork?.thumbnail.takeIfImageType(ArtworkType.THUMBNAIL), fields.thumbnail, ArtworkType.THUMBNAIL, providerFor("thumbnail"), roleFor("thumbnail"), updatedAtMs, rank),
        posterProviderTag = stringSlot(fields.posterProviderTag, providerFor("posterProviderTag"), roleFor("posterProviderTag"), updatedAtMs, rank)
    )
}

/**
 * Projects a [HomeDisplayMetadata] into rank-tagged slots at [rank]. Null /
 * blank fields become EMPTY-rank slots (per the rank coercion in [stringSlot]
 * et al.), so a lower-rank input with a non-null value will still win for that
 * field — equivalent to [com.nexio.tv.domain.model.coalesceWith]'s null-fallback
 * semantic but routed through the typed-slot rank machinery.
 *
 * Used by `ContinueWatchingMetadataSnapshot.renderDisplayMetadata` (Phase 3.8)
 * to merge canonical / clickTime / persistedFallback metadata via
 * [HomeRailProjectionReducer]. Mirrors [HydratedHomeOverlay.toResolvedSlots]
 * (overlay.fields IS a HomeDisplayMetadata — same field set).
 */
fun HomeDisplayMetadata.toResolvedFieldSlots(
    nowMs: Long,
    rank: DisplaySourceRank,
    provider: String? = null,
    role: String? = ROLE_HYDRATION_RESOLVED,
): ResolvedDisplayFieldSlots {
    return ResolvedDisplayFieldSlots(
        title = stringSlot(title, provider, role, nowMs, rank),
        originalTitle = ResolvedSlot.empty(nowMs),
        overview = stringSlot(description, provider, role, nowMs, rank),
        genres = listSlot(genres, provider, role, nowMs, rank),
        releaseInfo = stringSlot(releaseInfo, provider, role, nowMs, rank),
        runtime = stringSlot(runtime, provider, role, nowMs, rank),
        rating = ratingSlot(imdbRating, ratingSource, provider, role, nowMs, rank),
        poster = artworkSlotFromBundle(
            artwork?.poster.takeIfImageType(ArtworkType.POSTER), poster,
            ArtworkType.POSTER, provider, role, nowMs, rank
        ),
        backdrop = artworkSlotFromBundle(
            artwork?.backdrop.takeIfImageType(ArtworkType.BACKDROP), backdrop,
            ArtworkType.BACKDROP, provider, role, nowMs, rank
        ),
        logo = artworkSlotFromBundle(
            artwork?.logo.takeIfImageType(ArtworkType.LOGO), logo,
            ArtworkType.LOGO, provider, role, nowMs, rank
        ),
        thumbnail = artworkSlotFromBundle(
            artwork?.thumbnail.takeIfImageType(ArtworkType.THUMBNAIL), thumbnail,
            ArtworkType.THUMBNAIL, provider, role, nowMs, rank
        ),
        posterProviderTag = stringSlot(posterProviderTag, provider, role, nowMs, rank)
    )
}

/**
 * Inverse of [HomeDisplayMetadata.toResolvedFieldSlots]: builds a
 * [HomeDisplayMetadata] from the merged slot bag, pulling `slot.value` per
 * field. Used by `ContinueWatchingMetadataSnapshot.renderDisplayMetadata` to
 * return the merged result in the legacy [HomeDisplayMetadata] shape (the
 * CW snapshot field-type change to [ResolvedDisplayFieldSlots] is out of
 * scope for Phase 3.8 partial; a future session that can verify persistence
 * schema migration on-device will land it).
 *
 * `tomatoesRating`, `originalLanguage`, and `imdbId` are not part of the
 * slot bag (they're not visible on rail/CW cards through the typed
 * authority path). They are intentionally dropped here.
 */
fun ResolvedDisplayFieldSlots.toHomeDisplayMetadata(): HomeDisplayMetadata {
    val posterUrl = poster.value?.toLegacyArtworkString()
    val backdropUrl = backdrop.value?.toLegacyArtworkString()
    val logoUrl = logo.value?.toLegacyArtworkString()
    val thumbnailUrl = thumbnail.value?.toLegacyArtworkString()
    val artworkBundle = ArtworkBundle(
        poster = poster.value.takeIfImageType(ArtworkType.POSTER),
        backdrop = backdrop.value.takeIfImageType(ArtworkType.BACKDROP),
        logo = logo.value.takeIfImageType(ArtworkType.LOGO),
        thumbnail = thumbnail.value.takeIfImageType(ArtworkType.THUMBNAIL)
    ).enforceArtworkTypeBoundaries().emptyOrNull()
    return HomeDisplayMetadata(
        title = title.value,
        logo = logoUrl,
        description = overview.value,
        genres = genres.value.orEmpty(),
        releaseInfo = releaseInfo.value,
        runtime = runtime.value,
        imdbRating = rating.value?.value?.toFloat(),
        ratingSource = rating.value?.source,
        tomatoesRating = null,
        originalLanguage = null,
        imdbId = null,
        poster = posterUrl,
        posterProviderTag = posterProviderTag.value,
        backdrop = backdropUrl,
        thumbnail = thumbnailUrl,
        artwork = artworkBundle
    )
}

internal fun emptySlotsAt(nowMs: Long): ResolvedDisplayFieldSlots = ResolvedDisplayFieldSlots(
    title = ResolvedSlot.empty(nowMs),
    originalTitle = ResolvedSlot.empty(nowMs),
    overview = ResolvedSlot.empty(nowMs),
    genres = ResolvedSlot.empty(nowMs),
    releaseInfo = ResolvedSlot.empty(nowMs),
    runtime = ResolvedSlot.empty(nowMs),
    rating = ResolvedSlot.empty(nowMs),
    poster = ResolvedSlot.empty(nowMs),
    backdrop = ResolvedSlot.empty(nowMs),
    logo = ResolvedSlot.empty(nowMs),
    thumbnail = ResolvedSlot.empty(nowMs),
    posterProviderTag = ResolvedSlot.empty(nowMs),
)

private fun stringSlot(
    value: String?,
    provider: String?,
    role: String?,
    nowMs: Long,
    rank: DisplaySourceRank = DisplaySourceRank.FIRST_PAINT
): ResolvedSlot<String> {
    val trimmed = value?.trim()?.takeIf { it.isNotEmpty() }
    return ResolvedSlot(
        value = trimmed,
        rank = if (trimmed == null) DisplaySourceRank.EMPTY else rank,
        provider = provider,
        role = role,
        updatedAtMs = nowMs,
        expiresAtMs = null,
        trace = emptyList()
    )
}

private fun listSlot(
    value: List<String>,
    provider: String?,
    role: String?,
    nowMs: Long,
    rank: DisplaySourceRank = DisplaySourceRank.FIRST_PAINT
): ResolvedSlot<List<String>> {
    val nonEmpty = value.takeIf { it.isNotEmpty() }
    return ResolvedSlot(
        value = nonEmpty,
        rank = if (nonEmpty == null) DisplaySourceRank.EMPTY else rank,
        provider = provider,
        role = role,
        updatedAtMs = nowMs,
        expiresAtMs = null,
        trace = emptyList()
    )
}

private fun ratingSlot(
    rating: Float?,
    source: TitleRatingSource?,
    provider: String?,
    role: String?,
    nowMs: Long,
    rank: DisplaySourceRank = DisplaySourceRank.FIRST_PAINT
): ResolvedSlot<TitleRating> {
    val sanitized = RatingValueValidator.sanitizeTitleRating(rating)
    val tr = sanitized?.let { TitleRating(it.toDouble(), source ?: TitleRatingSource.IMDB) }
    return ResolvedSlot(
        value = tr,
        rank = if (tr == null) DisplaySourceRank.EMPTY else rank,
        provider = provider,
        role = role,
        updatedAtMs = nowMs,
        expiresAtMs = null,
        trace = emptyList()
    )
}

private fun artworkSlot(
    legacy: String?,
    type: ArtworkType,
    provider: String?,
    role: String?,
    nowMs: Long,
    rank: DisplaySourceRank = DisplaySourceRank.FIRST_PAINT
): ResolvedSlot<ArtworkDisplayRef> {
    val trimmed = legacy?.trim()?.takeIf { it.isNotEmpty() }
    val ref: ArtworkDisplayRef? = trimmed?.let {
        ArtworkDisplayRef.LegacyString(value = it, imageType = type, trace = ArtworkTrace.empty())
    }
    return ResolvedSlot(
        value = ref,
        rank = if (ref == null) DisplaySourceRank.EMPTY else rank,
        provider = provider,
        role = role,
        updatedAtMs = nowMs,
        expiresAtMs = null,
        trace = emptyList()
    )
}

private fun artworkSlotFromBundle(
    structured: ArtworkDisplayRef?,
    legacyFallback: String?,
    type: ArtworkType,
    provider: String?,
    role: String?,
    nowMs: Long,
    rank: DisplaySourceRank
): ResolvedSlot<ArtworkDisplayRef> {
    val ref: ArtworkDisplayRef? = structured ?: legacyFallback
        ?.trim()
        ?.takeIf { it.isNotEmpty() }
        ?.let { ArtworkDisplayRef.LegacyString(it, type, ArtworkTrace.empty()) }
    return ResolvedSlot(
        value = ref,
        rank = if (ref == null) DisplaySourceRank.EMPTY else rank,
        provider = provider,
        role = role,
        updatedAtMs = nowMs,
        expiresAtMs = null,
        trace = emptyList()
    )
}
