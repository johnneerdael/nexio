package com.nexio.tv.domain.model

import androidx.compose.runtime.Immutable
import com.nexio.tv.core.artwork.ArtworkAssetKey
import com.nexio.tv.core.artwork.ArtworkDecisionKey
import com.nexio.tv.core.artwork.ArtworkDisplayRef
import com.nexio.tv.core.artwork.ArtworkDisplayHints
import com.nexio.tv.core.artwork.ArtworkProviderId
import com.nexio.tv.core.artwork.ArtworkSourceRole
import com.nexio.tv.core.artwork.ArtworkType
import com.nexio.tv.core.artwork.PlaceholderType

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
    val poster: ArtworkDisplayRefSignature?,
    val backdrop: ArtworkDisplayRefSignature?,
    val logo: ArtworkDisplayRefSignature?,
    val thumbnail: ArtworkDisplayRefSignature?,
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
    val poster: SlotSignature<ArtworkDisplayRefSignature>,
    val backdrop: SlotSignature<ArtworkDisplayRefSignature>,
    val logo: SlotSignature<ArtworkDisplayRefSignature>,
    val thumbnail: SlotSignature<ArtworkDisplayRefSignature>,
    val posterProviderTag: SlotSignature<String>
)

@Immutable
data class SlotSignature<T>(
    val value: T?,
    val rank: DisplaySourceRank,
    val provider: String?,
    val role: String?
)

@Immutable
sealed interface ArtworkDisplayRefSignature {
    val imageType: ArtworkType
    val displayHints: ArtworkDisplayHints

    data class RuntimeAsset(
        val decisionKey: ArtworkDecisionKey,
        val assetKey: ArtworkAssetKey?,
        override val imageType: ArtworkType,
        val selectedProvider: ArtworkProviderId?,
        val sourceRole: ArtworkSourceRole,
        override val displayHints: ArtworkDisplayHints
    ) : ArtworkDisplayRefSignature

    data class Placeholder(
        val placeholderType: PlaceholderType,
        override val imageType: ArtworkType,
        override val displayHints: ArtworkDisplayHints
    ) : ArtworkDisplayRefSignature

    data class LegacyString(
        val value: String,
        override val imageType: ArtworkType,
        override val displayHints: ArtworkDisplayHints
    ) : ArtworkDisplayRefSignature
}

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
        poster = artwork.poster.visibleArtworkSignature(),
        backdrop = artwork.backdrop.visibleArtworkSignature(),
        logo = artwork.logo.visibleArtworkSignature(),
        thumbnail = artwork.thumbnail.visibleArtworkSignature(),
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
        poster = poster.artworkSignature(),
        backdrop = backdrop.artworkSignature(),
        logo = logo.artworkSignature(),
        thumbnail = thumbnail.artworkSignature(),
        posterProviderTag = posterProviderTag.signature()
    )

private fun <T> ResolvedSlot<T>.signature(): SlotSignature<T> =
    SlotSignature(
        value = value,
        rank = rank,
        provider = provider,
        role = role
    )

private fun ResolvedSlot<ArtworkDisplayRef>.artworkSignature(): SlotSignature<ArtworkDisplayRefSignature> =
    SlotSignature(
        value = value.visibleArtworkSignature(),
        rank = rank,
        provider = provider,
        role = role
    )

private fun ArtworkDisplayRef?.visibleArtworkSignature(): ArtworkDisplayRefSignature? =
    when (this) {
        is ArtworkDisplayRef.RuntimeAsset -> ArtworkDisplayRefSignature.RuntimeAsset(
            decisionKey = decisionKey,
            assetKey = assetKey,
            imageType = imageType,
            selectedProvider = selectedProvider,
            sourceRole = sourceRole,
            displayHints = displayHints
        )
        is ArtworkDisplayRef.Placeholder -> ArtworkDisplayRefSignature.Placeholder(
            placeholderType = placeholderType,
            imageType = imageType,
            displayHints = displayHints
        )
        is ArtworkDisplayRef.LegacyString -> ArtworkDisplayRefSignature.LegacyString(
            value = value,
            imageType = imageType,
            displayHints = displayHints
        )
        null -> null
    }
