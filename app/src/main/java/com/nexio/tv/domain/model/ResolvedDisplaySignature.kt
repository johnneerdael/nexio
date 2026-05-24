package com.nexio.tv.domain.model

import androidx.compose.runtime.Immutable
import com.nexio.tv.core.artwork.ArtworkDisplayRef
import com.nexio.tv.core.artwork.ArtworkProviderId
import com.nexio.tv.core.artwork.ArtworkType

@Immutable
data class DisplayFeatureSignature(
    val languageTag: String?,
    val artworkSettingsSignature: String,
    val ratingProviderPolicy: String,
    val displayPolicyVersion: Int
)

@Immutable
data class ResolvedVisibleDisplaySignature(
    val featureSignature: DisplayFeatureSignature,
    val itemType: ContentType,
    val mediaKind: String,
    val displayLanguageTag: String?,
    val title: String?,
    val originalTitle: String?,
    val year: Int?,
    val releaseDate: String?,
    val overview: String?,
    val genres: List<String>,
    val runtimeText: String?,
    val tomatoesRating: Double?,
    val rating: TitleRating?,
    val poster: ArtworkDisplayRef?,
    val backdrop: ArtworkDisplayRef?,
    val logo: ArtworkDisplayRef?,
    val thumbnail: ArtworkDisplayRef?,
    val preferredArtworkProviders: Map<ArtworkType, ArtworkProviderId>,
    val slots: ResolvedVisibleSlotSignature?
)

@Immutable
data class ResolvedIdentitySignature(
    val itemKey: String,
    val contentId: String,
    val parentId: String,
    val itemType: ContentType,
    val mediaKind: String,
    val canonicalProvider: String?,
    val canonicalId: String?,
    val imdbId: String?,
    val stableIds: ProviderIds
)

@Immutable
data class ResolvedVisibleSlotSignature(
    val title: SlotSignature<String>,
    val originalTitle: SlotSignature<String>,
    val overview: SlotSignature<String>,
    val genres: SlotSignature<List<String>>,
    val releaseInfo: SlotSignature<String>,
    val runtime: SlotSignature<String>,
    val rating: SlotSignature<TitleRating>,
    val poster: SlotSignature<ArtworkDisplayRef>,
    val backdrop: SlotSignature<ArtworkDisplayRef>,
    val logo: SlotSignature<ArtworkDisplayRef>,
    val thumbnail: SlotSignature<ArtworkDisplayRef>,
    val posterProviderTag: SlotSignature<String>
)

@Immutable
data class SlotSignature<T>(
    val value: T?,
    val rank: DisplaySourceRank,
    val provider: String?,
    val role: String?
)

fun ResolvedDisplayItem.visibleDisplaySignature(
    featureSignature: DisplayFeatureSignature
): ResolvedVisibleDisplaySignature =
    ResolvedVisibleDisplaySignature(
        featureSignature = featureSignature,
        itemType = itemType,
        mediaKind = mediaKind.name,
        displayLanguageTag = displayLanguageTag,
        title = display.title,
        originalTitle = display.originalTitle,
        year = display.year,
        releaseDate = display.releaseDate,
        overview = display.overview,
        genres = display.genres,
        runtimeText = display.runtimeText,
        tomatoesRating = display.tomatoesRating,
        rating = rating,
        poster = artwork.poster,
        backdrop = artwork.backdrop,
        logo = artwork.logo,
        thumbnail = artwork.thumbnail,
        preferredArtworkProviders = preferredArtworkProviders,
        slots = slots?.visibleSlotSignature()
    )

fun ResolvedDisplayItem.identitySignature(): ResolvedIdentitySignature =
    ResolvedIdentitySignature(
        itemKey = itemKey,
        contentId = contentId,
        parentId = parentId,
        itemType = itemType,
        mediaKind = mediaKind.name,
        canonicalProvider = canonicalProvider,
        canonicalId = canonicalId,
        imdbId = imdbId,
        stableIds = stableIds
    )

fun ResolvedDisplayFieldSlots.visibleSlotSignature(): ResolvedVisibleSlotSignature =
    ResolvedVisibleSlotSignature(
        title = title.signature(),
        originalTitle = originalTitle.signature(),
        overview = overview.signature(),
        genres = genres.signature(),
        releaseInfo = releaseInfo.signature(),
        runtime = runtime.signature(),
        rating = rating.signature(),
        poster = poster.signature(),
        backdrop = backdrop.signature(),
        logo = logo.signature(),
        thumbnail = thumbnail.signature(),
        posterProviderTag = posterProviderTag.signature()
    )

private fun <T> ResolvedSlot<T>.signature(): SlotSignature<T> =
    SlotSignature(
        value = value,
        rank = rank,
        provider = provider,
        role = role
    )
