package com.nexio.tv.ui.screens.home

import com.nexio.tv.core.artwork.ArtworkDisplayRef
import com.nexio.tv.core.artwork.ArtworkTrace
import com.nexio.tv.core.artwork.ArtworkType
import com.nexio.tv.core.artwork.takeIfImageType
import com.nexio.tv.core.artwork.toLegacyArtworkString
import com.nexio.tv.domain.model.DisplaySourceRank
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
