package com.nexio.tv.domain.model

import androidx.compose.runtime.Immutable
import com.nexio.tv.core.artwork.ArtworkDisplayRef

/**
 * Per-field source-ranked slots for a Home row. Each slot carries its own
 * provenance so [HomeRailProjectionReducer] can apply the spec's non-downgrade
 * rule on every field independently. Artwork is split per type — poster /
 * backdrop / logo / thumbnail — never merged into one "best image".
 */
@Immutable
data class ResolvedDisplayFieldSlots(
    val title: ResolvedSlot<String>,
    val originalTitle: ResolvedSlot<String>,
    val overview: ResolvedSlot<String>,
    val genres: ResolvedSlot<List<String>>,
    val releaseInfo: ResolvedSlot<String>,
    val runtime: ResolvedSlot<String>,
    val rating: ResolvedSlot<TitleRating>,
    val poster: ResolvedSlot<ArtworkDisplayRef>,
    val backdrop: ResolvedSlot<ArtworkDisplayRef>,
    val logo: ResolvedSlot<ArtworkDisplayRef>,
    val thumbnail: ResolvedSlot<ArtworkDisplayRef>,
    val posterProviderTag: ResolvedSlot<String>
)
