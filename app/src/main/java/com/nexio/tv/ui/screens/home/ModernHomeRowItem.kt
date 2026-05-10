package com.nexio.tv.ui.screens.home

import androidx.compose.runtime.Immutable
import com.nexio.tv.core.artwork.ArtworkDisplayRef
import com.nexio.tv.domain.model.HydrationState
import com.nexio.tv.domain.model.ResolvedDisplayItem
import com.nexio.tv.domain.model.TitleRating
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
    }
}

internal fun ArtworkDisplayRef?.deriveProviderTag(): String? = when (this) {
    is ArtworkDisplayRef.RuntimeAsset -> selectedProvider?.key?.lowercase()
    is ArtworkDisplayRef.LegacyString, is ArtworkDisplayRef.Placeholder, null -> null
}
