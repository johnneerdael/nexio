package com.nexio.tv.ui.screens.home

import androidx.compose.runtime.Immutable
import com.nexio.tv.core.artwork.ArtworkDisplayRef
import com.nexio.tv.core.artwork.ArtworkTrace
import com.nexio.tv.core.artwork.ArtworkType
import com.nexio.tv.domain.model.HydrationState
import com.nexio.tv.domain.model.MetaPreview
import com.nexio.tv.domain.model.ResolvedDisplayItem
import com.nexio.tv.domain.model.TitleRating
import com.nexio.tv.domain.model.homeDisplayItemKey
import com.nexio.tv.ui.components.RailCardData

@Immutable
data class ModernHomeRowItem(
    val itemKey: String,
    val contentId: String,
    val parentId: String,
    val title: String?,
    val year: Int?,
    override val posterRef: ArtworkDisplayRef?,
    val backdropRef: ArtworkDisplayRef?,
    val logoRef: ArtworkDisplayRef?,
    val thumbnailRef: ArtworkDisplayRef?,
    val rating: TitleRating?,
    val hydrationState: HydrationState,
    override val posterProviderTag: String?
) : RailCardData {
    override val id: String get() = contentId
    override val name: String? get() = title

    companion object {
        fun from(resolved: ResolvedDisplayItem): ModernHomeRowItem =
            ModernHomeRowItem(
                itemKey = resolved.itemKey,
                contentId = resolved.contentId,
                parentId = resolved.parentId,
                title = resolved.display.title,
                year = resolved.display.year,
                posterRef = resolved.artwork.poster,
                backdropRef = resolved.artwork.backdrop,
                logoRef = resolved.artwork.logo,
                thumbnailRef = resolved.artwork.thumbnail,
                rating = resolved.rating,
                hydrationState = resolved.hydrationState,
                posterProviderTag = resolved.artwork.poster.deriveProviderTag()
            )

        /**
         * Best-effort projection from a legacy [MetaPreview] when the resolved
         * authority's `ResolvedDisplayItem` is not available at the call site.
         * Wraps poster/background/logo URL strings as
         * [ArtworkDisplayRef.LegacyString] of the typed [ArtworkType] so the
         * surface boundary's rule #1 strict-typed-slot contract holds.
         *
         * Mirrors [com.nexio.tv.ui.screens.detail.DetailRailItem.fromMetaPreview]
         * (commit `d777c65b3`). Callers that already have a `ResolvedDisplayItem`
         * should use [from] instead.
         */
        fun fromMetaPreview(meta: MetaPreview): ModernHomeRowItem = ModernHomeRowItem(
            itemKey = homeDisplayItemKey(meta.apiType, meta.id),
            contentId = meta.id,
            parentId = meta.id,
            title = meta.name,
            year = meta.releaseInfo?.split("-")?.firstOrNull()?.toIntOrNull(),
            posterRef = meta.poster.toLegacyHomeRailRefOrNull(ArtworkType.POSTER),
            backdropRef = meta.background.toLegacyHomeRailRefOrNull(ArtworkType.BACKDROP),
            logoRef = meta.logo.toLegacyHomeRailRefOrNull(ArtworkType.LOGO),
            thumbnailRef = null,
            rating = null,
            hydrationState = HydrationState.PREVIEW_ONLY,
            posterProviderTag = meta.posterProviderTag
        )
    }
}

internal fun ArtworkDisplayRef?.deriveProviderTag(): String? = when (this) {
    is ArtworkDisplayRef.RuntimeAsset -> selectedProvider?.key?.lowercase()
    is ArtworkDisplayRef.LegacyString, is ArtworkDisplayRef.Placeholder, null -> null
}

private fun String?.toLegacyHomeRailRefOrNull(type: ArtworkType): ArtworkDisplayRef? {
    val v = this?.takeIf { it.isNotBlank() } ?: return null
    return ArtworkDisplayRef.LegacyString(value = v, imageType = type, trace = ArtworkTrace.empty())
}
